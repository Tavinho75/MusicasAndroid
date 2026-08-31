@echo off
REM ============================================================================
REM  Downloader de Musicas - Atualizador de dependencias
REM
REM  Este arquivo atualiza automaticamente:
REM    - o pip (gerenciador de pacotes do Python)
REM    - o yt-dlp (motor de extracao/download, atualizado com mais frequencia
REM      pois os sites de video/musica mudam constantemente)
REM    - as demais dependencias listadas em requirements.txt
REM      (mutagen, requests, colorama)
REM
REM  Execute este arquivo periodicamente para manter o programa funcionando
REM  corretamente, especialmente se os downloads comecarem a falhar.
REM ============================================================================

chcp 65001 >nul
title Atualizador - Downloader de Musicas
setlocal

cd /d "%~dp0"

echo ============================================================
echo       ATUALIZADOR - DOWNLOADER DE MUSICAS
echo ============================================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [ERRO] Python nao foi encontrado no PATH do sistema.
    echo Instale o Python 3.12 ou superior em https://www.python.org/downloads/
    echo.
    pause
    exit /b 1
)

echo [1/3] Atualizando o pip...
echo ------------------------------------------------------------
python -m pip install --upgrade pip
echo.

echo [2/3] Atualizando o yt-dlp para a versao mais recente...
echo ------------------------------------------------------------
python -m pip install --upgrade yt-dlp
echo.

echo [3/3] Atualizando as demais dependencias do projeto...
echo ------------------------------------------------------------
python -m pip install --upgrade -r requirements.txt
set UPDATE_RESULT=%errorlevel%
echo.

if not "%UPDATE_RESULT%"=="0" (
    echo ============================================================
    echo  Ocorreu um erro durante a atualizacao. Verifique as
    echo  mensagens acima e sua conexao com a internet.
    echo ============================================================
) else (
    echo ============================================================
    echo  Atualizacao concluida com sucesso!
    echo ============================================================
)

echo.
pause
endlocal
