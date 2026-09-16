//go:build linux

package download

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"os"
	"testing"

	"melora/internal/model"
	"melora/internal/storage"
)

// /dev/full 只用于直接驱动复制分支的确定性 ENOSPC 测试；生产 run 始终
// 通过 os.Root + openRegular 打开 .part，不存在设备路径或网络绕过入口。
func TestTransferHandlesRealENOSPCWithoutAutomaticRetry(t *testing.T) {
	full, err := os.OpenFile("/dev/full", os.O_WRONLY, 0)
	if err != nil {
		t.Skipf("/dev/full unavailable: %v", err)
	}
	defer full.Close()
	base := t.TempDir()
	m := testManager(t, base, func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, nil, nil,
		func(d *dependencies) {
			d.probe = func(*os.Root) (storage.Info, error) { return capacity(1 << 30), nil }
		})
	job := model.DownloadJob{ID: "full", Track: testTrack("full"), Quality: "standard", State: "downloading"}
	job.TargetPath = targetPath(base, job, ".audio")
	e := &entry{job: job, root: base}
	u, _ := url.Parse("https://media.example.com/audio")
	err = m.transfer(context.Background(), e, m.files, full, job, u, &partialMeta{})
	if !errors.Is(err, errSpace) {
		t.Fatalf("real write ENOSPC misclassified: %v", err)
	}
	var retry *attemptError
	if errors.As(err, &retry) {
		t.Fatal("ENOSPC retried")
	}
}
