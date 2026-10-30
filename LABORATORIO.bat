@echo off
title Laboratorio DEXONDEV
cd /d "%~dp0"
if not exist "tools\laboratorio.ps1" (
  echo [ERRO] Arquivo tools\laboratorio.ps1 nao encontrado. Extraia o ZIP inteiro antes de abrir.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "tools\laboratorio.ps1"
if errorlevel 1 pause
