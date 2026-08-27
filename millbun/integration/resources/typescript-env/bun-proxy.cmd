@echo off
setlocal
set "FIRST=%~1"
if "%FIRST%"=="" set "FIRST=unknown"
set "MARKER=%BUN_PROXY_MARKER%"
if "%MARKER%"=="" set "MARKER=missing"
>> "%CD%\.bun-env-log" echo %FIRST%:%MARKER%
bun %*
exit /b %ERRORLEVEL%
