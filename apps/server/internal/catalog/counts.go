package catalog

import (
	"encoding/json"
	"strconv"
	"strings"
)

// 播放量是上游真实计数，缺失或非法不是0；不把收藏量/热度/缩略字符串猜成播放数。
func publicPlayCount(value any) *int64 {
	raw, err := json.Marshal(value)
	if err != nil {
		return nil
	}
	text := strings.TrimSpace(string(raw))
	if strings.HasPrefix(text, "\"") {
		if json.Unmarshal(raw, &text) != nil {
			return nil
		}
	}
	if text == "" || len(text) > 16 {
		return nil
	}
	for _, r := range text {
		if r < '0' || r > '9' {
			return nil
		}
	}
	n, err := strconv.ParseInt(text, 10, 64)
	if err != nil || n > 9007199254740991 {
		return nil
	}
	return &n
}
func rawPlayCount(row map[string]json.RawMessage, keys ...string) *int64 {
	for _, key := range keys {
		if raw, exists := row[key]; exists {
			if n := publicPlayCount(raw); n != nil {
				return n
			}
		}
	}
	return nil
}
func firstPlayCount(values ...any) *int64 {
	for _, v := range values {
		if n := publicPlayCount(v); n != nil {
			return n
		}
	}
	return nil
}
