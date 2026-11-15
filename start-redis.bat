@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 汇智 ERP - Redis 模式

REM ============================================================
REM Redis 模式：会话与缓存迁移到 Redis，多实例可共享登录态。
REM 业务功能与默认模式完全一致；没有 Redis 时请用 start.bat。
REM ============================================================

if "%JAVA_HOME%"=="" set "JAVA_HOME=E:\develop\JDK"
if "%ERP_PORT%"=="" set "ERP_PORT=8080"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 找不到 JDK，请修改本脚本中的 JAVA_HOME
    pause & exit /b 1
)
set "PATH=%JAVA_HOME%\bin;D:\maven\apache-maven-3.9.6\bin;%PATH%"

set "REDIS_DIR=D:\redis8.4.0"
if exist "%REDIS_DIR%\redis-cli.exe" (
    "%REDIS_DIR%\redis-cli.exe" -h 127.0.0.1 -p 6379 PING >nul 2>&1
    if errorlevel 1 (
        echo [1/3] Redis 未运行，正在启动 %REDIS_DIR%\redis-server.exe ...
        start "ERP Redis" /min "%REDIS_DIR%\redis-server.exe"
        timeout /t 2 /nobreak >nul
    ) else (
        echo [1/3] Redis 已在运行 ^(PONG^)
    )
) else (
    echo [警告] 未找到 %REDIS_DIR%\redis-cli.exe，请确认 Redis 已自行启动
)

if not exist "target\erp-system.jar" (
    echo [2/3] 正在构建，请稍候...
    call mvn.cmd -q -DskipTests package
    if errorlevel 1 (
        echo [错误] 构建失败
        pause & exit /b 1
    )
) else (
    echo [2/3] 使用已有 jar ^(改过代码请先重新 package^)
)

echo.
echo [3/3] 正在启动 ERP ^(redis profile^)...
echo     管理后台 : http://localhost:%ERP_PORT%
echo     默认账号 : admin  /  admin123
echo     运维页面 : 系统管理 -^> 系统运维  ^(会话存储应显示 redis^)
echo.
java -jar target\erp-system.jar --server.port=%ERP_PORT% --spring.profiles.active=redis
pause
