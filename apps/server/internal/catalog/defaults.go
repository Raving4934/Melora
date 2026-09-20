package catalog

// NewAllRegistry 共享一个受保护的元数据HTTP传输，各Adapter保持独立ID与协议转换。
func NewAllRegistry(netease *WY) *Registry {
	return NewRegistry(map[string]Adapter{
		"wy": netease,
		"tx": NewTX(netease.http),
		"kw": NewKW(netease.http),
		"kg": NewKG(netease.http),
		"mg": NewMG(netease.http),
	})
}
