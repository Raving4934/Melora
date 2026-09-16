//go:build !linux

package storage

import "os"

func probeFile(*os.File) (Info, error) { return Info{}, ErrUnsupported }
