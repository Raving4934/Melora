package catalog

import (
	"errors"
	"net/http"
	"testing"
)

type sharedDoer struct{}

func (sharedDoer) Do(*http.Request) (*http.Response, error) { return nil, errors.New("unexpected") }

// 五种平台的目录请求必须共用同一个受保护客户端；任何适配器自建 HTTP 路径都会
// 绕过限流、Retry-After 冷却、短 TTL 缓存与连接复用。
func TestAllPlatformsShareHardenedHTTPDoer(t *testing.T) {
	doer := sharedDoer{}
	registry := NewAllRegistry(NewWY(doer))
	for _, id := range []string{"wy", "tx", "kw", "kg", "mg"} {
		adapter, err := registry.Adapter(id)
		if err != nil {
			t.Fatal(err)
		}
		var got HTTPDoer
		switch value := adapter.(type) {
		case *WY:
			got = value.http
		case *TX:
			got = value.client
		case *KW:
			got = value.http
		case *KG:
			got = value.http
		case *MG:
			got = value.http
		default:
			t.Fatalf("unknown adapter %T", adapter)
		}
		if got != HTTPDoer(doer) {
			t.Fatalf("%s adapter bypasses shared http doer: %T", id, got)
		}
	}
}
