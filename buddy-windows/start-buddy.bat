@echo off
title Buddy Headset Control
cd /d "C:\Users\Dans.minme\Documents\GitHub\dooropen\buddy-windows"
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8765" ^| findstr LISTENING') do (
  taskkill /F /PID %%a >nul 2>&1
)
timeout /t 2 /nobreak >nul
echo.
echo  Buddy Headset Control
echo  =====================
echo  Keep this window open.
echo  Open in browser: http://localhost:8765
echo.
node src/index.js
pause
