package catalog

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"testing"
	"time"
)

// 显式启用才联网：最多两次固定公共元数据 GET，无原文/URL/身份字段输出。
// 默认单测完全离线；不尝试重试、登录、验证码、签名或音频请求。
func TestTXBoundedPublicMetadataProbe(t *testing.T) {
	if os.Getenv("MELORA_TX_PUBLIC_PROBE") != "1" {
		t.Skip("optional bounded public metadata probe")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	defer cancel()
	t.Logf("observed_at_utc=%s (probe execution does not imply upstream availability)", time.Now().UTC().Format(time.RFC3339))
	codeValue := func(code *int) any {
		if code == nil {
			return "missing"
		}
		return *code
	}
	issueCode := func(err error) string {
		if err == nil {
			return "none"
		}
		var coded interface{ CatalogIssueCode() string }
		if errors.As(err, &coded) && coded.CatalogIssueCode() != "" {
			return coded.CatalogIssueCode()
		}
		return "upstream_unavailable"
	}
	calls := 0
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls > 2 {
			t.Fatal("public probe request budget exceeded")
		}
		request := txTestReadRPC(t, r)
		res, err := GuardedClient().Do(r)
		if err != nil {
			t.Log("transport unavailable")
			return nil, ErrUnavailable
		}
		raw, readErr := io.ReadAll(io.LimitReader(res.Body, (1<<20)+1))
		res.Body.Close()
		if readErr != nil || len(raw) > 1<<20 {
			return nil, ErrUnavailable
		}
		res.Body = io.NopCloser(bytes.NewReader(raw))
		var env struct {
			Code *int
			Req  struct {
				Code *int
				Data json.RawMessage
			}
		}
		if json.Unmarshal(raw, &env) == nil {
			t.Logf("method=%s http=%d bytes=%d root_code=%v rpc_code=%v sha256=%x", request.Req.Method, res.StatusCode, len(raw), codeValue(env.Code), codeValue(env.Req.Code), sha256.Sum256(raw))
			var lists struct {
				Items []txPlaylistBasic `json:"v_playlist"`
			}
			var mismatch *json.UnmarshalTypeError
			if err := json.Unmarshal(env.Req.Data, &lists); errors.As(err, &mismatch) {
				t.Logf("playlist_schema_mismatch field=%s expected=%s received=%s", mismatch.Field, mismatch.Type, mismatch.Value)
			}
		}
		return res, nil
	}))
	tracks, err := q.Search(ctx, "孤独", "track", 1)
	t.Logf("tracks count=%d available=%t issue=%s", len(tracks.Tracks), err == nil, issueCode(err))
	playlists, err := q.Playlists(ctx, "all", 1)
	t.Logf("playlists count=%d available=%t issue=%s", len(playlists), err == nil, issueCode(err))
	_, err = q.NewTracks(ctx, "all")
	t.Logf("new_tracks unsupported=%t requests=%d", errors.Is(err, ErrUnsupported), calls)
}
