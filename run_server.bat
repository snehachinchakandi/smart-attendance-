@echo off
title Face Attendance API Server
echo ===================================================
echo   Starting Face Attendance Hybrid API Server
echo ===================================================
echo.

:: Check python is available
where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed or not in PATH!
    echo Please install Python and try again.
    pause
    exit /b 1
)

echo [INFO] Python detected. Checking and installing dependencies...
python -m pip install --upgrade pip --quiet
python -m pip install fastapi uvicorn onnxruntime opencv-python numpy --quiet

if %errorlevel% neq 0 (
    echo [ERROR] Failed to install dependencies!
    pause
    exit /b 1
)

echo.
echo [SUCCESS] All dependencies are ready!
echo.
echo ---------------------------------------------------
echo   Local Address:   http://localhost:8000
echo   API Docs:        http://localhost:8000/docs
echo ---------------------------------------------------
echo.
echo Launching server...
python server.py
pause
