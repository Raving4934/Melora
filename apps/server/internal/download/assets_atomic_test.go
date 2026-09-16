package download

import (
	"bytes"
	"errors"
	"os"
	"sync"
	"testing"
)

func TestV5AssetConcurrentPublishIsWholeAndNoReplace(t *testing.T) {
	for _, extension := range []string{".lrc", ".jpg", ".png"} {
		t.Run(extension, func(t *testing.T) {
			m := testManager(t, t.TempDir(), nil, nil, nil, nil, nil)
			const writers = 12
			start := make(chan struct{})
			results := make(chan error, writers)
			var wg sync.WaitGroup
			for i := range writers {
				wg.Add(1)
				go func(i int) {
					defer wg.Done()
					<-start
					results <- m.writeAsset(m.files, "shared"+extension, bytes.Repeat([]byte{byte('a' + i)}, 256<<10))
				}(i)
			}
			close(start)
			wg.Wait()
			close(results)
			wins := 0
			for err := range results {
				if err == nil {
					wins++
				} else if !errors.Is(err, errCollision) {
					t.Fatalf("unexpected publish failure: %v", err)
				}
			}
			data, err := m.files.ReadFile("shared" + extension)
			if err != nil || wins != 1 || len(data) != 256<<10 || data[0] < 'a' || data[0] >= 'a'+writers || !bytes.Equal(data, bytes.Repeat(data[:1], len(data))) {
				t.Fatalf("partial or overlapping asset: wins=%d bytes=%d err=%v", wins, len(data), err)
			}
			dir, err := m.files.Open(".")
			if err != nil {
				t.Fatal(err)
			}
			defer dir.Close()
			names, err := dir.Readdirnames(-1)
			if err != nil || len(names) != 1 || names[0] != "shared"+extension {
				t.Fatalf("staging artifact leaked: %v %v", names, err)
			}
			info, err := m.files.Stat(names[0])
			if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() != os.FileMode(0600) {
				t.Fatal("unsafe asset type or permissions")
			}
		})
	}
}
