#!/bin/bash

# 定义 Jar 包的名字和路径
JAR_NAME="light-of-shadow-backend-0.0.1-SNAPSHOT.jar"
JAR_PATH="target/$JAR_NAME"

echo "========================================"
echo " 1. 开始执行 Maven 打包..."
echo "========================================"
# 执行打包（强制刷新依赖，避免本地缓存旧的解析失败）
mvn -U clean package -DskipTests

# 检查上一条命令（mvn）是否成功执行，0 代表成功
if [ $? -ne 0 ]; then
    echo "❌ 编译失败，请检查代码！"
    exit 1
fi

echo "========================================"
echo " 2. 检查并清理旧的后端进程..."
echo "========================================"
# 查找正在运行该 jar 包的进程 ID (PID)
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "发现正在运行的旧进程 (PID: $PID)，正在关闭..."
    kill -15 $PID
    sleep 2 # 等待 2 秒让进程优雅退出
else
    echo "没有发现正在运行的旧进程，直接启动新版。"
fi

echo "========================================"
echo " 启动中..."
echo "========================================"
# 启动程序（这里在前台运行，方便你直接看日志输出）
java -jar $JAR_PATH
