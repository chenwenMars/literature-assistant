@echo off
chcp 65001 > nul
setlocal

:: 设置项目目录和 JAR 文件名
cd /d "%~dp0"
if errorlevel 1 exit /b 1
set BACKEND_DIR=.
set FRONTEND_DIR=literature-assistant-frontend
set JAR_FILE=literature-assistant-0.0.1-SNAPSHOT.jar

echo ==========================================================
echo                  正在启动 鱼皮文献助手
echo ==========================================================
echo.

:: 检查 Java 环境
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未在 PATH 中找到 Java。请安装 Java 后重试。
    goto:eof
)

:: 检查 Node.js 环境
where npm >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未在 PATH 中找到 Node.js/npm。请安装 Node.js 后重试。
    goto:eof
)

:: 缺少启动 JAR 时，先打包并复制到 jar 目录
set "JAR_PATH=%BACKEND_DIR%\jar\%JAR_FILE%"

if not exist "%JAR_PATH%" (
    echo 未找到后端 JAR，正在编译打包（跳过测试编译和执行）...
    call mvn clean package -Dmaven.test.skip=true
    if errorlevel 1 (
        echo [错误] 后端打包失败，请检查上方 Maven 日志。
        exit /b 1
    )
    if not exist "%BACKEND_DIR%\jar" (
        mkdir "%BACKEND_DIR%\jar"
        if errorlevel 1 (
            echo [错误] 无法创建 jar 目录。
            exit /b 1
        )
    )
    copy /y "%BACKEND_DIR%\target\%JAR_FILE%" "%JAR_PATH%" >nul
    if errorlevel 1 (
        echo [错误] 无法将打包产物复制到 %JAR_PATH%。
        exit /b 1
    )
    echo [成功] 后端 JAR 已放置到 %JAR_PATH%。
)

:: 启动后端
echo [1/3] 正在从 JAR 文件启动 Spring Boot 后端服务器...
start "后端服务器" cmd /c "echo 正在启动后端... && java -jar %JAR_PATH%"
echo [成功] 后端服务器正在新窗口中启动。
echo.

:: 延迟一小段时间，等待后端端口释放（如果之前运行过）
timeout /t 5 > nul

:: 启动前端
echo [2/3] 正在准备并启动 Vue 前端服务器...
if not exist "%FRONTEND_DIR%" (
    echo [错误] 未找到前端目录 '%FRONTEND_DIR%'。
    goto:eof
)
cd %FRONTEND_DIR%

echo 正在检查前端依赖 (node_modules)...
if not exist "node_modules" (
    echo 未找到 'node_modules'。正在运行 'npm install'...
    call npm install
) else (
    echo 依赖已安装。
)

start "前端服务器" cmd /c "echo 正在启动前端... && npm run dev"
cd ..
echo [成功] 前端服务器正在新窗口中启动。
echo.

echo [3/3] 所有服务已启动。
echo ==========================================================
echo.
echo 请在新打开的命令提示符窗口中查看服务器日志。
echo 您可以通过关闭这些窗口来停止服务器。
echo.
endlocal
pause

