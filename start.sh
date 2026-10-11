#!/bin/sh

# ---
# 文献助手 macOS 和 Linux 启动脚本
# ---

# 退出时只清理本脚本启动的后端进程
cleanup() {
    if kill -0 "$BACKEND_PID" 2>/dev/null; then
        printf '\n正在关闭后端服务器...\n'
        kill -TERM "$BACKEND_PID"
        wait "$BACKEND_PID"
    fi
}

# 设置输出的颜色代码
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # 无颜色

printf '%b\n' "${GREEN}==========================================================${NC}"
printf '%b\n' "${GREEN}                   启动文献助手                   	   ${NC}"
printf '%b\n' "${GREEN}==========================================================${NC}"
echo ""

# --- 项目设置 ---
cd -- "$(dirname -- "$0")" || exit 1
BACKEND_DIR="./"
FRONTEND_DIR="literature-assistant-frontend"
JAR_FILE="literature-assistant-0.0.1-SNAPSHOT.jar"
JAR_PATH="$BACKEND_DIR/jar/$JAR_FILE"

# 每次启动前重新打包，并覆盖 jar 目录中的启动文件
echo "正在重新编译打包后端（跳过测试编译和执行）..."
if ! mvn clean package -Dmaven.test.skip=true; then
    printf '%b\n' "${RED}[错误] 后端打包失败，请检查上方 Maven 日志。${NC}"
    exit 1
fi
if ! mkdir -p "$BACKEND_DIR/jar" || ! cp "$BACKEND_DIR/target/$JAR_FILE" "$JAR_PATH"; then
    printf '%b\n' "${RED}[错误] 无法将打包产物复制到 $JAR_PATH。${NC}"
    exit 1
fi
printf '%b\n' "${GREEN}[成功] 后端 JAR 已放置到 $JAR_PATH。${NC}"

# 检查前端目录是否存在
if [ ! -d "$FRONTEND_DIR" ]; then
    printf '%b\n' "${RED}[错误] 前端目录 '$FRONTEND_DIR' 未找到。${NC}"
    exit 1
fi


# 按锁文件安装当前 Node.js 架构的依赖，避免复用其他架构的原生模块
printf '[1/3] 正在安装前端依赖（包含开发依赖和可选依赖）...\n'
if ! (cd "$FRONTEND_DIR" && npm ci --include=dev --include=optional); then
    printf '%b\n' "${RED}[错误] 前端依赖安装失败，请检查上方 npm 日志。${NC}"
    exit 1
fi

# --- 启动后端服务器 ---
printf '[2/3] 正在后台启动 Spring Boot 后端服务器...\n'
java -jar "$JAR_PATH" &
BACKEND_PID=$!
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 前端在前台运行，保留真实退出状态；退出后由 cleanup 关闭后端
printf '[3/3] 正在启动 Vue 前端服务器，访问地址以 Vite 日志为准。\n'
printf '%b\n' "按 ${RED}Ctrl+C${NC} 停止服务。"
(cd "$FRONTEND_DIR" && npm run dev)
FRONTEND_STATUS=$?
if [ "$FRONTEND_STATUS" -ne 0 ]; then
    printf '%b\n' "${RED}[错误] 前端进程退出，退出码：$FRONTEND_STATUS。${NC}"
fi
exit "$FRONTEND_STATUS"
