@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 汇智 ERP 管理系统

if "%JAVA_HOME%"=="" set "JAVA_HOME=E:\develop\JDK"
if "%ERP_PORT%"=="" set "ERP_PORT=8080"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 找不到 JDK，请修改 start.bat 中的 JAVA_HOME
    pause & exit /b 1
)
set "PATH=%JAVA_HOME%\bin;D:\maven\apache-maven-3.9.6\bin;%PATH%"

if not exist "target\erp-system.jar" (
    echo [1/2] 首次运行，正在下载依赖并构建，请稍候...
    call mvn.cmd -q -DskipTests package
    if errorlevel 1 (
        echo [错误] 构建失败，请检查上方日志
        pause & exit /b 1
    )
)

echo.
echo [2/2] 正在启动 ERP 服务...
echo     管理后台 : http://localhost:%ERP_PORT%
echo     默认账号 : admin  /  admin123
echo     数据库    : D:\erp-data\erpdb.mv.db
echo.
REM 命令行参数优先级最高，避免环境变量 SERVER_PORT 意外改写端口
java -jar target\erp-system.jar --server.port=%ERP_PORT%
pause
