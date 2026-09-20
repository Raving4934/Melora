// Package config 校验监听地址和用户授权的目录边界。
package config

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"melora/internal/storage"
)

const (
	// DeployModeNAS 保留飞牛网关/服务端下载的默认部署形态。
	DeployModeNAS = "nas"
	// DeployModeCloud 面向公网服务器：仅在线播放，不提供任何下载能力。
	DeployModeCloud = "cloud"
)

type Config struct {
	downloadGrants        []downloadGrant
	Addr                  string
	DataDir               string
	WebDir                string
	DownloadRoot          string
	DownloadRoots         []string
	DownloadAuthorization string
	AuthToken             string
	AdminUser             string
	AdminPassword         string
	DeployMode            string
	TrustedProxyNets      []*net.IPNet
	AllowInsecureHTTP     bool
	SocketPath            string
	BasePath              string
	GatewayAuth           string
}

func Load() (Config, error) { return FromEnv(os.Getenv) }
func FromEnv(getenv func(string) string) (Config, error) {
	c := Config{Addr: getenv("MELORA_ADDR"), DataDir: getenv("MELORA_DATA_DIR"), WebDir: getenv("WEB_DIR"), DownloadRoot: getenv("MELORA_DOWNLOAD_ROOT"), AuthToken: getenv("MELORA_AUTH_TOKEN")}
	allowInsecureHTTP, err := explicitBoolean(getenv("MELORA_ALLOW_INSECURE_HTTP"))
	if err != nil {
		return c, fmt.Errorf("MELORA_ALLOW_INSECURE_HTTP: %w", err)
	}
	c.AllowInsecureHTTP = allowInsecureHTTP
	c.SocketPath = getenv("MELORA_SOCKET")
	c.BasePath = getenv("MELORA_BASE_PATH")
	c.GatewayAuth = getenv("MELORA_GATEWAY_AUTH")
	c.AdminUser = getenv("MELORA_ADMIN_USER")
	c.AdminPassword = getenv("MELORA_ADMIN_PASSWORD")
	c.DeployMode = getenv("MELORA_DEPLOY_MODE")
	if c.TrustedProxyNets, err = ParseTrustedProxies(getenv("MELORA_TRUSTED_PROXIES")); err != nil {
		return c, err
	}
	if c.Addr == "" {
		c.Addr = "127.0.0.1:3780"
	}
	if c.DataDir == "" {
		c.DataDir = "./data"
	}
	if c.WebDir == "" {
		// 同时支持从仓库根目录与 apps/server 执行 go run。
		c.WebDir = "apps/web/dist"
		if _, err := os.Stat("../web"); err == nil {
			if _, err := os.Stat("go.mod"); err == nil {
				c.WebDir = "../web/dist"
			}
		}
	} else if !filepath.IsAbs(c.WebDir) {
		return c, errors.New("WEB_DIR 必须是绝对路径")
	}
	if c.DataDir, err = filepath.Abs(c.DataDir); err != nil {
		return c, err
	}
	if c.WebDir, err = filepath.Abs(c.WebDir); err != nil {
		return c, err
	}
	if c.AdminUser == "" {
		c.AdminUser = "admin"
	}
	if c.DeployMode == "" {
		c.DeployMode = DeployModeNAS
	}
	if c.DeployMode != DeployModeNAS && c.DeployMode != DeployModeCloud {
		return c, errors.New("MELORA_DEPLOY_MODE 仅支持 nas 或 cloud")
	}
	if len(c.AdminUser) > 64 {
		return c, errors.New("MELORA_ADMIN_USER 不得超过 64 字节")
	}
	if c.AdminPassword != "" && (len(c.AdminPassword) < 8 || len(c.AdminPassword) > 4096) {
		return c, errors.New("MELORA_ADMIN_PASSWORD 长度必须为 8–4096 字节")
	}
	if c.DeployMode == DeployModeCloud && c.AuthToken == "" && c.AdminPassword == "" {
		return c, errors.New("cloud 部署必须配置 MELORA_ADMIN_PASSWORD 或 MELORA_AUTH_TOKEN")
	}
	if c.DownloadRoot != "" {
		if c.DeployMode == DeployModeCloud {
			return c, errors.New("cloud 部署不提供服务端下载目录，请取消 MELORA_DOWNLOAD_ROOT")
		}
		if !filepath.IsAbs(c.DownloadRoot) {
			return c, errors.New("MELORA_DOWNLOAD_ROOT 必须是已存在的绝对目录")
		}
		c.DownloadRoot = filepath.Clean(c.DownloadRoot)
		if err = NoSymlinks(c.DownloadRoot); err != nil && c.GatewayAuth != "fnos-admin" {
			return c, fmt.Errorf("授权下载根目录无效: %w", err)
		}
	}
	if c.SocketPath == "" {
		if err = ValidateAddress(c.Addr, c.AuthToken, c.AdminPassword); err != nil {
			return c, err
		}
	}
	// fnOS 中 DownloadRoot 只是收窄上限；私有边界按最终 canonical 交集检查，不能误判宽上限。
	if overlaps(c.WebDir, c.DataDir) || (c.GatewayAuth != "fnos-admin" && c.DownloadRoot != "" && overlaps(c.WebDir, c.DownloadRoot)) {
		return c, errors.New("静态目录不得与数据或下载目录重叠")
	}
	if c.GatewayAuth != "fnos-admin" && c.DownloadRoot != "" && overlaps(c.DataDir, c.DownloadRoot) {
		return c, errors.New("私有数据目录不得与授权下载目录重叠")
	}
	if err = c.ValidateGateway(); err != nil {
		return c, err
	}
	c.loadDownloadAuthorization(getenv)
	return c, nil
}
func IsLoopbackHost(host string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ParseTrustedProxies 解析逗号分隔的代理地址/CIDR；只在显式配置时才信任
// 转发头，空配置表示不信任任何代理（保持原有防伪造语义）。
func ParseTrustedProxies(raw string) ([]*net.IPNet, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	var nets []*net.IPNet
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.Contains(part, "/") {
			_, network, err := net.ParseCIDR(part)
			if err != nil {
				return nil, fmt.Errorf("MELORA_TRUSTED_PROXIES 含无效网段: %q", part)
			}
			nets = append(nets, network)
			continue
		}
		ip := net.ParseIP(part)
		if ip == nil {
			return nil, fmt.Errorf("MELORA_TRUSTED_PROXIES 含无效地址: %q", part)
		}
		bits := 32
		if ip.To4() == nil {
			bits = 128
		}
		nets = append(nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
	}
	return nets, nil
}

func explicitBoolean(value string) (bool, error) {
	switch value {
	case "", "0":
		return false, nil
	case "1":
		return true, nil
	default:
		return false, errors.New("仅接受 0、1 或空值")
	}
}

func ValidateAddress(addr, token, password string) error {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return errors.New("MELORA_ADDR 必须包含主机和端口")
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return errors.New("监听端口无效")
	}
	if !IsLoopbackHost(host) && token == "" && password == "" {
		return errors.New("非回环监听必须配置 MELORA_AUTH_TOKEN 或 MELORA_ADMIN_PASSWORD")
	}
	if token != "" && (len(token) < 24 || len(token) > 4096) {
		return errors.New("MELORA_AUTH_TOKEN 长度必须为 24–4096 字节；建议使用随机令牌")
	}
	return nil
}
func Within(root, path string) bool {
	relative, err := filepath.Rel(filepath.Clean(root), filepath.Clean(path))
	return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) && !filepath.IsAbs(relative)
}
func overlaps(a, b string) bool { return Within(a, b) || Within(b, a) }

// NoSymlinks 拒绝任何路径分量中的符号链接，不将 EvalSymlinks 的结果当作授权。
func NoSymlinks(path string) error {
	return storage.ValidateDirectoryPath(path)
}

// ValidateDownloadPath 只允许显式授权根内部的现存目录；不创建用户目录。
// 写入探针通过 os.Root 完成，无法由路径逃逸到授权根之外。
func ValidateDownloadPath(authorized, path string) (string, error) {
	if authorized == "" {
		return "", errDownloadNotAuthorized
	}
	if !validDownloadInput(path) || !validDownloadInput(authorized) {
		return "", storage.ErrDirectoryInvalid
	}
	authorized, path = filepath.Clean(authorized), filepath.Clean(path)
	if authorized == string(filepath.Separator) {
		return "", storage.ErrDirectoryOutside
	}
	if !Within(authorized, path) {
		return "", storage.ErrDirectoryOutside
	}
	// 此静态函数没有 fnOS 授权上下文，不接受根 alias；内层链接仍须留在显式根内。
	trusted, err := storage.OpenDirectory(authorized)
	if err != nil {
		return "", err
	}
	defer trusted.Close()
	rel, _ := filepath.Rel(authorized, path)
	selected, canonical, err := storage.OpenContainedDirectory(trusted, authorized, rel, nil)
	if err != nil {
		return "", err
	}
	defer selected.Close()
	if err := probeDownloadDirectory(selected); err != nil {
		return "", err
	}
	return canonical, nil
}

// ValidateRuntimePaths 在目录创建后再次检查真实目录边界，静态资源绝不能包含私有数据。
func (c Config) ValidateRuntimePaths() error {
	if err := NoSymlinks(c.DataDir); err != nil {
		return fmt.Errorf("数据目录: %w", err)
	}
	if _, err := os.Stat(c.WebDir); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	if err := NoSymlinks(c.WebDir); err != nil {
		return fmt.Errorf("静态目录: %w", err)
	}
	// fnOS 中 DownloadRoot 只是收窄上限；私有边界按最终 canonical 交集检查，不能误判宽上限。
	if overlaps(c.WebDir, c.DataDir) || (c.GatewayAuth != "fnos-admin" && c.DownloadRoot != "" && overlaps(c.WebDir, c.DownloadRoot)) {
		return errors.New("静态目录与私有目录重叠")
	}
	return nil
}

// ValidateGateway 只支持稳定的 fnOS 网关前缀；standalone 非回环明文监听必须显式确认风险。
// 绝不在 TCP 监听上信任网关身份 Header。
func (c Config) ValidateGateway() error {
	if c.SocketPath == "" && c.BasePath == "" && c.GatewayAuth == "" {
		host, _, err := net.SplitHostPort(c.Addr)
		if err == nil && !IsLoopbackHost(host) && !c.AllowInsecureHTTP {
			return errors.New("standalone 非回环明文监听必须显式设置 MELORA_ALLOW_INSECURE_HTTP=1；建议改用 TLS 反向代理并仅监听回环地址")
		}
		return nil
	}
	if c.GatewayAuth != "fnos-admin" || c.SocketPath == "" || c.BasePath != "/app/melora" {
		return errors.New("网关模式必须同时配置 Unix Socket、/app/melora 前缀及 fnos-admin 鉴权")
	}
	if !filepath.IsAbs(c.SocketPath) || filepath.Clean(c.SocketPath) != c.SocketPath || len(c.SocketPath) > 107 {
		return errors.New("Unix Socket 必须是规范绝对路径且不超过 107 字节")
	}
	if err := NoSymlinks(filepath.Dir(c.SocketPath)); err != nil {
		return fmt.Errorf("Unix Socket 父目录校验失败: %w", err)
	}
	// 下载区域是否包含 Socket 由 safeDownloadCanonical 针对授权交集判断。
	if Within(c.WebDir, c.SocketPath) || Within(c.DataDir, c.SocketPath) {
		return errors.New("Unix Socket 必须位于独立应用目录，不得位于 Web 或数据库目录")
	}
	return nil
}
