// 独立编译的纯 Go 测试 worker：真实 exec，不在测试进程运行用户 JS。
package main

import (
	"melora/internal/lxruntime"
	"os"
	"strings"
)

func main() {
	if len(os.Args) != 2 || os.Args[1] != lxruntime.WorkerArgument {
		os.Exit(78)
	}
	allowed := map[string]bool{"LANG": true, "TZ": true, "GOMAXPROCS": true, "GOMEMLIMIT": true, "GOGC": true, "GOTRACEBACK": true}
	for _, entry := range os.Environ() {
		key, _, _ := strings.Cut(entry, "=")
		if !allowed[key] {
			os.Exit(79)
		}
	}
	os.Exit(lxruntime.WorkerMain())
}
