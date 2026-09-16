//go:build linux

package uninstall

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRegistryCaseFoldAliasesCannotAuthorizeDifferentScripts(t *testing.T) {
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	for _, alias := range []string{"SHA256", "Sha256", "ſha256"} {
		raw := []byte(`{"items":[{"id":"` + a[:24] + `","sha256":"` + a + `","ID":"` + b[:24] + `","` + alias + `":"` + b + `"}],"activeSourceId":""}`)
		// 与 Manager 的 json tags 相同；真实 encoding/json 在无防护时会采用后来的别名。
		var manager struct {
			Items []struct {
				ID     string `json:"id"`
				SHA256 string `json:"sha256"`
			} `json:"items"`
		}
		if err := json.Unmarshal(raw, &manager); err != nil || manager.Items[0].ID != b[:24] || manager.Items[0].SHA256 != b {
			t.Fatalf("fixture did not reproduce Manager semantics: %v", err)
		}
		if _, err := parseRegistry(raw); err == nil {
			t.Errorf("ambiguous %s alias accepted", alias)
		}
	}
}

func TestRegistryMetadataTypesMatchManagerWithoutConstructingIt(t *testing.T) {
	hash := strings.Repeat("a", 64)
	for _, extra := range []string{`"name":42`, `"platforms":"not-a-map"`, `"allowHTTPHosts":"not-an-array"`} {
		raw := []byte(`{"items":[{"id":"` + hash[:24] + `","sha256":"` + hash + `",` + extra + `}],"activeSourceId":""}`)
		if _, err := parseRegistry(raw); err == nil {
			t.Errorf("Manager-incompatible metadata accepted: %s", extra)
		}
	}
}

func TestRegistryNonCanonicalAndEqualFoldKeysAreRejected(t *testing.T) {
	hash := strings.Repeat("a", 64)
	for _, key := range []string{"SHA256", "Sha256", "ſha256"} {
		raw := []byte(`{"items":[{"id":"` + hash[:24] + `","` + key + `":"` + hash + `"}],"activeSourceId":""}`)
		if _, err := parseRegistry(raw); err == nil {
			t.Errorf("non-canonical critical key accepted: %s", key)
		}
	}
	if err := uniqueJSON([]byte(`{"key":1,"Key":2}`)); err == nil {
		t.Fatal("Unicode EqualFold duplicate accepted")
	}
}
