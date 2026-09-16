package catalog

import (
	"net/url"
	"strings"
	"testing"
)

func TestNormalizeCoverURLRewritesKuwoCDNShardToMirror(t *testing.T) {
	paths := []string{
		"/star/upload/8/8/1543919795640_.png",
		"/star/upload/1/1/1554695862673_.png",
		"/star/upload/9/9/1543919747769_.png",
		"/star/upload/6/6/1554970547302_.png",
		"/star/upload/2/2/1543919658018_.png",
		"/star/upload/11/11/1576751304219_.png",
		"/star/albumcover/500/22/47/360392367.jpg",
	}
	for shard := 1; shard <= 4; shard++ {
		host := "img" + string(rune('0'+shard)) + ".kwcdn.kuwo.cn"
		for _, scheme := range []string{"http://", "https://", "//", "HTTPS://"} {
			for _, path := range paths {
				t.Run(host+"/"+scheme+path, func(t *testing.T) {
					t.Parallel()
					// 不重新编码原始查询串，也不改变客户端片段。
					suffix := "?size=300&v=a%2Fb&v=a+b#cover"
					got := NormalizeCoverURL(scheme + host + path + suffix)
					want := "https://img" + string(rune('0'+shard)) + ".kuwo.cn" + path + suffix
					if got != want {
						t.Fatalf("normalized cover = %q, want %q", got, want)
					}
					if again := NormalizeCoverURL(got); again != got {
						t.Fatal("normalization must be idempotent")
					}
				})
			}
		}
	}
}

func TestNormalizeCoverURLLeavesUnrelatedInputUnchanged(t *testing.T) {
	const path = "/star/upload/8/8/1543919795640_.png"
	cases := []struct {
		name string
		raw  string
	}{
		{"empty", ""},
		{"relative", path},
		{"unrelated_http", "http://covers.example" + path},
		{"unrelated_https", "https://covers.example" + path},
		{"unrelated_scheme_relative", "//covers.example" + path},
		{"existing_official_http", "http://img1.kuwo.cn" + path},
		{"existing_official_https", "https://img1.kuwo.cn" + path},
		{"other_official_host", "https://kwimg2.kuwo.cn" + path},
		{"cdn_parent", "https://kwcdn.kuwo.cn" + path},
		{"unknown_shard", "https://img5.kwcdn.kuwo.cn" + path},
		{"prefix_attack", "https://evil-img2.kwcdn.kuwo.cn" + path},
		{"nested_host", "https://evil.img2.kwcdn.kuwo.cn" + path},
		{"suffix_attack", "https://img2.kwcdn.kuwo.cn.evil.example" + path},
		{"userinfo", "https://user@img2.kwcdn.kuwo.cn" + path},
		{"userinfo_host_spoof", "https://img2.kwcdn.kuwo.cn@evil.example" + path},
		{"port", "https://img2.kwcdn.kuwo.cn:8443" + path},
		{"default_port", "https://img2.kwcdn.kuwo.cn:443" + path},
		{"empty_port", "https://img2.kwcdn.kuwo.cn:" + path},
		{"invalid_port", "https://img2.kwcdn.kuwo.cn:invalid" + path},
		{"trailing_dot", "https://img2.kwcdn.kuwo.cn." + path},
		{"invalid_escape", "https://img2.kwcdn.kuwo.cn/%zz"},
		{"opaque_url", "https:img2.kwcdn.kuwo.cn" + path},
		{"ftp", "ftp://img2.kwcdn.kuwo.cn" + path},
		{"file", "file://img2.kwcdn.kuwo.cn" + path},
		{"data", "data:image/png;base64,test"},
		{"private_host", "http://127.0.0.1" + path},
		{"ipv6_host", "https://[::1]" + path},
		{"leading_whitespace", " https://img2.kwcdn.kuwo.cn" + path},
		{"newline", "https://img2.kwcdn.kuwo.cn" + path + "\n"},
		{"oversized", "https://img2.kwcdn.kuwo.cn" + path + "?q=" + strings.Repeat("a", 2048)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := NormalizeCoverURL(tc.raw); got != tc.raw {
				t.Fatal("unrelated or unsafe URL was rewritten")
			}
		})
	}
}

func TestNormalizeCoverURLPreservesUnverifiedPathsOnTrustedMirror(t *testing.T) {
	// 未逐一核验的资源不再丢弃：只把失效 CDN 主机换成同分片镜像，路径原样保留；
	// 资源不存在时由浏览器/下载方拿到上游 404，而不是被目录层静默清空。
	for shard := 1; shard <= 4; shard++ {
		host := "img" + string(rune('0'+shard)) + ".kwcdn.kuwo.cn"
		mirror := "img" + string(rune('0'+shard)) + ".kuwo.cn"
		for _, path := range []string{
			"/a.jpg",
			"/star/upload/unknown.png",
			"/star/albumcover/unverified.jpg",
			"/star/upload/8/8/1543919795640_.jpg",
			"/star/upload/8/8/1543919795640_.png/extra",
			"/star/upload/8/8/1543919795640_.png/../unknown.png",
			"/star/upload/8/8/../8/1543919795640_.png",
		} {
			for _, scheme := range []string{"http://", "https://", "//"} {
				got := NormalizeCoverURL(scheme + host + path)
				want := "https://" + mirror + path
				if got != want {
					t.Errorf("normalized %s: got %q, want %q", path, got, want)
				}
			}
		}
	}
}

func FuzzNormalizeCoverURL(f *testing.F) {
	for _, seed := range []string{
		"", "http://covers.example/a.png",
		"http://img2.kwcdn.kuwo.cn/star/upload/8/8/1543919795640_.png",
		"https://img3.kwcdn.kuwo.cn/star/upload/1/1/1554695862673_.png?a=%2f#cover",
		"//img4.kwcdn.kuwo.cn/star/upload/unknown.png",
		"https://img2.kwcdn.kuwo.cn:443/star/upload/8/8/1543919795640_.png",
		"https://img4.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png",
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		got := NormalizeCoverURL(raw)
		if NormalizeCoverURL(got) != got {
			t.Fatal("normalization is not idempotent")
		}
		if got == raw {
			return
		}
		original, err := url.Parse(raw)
		if err != nil || original.User != nil || original.Port() != "" {
			t.Fatal("normalization changed an invalid authority")
		}
		shard := ""
		switch strings.ToLower(original.Host) {
		case "img1.kwcdn.kuwo.cn":
			shard = "1"
		case "img2.kwcdn.kuwo.cn":
			shard = "2"
		case "img3.kwcdn.kuwo.cn":
			shard = "3"
		case "img4.kwcdn.kuwo.cn":
			shard = "4"
		default:
			t.Fatal("normalization changed an unrelated authority")
		}
		normalized, err := url.Parse(got)
		if err != nil || normalized.Scheme != "https" || normalized.Host != "img"+shard+".kuwo.cn" || normalized.User != nil {
			t.Fatal("normalization did not produce the same-shard HTTPS mirror")
		}
		if normalized.EscapedPath() != original.EscapedPath() || normalized.RawQuery != original.RawQuery || normalized.EscapedFragment() != original.EscapedFragment() {
			t.Fatal("normalization changed resource path, query or fragment")
		}
	})
}
