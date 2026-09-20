//go:build !linux

package storage

import (
	"errors"
	"testing"
)

func TestUnsupportedDoesNotInventCapacity(t *testing.T) {
	if info, err := Probe(t.TempDir()); !errors.Is(err, ErrUnsupported) || info != (Info{}) {
		t.Fatalf("unsupported probe: %+v %v", info, err)
	}
}
