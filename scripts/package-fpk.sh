#!/bin/bash
# 仅消费真实构建产物；不编译、不下载工具、不把 tar/zip 改名为 FPK。
set +x
set -euo pipefail
umask 022

usage() {
    cat <<'HELP'
用法：scripts/package-fpk.sh --arch amd64|arm64 [--stage-only] [--fnpack /absolute/path]
输入：dist/melora-linux-<arch>，apps/web/dist/
输出：packaging/.build/<arch>/stage/，packaging/out/melora-<version>-linux-<arch>.fpk
--stage-only 仅生成 staging，明确不创建 .fpk。
正常打包必须找到真实 fnpack（--fnpack > FNPACK > PATH）；不会自动下载或替代它。
可用 SOURCE_DATE_EPOCH 固定 staging mtime（默认 0），不保证 fnpack 输出逐字节相同。
HELP
}
fail() { printf 'Melora 打包失败：%s\n' "$1" >&2; exit "${2:-1}"; }
ARCH= STAGE_ONLY=false FNPACK_BIN=${FNPACK:-fnpack}
while (( $# )); do
    case "$1" in
        --arch) (( $# >= 2 )) || fail '--arch 缺少参数。'; ARCH=$2; shift 2 ;;
        --fnpack) (( $# >= 2 )) || fail '--fnpack 缺少参数。'; FNPACK_BIN=$2; shift 2 ;;
        --stage-only) STAGE_ONLY=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail '未知参数；使用 --help 查看用法。' ;;
    esac
done
case "$ARCH" in amd64|arm64) ;; *) fail '必须显式选择 --arch amd64 或 arm64；禁止通用二进制包。' ;; esac
if [[ $STAGE_ONLY == false ]]; then
    FNPACK_BIN=$(command -v -- "$FNPACK_BIN") || fail '找不到 fnpack。请从飞牛官方文档获取工具，或使用 --stage-only；未生成 FPK。' 127
    [[ -f $FNPACK_BIN && -x $FNPACK_BIN ]] || fail 'fnpack 不是可执行文件。' 127
    FNPACK_BIN=$(realpath -- "$FNPACK_BIN")
fi
command -v python3 >/dev/null || fail '需要 Python 3 进行 ELF、JSON 和 staging 校验。'
REPO=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
if ! VERSION=$(python3 - "$REPO/package.json" <<'PY'
import json
import re
import sys

value = json.load(open(sys.argv[1], encoding='utf-8')).get('version')
if not isinstance(value, str) or not re.fullmatch(r'\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?', value):
    raise SystemExit(1)
print(value)
PY
); then
    fail '无法从 package.json 读取有效版本。'
fi
BUILD="$REPO/packaging/.build"
OUT="$REPO/packaging/out"
[[ ! -L $BUILD && ! -L $OUT ]] || fail '构建或输出目录不得为符号链接。'
mkdir -p -- "$BUILD"
LOCK="$BUILD/$ARCH.lock"
mkdir -- "$LOCK" 2>/dev/null || fail '同架构构建正在进行或遗留锁未清理；请先确认没有打包进程。'
WORK=
FNPACK_LOCK=
cleanup() {
    if [[ -n $WORK && -d $WORK ]]; then rm -rf -- "$WORK"; fi
    if [[ -n $FNPACK_LOCK ]]; then rmdir -- "$FNPACK_LOCK" 2>/dev/null || :; fi
    rmdir -- "$LOCK" 2>/dev/null || :
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# 官方fnpack的并行同appname构建可能串用临时产物；不同架构也必须互斥。
# 只在成功取得锁后登记清理，不移除另一个进程的锁。
if [[ $STAGE_ONLY == false ]]; then
    mkdir -- "$BUILD/fnpack.lock" 2>/dev/null || fail '另一架构正在使用 fnpack 或留有锁；请确认进程后逐架构串行打包。'
    FNPACK_LOCK="$BUILD/fnpack.lock"
fi
DEST="$BUILD/$ARCH"
[[ ! -L $DEST ]] || fail '固定 staging 路径不得为符号链接。'
if [[ -e $DEST ]]; then
    [[ -d $DEST && -f $DEST/.melora-staging && ! -L $DEST/.melora-staging ]] \
        || fail '旧 staging 缺少所有权标记，拒绝删除。'
fi
WORK=$(mktemp -d "$BUILD/.$ARCH.XXXXXXXX")
python3 "$REPO/packaging/tools/stage.py" --repo "$REPO" --dest "$WORK/stage" \
    --arch "$ARCH" --epoch "${SOURCE_DATE_EPOCH:-0}"
if [[ $STAGE_ONLY == false ]]; then
    # cwd 位于全新临时目录，绝不会误认上次遗留包。只用官方文档确认的 build 语法。
    if ! (cd -- "$WORK/stage" && "$FNPACK_BIN" build) > "$WORK/fnpack.log" 2>&1; then
        cat "$WORK/fnpack.log" >&2
        fail 'fnpack build 失败；未发布新 FPK，已有输出保持不变。'
    fi
    cat "$WORK/fnpack.log"
    # fnpack 的输出文件名不作为接口假定：只接受这次调用在临时目录产生的唯一非空包。
    mapfile -d '' -t PACKAGES < <(find "$WORK" -type f -name '*.fpk' -size +0c -print0)
    (( ${#PACKAGES[@]} == 1 )) || fail 'fnpack 未生成唯一的非空 .fpk；拒绝伪造或复用旧产物。'
    mkdir -p -- "$OUT"
    PACKAGE="$OUT/melora-$VERSION-linux-$ARCH.fpk"
    [[ ! -L $PACKAGE && ! -L $PACKAGE.sha256 ]] || fail '输出文件不得为符号链接。'
    # 同一文件系统内原子替换包，校验和从真正的 fnpack 输出计算。
    mv -f -- "${PACKAGES[0]}" "$PACKAGE"
    (cd -- "$OUT"; sha256sum -- "${PACKAGE##*/}") > "$WORK/package.sha256"
    mv -f -- "$WORK/package.sha256" "$PACKAGE.sha256"
fi
if [[ -e $DEST ]]; then rm -rf -- "$DEST"; fi
printf 'melora staging v1\n' > "$WORK/.melora-staging"
mv -- "$WORK" "$DEST"
WORK=
printf 'Staging: %s\n' "$DEST/stage"
if [[ $STAGE_ONLY == true ]]; then
    printf '仅 staging：本次未调用 fnpack，未生成 .fpk；已有输出不代表本次结果。\n'
else
    printf 'FPK: %s\nSHA-256: %s\n尚未在 fnOS 真机安装验证。\n' "$PACKAGE" "$PACKAGE.sha256"
fi
