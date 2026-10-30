@echo off
chcp 65001 >nul
setlocal EnableExtensions
title Laboratorio DEXONDEV - gerar app
cd /d "%~dp0"

echo ==============================================
echo    LABORATORIO DEXONDEV - gerar app Android
echo ==============================================
echo.
echo Requisitos (uma vez so): Python 3, Java JDK 17 e Android SDK (Android Studio).
echo.

where python >nul 2>nul || (echo [ERRO] Python nao encontrado. Instale em python.org e marque "Add to PATH". & pause & exit /b 1)
where java >nul 2>nul || (echo [ERRO] Java JDK 17 nao encontrado. Instale o Temurin 17: adoptium.net & pause & exit /b 1)
if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_HOME%"=="" (echo [ERRO] Android SDK nao encontrado. Instale o Android Studio ou defina ANDROID_HOME. & pause & exit /b 1)
python -c "import PIL" 2>nul || python -m pip install -q pillow

echo 1 = NovaTV   (filmes, series, canais)
echo 2 = NovaSports (futebol e esportes)
set /p ESC=Qual app? [1/2]: 
set "DIR=android"
if "%ESC%"=="2" set "DIR=sports\android"

set /p APP_LABEL=Nome do app (ex.: Minha TV): 
if "%APP_LABEL%"=="" set "APP_LABEL=Minha TV"
set /p APP_ID=Pacote unico (ex.: com.minhatv.app): 
if "%APP_ID%"=="" set "APP_ID=com.minhatv.app"
set /p PANEL=URL do painel (Enter = padrao): 
set /p LOGO=Arquivo da logo PNG (arraste aqui, Enter = manter): 
set /p ICON=Arquivo do icone quadrado PNG (Enter = usar a logo): 
set /p BG=Arquivo do fundo JPG/PNG (Enter = manter): 
set /p COR=Cor do fundo do icone #RRGGBB (Enter = #0B1020): 
if "%COR%"=="" set "COR=#0B1020"

if defined LOGO set "LOGO=%LOGO:"=%"
if defined ICON set "ICON=%ICON:"=%"
if defined BG set "BG=%BG:"=%"
set "ARGS=--app %DIR% --color %COR%"
if defined LOGO set ARGS=%ARGS% --logo "%LOGO%"
if defined ICON set ARGS=%ARGS% --icon "%ICON%"
if defined BG set ARGS=%ARGS% --background "%BG%"
if not "%PANEL%"=="" set ARGS=%ARGS% --panel-url %PANEL%

echo.
echo Aplicando a marca...
python tools\lab_brand.py %ARGS% || (echo [ERRO] Falha ao aplicar a marca. & pause & exit /b 1)

echo.
echo Compilando (a primeira vez demora alguns minutos)...
if not "%PANEL%"=="" set "PANEL_URL=%PANEL%"
pushd "%DIR%"
call gradlew.bat assembleFullRelease --no-daemon || (popd & echo [ERRO] Falha na compilacao. Veja as mensagens acima. & pause & exit /b 1)
popd

if not exist "SAIDA" mkdir "SAIDA"
for %%F in ("%DIR%\app\build\outputs\apk\full\release\*.apk") do copy /y "%%F" "SAIDA\%APP_ID%.apk" >nul
echo.
echo ==============================================
echo  PRONTO!  APK em: %CD%\SAIDA\%APP_ID%.apk
echo ==============================================
echo Dica: para voltar a marca original, use "git checkout -- android sports" ou baixe o projeto de novo.
start "" "%CD%\SAIDA"
pause
