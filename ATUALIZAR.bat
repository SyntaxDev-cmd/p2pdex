@echo off
title Atualizar Laboratorio DEXONDEV
cd /d "%~dp0"
echo ===================================================
echo   ATUALIZAR LABORATORIO DEXONDEV
echo   Pasta: %CD%
echo ===================================================
echo Baixa a versao mais nova (NovaTV, NovaSports e o laboratorio) e atualiza esta pasta.
echo O que for substituido vai para ferramentas\backup (nada se perde).
echo Clientes salvos, chave de assinatura e APKs prontos NAO sao alterados.
echo.
pause
set "TMPD=%TEMP%\lab-dexondev-%RANDOM%"
mkdir "%TMPD%"
echo Baixando...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing 'https://github.com/SyntaxDev-cmd/p2pdex/releases/download/laboratorio/LABORATORIO-APP-DEXONDEV.zip' -OutFile '%TMPD%\lab.zip'; Expand-Archive '%TMPD%\lab.zip' '%TMPD%\pkg' -Force"
if not exist "%TMPD%\pkg\tools\laboratorio.ps1" (
  echo [ERRO] Nao consegui baixar o pacote. Verifique a internet e tente de novo.
  pause
  exit /b 1
)
rem bloco unico: o cmd le tudo antes de rodar (este .bat pode ser substituido durante a atualizacao)
(
  powershell -NoProfile -ExecutionPolicy Bypass -File "%TMPD%\pkg\tools\laboratorio.ps1" -Task install -Arg "%CD%"
  rmdir /s /q "%TMPD%" 2>nul
  echo.
  echo Pronto! Abra o LABORATORIO.bat ^(ou NOVA-LAB\LABORATORIO.bat, se foi criada^)
  pause
  exit /b 0
)
