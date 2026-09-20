package catalog

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// catalogIssueError 保留白名单内的能力级错误码（目前是本地限流/冷却），
// 其它传输错误统一折叠为 ErrUnavailable，避免远端正文或未知码影响分类。
func catalogIssueError(err error) error {
	if err == nil {
		return nil
	}
	if catalogIssueCode(err) != "" {
		return err
	}
	return ErrUnavailable
}

func catalogIssueCode(err error) string {
	var coded interface{ CatalogIssueCode() string }
	if !errors.As(err, &coded) {
		return ""
	}
	switch code := coded.CatalogIssueCode(); code {
	case "rate_limited":
		return code
	default:
		return ""
	}
}

// catalogRequest 统一限制公开目录请求；HTTPDoer由生产公网安全客户端或测试替身注入。
func catalogRequest(ctx context.Context, client HTTPDoer, method, rawURL string, headers http.Header, body []byte) ([]byte, error) {
	if client == nil || len(rawURL) > 8192 || len(body) > 64<<10 {
		return nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, rawURL, bytes.NewReader(body))
	if err != nil {
		return nil, ErrUnavailable
	}
	req.Header = headers.Clone()
	if req.Header == nil {
		req.Header = make(http.Header)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; Melora/0.3)")
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, catalogIssueError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, ErrUnavailable
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, (1<<20)+1))
	if err != nil || len(data) > 1<<20 {
		return nil, ErrUnavailable
	}
	return data, nil
}

// catalogImage 不把远端任意URL带给浏览器，仅接收对应平台的固定图片域。
func catalogImage(raw string, hosts ...string) string {
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.User != nil || u.Port() != "" || len(raw) > 2048 || (u.Scheme != "http" && u.Scheme != "https") {
		return ""
	}
	host := strings.ToLower(u.Hostname())
	if strings.HasSuffix(host, ".") {
		return ""
	}
	allowed := false
	for _, base := range hosts {
		if host == base || strings.HasSuffix(host, "."+base) {
			allowed = true
			break
		}
	}
	if !allowed {
		return ""
	}
	u.Scheme = "https"
	u.Fragment = ""
	return u.String()
}
