package download

import (
	"context"
	"encoding/binary"
	"io"
)

// 只读有界结构识别：不执行解码/转码，不依据MIME或请求音质猜实际编码。
const maxProbeMetadata = 32 << 20

func readProbe(src io.ReaderAt, offset int64, size int, total int64) ([]byte, error) {
	if offset < 0 || size < 0 || size > 64 || offset > total || int64(size) > total-offset {
		return nil, mediaDiagnostic(mediaStructureInvalid)
	}
	data := make([]byte, size)
	if _, err := src.ReadAt(data, offset); err != nil {
		return nil, err
	}
	return data, nil
}

// ID3 是元数据，不是MP3证明。按synchsafe长度跳过标签，而不是在标签图片中搜索魔数。
func audioOffset(src io.ReaderAt, total int64) (int64, error) {
	if total < 10 {
		return 0, nil
	}
	h, err := readProbe(src, 0, 10, total)
	if err != nil {
		return 0, err
	}
	if string(h[:3]) != "ID3" {
		return 0, nil
	}
	offset, err := id3Size(h)
	if err != nil {
		return 0, err
	}
	if offset >= total {
		return 0, mediaDiagnostic(mediaStructureInvalid)
	}
	if h[3] == 4 && h[5]&0x10 != 0 {
		foot, err := readProbe(src, offset-10, 10, total)
		if err != nil || string(foot[:3]) != "3DI" || string(foot[3:]) != string(h[3:]) {
			return 0, mediaDiagnostic(mediaStructureInvalid)
		}
	}
	return offset, nil
}

// v2.2/v2.3/v2.4均为四字节synchsafe标签长度；v2.4 footer不计入该长度。
func id3Size(h []byte) (int64, error) {
	if len(h) < 10 || h[3] < 2 || h[3] > 4 || h[4] == 255 {
		return 0, mediaDiagnostic(mediaStructureInvalid)
	}
	allowed := byte(0xe0)
	if h[3] == 2 {
		allowed = 0xc0
	}
	if h[3] == 4 {
		allowed = 0xf0
	}
	if h[5]&^allowed != 0 {
		return 0, mediaDiagnostic(mediaStructureInvalid)
	}
	n := int64(0)
	for _, b := range h[6:10] {
		if b&128 != 0 {
			return 0, mediaDiagnostic(mediaStructureInvalid)
		}
		n = n<<7 | int64(b)
	}
	if n > maxProbeMetadata {
		return 0, mediaDiagnostic(mediaStructureInvalid)
	}
	offset := n + 10
	if h[3] == 4 && h[5]&0x10 != 0 {
		offset += 10
	}
	return offset, nil
}

func fileFormat(src io.ReaderAt, total int64) (string, int64, error) {
	offset, err := audioOffset(src, total)
	if err != nil {
		return "", 0, err
	}
	prefix, err := readProbe(src, offset, int(min(64, total-offset)), total)
	if err != nil {
		return "", 0, err
	}
	// 不允许多个未知长度标签被当成MP3；目前只接受单个有界前置ID3。
	if len(prefix) >= 3 && string(prefix[:3]) == "ID3" {
		return "", 0, mediaDiagnostic(mediaStructureInvalid)
	}
	ext := detectFormat(prefix)
	if ext == "" {
		return "", 0, mediaDiagnostic(mediaMagicUnknown)
	}
	return ext, offset, nil
}

// 仅为MIME冲突/纠正ID3误判的MP3和FLAC启动更强结构确认。
// 不把原有轻量魔数检查升级为“已解码”声明；其它格式的冲突继续拒绝。
func confirmMedia(ctx context.Context, src io.ReaderAt, total int64, extension string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	actual, offset, err := fileFormat(src, total)
	if err != nil {
		return err
	}
	if actual != extension {
		return mediaDiagnostic(mediaStructureInvalid)
	}
	switch extension {
	case ".mp3":
		return confirmMP3(src, offset, total)
	case ".flac":
		return confirmFLAC(ctx, src, offset, total)
	default:
		return mediaDiagnostic(mediaMIMEMismatch)
	}
}

func mp3FrameSize(h []byte) (int, int, bool) {
	if len(h) < 4 || h[0] != 255 || h[1]&0xe0 != 0xe0 {
		return 0, 0, false
	}
	version, layer := int(h[1]>>3&3), int(h[1]>>1&3)
	bitrate, sample := int(h[2]>>4), int(h[2]>>2&3)
	// 本扩展名仅代表Layer III，不把Layer I/II、free-format或保留编码猜成MP3。
	if version == 1 || layer != 1 || bitrate == 0 || bitrate == 15 || sample == 3 || h[3]&3 == 2 {
		return 0, 0, false
	}
	rates := [3]int{44100, 48000, 32000}
	rate := rates[sample]
	bits := [15]int{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
	coefficient := 144
	if version != 3 {
		bits = [15]int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
		rate /= 2
		coefficient = 72
		if version == 0 {
			rate /= 2
		}
	}
	size := coefficient*bits[bitrate]*1000/rate + int(h[2]>>1&1)
	return size, version<<2 | sample, size >= 4
}
func confirmMP3(src io.ReaderAt, offset, total int64) error {
	var stream int
	// 在计算出的帧边界确认三帧，不在任意垃圾数据中扫描同步字。
	for i := 0; i < 3; i++ {
		h, err := readProbe(src, offset, 4, total)
		if err != nil {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		size, key, ok := mp3FrameSize(h)
		if !ok || int64(size) > total-offset || i > 0 && key != stream {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		stream = key
		offset += int64(size)
	}
	return nil
}

func confirmFLAC(ctx context.Context, src io.ReaderAt, offset, total int64) error {
	offset += 4
	start := offset
	for i := 0; ; i++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		if i >= 4096 || offset-start > maxProbeMetadata {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		h, err := readProbe(src, offset, 4, total)
		if err != nil {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		kind := h[0] & 127
		n := int64(h[1])<<16 | int64(h[2])<<8 | int64(h[3])
		if kind == 127 || i == 0 && (kind != 0 || n != 34) || i > 0 && kind == 0 || n > total-offset-4 || n > maxProbeMetadata-(offset-start)-4 {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		if i == 0 {
			info, err := readProbe(src, offset+4, 34, total)
			if err != nil {
				return mediaDiagnostic(mediaStructureInvalid)
			}
			minBlock, maxBlock := binary.BigEndian.Uint16(info[:2]), binary.BigEndian.Uint16(info[2:4])
			sampleRate := uint32(info[10])<<12 | uint32(info[11])<<4 | uint32(info[12])>>4
			bits := (info[12]&1)<<4 | info[13]>>4
			if minBlock < 16 || maxBlock < minBlock || sampleRate == 0 || bits < 3 {
				return mediaDiagnostic(mediaStructureInvalid)
			}
		}
		offset += 4 + n
		if h[0]&128 != 0 {
			break
		}
	}
	h, err := readProbe(src, offset, int(min(16, total-offset)), total)
	if err != nil || len(h) < 6 || h[0] != 255 || h[1]&0xfe != 0xf8 || h[2]>>4 == 0 || h[2]&15 == 15 || h[3]>>4 > 10 || h[3]&1 != 0 || h[3]>>1&7 == 3 {
		return mediaDiagnostic(mediaStructureInvalid)
	}
	// 正在确认完整FLAC流的第一帧，其帧号/采样号必须为0（规范编码为单字节0）。
	if h[4] != 0 {
		return mediaDiagnostic(mediaStructureInvalid)
	}
	pos := 5
	switch h[2] >> 4 {
	case 6:
		pos++
	case 7:
		if pos+2 > len(h) || binary.BigEndian.Uint16(h[pos:pos+2]) == 65535 {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		pos += 2
	}
	switch h[2] & 15 {
	case 12:
		if pos >= len(h) || h[pos] == 0 {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		pos++
	case 13, 14:
		if pos+2 > len(h) || binary.BigEndian.Uint16(h[pos:pos+2]) == 0 {
			return mediaDiagnostic(mediaStructureInvalid)
		}
		pos += 2
	}
	if pos >= len(h) || int64(pos+3) > total-offset {
		return mediaDiagnostic(mediaStructureInvalid)
	}
	crc := byte(0)
	for _, b := range h[:pos] {
		crc ^= b
		for j := 0; j < 8; j++ {
			if crc&128 != 0 {
				crc = crc<<1 ^ 7
			} else {
				crc <<= 1
			}
		}
	}
	if crc != h[pos] {
		return mediaDiagnostic(mediaStructureInvalid)
	}
	return nil
}
