@echo off
setlocal
rem Goc du an = thu muc chua file .bat nay, nen chep di dau cung chay duoc.
set "POC=%~dp0"
if "%POC:~-1%"=="\" set "POC=%POC:~0,-1%"

echo ==========================================
echo   BUILD AUTO LANG LA
echo   Goc du an: %POC%
echo ==========================================

echo [1/2] Kiem tra va Build C# Manager...

rem Kiem tra .NET 8 SDK
dotnet --list-sdks 2>nul | findstr /b 8. >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    goto missing_dotnet8
)

dotnet build "%POC%\Manager\Manager.csproj" -c Release
if %ERRORLEVEL% NEQ 0 (
    rem Kiem tra lai neu loi xay ra do chua co .NET 8 SDK
    dotnet --list-sdks 2>nul | findstr /b 8. >nul 2>&1
    if %ERRORLEVEL% NEQ 0 goto missing_dotnet8
    
    echo.
    echo [LOI] Build Manager that bai!
    echo   - Manager.exe dang chay? Dong no roi build lai.
    echo   - Kiem tra lai ma nguon Manager xem co loi khong.
    pause
    exit /b %ERRORLEVEL%
)
echo Build Manager xong.

echo.
echo [2/2] Bien dich mod Java va va vao jar game...
python "%POC%\Injector\inject.py"
if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Inject bytecode that bai - doc thong bao ben tren.
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
echo    2. Sua doi_hinh.cfg - thay nick_01, nick_02... bang username that
echo    3. Kiem quest_anchors.cfg cho khop map/toa do server ban choi
echo ------------------------------------------
pause
endlocal
exit /b 0

:missing_dotnet8
echo.
echo ==========================================================
echo [CANH BAO] May tinh cua ban chua cai dat .NET 8 SDK!
echo ==========================================================
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] LUU Y: De tu dong cai dat thanh cong, ban nen mo file nay
    echo     bang quyen Quan tri vien (Chuot phai ^> Run as administrator).
    echo.
)

set "INSTALL_CHOICE="
set /p "INSTALL_CHOICE=Ban co muon tu dong tai va cai dat .NET 8 SDK khong? (y/n): "
if /i "%INSTALL_CHOICE%"=="y" goto install_dotnet8
if /i "%INSTALL_CHOICE%"=="yes" goto install_dotnet8

echo.
echo [THONG BAO] Bat buoc phai co .NET 8 SDK moi su dung duoc tool nay!
echo Ban co the tai va cai dat thu cong tai:
echo    https://dotnet.microsoft.com/download/dotnet/8.0
echo.
pause
exit /b 1

:install_dotnet8
echo.
echo ----------------------------------------------------------
echo Dang chuan bi tai va cai dat .NET 8 SDK...
echo Vui long cho trong giay lat (co the mat 1-3 phut tuy toc do mang)...
echo ----------------------------------------------------------

set "INSTALLER_PATH=%TEMP%\dotnet-sdk-8-setup.exe"
set "DOTNET_URL=https://aka.ms/dotnet/8.0/dotnet-sdk-win-x64.exe"
if "%PROCESSOR_ARCHITECTURE%"=="x86" set "DOTNET_URL=https://aka.ms/dotnet/8.0/dotnet-sdk-win-x86.exe"
if "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "DOTNET_URL=https://aka.ms/dotnet/8.0/dotnet-sdk-win-arm64.exe"

rem Uu tien dung curl de tai file
where curl >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Dang tai file cai dat qua curl...
    curl -L -o "%INSTALLER_PATH%" "%DOTNET_URL%"
) else (
    echo Dang tai file cai dat qua PowerShell...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('%DOTNET_URL%', '%INSTALLER_PATH%')"
)

if not exist "%INSTALLER_PATH%" (
    echo [LOI] Khong the tai bo cai dat .NET 8 SDK.
    echo Vui long kiem tra ket noi mang hoac tai thu cong tai:
    echo    https://dotnet.microsoft.com/download/dotnet/8.0
    pause
    exit /b 1
)

echo.
echo Dang cai dat .NET 8 SDK vao he thong...
echo (Neu xuat hien hop thoai yeu cau quyen Administrator / UAC, vui long chon 'Yes')
start /wait "" "%INSTALLER_PATH%" /install /passive /norestart

rem Xoa file cai dat tam sau khi hoan tat
if exist "%INSTALLER_PATH%" del /f /q "%INSTALLER_PATH%" >nul 2>&1

echo.
echo ==========================================================
echo [THANH CONG] Da hoan tat cai dat .NET 8 SDK!
echo Vui long dong cua so nay va mo lai build_run.bat de su dung.
echo ==========================================================
echo.
pause
exit /b 0
