#!/bin/bash
# 在云服务器上执行：打包（可选）+ 后台启动 + 健康检查
# 用法: bash deploy-remote-run.sh          # 打包并启动
#       bash deploy-remote-run.sh start-only  # 只启动已有 jar
set -euo pipefail
cd /home/ubuntu/light-of-shadow-backend
JAR=target/light-of-shadow-backend-0.0.1-SNAPSHOT.jar
LOG=/tmp/light-of-shadow.log

if [ "${1:-}" != "start-only" ]; then
  echo "== mvn package =="
  mvn -U clean package -DskipTests
fi

PID=$(ps -ef | grep 'light-of-shadow-backend-0.0.1' | grep -v grep | awk '{print $2}' | head -1 || true)
if [ -n "${PID:-}" ]; then
  echo "== 停旧进程 PID=$PID =="
  kill -15 "$PID" || true
  sleep 2
fi

echo "== 后台启动 =="
nohup java -jar "$JAR" > "$LOG" 2>&1 &
echo "started pid=$!"
sleep 6
echo "== 进程 =="
ps -ef | grep 'light-of-shadow-backend-0.0.1' | grep -v grep || echo "未运行"
echo "== 端口 =="
ss -ltn | grep 8080 || echo "8080 未监听"
echo "== 健康 =="
curl -s -o /dev/null -w "HTTP %{http_code}\n" --connect-timeout 3 http://127.0.0.1:8080/api/shop/products || true
echo "== 日志尾 =="
tail -8 "$LOG"
