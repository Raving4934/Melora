// Package uninstall 只供 fnOS 卸载 hook 使用；不初始化服务、数据库或音源运行时。
package uninstall

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
)

const (
	PreflightArgument       = "--uninstall-preflight"
	CleanupArgument         = "--uninstall-cleanup"
	StopCheckArgument       = "--uninstall-stop-check"
	DeferredCleanupArgument = "--uninstall-cleanup-deferred"
)

type operation uint8

const (
	operationPreflight operation = iota
	operationCleanup
	operationStopCheck
	operationDeferredCleanup
)

// Execute 不接受路径参数；敏感路径只能来自系统的 TRIM_* 环境。
// 卸载前由安装目录中的服务完成预检、停服证明和可选的即时清理；如果 fnOS
// 只在 callback 提交向导值，则由 init 留下的受限快照在 callback 完成同一清理。
func Execute(argument string) (int, error) {
	var op operation
	switch argument {
	case PreflightArgument:
		op = operationPreflight
	case CleanupArgument:
		op = operationCleanup
	case StopCheckArgument:
		op = operationStopCheck
	case DeferredCleanupArgument:
		op = operationDeferredCleanup
	default:
		return 0, errors.New("拒绝清理：无效辅助命令")
	}
	if op != operationStopCheck && !confirmed(os.Getenv("wizard_uninstall_data"), os.Getenv("wizard_uninstall_confirm")) {
		return 0, errors.New("拒绝清理：必须明确选择清理并勾选确认；未删除私有数据")
	}
	return execute(op)
}

func confirmed(choice, confirmation string) bool {
	if choice != "purge" || len(confirmation) > 128 {
		return false
	}
	if confirmation == "purge_confirmed" {
		return true
	}
	// 官方只保证环境变量为字符串；兼容单项原值和 JSON 单项数组，绝不做 truthy 判断。
	var values []string
	return json.Unmarshal([]byte(confirmation), &values) == nil && len(values) == 1 && values[0] == "purge_confirmed"
}

// Failure 明确区分全量预检失败与执行期部分清理，不包含路径、源代码或配置内容。
type Failure struct {
	Removed int
	Reason  string
}

func (e *Failure) Error() string {
	if e.Removed == 0 {
		return "拒绝清理：" + e.Reason + "；未删除私有数据"
	}
	return fmt.Sprintf("清理未完成：已删除 %d 个已知文件，发生部分清理且未自动回滚；%s。修复后可重试", e.Removed, e.Reason)
}
