@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem Goc du an = thu muc chua file .bat nay
set "POC=%~dp0"
if "%POC:~-1%"=="\" set "POC=%POC:~0,-1%"

echo ==========================================================
echo       DONG GOI BAN PHAT HANH (RELEASE PACKAGER)
echo ==========================================================
echo Goc du an: %POC%
echo.

set "DIST_DIR=%POC%\dist"
set "STAGE_DIR=%DIST_DIR%\release_stage"
set "PUBLISH_TEMP=%DIST_DIR%\publish_temp"
set "ZIP_OUT=%DIST_DIR%\update.zip"

rem 1. Lam sach thu muc dist tam
if exist "%DIST_DIR%" (
    echo [1/5] Don dep thu muc dist cu...
    if exist "%STAGE_DIR%" rd /s /q "%STAGE_DIR%" 2>nul
    if exist "%PUBLISH_TEMP%" rd /s /q "%PUBLISH_TEMP%" 2>nul
    if exist "%ZIP_OUT%" del /f /q "%ZIP_OUT%" 2>nul
) else (
    mkdir "%DIST_DIR%" 2>nul
)
mkdir "%STAGE_DIR%" 2>nul

echo.
echo [2/5] Bien dich C# Manager sang ban phat hanh (dotnet publish)...
dotnet publish "%POC%\Manager\Manager.csproj" -c Release -o "%PUBLISH_TEMP%" --nologo
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [LOI] Bien dich Manager that bai! Vui long kiem tra lai code.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/5] Kiem tra va va Mod Java vao client_modded.jar...
if exist "%POC%\Injector\inject.py" (
    python "%POC%\Injector\inject.py"
    if %ERRORLEVEL% NEQ 0 (
        echo.
        echo [CANH BAO] Inject Java mod co van de, kiem tra lai Python/Java.
    )
)

echo.
echo [4/5] Gom file sach se cho ban cap nhat (Loai bo data ca nhan)...
rem Chep cac file binary tu dotnet publish
xcopy "%PUBLISH_TEMP%\*" "%STAGE_DIR%\" /E /I /Y /Q >nul

rem Chep client_modded.jar
if exist "%POC%\client_modded.jar" (
    copy /y "%POC%\client_modded.jar" "%STAGE_DIR%\client_modded.jar" >nul
) else (
    echo [CANH BAO] Khong tim thay client_modded.jar trong goc du an!
)

rem Chep cac file config mau va toa do
if exist "%POC%\config.mau.json" copy /y "%POC%\config.mau.json" "%STAGE_DIR%\config.mau.json" >nul
if exist "%POC%\doi_hinh.cfg" copy /y "%POC%\doi_hinh.cfg" "%STAGE_DIR%\doi_hinh.cfg" >nul
if exist "%POC%\quest_anchors.cfg" copy /y "%POC%\quest_anchors.cfg" "%STAGE_DIR%\quest_anchors.cfg" >nul

rem Chep thu muc tools va lib neu co
if exist "%POC%\tools" xcopy "%POC%\tools" "%STAGE_DIR%\tools\" /E /I /Y /Q >nul
if exist "%POC%\lib" xcopy "%POC%\lib" "%STAGE_DIR%\lib\" /E /I /Y /Q >nul

echo.
echo [5/5] Nen toan bo thanh update.zip...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '%STAGE_DIR%\*' -DestinationPath '%ZIP_OUT%' -Force"

rem Don dep thu muc trung gian
if exist "%STAGE_DIR%" rd /s /q "%STAGE_DIR%" 2>nul
if exist "%PUBLISH_TEMP%" rd /s /q "%PUBLISH_TEMP%" 2>nul

echo.
echo ==========================================================
echo [THANH CONG] Da dong goi xong ban cap nhat!
echo ==========================================================
echo File update: %ZIP_OUT%
echo.
echo Cach phat hanh len GitHub Release:
echo   1. Vao trang https://github.com/skienn81/langlatoolgame/releases/new
echo   2. Tao Tag moi (vi du: v1.0.1)
echo   3. Keo tha file 'update.zip' vao muc Attach binaries
echo   * Tat ca client se tu dong nhan duoc ban cap nhat!
echo ==========================================================
echo.
if /i not "%1"=="--no-pause" pause
endlocal
exit /b 0
