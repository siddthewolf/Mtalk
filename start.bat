@echo off
REM CoinDCX Agent — Windows launcher
REM Double-click this file or run from Command Prompt

echo.
echo ==================================================
echo   CoinDCX Portfolio Agent — Setup & Launch
echo ==================================================

python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found. Install from https://python.org
    pause
    exit /b 1
)

echo Installing dependencies...
pip install -q requests numpy flask python-dotenv

if not exist .env (
    copy .env.example .env
    echo Created .env file
)

echo.
echo Finding your local IP address...
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    set LOCAL_IP=%%a
    goto :found
)
:found
set LOCAL_IP=%LOCAL_IP: =%

echo.
echo ==================================================
echo   Open on your phone (same WiFi):
echo   http://%LOCAL_IP%:5000
echo ==================================================
echo.

python dashboard.py
pause
