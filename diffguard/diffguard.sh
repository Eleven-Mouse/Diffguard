#!/bin/bash
# DiffGuard 启动脚本
# 用法: ./diffguard.sh review --staged

JAVA="/c/Users/33313/.jdks/ms-17.0.18/bin/java.exe"
JAR="/c/Users/33313/Desktop/x/diffguard/target/diffguard-1.0.0.jar"

$JAVA -jar $JAR "$@"
