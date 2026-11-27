@rem Project-local Gradle 8.9 launcher using the Huawei Cloud mirror.
@echo off
setlocal
set "APP_HOME=%~dp0"
if not exist "%APP_HOME%.gradle-dist\gradle-8.9\bin\gradle.bat" (
  powershell.exe -NoProfile -File "%APP_HOME%tools\bootstrap-gradle.ps1"
  if errorlevel 1 exit /b 1
)
call "%APP_HOME%.gradle-dist\gradle-8.9\bin\gradle.bat" -p "%APP_HOME%." %*
exit /b %ERRORLEVEL%
