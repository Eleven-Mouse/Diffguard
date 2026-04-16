#!/bin/bash
# DiffGuard 启动脚本
# 用法: ./diffguard.sh review --staged

# 查找 Java 运行时
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java &> /dev/null; then
    JAVA="java"
else
    echo "错误：未找到 Java 运行时。请设置 JAVA_HOME 或将 java 添加到 PATH。"
    exit 1
fi

# 查找 JAR 文件（相对于脚本所在目录）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/target/diffguard-1.0.0.jar"

if [ ! -f "$JAR" ]; then
    echo "错误：未找到 DiffGuard JAR 文件：$JAR"
    echo "请先运行 mvn package 构建。"
    exit 1
fi

exec "$JAVA" -jar "$JAR" "$@"
