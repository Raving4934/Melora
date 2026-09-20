package api

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func TestV10SourceExportRequiresSessionAndNeverLeaksIntoList(t *testing.T) {
	s, manager, runner := liveSetup(t, "test-source-export-token")
	code := "/** @name 导出回归 */\nconst privateMarker = 'OWNED-EXPORT-CONTENT';"
	source, _, err := manager.Import(t.Context(), "source-export.js", []byte(code), nil)
	if err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/sources/" + source.ID + "/export"
	denied := request(s, "GET", path, nil, nil)
	assertStatus(t, denied, 401)
	if strings.Contains(denied.Body.String(), "OWNED-EXPORT-CONTENT") {
		t.Fatal("code leaked without session")
	}
	login := request(s, "POST", "/api/v1/auth/session", map[string]string{"token": "test-source-export-token"}, nil)
	assertStatus(t, login, 200)
	cookie := login.Result().Cookies()[0]
	got := request(s, "GET", path, nil, cookie)
	assertStatus(t, got, 200)
	var result struct {
		Filename string `json:"filename"`
		Content  string `json:"content"`
	}
	if err := json.Unmarshal(got.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Filename != "source-export.js" || result.Content != code || got.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("export bytes/name/cache boundary incorrect")
	}
	if runner.calls != 0 {
		t.Fatal("export invoked script")
	}
	list := request(s, "GET", "/api/v1/sources", nil, cookie)
	assertStatus(t, list, 200)
	if strings.Contains(list.Body.String(), "OWNED-EXPORT-CONTENT") {
		t.Fatal("code leaked into source list")
	}
	assertStatus(t, request(s, "GET", "/api/v1/sources/aaaaaaaaaaaaaaaaaaaaaaaa/export", nil, cookie), http.StatusNotFound)
	assertStatus(t, request(s, "POST", path, nil, cookie), http.StatusMethodNotAllowed)
}

func TestV10SourceExportRetainsJSImportableExtension(t *testing.T) {
	for _, length := range []int{158, 159, 160, 180} {
		t.Run(strings.Repeat("a", length)+".js", func(t *testing.T) {
			s, manager, runner := liveSetup(t, "")
			const code = "// synthetic round-trip fixture only\n"
			source, _, err := manager.Import(t.Context(), strings.Repeat("a", length)+".js", []byte(code), nil)
			if err != nil {
				t.Fatal(err)
			}
			response := request(s, "GET", "/api/v1/sources/"+source.ID+"/export", nil, nil)
			assertStatus(t, response, http.StatusOK)
			var result struct {
				Filename string `json:"filename"`
				Content  string `json:"content"`
			}
			if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
				t.Fatal(err)
			}
			if !strings.HasSuffix(result.Filename, ".js") || len([]rune(result.Filename)) > 160 || result.Content != code {
				t.Fatalf("invalid export filename/bytes: filename=%q", result.Filename)
			}
			if response.Header().Get("Cache-Control") != "no-store" || runner.calls != 0 {
				t.Fatal("export changed runtime/cache contract")
			}
			again, created, err := manager.Import(t.Context(), result.Filename, []byte(result.Content), nil)
			if err != nil || created || again.ID != source.ID {
				t.Fatalf("cannot reimport export: created=%v err=%v", created, err)
			}
		})
	}
}
