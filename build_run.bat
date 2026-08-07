@echo off
setlocal
rem Goc du an = thu muc chua file .bat nay, nen chep di dau cung chay duoc.
set "POC=%~dp0"
if "%POC:~-1%"=="\" set "POC=%POC:~0,-1%"

echo ==========================================
echo   BUILD AUTO LANG LA
echo   Goc du an: %POC%
echo ==========================================

echo [1/2] Build C# Manager...
dotnet build "%POC%\Manager\Manager.csproj" -c Release
if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Build Manager that bai.
    echo   - Chua cai .NET 8 SDK? Tai o https://dotnet.microsoft.com/download
    echo   - Manager.exe dang chay? Dong no roi build lai.
    pause
    exit /b %ERRORLEVEL%
)
echo Build Manager xong.

echo.
echo [2/2] Bien dich mod Java va va vao jar game...
python "%POC%\Injector\inject.py"
if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Inject bytecode that bai — doc thong bao ben tren.
    pause
    exit /b %ERRORLEVEL%
)
echo Inject xong.

echo.
echo ------------------------------------------
echo XONG. Mo Manager:
echo    %POC%\Manager\bin\Release\net8.0-windows\Manager.exe
echo.
echo Lan dau chay can:
echo    1. Doi ten config.mau.json thanh config.json roi dien tai khoan
echo    2. Sua doi_hinh.cfg — thay nick_01, nick_02... bang username that
echo    3. Kiem quest_anchors.cfg cho khop map/toa do server ban choi
echo ------------------------------------------
pause
endlocal
