#!/bin/bash
# 同步本地后端到云服务器并重启（替代 IDEA Deployment）
# 用法:
#   ./deploy-remote.sh dry-run   # 只看将要同步什么
#   ./deploy-remote.sh sync      # 只同步代码
#   ./deploy-remote.sh deploy    # 同步 + 远程 deploy.sh（打包重启）
#   ./deploy-remote.sh start     # 只远程启动（不同步）
#   ./deploy-remote.sh status    # 看远程进程/端口
set -euo pipefail

HOST="ubuntu@132.232.75.62"
REMOTE_DIR="/home/ubuntu/light-of-shadow-backend"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"
SSH_OPTS="-o BatchMode=yes -o ConnectTimeout=10"

# 不覆盖服务器上的密钥配置与构建产物、IDE、git
RSYNC_EXCLUDES=(
  --exclude 'target/'
  --exclude '.idea/'
  --exclude '.git/'
  --exclude 'uploads/'
  --exclude 'src/main/resources/application.yaml'
  --exclude 'src/test/resources/application-*.yaml'
)

sync_code() {
  local dry="$1"
  if [ "$dry" = "dry" ]; then
    echo "== dry-run：以下文件将同步到 $HOST:$REMOTE_DIR =="
    rsync -avzn "${RSYNC_EXCLUDES[@]}" "$LOCAL_DIR/" "$HOST:$REMOTE_DIR/"
  else
    echo "== 同步代码到 $HOST:$REMOTE_DIR =="
    rsync -avz "${RSYNC_EXCLUDES[@]}" "$LOCAL_DIR/" "$HOST:$REMOTE_DIR/"
  fi
}

case "${1:-help}" in
  dry-run)
    sync_code dry
    ;;
  sync)
    sync_code sync
    ;;
  deploy)
    sync_code sync
    echo "== 远程打包并后台启动 =="
    ssh $SSH_OPTS "$HOST" "cd $REMOTE_DIR && bash deploy-remote-run.sh"
    ;;
  start)
    echo "== 远程仅启动（不重新打包）=="
    ssh $SSH_OPTS "$HOST" "cd $REMOTE_DIR && bash deploy-remote-run.sh start-only"
    ;;
  status)
    ssh $SSH_OPTS "$HOST" '
      echo "--- 进程 ---"
      ps -ef | grep light-of-shadow | grep -v grep || echo "未运行"
      echo "--- 8080 ---"
      ss -ltn | grep 8080 || echo "8080 未监听"
      echo "--- 健康 ---"
      curl -s -o /dev/null -w "HTTP %{http_code}\n" --connect-timeout 3 http://127.0.0.1:8080/api/shop/products || true
    '
    ;;
  help|*)
    echo "用法: $0 {dry-run|sync|deploy|start|status}"
    exit 1
    ;;
esac
