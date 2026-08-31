@echo off
REM ============================================================================
REM  Downloader de Musicas - Launcher principal
REM
REM  Este arquivo eh o UNICO que o usuario precisa executar. Ele verifica os
REM  pre-requisitos (Python e FFmpeg), instala automaticamente as dependencias
REM  Python na primeira execucao (se necessario) e entao inicia o programa
REM  "downloader.py".
REM
REM  Este arquivo pode ser executado quantas vezes forem necessarias, ao mesmo
REM  tempo, em janelas diferentes, permitindo varias instancias simultaneas do
REM  programa (por exemplo, para baixar varios links em paralelo).
REM ============================================================================

chcp 65001 >nul
title Downloader de Musicas
setlocal EnableDelayedExpansion

cd /d "%~dp0"

echo ============================================================
echo            DOWNLOADER DE MUSICAS - Inicializando
echo ============================================================
echo.

REM ---------------------------------------------------------------------------
REM 1) Verifica se o Python esta disponivel no PATH do sistema
REM ---------------------------------------------------------------------------
where python >nul 2>nul
if errorlevel 1 (
    echo [ERRO] Python nao foi encontrado no PATH do sistema.
    echo.
    echo Instale o Python 3.12 ou superior em:
    echo     https://www.python.org/downloads/
    echo.
    echo IMPORTANTE: marque a opcao "Add python.exe to PATH" durante a instalacao.
    echo.
    pause
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM 2) Verifica se o FFmpeg esta disponivel (no PATH ou na pasta do programa)
REM ---------------------------------------------------------------------------
set FFMPEG_OK=0
where ffmpeg >nul 2>nul
if not errorlevel 1 set FFMPEG_OK=1
if exist "%~dp0ffmpeg.exe" set FFMPEG_OK=1

if "%FFMPEG_OK%"=="0" (
    echo [AVISO] FFmpeg nao foi encontrado no PATH nem na pasta do programa.
    echo O programa pode falhar ao converter os arquivos para MP3.
    echo.
    echo Baixe o FFmpeg build essentials ou full em:
    echo     https://www.gyan.dev/ffmpeg/builds/
    echo.
    echo Depois, adicione a pasta "bin" do FFmpeg ao PATH do Windows, ou copie
    echo o arquivo ffmpeg.exe para esta mesma pasta do programa.
    echo.
)

REM ---------------------------------------------------------------------------
REM 3) Garante que as pastas auxiliares existem
REM ---------------------------------------------------------------------------
if not exist "cache" mkdir "cache"
if not exist "logs" mkdir "logs"
if not exist "temp" mkdir "temp"

REM ---------------------------------------------------------------------------
REM 4) Verifica se as dependencias Python (yt-dlp, mutagen, requests, colorama)
REM    ja estao instaladas. Caso nao estejam, instala automaticamente.
REM ---------------------------------------------------------------------------
python -c "import yt_dlp, mutagen, requests, colorama" >nul 2>nul
if errorlevel 1 (
    echo Instalando dependencias necessarias, aguarde...
    echo.
    python -m pip install --upgrade pip >nul 2>nul
    python -m pip install -r requirements.txt
    if errorlevel 1 (
        echo.
        echo [ERRO] Falha ao instalar as dependencias do projeto.
        echo Verifique sua conexao com a internet e tente novamente.
        echo.
        pause
        exit /b 1
    )
    echo.
    echo Dependencias instaladas com sucesso!
    echo.
)

REM ---------------------------------------------------------------------------
REM 5) Inicia o programa principal
REM ---------------------------------------------------------------------------
python downloader.py

echo.
echo ============================================================
echo  Programa finalizado. Pressione qualquer tecla para fechar.
echo ============================================================
pause >nul

endlocal
