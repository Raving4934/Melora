package netguard

import (
	"bufio"
	"bytes"
	"net/http"
	"strings"
	"testing"
)

func TestV12MetadataQueryEscapesOnlyLiteralUnsafeBytes(t *testing.T) {
	for _, tc := range []struct{ query, want string }{
		{`name=a b&quote="x"`, `name=a%20b&quote=%22x%22`},
		{`signature=fixture%2Fonly&x=1&x=2&literal=a+b`, `signature=fixture%2Fonly&x=1&x=2&literal=a+b`},
		{"q='<>`{}|\\^'", "q=%27%3C%3E%60%7B%7D%7C%5C%5E%27"},
	} {
		t.Run(tc.want, func(t *testing.T) {
			original := "https://metadata.example.com/a%20b?" + tc.query
			b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				if r.URL.RawQuery != tc.want {
					t.Errorf("query=%q want=%q", r.URL.RawQuery, tc.want)
				}
				var wire bytes.Buffer
				if err := r.Write(&wire); err != nil {
					t.Fatal(err)
				}
				parsed, err := http.ReadRequest(bufio.NewReader(&wire))
				if err != nil {
					t.Fatal("serialized request contains invalid literal bytes")
				}
				parsed.Body.Close()
				return response(r, 200, []byte(`{}`)), nil
			}))
			if _, err := b.Do(t.Context(), Request{URL: original}); err != nil {
				t.Fatal(err)
			}
		})
	}
}
func TestV12RedirectQueryIsEscapedWithoutHidingPrivateTarget(t *testing.T) {
	for _, private := range []bool{false, true} {
		calls := 0
		b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls++
			if calls == 1 {
				resp := response(r, 302, nil)
				target := "https://metadata.example.com/next?name=a b"
				if private {
					target = "https://127.0.0.1/next?name=a b"
				}
				resp.Header.Set("Location", target)
				return resp, nil
			}
			if !strings.Contains(r.URL.RawQuery, "a%20b") {
				t.Error("redirect query not escaped")
			}
			return response(r, 200, nil), nil
		}))
		_, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/"})
		if private {
			if err == nil || calls != 1 {
				t.Fatal("private redirect connected")
			}
		} else if err != nil || calls != 2 {
			t.Fatal("safe redirect failed")
		}
	}
}
