package catalog

import "testing"

func TestCoverDownloadTargetRetainsOnlyAllowlistedCDNHTTP(t *testing.T) {
	for _, tc := range []struct {
		name, raw, want, host string
		ok                    bool
	}{
		{name: "supported-http", raw: "http://img1.kuwo.cn/cover/a.jpg", want: "http://img1.kuwo.cn/cover/a.jpg", host: "img1.kuwo.cn", ok: true},
		{name: "supported-https", raw: "https://music.126.net/cover/a.jpg", want: "https://music.126.net/cover/a.jpg", ok: true},
		{name: "arbitrary-http", raw: "http://cover.example/cover/a.jpg", ok: false},
		{name: "lookalike-host", raw: "http://img1.kuwo.cn.evil.example/cover/a.jpg", ok: false},
		{name: "explicit-port", raw: "http://img1.kuwo.cn:80/cover/a.jpg", ok: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, host, ok := CoverDownloadTarget(tc.raw)
			if got != tc.want || host != tc.host || ok != tc.ok {
				t.Fatalf("CoverDownloadTarget(%q) = %q,%q,%v; want %q,%q,%v", tc.raw, got, host, ok, tc.want, tc.host, tc.ok)
			}
		})
	}
}
