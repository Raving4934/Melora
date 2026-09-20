package provider

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/catalog"
	"melora/internal/model"
)

// sourceMediaResult is the optional object form of a musicUrl result.
// A legacy string (including the historical nested URL shapes) is still accepted.
type sourceMediaResult struct {
	URL             string
	Quality         string
	ReportedQuality bool
	Platform        string
	ResourceID      string
	Track           model.Track
}

var mediaInfoIdentityFields = []string{
	"id", "songmid", "songId", "songid", "musicId", "musicid", "rid", "hash",
	"audioId", "audioid", "audio_id", "MUSICRID", "musicrid", "musicrId",
}

var mediaInfoTitleFields = []string{"name", "songname", "songName", "title"}
var mediaInfoArtistFields = []string{"singer", "artist", "artistName", "author"}
var mediaInfoAlbumFields = []string{"albumName", "album", "albumname"}

func parseSourceMediaResult(raw json.RawMessage, candidate resolveCandidate, actual model.Track, input map[string]any) (sourceMediaResult, error) {
	if len(raw) == 0 || len(raw) > 256<<10 {
		return sourceMediaResult{}, ErrMediaURL
	}
	value, err := decodeResultValue(raw)
	if err != nil {
		return sourceMediaResult{}, ErrMediaURL
	}
	object, isObject := value.(map[string]any)
	if !isObject || !hasMediaResultExtension(object) {
		resolved, err := decodeMediaURL(raw)
		if err != nil {
			return sourceMediaResult{}, err
		}
		return sourceMediaResult{URL: resolved, Quality: candidate.quality, Platform: actual.ProviderID, Track: actual}, nil
	}

	urlValue, ok := object["url"].(string)
	if !ok || strings.TrimSpace(urlValue) == "" {
		return sourceMediaResult{}, ErrMediaResult
	}
	urlRaw, _ := json.Marshal(urlValue)
	resolved, err := decodeMediaURL(urlRaw)
	if err != nil {
		return sourceMediaResult{}, err
	}
	if mediaResultHeaders(object) {
		return sourceMediaResult{}, ErrMediaHeaders
	}

	platform := actual.ProviderID
	if value, exists := object["source"]; exists {
		platform, ok = mediaResultString(value)
		if !ok || catalog.PlatformNames[platform] == "" {
			return sourceMediaResult{}, ErrMediaResult
		}
	}
	// source 是脚本返回的实际目录平台，不要求它与本次 musicUrl
	// 调用平台相同；调用候选已由该脚本的实际平台能力与音质筛选。
	// 这样可泛化支持脚本内部跨平台搜索，而不引入任何平台专用分支。
	qualities := sourceQualities(candidate.source, actual.ProviderID)
	if len(qualities) == 0 {
		return sourceMediaResult{}, ErrMediaResult
	}

	quality := candidate.quality
	if value, exists := object["type"]; exists {
		quality, ok = mediaResultString(value)
		if !ok || !qualityNameValid(quality) {
			return sourceMediaResult{}, ErrMediaResult
		}
	}
	if !compatibleReturnedQuality(qualities, candidate.quality, quality) {
		return sourceMediaResult{}, ErrQuality
	}

	var musicInfo map[string]any
	if value, exists := object["musicInfo"]; exists {
		musicInfo, ok = value.(map[string]any)
		if !ok || len(musicInfo) == 0 || !validReturnedMusicInfo(musicInfo, platform, actual, input) {
			return sourceMediaResult{}, ErrMediaResult
		}
	}
	if platform != actual.ProviderID && (musicInfo == nil || mediaInfoText(musicInfo, mediaInfoIdentityFields...) == "") {
		return sourceMediaResult{}, ErrMediaResult
	}

	resourceID := ""
	if value, exists := object["resourceId"]; exists {
		resourceID, ok = mediaResultString(value)
		if !ok || !validResourceID(resourceID) {
			return sourceMediaResult{}, ErrMediaResult
		}
	}
	_, reportedQuality := object["type"]
	result := sourceMediaResult{URL: resolved, Quality: quality, ReportedQuality: reportedQuality, Platform: platform, ResourceID: resourceID}
	result.Track = actual
	if musicInfo != nil {
		result.Track.ProviderID = platform
		result.Track.ID = returnedTrackKey(platform, actual, musicInfo)
		if title := mediaInfoText(musicInfo, mediaInfoTitleFields...); title != "" {
			result.Track.Title = title
		}
		if artist := mediaInfoText(musicInfo, mediaInfoArtistFields...); artist != "" {
			result.Track.Artist = artist
		}
		if album := mediaInfoText(musicInfo, mediaInfoAlbumFields...); album != "" {
			result.Track.Album = album
		}
		if duration := mediaInfoDuration(musicInfo); duration > 0 {
			result.Track.Duration = duration
		}
		if image := mediaInfoText(musicInfo, "img", "pic", "coverUrl"); image != "" {
			result.Track.CoverURL = catalog.NormalizeCoverURL(image)
		}
	}
	if resourceID != "" {
		result.ResourceID = scopedResourceID(candidate.source.ID, platform, result.Track.ID, quality, resourceID)
	}
	return result, nil
}

// 物理资源可以更换；命名空间隔离而不是永久锁死某歌曲→某文件映射。
func scopedResourceID(script, platform, track, quality, resource string) string {
	payload := strings.Join([]string{platform, track, quality, resource}, "\x00")
	return fmt.Sprintf("lx:%s:%x", script, sha256.Sum256([]byte(payload)))
}

var artistSeparators = regexp.MustCompile(`(?i)\s*(?:[、,，/&;；]|\s+(?:feat\.?|ft\.?|featuring|and|×|x)\s+)\s*`)

func recordingText(text string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsPunct(r) || unicode.IsSpace(r) {
			return -1
		}
		return r
	}, matchKey(text))
}
func sameRecordingArtists(left, right string) bool {
	names := func(value string) map[string]bool {
		out := map[string]bool{}
		for _, name := range artistSeparators.Split(value, -1) {
			if key := recordingText(name); key != "" {
				out[key] = true
			}
		}
		return out
	}
	a, b := names(left), names(right)
	if len(a) == 0 || len(a) != len(b) {
		return false
	}
	for name := range a {
		if !b[name] {
			return false
		}
	}
	return true
}

func decodeResultValue(raw json.RawMessage) (any, error) {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		if err == nil {
			return nil, ErrMediaResult
		}
		return nil, err
	}
	return value, nil
}

func hasMediaResultExtension(object map[string]any) bool {
	for _, key := range []string{"type", "source", "musicInfo", "resourceId"} {
		if _, ok := object[key]; ok {
			return true
		}
	}
	return false
}

func mediaResultHeaders(object map[string]any) bool {
	for _, key := range []string{"headers", "header"} {
		value, ok := object[key]
		if !ok || value == nil {
			continue
		}
		if headers, ok := value.(map[string]any); !ok || len(headers) > 0 {
			return true
		}
	}
	return false
}

func mediaResultString(value any) (string, bool) {
	text, ok := value.(string)
	if !ok {
		return "", false
	}
	text = strings.TrimSpace(text)
	return text, text != ""
}

func qualityNameValid(value string) bool {
	if value == "" || len(value) > 32 {
		return false
	}
	for _, c := range value {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

func compatibleReturnedQuality(qualities []string, requested, returned string) bool {
	canonical, ok := selectSourceQuality(qualities, requested)
	if !ok {
		return false
	}
	if canonical == returned || isHighResolutionQuality(canonical) && isHighResolutionQuality(returned) {
		return true
	}
	// 能力表声明的是可请求档位，不是实际编码白名单。基础档接受真实有损
	// 编码，但保留其原始标签；高品质/无损/HR 仍不能静默降到基础档。
	return canonical == "128k" && basicLossyMIME(returned) != ""
}

var basicLossyBitrate = regexp.MustCompile(`^(aac|ogg)?([1-9][0-9]{0,3})k$`)

func basicLossyMIME(quality string) string {
	switch quality {
	case "mp3":
		return "audio/mpeg"
	case "aac", "ogg":
		return "audio/" + quality
	}
	match := basicLossyBitrate.FindStringSubmatch(quality)
	if match == nil {
		return ""
	}
	if match[1] != "" {
		return "audio/" + match[1]
	}
	bitrate, _ := strconv.Atoi(match[2])
	if bitrate < 192 {
		return "audio/mpeg"
	}
	return ""
}

func validResourceID(value string) bool {
	if value == "" || len(value) > 512 || !utf8.ValidString(value) {
		return false
	}
	for _, c := range value {
		if unicode.IsControl(c) || unicode.Is(unicode.Cf, c) {
			return false
		}
	}
	return true
}

func validReturnedMusicInfo(info map[string]any, platform string, actual model.Track, input map[string]any) bool {
	if source := mediaInfoText(info, "source"); source != "" && source != platform {
		return false
	}
	if !musicInfoMatchesTrack(info, actual, platform != actual.ProviderID) {
		return false
	}
	if platform == actual.ProviderID && input != nil {
		// 同一目录平台必须保留请求快照中的稳定身份字段；只回显歌名
		// 不能证明是同一版本，避免同名不同版歌曲混入解析结果。
		if len(mediaInfoIdentity(input)) > 0 && len(mediaInfoIdentity(info)) == 0 {
			return false
		}
		if !sameMusicInfoIdentity(info, input) {
			return false
		}
	}
	return true
}

func sameMusicInfoIdentity(left, right map[string]any) bool {
	leftIDs, rightIDs := mediaInfoIdentity(left), mediaInfoIdentity(right)
	if len(leftIDs) > 0 && len(rightIDs) > 0 {
		for id := range leftIDs {
			if rightIDs[id] {
				return true
			}
		}
		return false
	}
	return musicInfoDescriptionsAgree(left, right)
}

func musicInfoMatchesTrack(info map[string]any, track model.Track, requireDescription bool) bool {
	title, artist := mediaInfoText(info, mediaInfoTitleFields...), mediaInfoText(info, mediaInfoArtistFields...)
	if requireDescription && (title == "" || artist == "") {
		return false
	}
	if title != "" && track.Title != "" && recordingText(title) != recordingText(track.Title) {
		return false
	}
	if artist != "" && track.Artist != "" && !sameRecordingArtists(artist, track.Artist) {
		return false
	}
	if album := mediaInfoText(info, mediaInfoAlbumFields...); album != "" && track.Album != "" && versionKey(album) != versionKey(track.Album) {
		return false
	}
	if duration := mediaInfoDuration(info); duration > 0 && track.Duration > 0 && !closeDuration(duration, track.Duration) {
		return false
	}
	return true
}

func musicInfoDescriptionsAgree(left, right map[string]any) bool {
	matched := false
	for _, fields := range [][]string{mediaInfoTitleFields, mediaInfoArtistFields, mediaInfoAlbumFields} {
		leftValue, rightValue := mediaInfoText(left, fields...), mediaInfoText(right, fields...)
		if leftValue == "" || rightValue == "" {
			continue
		}
		matched = true
		if matchKey(leftValue) != matchKey(rightValue) {
			return false
		}
	}
	return matched
}

func mediaInfoIdentity(info map[string]any) map[string]bool {
	out := map[string]bool{}
	for _, key := range mediaInfoIdentityFields {
		if value, ok := mediaInfoScalar(info[key]); ok && value != "" {
			out[key+"="+value] = true
			out[value] = true
		}
	}
	return out
}

func mediaInfoText(info map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := mediaInfoScalar(info[key]); ok && value != "" {
			return value
		}
	}
	return ""
}

func mediaInfoScalar(value any) (string, bool) {
	switch value := value.(type) {
	case string:
		return strings.TrimSpace(value), true
	case json.Number:
		return value.String(), true
	case float64:
		if math.IsNaN(value) || math.IsInf(value, 0) {
			return "", false
		}
		return strconv.FormatFloat(value, 'f', -1, 64), true
	case float32:
		if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
			return "", false
		}
		return strconv.FormatFloat(float64(value), 'f', -1, 32), true
	default:
		return "", false
	}
}

func mediaInfoDuration(info map[string]any) int {
	for _, key := range []string{"duration", "_interval", "timelength", "durationMs", "dt"} {
		if value, ok := mediaInfoNumber(info[key]); ok {
			if key == "durationMs" || key == "dt" || value > 86400 {
				value /= 1000
			}
			if value > 0 && value <= 86400 {
				return int(value + 0.5)
			}
		}
	}
	if interval := mediaInfoText(info, "interval"); interval != "" {
		parts := strings.Split(interval, ":")
		if len(parts) == 2 {
			minutes, err1 := strconv.Atoi(parts[0])
			seconds, err2 := strconv.Atoi(parts[1])
			if err1 == nil && err2 == nil && minutes >= 0 && seconds >= 0 && seconds < 60 {
				return minutes*60 + seconds
			}
		}
	}
	return 0
}

func mediaInfoNumber(value any) (float64, bool) {
	text, ok := mediaInfoScalar(value)
	if !ok || text == "" {
		return 0, false
	}
	number, err := strconv.ParseFloat(text, 64)
	return number, err == nil && !math.IsNaN(number) && !math.IsInf(number, 0)
}

func closeDuration(left, right int) bool {
	difference := left - right
	if difference < 0 {
		difference = -difference
	}
	longer := max(left, right)
	return difference <= 3 || difference*100 <= longer*2
}

func returnedTrackKey(platform string, actual model.Track, info map[string]any) string {
	if platform == actual.ProviderID {
		return actual.ID
	}
	id := strings.TrimPrefix(mediaInfoText(info, mediaInfoIdentityFields...), platform+":")
	return platform + ":" + id
}
