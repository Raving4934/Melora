#!/bin/bash
# fnOS 生命周期公共实现。禁止 xtrace 和输出环境变量/用户输入。
set +x
set -euo pipefail
umask 077

# 探测自身的 Go 超时小于 2 秒；timeout 防止错误二进制或实现回归造成无限等待。
START_TIMEOUT_SECONDS=30
CLEANUP_TIMEOUT_SECONDS=5
UNINSTALL_HELPER_TIMEOUT_SECONDS=15
SERVER_LOG_MAX_BYTES=$((5 * 1024 * 1024))
SERVER_LOG_RETAIN_BYTES=$((4 * 1024 * 1024))
SERVER_LOG_CHUNK_BYTES=$((64 * 1024))

fail() {
    printf 'Melora: %s\n' "$1" >&2
    if [[ -n ${TRIM_TEMP_LOGFILE:-} && ! -L $TRIM_TEMP_LOGFILE ]]; then
        printf 'Melora: %s\n' "$1" > "$TRIM_TEMP_LOGFILE" 2>/dev/null || :
    fi
    exit 1
}

run_uninstall_helper() {
    local binary=$1 argument=$2
    command -v timeout >/dev/null || fail '缺少 timeout，不能安全执行卸载辅助校验。'
    timeout --signal=TERM --kill-after=1s "${UNINSTALL_HELPER_TIMEOUT_SECONDS}s" \
        "$binary" "$argument"
}

check_user() {
    (( EUID != 0 )) || fail '拒绝以 root 运行；请检查 config/privilege 的 package 身份。'
    [[ -n ${TRIM_UID:-} && $TRIM_UID == "$EUID" ]] || fail '执行身份不是 fnOS 指定的包用户。'
}

check_arch() {
    case "$(uname -m)" in x86_64|aarch64|arm64) ;; *) fail '仅支持 Linux amd64 或 arm64。' ;; esac
}

check_paths() {
    local name value
    for name in TRIM_APPDEST TRIM_PKGETC TRIM_PKGVAR; do
        value=${!name:-}
        [[ $value == /* && $value != / && -d $value ]] || fail '缺少有效的 fnOS 应用目录环境变量。'
    done
    # fnOS 的 target/etc/var 通常是系统管理的软链；Go 静态路径校验需要物理路径。
    APP_DIR=$(readlink -e -- "$TRIM_APPDEST") || fail '无法解析应用安装目录。'
    ETC_DIR=$(readlink -e -- "$TRIM_PKGETC") || fail '无法解析应用配置目录。'
    VAR_DIR=$(readlink -e -- "$TRIM_PKGVAR") || fail '无法解析应用数据目录。'
    SERVER="$APP_DIR/bin/melora"
    RUN_DIR="$VAR_DIR/run"
    LOG_DIR="$VAR_DIR/log"
    PID_FILE="$RUN_DIR/melora.pid"
    CONFIG_FILE="$ETC_DIR/melora.env"
    SERVER_LOG="$LOG_DIR/server.log"
}

prepare_private_dirs() {
    local dir
    for dir in "$VAR_DIR/data" "$RUN_DIR" "$LOG_DIR"; do
        [[ ! -L $dir ]] || fail '应用私有子目录不得为符号链接。'
        mkdir -p -- "$dir" || fail '无法创建应用私有目录。'
        chmod 700 -- "$dir" || fail '无法限制应用私有目录权限。'
    done
}

# 打开并收紧包用户自己的配置；解析始终使用同一描述符，避免 chmod/读取跟随替换路径。
# 调用者声明 local config_fd，并在使用后关闭。
open_private_config() {
    local expected mode private_mode fdpath device inode owner links
    [[ -f $CONFIG_FILE && ! -L $CONFIG_FILE && -r $CONFIG_FILE ]] || fail '配置文件缺失、不可读或为符号链接。'
    expected=$(stat -c '%d:%i:%u:%h' -- "$CONFIG_FILE") || fail '无法检查配置文件。'
    IFS=: read -r device inode owner links <<< "$expected"
    [[ $owner == "$EUID" ]] || fail '配置必须由包用户持有。'
    [[ $links == 1 ]] || fail '配置必须是普通单链接文件，拒绝修改硬链接。'
    exec {config_fd}< "$CONFIG_FILE" || fail '无法打开配置文件。'
    fdpath="/proc/self/fd/$config_fd"
    [[ -f $fdpath && ! -L $CONFIG_FILE && $CONFIG_FILE -ef $fdpath && $(stat -Lc '%d:%i:%u:%h' -- "$fdpath") == "$expected" ]] \
        || fail '配置在检查期间发生变化，拒绝修改。'
    mode=$(stat -Lc %a -- "$fdpath") || fail '无法检查配置权限。'
    [[ $mode =~ ^[0-7]+$ ]] || fail '无法识别配置权限。'
    # 默认 ACL 可以使新文件忽略 umask；旧配置也可能为 0644/0640。
    # 只收紧到 owner-only，原本只读的配置仍保持只读；绝不重写内容或修改属主。
    private_mode=600
    if (( (8#$mode & 0200) == 0 )); then private_mode=400; fi
    if [[ $mode != "$private_mode" ]]; then
        chmod "$private_mode" -- "$fdpath" || fail '无法收紧配置权限；未修改配置内容。'
    fi
    [[ $(stat -Lc %a -- "$fdpath") == "$private_mode" && ! -L $CONFIG_FILE && $CONFIG_FILE -ef $fdpath && $(stat -Lc '%d:%i:%u:%h' -- "$fdpath") == "$expected" ]] \
        || fail '配置权限或文件身份复核失败。'
}

init_config() {
    local config_fd
    if [[ ! -e $CONFIG_FILE && ! -L $CONFIG_FILE ]]; then
        # noclobber 防止并发初始化覆盖已有配置。
        (set -o noclobber; cat "$APP_DIR/config/melora.env.example" > "$CONFIG_FILE") \
            || fail '无法初始化配置；请检查包用户权限。'
    fi
    # 不依赖 umask：先收紧继承权限，再允许后续读取或启动。
    open_private_config
    exec {config_fd}<&-
}

no_symlink_components() {
    local current= part
    local -a parts
    IFS=/ read -r -a parts <<< "$1"
    for part in "${parts[@]}"; do
        [[ -n $part && $part != . ]] || continue
        [[ $part != .. ]] || fail '下载根不得包含父目录跳转。'
        current="$current/$part"
        [[ ! -L $current ]] || fail '下载目录及其父路径不得包含符号链接。'
    done
}

load_config() {
    # 持久化模式以配置文件为准，旧文件缺少字段必须默认 gateway。
    # 不允许上级进程意外继承的 ACCESS_MODE 改变升级后的默认入口。
    MELORA_ACCESS_MODE=gateway
    MELORA_ADDR=${MELORA_ADDR:-127.0.0.1:3780}
    MELORA_DOWNLOAD_ROOT=${MELORA_DOWNLOAD_ROOT:-}
    MELORA_AUTH_TOKEN=${MELORA_AUTH_TOKEN:-}
    MELORA_ALLOW_INSECURE_HTTP=0
    local line key value config_fd
    open_private_config
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        case "$line" in ''|'#'*) continue ;; esac
        [[ $line == *=* ]] || fail '配置格式错误；仅接受 KEY=VALUE，不接受 Shell 命令。'
        key=${line%%=*}; value=${line#*=}
        case "$key" in
            MELORA_ACCESS_MODE) MELORA_ACCESS_MODE=$value ;;
            MELORA_ADDR) MELORA_ADDR=$value ;;
            MELORA_DOWNLOAD_ROOT) MELORA_DOWNLOAD_ROOT=$value ;;
            MELORA_AUTH_TOKEN) MELORA_AUTH_TOKEN=$value ;;
            MELORA_ALLOW_INSECURE_HTTP) MELORA_ALLOW_INSECURE_HTTP=$value ;;
            *) fail '配置含不支持的键；数据目录与静态目录由包管理。' ;;
        esac
    done <&"$config_fd"
    exec {config_fd}<&-
    case "$MELORA_ALLOW_INSECURE_HTTP" in
        ''|0) MELORA_ALLOW_INSECURE_HTTP=0 ;;
        1) ;;
        *) fail 'MELORA_ALLOW_INSECURE_HTTP 仅接受空值、0 或 1。' ;;
    esac
    case "$MELORA_ACCESS_MODE" in
        gateway)
            MELORA_SOCKET="$APP_DIR/app.sock"
            MELORA_BASE_PATH=/app/melora
            MELORA_GATEWAY_AUTH=fnos-admin
            # 保留文件原样，但不把旧 token 或 TCP 地址传给网关服务/健康探测。
            MELORA_AUTH_TOKEN=
            MELORA_ALLOW_INSECURE_HTTP=0
            MELORA_ADDR=
            [[ ! -L $MELORA_SOCKET ]] || fail '网关 Socket 不得为符号链接。'
            ;;
        standalone)
            # 显式清空，不允许外部环境串入网关参数。
            MELORA_SOCKET=
            MELORA_BASE_PATH=
            MELORA_GATEWAY_AUTH=
            case "$MELORA_ADDR" in
                127.0.0.1:3780|'[::1]:3780') ;;
                0.0.0.0:3780|'[::]:3780')
                    [[ -n $MELORA_AUTH_TOKEN ]] || fail '非回环监听必须设置鉴权 token。'
                    [[ $MELORA_ALLOW_INSECURE_HTTP == 1 ]] \
                        || fail 'standalone 非回环明文监听必须显式设置 MELORA_ALLOW_INSECURE_HTTP=1；建议改用 TLS 反向代理和回环监听。' ;;
                *) fail 'standalone 仅支持回环或通配地址，端口固定 3780。' ;;
            esac
            if [[ -n $MELORA_AUTH_TOKEN ]]; then
                [[ ${#MELORA_AUTH_TOKEN} -le 4096 && $MELORA_AUTH_TOKEN =~ ^[A-Za-z0-9._~-]{32,}$ ]] \
                    || fail 'token 必须为 32–4096 字节的 URL 安全随机字符串。'
            fi
            ;;
        *) fail 'MELORA_ACCESS_MODE 仅支持 gateway 或 standalone。' ;;
    esac
    # 下载是可选能力。网关授权列表及其与管理员根的交集由 Go 在使用时校验。
    # 撤权、目录消失或被替换为软链，不得阻止用户进入应用重新选择目录。
    # 保留已有管理员根原值，不能为了启动而清空它，否则会扩大到其它授权目录。
    if [[ -n $MELORA_DOWNLOAD_ROOT ]]; then
        [[ $MELORA_DOWNLOAD_ROOT == /* && $MELORA_DOWNLOAD_ROOT != / ]] \
            || fail '下载根必须是绝对目录，不能是文件系统根。'
        if [[ $MELORA_ACCESS_MODE == standalone ]]; then
            # 独立模式不采纳系统授权列表，仍要求管理员显式根有效；不创建或 chmod 用户目录。
            no_symlink_components "$MELORA_DOWNLOAD_ROOT"
            [[ -d $MELORA_DOWNLOAD_ROOT && -w $MELORA_DOWNLOAD_ROOT && -x $MELORA_DOWNLOAD_ROOT ]] \
                || fail '下载根不存在或包用户无权写入。'
        fi
    fi
    MELORA_DATA_DIR="$VAR_DIR/data"
    WEB_DIR="$APP_DIR/web"
    [[ -x $SERVER && -s $WEB_DIR/index.html ]] || fail '二进制或前端构建文件缺失。'
}

lock_runtime() {
    command -v flock >/dev/null || fail '缺少 flock，不能安全串行执行生命周期操作。'
    [[ -d $RUN_DIR && ! -L $RUN_DIR ]] || fail '生命周期运行目录不可信。'
    [[ ! -L $RUN_DIR/control.lock ]] || fail '生命周期锁不得为符号链接。'
    if [[ -e $RUN_DIR/control.lock ]]; then
        [[ -f $RUN_DIR/control.lock && $(stat -c %u -- "$RUN_DIR/control.lock") == "$EUID" && $(stat -c %h -- "$RUN_DIR/control.lock") == 1 ]] \
            || fail '生命周期锁必须是包用户持有的普通单链接文件。'
    fi
    local shell_pid=$BASHPID guard_pid guard_read guard_write run_fd run_ref lock_path
    local run_identity lock_identity restore_noclobber=false opened=false phase
    # read-only open 仍可能被竞态换成 FIFO。coproc 的 builtin read 定时，不生成
    # sleep 孤儿；成功即通过管道取消，父进程退出导致 EOF 时也不发送信号。
    # 不在子 shell 做可能阻塞的 open，以免只终止父进程而留下阻塞子进程。
    coproc MELORA_LOCK_OPEN_GUARD {
        if IFS= read -r -t 3 _; then exit 0; else guard_status=$?; fi
        if (( guard_status > 128 )); then
            printf '%s\n' 'Melora: 安全打开生命周期锁超时；未执行服务操作或数据清理。' >&2
            kill -TERM "$shell_pid" 2>/dev/null || :
        fi
    }
    guard_pid=$MELORA_LOCK_OPEN_GUARD_PID
    guard_read=${MELORA_LOCK_OPEN_GUARD[0]}
    guard_write=${MELORA_LOCK_OPEN_GUARD[1]}
    # 先固定 run 目录，后续创建不因父目录软链替换而落到共享/音乐目录。
    if ! { exec {run_fd}< "$RUN_DIR"; } 2>/dev/null; then
        fail '无法安全打开生命周期运行目录。'
    fi
    run_ref="/proc/$shell_pid/fd/$run_fd"
    [[ -d $run_ref && ! -L $RUN_DIR && $RUN_DIR -ef $run_ref && $(stat -Lc %u -- "$run_ref") == "$EUID" ]] \
        || fail '生命周期运行目录身份发生变化。'
    run_identity=$(stat -Lc '%d:%i:%u' -- "$run_ref") || fail '无法核实运行目录身份。'
    lock_path="$run_ref/control.lock"
    if [[ ! -e $lock_path && ! -L $lock_path ]]; then
        # noclobber 在不存在时使用排他创建；不得用 >> / <>，它们会沿 dangling
        # symlink 创建外部文件。创建冲突后只尝试只读打开，仍需后续全部身份检查。
        if [[ $- != *C* ]]; then restore_noclobber=true; set -o noclobber; fi
        if { : > "$lock_path"; } 2>/dev/null; then :; fi
        if [[ $restore_noclobber == true ]]; then set +o noclobber; fi
    fi
    lock_identity=$(stat -c '%d:%i:%u:%h' -- "$lock_path") || fail '无法核实生命周期锁身份。'
    # 已有文件只读、不 CREATE、不 TRUNC；即使末次检查后被换成音乐软链也不改内容。
    if { exec 9< "$lock_path"; } 2>/dev/null; then opened=true; fi
    printf 'opened\n' >&"$guard_write" || :
    exec {guard_write}>&-
    wait "$guard_pid" || :
    exec {guard_read}<&-
    [[ $opened == true ]] || fail '无法安全打开生命周期锁；未创建外部文件或截断数据。'
    for phase in before after; do
        [[ ! -L $RUN_DIR && -d $RUN_DIR && $RUN_DIR -ef $run_ref && $(stat -c '%d:%i:%u' -- "$RUN_DIR") == "$run_identity" ]] \
            || fail '加锁期间运行目录身份发生变化。'
        [[ ! -L $RUN_DIR/control.lock && -f $RUN_DIR/control.lock && -f /proc/$shell_pid/fd/9 && $RUN_DIR/control.lock -ef /proc/$shell_pid/fd/9 ]] \
            || fail '加锁期间锁路径被替换，未执行服务操作或清理。'
        [[ $(stat -Lc '%u:%h' -- "/proc/$shell_pid/fd/9") == "$EUID:1" && $(stat -Lc '%d:%i:%u:%h' -- "/proc/$shell_pid/fd/9") == "$lock_identity" && $(stat -c '%d:%i:%u:%h' -- "$RUN_DIR/control.lock") == "$lock_identity" ]] \
            || fail '生命周期锁必须保持同一普通单链接文件身份。'
        if [[ $phase == before ]]; then
            flock -w 30 9 || fail '另一个生命周期操作仍在执行，请稍后重试。'
        fi
    done
    exec {run_fd}<&-
}

# /proc stat 的 comm 可以含空格/括号，不能直接按整行第 22 列切割。
process_info() {
    local text tail
    [[ -r /proc/$1/stat ]] || return 1
    text=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    tail=${text##*) }
    local -a fields
    read -r -a fields <<< "$tail"
    [[ ${#fields[@]} -ge 20 && ${fields[0]} != Z ]] || return 1
    PROC_START=${fields[19]}
    PROC_PARENT=${fields[1]}
    [[ $PROC_START =~ ^[0-9]+$ ]]
}

process_matches() {
    local extra exe
    [[ -f $PID_FILE && ! -L $PID_FILE ]] || return 1
    read -r PID START extra < "$PID_FILE" || return 1
    [[ $PID =~ ^[1-9][0-9]*$ && $START =~ ^[0-9]+$ && -z $extra ]] || return 1
    [[ $(stat -c %u "/proc/$PID" 2>/dev/null) == "$EUID" ]] || return 1
    process_info "$PID" || return 1
    [[ $PROC_START == "$START" ]] || return 1
    exe=$(readlink "/proc/$PID/exe" 2>/dev/null) || return 1
    [[ $exe == "$SERVER" || $exe == "$SERVER (deleted)" ]] || return 1
    kill -0 "$PID" 2>/dev/null
}

check_log_file() {
    local file=$1
    [[ ! -L $file ]] || fail '日志文件不得为符号链接。'
    if [[ -e $file ]]; then
        [[ -f $file && $(stat -c %u -- "$file") == "$EUID" && $(stat -c %h -- "$file") == 1 ]] \
            || fail '日志必须是包用户持有的普通单链接文件。'
        chmod 600 -- "$file" || fail '无法限制日志权限。'
    fi
}

prepare_server_log() {
    local file i source dest
    for file in "$SERVER_LOG" "$SERVER_LOG.1" "$SERVER_LOG.2" "$SERVER_LOG.3"; do
        check_log_file "$file"
    done
    # 每次真正启动时轮换，保留最多三份、每份最后 5 MiB；运行期间由受限写入器持续保留最新 5 MiB。
    for i in 3 2 1; do
        dest="$SERVER_LOG.$i"
        if (( i == 1 )); then source=$SERVER_LOG; else source="$SERVER_LOG.$((i - 1))"; fi
        if [[ -f $source ]]; then
            tail -c 5242880 -- "$source" > "$dest" || fail '无法轮换服务日志。'
            chmod 600 -- "$dest"
        elif [[ -f $dest ]]; then
            rm -f -- "$dest"
        fi
    done
    : > "$SERVER_LOG" || fail '无法创建服务日志。'
    chmod 600 -- "$SERVER_LOG"
}

open_server_log() {
    local expected fdpath
    expected=$(stat -c '%d:%i:%u:%h' -- "$SERVER_LOG") || fail '无法检查服务日志。'
    exec {SERVER_LOG_FD}>> "$SERVER_LOG" || fail '无法打开服务日志。'
    fdpath="/proc/self/fd/$SERVER_LOG_FD"
    [[ -f $fdpath && ! -L $SERVER_LOG && $SERVER_LOG -ef $fdpath && $(stat -Lc '%d:%i:%u:%h' -- "$fdpath") == "$expected" ]] \
        || fail '服务日志在打开期间发生变化，拒绝启动。'
}

bounded_server_log() {
    local log_fd=$1 pipe_dir=$2 chunk trim chunk_size current keep
    trap '' HUP
    exec 9>&-
    chunk=$(mktemp -- "$RUN_DIR/.server-log-chunk.XXXXXX") || return 1
    trim=$(mktemp -- "$RUN_DIR/.server-log-trim.XXXXXX") || { rm -f -- "$chunk"; return 1; }
    trap 'rm -f -- "$chunk" "$trim" "$pipe_dir/stream"; rmdir -- "$pipe_dir" 2>/dev/null || :' EXIT
    while :; do
        : > "$chunk" || return 1
        dd bs="$SERVER_LOG_CHUNK_BYTES" count=1 of="$chunk" 2>/dev/null || return 1
        chunk_size=$(stat -c %s -- "$chunk") || return 1
        (( chunk_size > 0 )) || break
        current=$(stat -Lc %s -- "/proc/self/fd/$log_fd") || return 1
        if (( current > SERVER_LOG_MAX_BYTES - chunk_size )); then
            keep=$SERVER_LOG_RETAIN_BYTES
            if (( keep > SERVER_LOG_MAX_BYTES - chunk_size )); then
                keep=$((SERVER_LOG_MAX_BYTES - chunk_size))
            fi
            tail -c "$keep" -- "/proc/self/fd/$log_fd" > "$trim" || return 1
            cat "$trim" > "/proc/self/fd/$log_fd" || return 1
        fi
        cat "$chunk" >&"$log_fd" || return 1
    done
}

audit() {
    local log="$LOG_DIR/lifecycle.log"
    check_log_file "$log"; check_log_file "$log.1"
    if [[ -f $log ]] && (( $(stat -c %s -- "$log") > 262144 )); then
        mv -f -- "$log" "$log.1"
    fi
    # 调用者只传固定事件名，不记录 token、Cookie、URL、配置或目录。
    printf '%s %s\n' "$(date -u +%FT%TZ)" "$1" >> "$log"
}

export_service_env() {
    export MELORA_ACCESS_MODE MELORA_DATA_DIR MELORA_DOWNLOAD_ROOT WEB_DIR
    export MELORA_SOCKET MELORA_BASE_PATH MELORA_GATEWAY_AUTH
    # FPK 固定 NAS 形态与网关认证，不继承云端模式或管理员凭据。
    export MELORA_DEPLOY_MODE=nas
    unset MELORA_ADMIN_USER MELORA_ADMIN_PASSWORD MELORA_TRUSTED_PROXIES
    unset TRIM_API_TOKEN MELORA_DEMO_MODE
    if [[ $MELORA_ACCESS_MODE == gateway ]]; then
        unset MELORA_ADDR MELORA_AUTH_TOKEN MELORA_ALLOW_INSECURE_HTTP
        # 仅由 fnOS 生命周期传入，不从 melora.env、向导字段或 HTTP 输入拼出授权。
        # 官方以冒号分隔，保持空值及含空格路径原样交给 Go 做边界/权限检查。
        export TRIM_DATA_ACCESSIBLE_PATHS="${TRIM_DATA_ACCESSIBLE_PATHS:-}"
    else
        unset TRIM_DATA_ACCESSIBLE_PATHS
        export MELORA_ADDR MELORA_AUTH_TOKEN MELORA_ALLOW_INSECURE_HTTP
    fi
}

healthcheck() {
    # 探测不得初始化数据库或依靠 PID/Socket 文件存在来返回成功。
    # 即使误用旧版二进制，把 --healthcheck 当成启动命令，外层超时也会收回整个探测进程组。
    (
        cd "$APP_DIR"
        export_service_env
        exec timeout --signal=TERM --kill-after=1s 2s "$SERVER" --healthcheck
    ) </dev/null >/dev/null 2>&1 9>&-
}

spawned_child_matches() {
    [[ ${SPAWN_PID:-} =~ ^[1-9][0-9]*$ && ${SPAWN_START:-} =~ ^[0-9]+$ ]] || return 1
    [[ $(stat -c %u "/proc/$SPAWN_PID" 2>/dev/null) == "$EUID" ]] || return 1
    process_info "$SPAWN_PID" || return 1
    [[ $PROC_START == "$SPAWN_START" && $PROC_PARENT == "$$" ]]
}

spawned_log_writer_matches() {
    [[ ${SPAWN_LOG_PID:-} =~ ^[1-9][0-9]*$ && ${SPAWN_LOG_START:-} =~ ^[0-9]+$ ]] || return 1
    [[ $(stat -c %u "/proc/$SPAWN_LOG_PID" 2>/dev/null) == "$EUID" ]] || return 1
    process_info "$SPAWN_LOG_PID" || return 1
    [[ $PROC_START == "$SPAWN_LOG_START" && $PROC_PARENT == "$$" ]]
}

cleanup_failed_start() {
    # 仅针对当前脚本刚创建、UID/启动时钟/父 PID 均匹配的子进程，不使用外部 PID 文件选目标。
    local deadline=$((SECONDS + CLEANUP_TIMEOUT_SECONDS))
    if spawned_child_matches; then kill -TERM "$SPAWN_PID" 2>/dev/null || :; fi
    while spawned_child_matches && (( SECONDS < deadline )); do sleep 0.1; done
    if spawned_child_matches; then
        kill -KILL "$SPAWN_PID" 2>/dev/null || :
        deadline=$((SECONDS + 2))
        while spawned_child_matches && (( SECONDS < deadline )); do sleep 0.1; done
    fi
    if spawned_child_matches; then
        # 不可中断的内核等待仍可能存在，保留 PID 供诊断；绝不无界 wait。
        audit startup_cleanup_incomplete
    else
        rm -f -- "$PID_FILE"
    fi
    local log_deadline=$((SECONDS + 2))
    while spawned_log_writer_matches && (( SECONDS < log_deadline )); do sleep 0.1; done
    if spawned_log_writer_matches; then kill -TERM "$SPAWN_LOG_PID" 2>/dev/null || :; fi
    audit startup_failed
}

start_service() {
    command -v timeout >/dev/null || fail '缺少 timeout，不能安全执行健康探测。'
    load_config
    if process_matches; then
        if healthcheck && process_matches; then return 0; fi
        fail '已有受管进程未通过健康检查；请查看私有 server.log，停止后再重试。'
    fi
    [[ ! -L $PID_FILE ]] || fail 'PID 文件不得为符号链接。'
    if [[ -e $PID_FILE ]]; then
        [[ -f $PID_FILE && $(stat -c %u -- "$PID_FILE") == "$EUID" && $(stat -c %h -- "$PID_FILE") == 1 ]] \
            || fail 'PID 文件必须是包用户持有的普通单链接文件。'
    fi
    if [[ -f $PID_FILE ]]; then chmod 600 -- "$PID_FILE" || fail '无法限制 PID 文件权限。'; fi
    : > "$PID_FILE" || fail '无法创建 PID 文件，未启动服务。'
    check_log_file "$LOG_DIR/lifecycle.log"; check_log_file "$LOG_DIR/lifecycle.log.1"
    prepare_server_log
    open_server_log
    local server_log_fd=$SERVER_LOG_FD server_log_pipe_dir server_log_pipe
    server_log_pipe_dir=$(mktemp -d -- "$RUN_DIR/.server-log-pipe.XXXXXX") \
        || { exec {SERVER_LOG_FD}>&-; fail '无法创建服务日志管道目录。'; }
    server_log_pipe="$server_log_pipe_dir/stream"
    mkfifo -m 600 -- "$server_log_pipe" \
        || { rmdir -- "$server_log_pipe_dir"; exec {SERVER_LOG_FD}>&-; fail '无法创建服务日志管道。'; }
    SPAWN_LOG_PID= SPAWN_LOG_START=
    bounded_server_log "$server_log_fd" "$server_log_pipe_dir" < "$server_log_pipe" >/dev/null 2>&1 &
    SPAWN_LOG_PID=$!
    if process_info "$SPAWN_LOG_PID" && [[ $PROC_PARENT == "$$" ]]; then
        SPAWN_LOG_START=$PROC_START
    else
        kill -TERM "$SPAWN_LOG_PID" 2>/dev/null || :
        rm -f -- "$server_log_pipe"
        rmdir -- "$server_log_pipe_dir" 2>/dev/null || :
        exec {SERVER_LOG_FD}>&-
        fail '无法启动受限服务日志写入器。'
    fi
    local deadline=$((SECONDS + START_TIMEOUT_SECONDS)) identified=false
    SPAWN_PID= SPAWN_START=
    # 不把 token 放在命令行。关闭继承的锁，防止服务长期持锁。
    (
        trap '' HUP
        cd "$APP_DIR"
        export_service_env
        exec {SERVER_LOG_FD}>&-
        exec "$SERVER"
    ) </dev/null > "$server_log_pipe" 2>&1 9>&- &
    SPAWN_PID=$!
    exec {SERVER_LOG_FD}>&-
    # 捕获子进程身份，包括执行 exec 前的 shell；即使执行失败也不做无界 wait。
    if process_info "$SPAWN_PID" && [[ $PROC_PARENT == "$$" ]]; then
        SPAWN_START=$PROC_START
        if ! printf '%s %s\n' "$SPAWN_PID" "$SPAWN_START" > "$PID_FILE"; then
            cleanup_failed_start
            fail '无法保存服务 PID；已清理本次子进程，请检查私有目录和磁盘空间。'
        fi
        identified=true
    fi
    if [[ $identified == true ]]; then
        while (( SECONDS < deadline )); do
            spawned_child_matches || break
            if process_matches && spawned_log_writer_matches && healthcheck && process_matches && spawned_log_writer_matches; then
                if (audit started); then return 0; fi
                cleanup_failed_start
                fail '无法记录启动状态；已清理本次子进程，请检查日志目录和磁盘空间。'
            fi
            sleep 0.2
        done
    fi
    cleanup_failed_start
    fail '服务未在约 30 秒内健康就绪或已退出；已执行有界清理，请查看私有 server.log。'
}

stop_service() {
    if ! process_matches; then
        [[ ! -L $PID_FILE ]] || fail 'PID 文件不得为符号链接。'
        rm -f -- "$PID_FILE"
        return 0
    fi
    kill -TERM "$PID" 2>/dev/null || :
    local i
    for i in {1..20}; do
        if ! process_matches; then
            rm -f -- "$PID_FILE"
            audit stopped
            return 0
        fi
        sleep 1
    done
    # 不强杀，保留 PID 和 .part。避免持久化尚未结束就宣称停止成功。
    fail 'SIGTERM 后 20 秒仍未退出；未强杀，请检查任务持久化并重试。'
}

main() {
    check_user; check_arch; check_paths; prepare_private_dirs; lock_runtime
    case "${1:-}" in
        start) start_service ;;
        stop) stop_service ;;
        status)
            if process_matches && (load_config; command -v timeout >/dev/null && healthcheck) && process_matches; then
                return 0
            else
                return 3
            fi ;;
        *) fail '仅支持 start、stop、status。' ;;
    esac
}

clean_absolute_path() {
    local path=$1
    [[ $path == /* && $path != / && $path != */ && $path != *//* &&
       $path != */./* && $path != */../* && $path != */. && $path != */.. ]]
}

validate_uninstall_artifact() {
    local path=$1
    [[ ! -L $path && -f $path && $(stat -c %u -- "$path") == "$EUID" && $(stat -c %h -- "$path") == 1 ]] \
        || fail '卸载阶段辅助文件不可信；未继续处理私有数据。'
}

stage_uninstall_helper() {
    local helper="$RUN_DIR/uninstall-helper" marker="$RUN_DIR/uninstall-server.path"
    local helper_tmp marker_tmp marker_size
    [[ $SERVER != *$'\n'* ]] || fail '应用服务路径无效，不能建立卸载阶段证明。'
    if [[ -e $helper || -L $helper ]]; then
        validate_uninstall_artifact "$helper"
        cmp -s -- "$SERVER" "$helper" || fail '已有卸载辅助快照与当前应用不一致。'
    fi
    if [[ -e $marker || -L $marker ]]; then
        validate_uninstall_artifact "$marker"
        [[ $(cat -- "$marker") == "$SERVER" && $(wc -l < "$marker") == 1 ]] \
            || fail '已有卸载阶段证明与当前应用不一致。'
    fi
    helper_tmp=$(mktemp -- "$RUN_DIR/.uninstall-helper.XXXXXX") \
        || fail '无法创建卸载辅助快照。'
    marker_tmp=$(mktemp -- "$RUN_DIR/.uninstall-marker.XXXXXX") || {
        rm -f -- "$helper_tmp"
        fail '无法创建卸载阶段证明。'
    }
    if ! cat -- "$SERVER" > "$helper_tmp" || ! chmod 500 -- "$helper_tmp" ||
       ! cmp -s -- "$SERVER" "$helper_tmp" ||
       ! printf '%s\n' "$SERVER" > "$marker_tmp" || ! chmod 400 -- "$marker_tmp"; then
        rm -f -- "$helper_tmp" "$marker_tmp"
        fail '无法完整写入卸载辅助快照；未开始删除私有数据。'
    fi
    # -T 防止目标在竞态中变成目录后把临时文件移入未知目录。
    if ! mv -fT -- "$helper_tmp" "$helper" || ! mv -fT -- "$marker_tmp" "$marker"; then
        rm -f -- "$helper_tmp" "$marker_tmp"
        fail '无法发布卸载辅助快照；未开始删除私有数据。'
    fi
    validate_uninstall_artifact "$helper"
    validate_uninstall_artifact "$marker"
    # Bash 在 UTF-8 locale 下的 ${#value} 是字符数，stat 是字节数；路径含中文时必须按字节复核。
    marker_size=$(printf '%s\n' "$SERVER" | wc -c)
    [[ $marker_size =~ ^[[:space:]]*[0-9]+$ &&
       $(stat -c %a -- "$helper") == 500 && $(stat -c %a -- "$marker") == 400 &&
       $(cat -- "$marker") == "$SERVER" && $(wc -l < "$marker") == 1 &&
       $(stat -c %s -- "$helper") == $(stat -c %s -- "$SERVER") &&
       $(stat -c %s -- "$marker") -eq marker_size ]] \
        || fail '卸载辅助快照发布后复核失败；未开始删除私有数据。'
}

remove_uninstall_artifacts() {
    local remove_lock=${1:-false} name path
    for name in uninstall-server.path uninstall-helper; do
        path="$RUN_DIR/$name"
        if [[ -e $path || -L $path ]]; then
            validate_uninstall_artifact "$path"
            rm -- "$path" || fail '已处理卸载选择，但无法移除内部阶段文件。'
        fi
    done
    if [[ $remove_lock == true ]]; then
        path="$RUN_DIR/control.lock"
        validate_uninstall_artifact "$path"
        rm -- "$path" || fail '已清理已知私有数据，但无法移除生命周期锁。'
    fi
}

check_callback_paths() {
    local raw value
    CALLBACK_VAR_MISSING=false
    CALLBACK_ETC_MISSING=false
    raw=${TRIM_PKGVAR:-}
    clean_absolute_path "$raw" \
        || fail '卸载回调缺少有效的应用数据目录环境变量。'
    if [[ ! -e $raw && ! -L $raw ]]; then
        VAR_DIR=$raw
        RUN_DIR="$VAR_DIR/run"
        CALLBACK_VAR_MISSING=true
    else
        [[ -d $raw ]] || fail '应用数据目录不可信，拒绝回调清理。'
        VAR_DIR=$(readlink -e -- "$raw") || fail '无法解析应用数据目录。'
        TRIM_PKGVAR=$VAR_DIR
        export TRIM_PKGVAR
        RUN_DIR="$VAR_DIR/run"
    fi

    raw=${TRIM_PKGETC:-}
    clean_absolute_path "$raw" \
        || fail '卸载回调缺少有效的应用配置目录环境变量。'
    if [[ ! -e $raw && ! -L $raw ]]; then
        ETC_DIR=$raw
        CALLBACK_ETC_MISSING=true
    else
        [[ -d $raw ]] || fail '应用配置目录不可信，拒绝回调清理。'
        ETC_DIR=$(readlink -e -- "$raw") || fail '无法解析应用配置目录。'
        TRIM_PKGETC=$ETC_DIR
        export TRIM_PKGETC
    fi
}

rmdir_private_if_empty() {
    local path=$1
    [[ ! -e $path && ! -L $path ]] && return 0
    [[ ! -L $path && -d $path && $(stat -c %u -- "$path" 2>/dev/null) == "$EUID" ]] || return 0
    rmdir -- "$path" 2>/dev/null || :
}

prune_declared_private_root() {
    local variable=$1 raw path
    raw=${!variable:-}
    [[ -n $raw ]] && clean_absolute_path "$raw" || return 0
    [[ -d $raw ]] || return 0
    path=$(readlink -e -- "$raw" 2>/dev/null) || return 0
    rmdir_private_if_empty "$path"
}

prune_uninstall_directories() {
    local share parent
    if [[ $CALLBACK_VAR_MISSING == false ]]; then
        rmdir_private_if_empty "$VAR_DIR/data/lx-sources"
        rmdir_private_if_empty "$VAR_DIR/data"
        rmdir_private_if_empty "$VAR_DIR/log"
        rmdir_private_if_empty "$VAR_DIR/run"
        rmdir_private_if_empty "$VAR_DIR"
    fi
    if [[ $CALLBACK_ETC_MISSING == false ]]; then
        rmdir_private_if_empty "$ETC_DIR"
    fi
    prune_declared_private_root TRIM_PKGHOME
    prune_declared_private_root TRIM_PKGMETA

    # data-share 可能包含多个冒号分隔路径；仅收尾本包声明的 melora/exports，
    # 且只 rmdir 空目录，绝不删除 exports 内容或其它共享路径。
    while IFS= read -r -d ':' share || [[ -n $share ]]; do
        [[ -n $share ]] && clean_absolute_path "$share" || continue
        [[ -d $share ]] || continue
        share=$(readlink -e -- "$share" 2>/dev/null) || continue
        parent=${share%/exports}
        [[ $parent != "$share" && ${parent##*/} == melora ]] || continue
        rmdir_private_if_empty "$share"
        rmdir_private_if_empty "$parent"
    done < <(printf '%s:' "${TRIM_DATA_SHARE_PATHS:-}")
}

uninstall_init_service() {
    # init 必须先停服并留下受限辅助快照；最终向导值可能直到 callback 才出现。
    if [[ ${wizard_delete_data:-} == true && ${wizard_uninstall_data:-keep} == keep ]]; then
        fail '当前卸载入口请求了清理，但未提交乐屿完整清理向导；未删除应用数据。请使用应用中心的卸载数据处理向导，选择清理并勾选确认。'
    fi
    case "${wizard_uninstall_data:-}" in
        ''|keep) ;;
        purge) [[ -n ${wizard_uninstall_confirm:-} ]] || fail '清理应用私有数据必须勾选确认；未删除私有数据。' ;;
        *) fail '未知的卸载数据选项；未停止服务或删除私有数据。' ;;
    esac

    check_paths
    lock_runtime
    local result
    if [[ ${wizard_uninstall_data:-} == purge ]]; then
        if ! result=$(run_uninstall_helper "$SERVER" --uninstall-preflight 2>&1); then
            fail "${result:-卸载清理预检失败；未删除私有数据。}"
        fi
    fi
    stop_service
    # 保留数据或 fnOS 尚未提交最终向导值时，不得进入私有数据清理校验。
    # 辅助快照仍需建立，以兼容最终 purge 选择只在 callback 阶段出现的平台流程。
    if [[ ${wizard_uninstall_data:-} == purge ]]; then
        if ! result=$(run_uninstall_helper "$SERVER" --uninstall-stop-check 2>&1); then
            fail "${result:-无法证明服务与音源 worker 已全部停止；未删除私有数据。}"
        fi
    fi
    stage_uninstall_helper

    if [[ ${wizard_uninstall_data:-} == purge ]]; then
        if ! result=$(run_uninstall_helper "$SERVER" --uninstall-cleanup 2>&1); then
            fail "${result:-卸载清理辅助失败；可能已部分清理，请检查后重试。}"
        fi
        printf '%s\n' "$result"
    elif [[ -z ${wizard_uninstall_data:-} ]]; then
        printf '%s\n' 'Melora: 卸载前未收到自定义数据选项；已安全停服，等待卸载回调的最终选择，若仍缺失则默认保留。'
    else
        printf '%s\n' 'Melora: 已选择保留；已安全停服，回调阶段不会删除应用私有数据。'
    fi
}

uninstall_callback_service() {
    if [[ ${wizard_delete_data:-} == true && ${wizard_uninstall_data:-keep} == keep ]]; then
        fail '当前卸载入口请求了清理，但未提交乐屿完整清理向导；未删除应用数据。'
    fi
    case "${wizard_uninstall_data:-keep}" in
        keep)
            check_callback_paths
            if [[ $CALLBACK_VAR_MISSING == false ]]; then
                [[ -d $RUN_DIR && ! -L $RUN_DIR ]] || fail '卸载阶段运行目录缺失或不可信；应用数据仍保留。'
                lock_runtime
                remove_uninstall_artifacts false
            fi
            if [[ -z ${wizard_uninstall_data:-} ]]; then
                printf '%s\n' 'Melora: 卸载回调未收到自定义数据选项，已按默认保留应用私有数据。'
            else
                printf '%s\n' 'Melora: 已选择保留；未删除应用私有数据。'
            fi
            ;;
        purge)
            [[ -n ${wizard_uninstall_confirm:-} ]] || fail '清理应用私有数据必须勾选确认；未删除私有数据。'
            check_callback_paths
            if [[ $CALLBACK_VAR_MISSING == true ]]; then
                [[ $CALLBACK_ETC_MISSING == true ]] \
                    || fail '应用数据目录已缺失，无法核验清理辅助；配置数据未删除。'
                prune_uninstall_directories
                printf '%s\n' 'Melora: 应用配置与数据根已不存在，无需重复清理。'
                return 0
            fi
            [[ -d $RUN_DIR && ! -L $RUN_DIR ]] || fail '卸载阶段运行目录缺失或不可信；未删除私有数据。'
            lock_runtime
            local helper="$RUN_DIR/uninstall-helper" result
            validate_uninstall_artifact "$helper"
            if ! result=$(run_uninstall_helper "$helper" --uninstall-cleanup-deferred 2>&1); then
                fail "${result:-卸载回调清理失败；可能已部分清理，请检查后重试。}"
            fi
            remove_uninstall_artifacts true
            prune_uninstall_directories
            printf '%s\n' "$result"
            ;;
        *) fail '未知的卸载数据选项；未删除私有数据。' ;;
    esac
}

hook() {
    check_user; check_arch
    case "$1" in
        install_init) : ;;
        install_callback|upgrade_callback)
            check_paths; prepare_private_dirs; lock_runtime; init_config; load_config ;;
        upgrade_init)
            main stop ;;
        uninstall_init)
            uninstall_init_service ;;
        config_init)
            # 配置前只验证，不把尚未应用的系统授权环境交给运行中的服务。
            check_paths; prepare_private_dirs; lock_runtime; load_config ;;
        config_callback)
            check_paths; prepare_private_dirs; lock_runtime; load_config
            # env 在 exec 时固定；应用配置后必须重启受管服务以刷新授权，不能只重读 Shell。
            # 不启动原本停止的应用，不接管陌生 PID；失败时不回滚到旧授权或启动第二个进程。
            if process_matches; then
                stop_service
                start_service
                audit reconfigured
            fi ;;
        uninstall_callback)
            uninstall_callback_service ;;
        *) fail '不支持的生命周期事件。' ;;
    esac
}
