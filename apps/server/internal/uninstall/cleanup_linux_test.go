//go:build linux

package uninstall

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

func privateFile(t *testing.T, name string, data []byte) {
	t.Helper()
	if err := os.WriteFile(name, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func testSession(t *testing.T) (*session, string) {
	t.Helper()
	base := t.TempDir()
	for _, dir := range []string{"target", "etc", "var", "var/run", "var/data", "var/data/lx-sources", "var/log"} {
		if err := os.Mkdir(filepath.Join(base, dir), 0700); err != nil {
			t.Fatal(err)
		}
	}
	for env, path := range map[string]string{"TRIM_APPDEST": "target", "TRIM_PKGETC": "etc", "TRIM_PKGVAR": "var"} {
		t.Setenv(env, filepath.Join(base, path))
	}
	lock, err := os.OpenFile(filepath.Join(base, "var/run/control.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { lock.Close() })
	if err := unix.Flock(int(lock.Fd()), unix.LOCK_EX); err != nil {
		t.Fatal(err)
	}
	s := &session{uid: uint32(os.Geteuid()), lockFD: int(lock.Fd())}
	t.Cleanup(s.close)
	for _, pair := range []struct {
		env string
		out **directory
	}{{"TRIM_APPDEST", &s.app}, {"TRIM_PKGETC", &s.etc}, {"TRIM_PKGVAR", &s.variable}} {
		*pair.out, err = s.root(pair.env, pair.env != "TRIM_APPDEST")
		if err != nil {
			t.Fatal(err)
		}
	}
	s.run, err = s.child(s.variable, "run", false)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.validateLock(); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"etc/melora.env", "var/data/melora.db", "var/log/server.log"} {
		privateFile(t, filepath.Join(base, path), []byte("UNCHANGED"))
	}
	privateFile(t, filepath.Join(base, "var/data/lx-sources/registry.json"), []byte(`{"items":[],"activeSourceId":""}`))
	return s, base
}

func TestConfirmationIsExact(t *testing.T) {
	for _, text := range []string{"purge_confirmed", `["purge_confirmed"]`, "[ \"purge_confirmed\" ]"} {
		if !confirmed("purge", text) {
			t.Errorf("valid confirmation rejected: %q", text)
		}
	}
	for _, text := range []string{"", "true", "false", "1", `"purge_confirmed"`, "null", "[]", `["purge_confirmed", "purge_confirmed"]`, `["purge_confirmed", null]`, `{"purge_confirmed":true}`} {
		if confirmed("purge", text) {
			t.Errorf("invalid confirmation accepted: %q", text)
		}
	}
	if confirmed("keep", "purge_confirmed") || confirmed("", "purge_confirmed") {
		t.Fatal("confirmation must not override keep/missing action")
	}
}

func TestRegistryRejectsAmbiguityWithoutManagerInitialization(t *testing.T) {
	sha := sha256.Sum256([]byte("throw new Error('never execute');"))
	hash := hex.EncodeToString(sha[:])
	item := fmt.Sprintf(`{"id":%q,"sha256":%q,"name":"metadata only"}`, hash[:24], hash)
	good := `{"items":[` + item + `],"activeSourceId":"` + hash[:24] + `"}`
	entries, err := parseRegistry([]byte(good))
	if err != nil || len(entries) != 1 || entries[0].id != hash[:24] {
		t.Fatalf("valid registry rejected: %v", err)
	}
	for _, data := range []string{
		"", "null", "[]", "{}", `{"items":null}`, `{"items":[]}{"items":[]}`,
		`{"items":[],"items":[]}`, `{"items":[],"Items":[]}`, `{"items":[],"activeSourceId":null}`,
		`{"items":[],"activeSourceId":"missing"}`, `{"items":[],"unknown":"preserve rather than guess"}`,
		`{"items":[` + item + `,` + item + `]}`,
		`{"items":[{"id":"../outside","sha256":"` + hash + `"}]}`,
		strings.Replace(good, hash[:24], strings.Repeat("a", 24), 1),
		strings.Replace(good, `"name":"metadata only"`, `"name":"one","name":"two"`, 1),
		strings.Replace(good, "metadata only", "invalid\xffutf8", 1),
		`{"items":[` + strings.TrimSuffix(strings.Repeat(item+",", 21), ",") + `]}`,
	} {
		if _, err := parseRegistry([]byte(data)); err == nil {
			t.Errorf("unsafe registry accepted: %q", data)
		}
	}
}

func TestInheritedLockRequiresSameOpenDescriptionAndExclusiveLock(t *testing.T) {
	s, base := testSession(t)
	original := s.lockFD
	for _, mode := range []string{"missing", "unlocked-same-inode", "other-file", "shared"} {
		t.Run(mode, func(t *testing.T) {
			s.lockFD = -1
			if mode != "missing" {
				name := filepath.Join(base, "var/run/control.lock")
				if mode == "other-file" {
					name = filepath.Join(base, "other.lock")
				}
				f, err := os.OpenFile(name, os.O_CREATE|os.O_RDWR, 0600)
				if err != nil {
					t.Fatal(err)
				}
				defer f.Close()
				s.lockFD = int(f.Fd())
				if mode == "other-file" {
					if err := unix.Flock(s.lockFD, unix.LOCK_EX|unix.LOCK_NB); err != nil {
						t.Fatal(err)
					}
				}
				if mode == "shared" {
					if err := unix.Flock(original, unix.LOCK_UN); err != nil {
						t.Fatal(err)
					}
					if err := unix.Flock(s.lockFD, unix.LOCK_SH|unix.LOCK_NB); err != nil {
						t.Fatal(err)
					}
				}
			}
			if err := s.validateLock(); err == nil {
				t.Fatal("accepted invalid inherited lock")
			}
		})
	}
	s.lockFD = original
}

func TestRegistryFailurePrecedesAnyDeletion(t *testing.T) {
	s, base := testSession(t)
	privateFile(t, filepath.Join(base, "var/data/lx-sources/registry.json"), []byte("corrupt"))
	if err := s.plan(); err == nil {
		t.Fatal("corrupt registry accepted")
	}
	for _, name := range []string{"etc/melora.env", "var/data/melora.db", "var/log/server.log"} {
		data, err := os.ReadFile(filepath.Join(base, name))
		if err != nil || string(data) != "UNCHANGED" {
			t.Fatalf("preflight altered %s", name)
		}
	}
}

func TestPartialIOFailureReportsCountAndRetainsRegistryForRetry(t *testing.T) {
	s, base := testSession(t)
	if err := s.plan(); err != nil {
		t.Fatal(err)
	}
	if err := s.revalidate(); err != nil {
		t.Fatal(err)
	}
	calls := 0
	n, err := s.apply(func(fd int, name string) error {
		calls++
		if calls == 2 {
			return unix.EIO
		}
		return unix.Unlinkat(fd, name, 0)
	})
	var failure *Failure
	if n != 1 || !errors.As(err, &failure) || failure.Removed != 1 || !strings.Contains(err.Error(), "部分清理") {
		t.Fatalf("missing partial failure semantics: n=%d, err=%v", n, err)
	}
	if _, err := os.Stat(filepath.Join(base, "var/data/lx-sources/registry.json")); err != nil {
		t.Fatal("registry removed before completion")
	}
}

func TestChangedTargetsDirectoriesAndLockFailClosed(t *testing.T) {
	for _, kind := range []string{"file", "directory", "lock"} {
		t.Run(kind, func(t *testing.T) {
			s, base := testSession(t)
			outside := filepath.Join(base, "outside")
			if err := os.Mkdir(outside, 0700); err != nil {
				t.Fatal(err)
			}
			privateFile(t, filepath.Join(outside, "melora.db"), []byte("EXTERNAL"))
			if err := s.plan(); err != nil {
				t.Fatal(err)
			}
			calls := 0
			n, err := s.apply(func(fd int, name string) error {
				calls++
				if err := unix.Unlinkat(fd, name, 0); err != nil {
					return err
				}
				if calls == 1 {
					switch kind {
					case "file":
						path := filepath.Join(base, "var/data/melora.db")
						if err := os.Remove(path); err != nil {
							t.Fatal(err)
						}
						if err := os.Symlink(filepath.Join(outside, "melora.db"), path); err != nil {
							t.Fatal(err)
						}
					case "directory":
						path := filepath.Join(base, "var/data")
						if err := os.Rename(path, filepath.Join(base, "old-data")); err != nil {
							t.Fatal(err)
						}
						if err := os.Symlink(outside, path); err != nil {
							t.Fatal(err)
						}
					case "lock":
						path := filepath.Join(base, "var/run/control.lock")
						if err := os.Rename(path, path+".old"); err != nil {
							t.Fatal(err)
						}
						privateFile(t, path, nil)
					}
				}
				return nil
			})
			if err == nil || n != 1 || calls != 1 {
				t.Fatalf("continued after changed boundary: %d %d %v", n, calls, err)
			}
			data, _ := os.ReadFile(filepath.Join(outside, "melora.db"))
			if string(data) != "EXTERNAL" {
				t.Fatal("external content changed")
			}
		})
	}
}

func fakeProcess(t *testing.T, root string, pid int, uid uint32, state, exe string) {
	t.Helper()
	base := filepath.Join(root, strconv.Itoa(pid))
	if err := os.Mkdir(base, 0700); err != nil {
		t.Fatal(err)
	}
	privateFile(t, filepath.Join(base, "status"), []byte(fmt.Sprintf("Uid:\t%d\t%d\t%d\t%d\n", uid, uid, uid, uid)))
	fields := []string{state}
	for range 18 {
		fields = append(fields, "0")
	}
	fields = append(fields, "12345")
	privateFile(t, filepath.Join(base, "stat"), []byte(fmt.Sprintf("%d (spaces ) and ( parentheses) %s", pid, strings.Join(fields, " "))))
	if exe != "" {
		if err := os.Symlink(exe, filepath.Join(base, "exe")); err != nil {
			t.Fatal(err)
		}
	}
}

func TestStoppedChecksExactUIDExecutableDeletedAndExcludesSelf(t *testing.T) {
	const server = "/private/melora/bin/melora"
	for _, tc := range []struct {
		name, state, exe string
		uid              uint32
		self             int
		refuse           bool
	}{
		{"service-or-worker", "S", server, 1234, 99, true},
		{"deleted", "S", server + " (deleted)", 1234, 99, true},
		{"same-basename-other-path", "S", "/another/bin/melora", 1234, 99, false},
		{"other-uid", "S", server, 1235, 99, false},
		{"self", "S", server, 1234, 42, false},
		{"zombie", "Z", "", 1234, 99, false},
		{"missing-exe-not-stopped", "S", "", 1234, 99, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			fakeProcess(t, root, 42, tc.uid, tc.state, tc.exe)
			s := &session{uid: 1234, server: server}
			err := s.stopped(root, tc.self)
			if (err != nil) != tc.refuse {
				t.Fatalf("refuse=%v, err=%v", tc.refuse, err)
			}
		})
	}
}

func TestMalformedStatAndStatusCannotProveStopped(t *testing.T) {
	for _, text := range []string{"", "42 (broken", "42 (ok) S 0"} {
		if _, _, err := parseProcessStat(text); err == nil {
			t.Fatalf("bad stat accepted: %q", text)
		}
	}
	if _, err := processUID("Uid:\tunknown"); err == nil {
		t.Fatal("bad process UID accepted")
	}
}
