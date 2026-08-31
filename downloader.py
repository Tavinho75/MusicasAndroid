#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Downloader de Músicas
======================

Programa de linha de comando, orientado a objetos, para baixar músicas,
álbuns e playlists de qualquer site suportado pelo yt-dlp (YouTube,
YouTube Music, SoundCloud, Bandcamp, Vimeo, Mixcloud, Audiomack, entre
muitos outros), convertendo automaticamente o áudio para MP3 com a melhor
qualidade disponível e incorporando metadados completos (título, artista,
álbum, faixa, disco, ano, gênero, compositor, descrição, comentários) e a
capa em alta resolução.

Este módulo é organizado em classes, cada uma com uma única
responsabilidade:

    ConsoleColors       -> utilitários de formatação colorida do terminal.
    AppConfig           -> representação tipada da configuração do programa.
    ConfigManager        -> leitura/escrita/validação de config.json.
    LoggingSetup         -> configuração do sistema de logs em logs/.
    FilenameSanitizer    -> limpeza de nomes inválidos no Windows.
    PathManager          -> cálculo das pastas de destino dos downloads.
    YdlLoggerAdapter      -> adaptador entre o logger do yt-dlp e o logging.
    ThumbnailDownloader   -> download de miniaturas/capas em alta resolução.
    MetadataEmbedder      -> incorporação de metadados completos em MP3.
    ProgressReporter      -> exibição de progresso, velocidade e ETA.
    LinkAnalyzer          -> identificação do tipo de link e suas faixas.
    YdlOptionsBuilder     -> construção das opções usadas pelo yt-dlp.
    MusicDownloader       -> orquestração completa do processo de download.
    Application           -> laço principal de interação com o usuário.

Requisitos externos: Python 3.12+, yt-dlp, FFmpeg (binário externo).
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, ClassVar, Dict, Iterator, List, Optional

import requests
import yt_dlp
from colorama import Fore, Style
from colorama import init as colorama_init
from mutagen.id3 import (
    APIC,
    COMM,
    ID3,
    TALB,
    TCOM,
    TCON,
    TDRC,
    TIT2,
    TPE1,
    TPOS,
    TRCK,
    TXXX,
    TYER,
)
from mutagen.mp3 import MP3
from yt_dlp.utils import DownloadError as YtDlpDownloadError

__version__: str = "1.0.0"


# ==============================================================================
# UTILITÁRIOS DE TERMINAL
# ==============================================================================
class ConsoleColors:
    """Agrupa utilitários estáticos para formatar mensagens coloridas no
    terminal do Windows usando a biblioteca colorama."""

    @staticmethod
    def success(text: str) -> str:
        """Formata um texto de sucesso em verde.

        Args:
            text: Texto a ser formatado.

        Returns:
            O texto envolvido pelos códigos ANSI de cor verde.
        """
        return f"{Fore.GREEN}{text}{Style.RESET_ALL}"

    @staticmethod
    def error(text: str) -> str:
        """Formata um texto de erro em vermelho.

        Args:
            text: Texto a ser formatado.

        Returns:
            O texto envolvido pelos códigos ANSI de cor vermelha.
        """
        return f"{Fore.RED}{text}{Style.RESET_ALL}"

    @staticmethod
    def warning(text: str) -> str:
        """Formata um texto de aviso em amarelo.

        Args:
            text: Texto a ser formatado.

        Returns:
            O texto envolvido pelos códigos ANSI de cor amarela.
        """
        return f"{Fore.YELLOW}{text}{Style.RESET_ALL}"

    @staticmethod
    def info(text: str) -> str:
        """Formata um texto informativo em ciano.

        Args:
            text: Texto a ser formatado.

        Returns:
            O texto envolvido pelos códigos ANSI de cor ciano.
        """
        return f"{Fore.CYAN}{text}{Style.RESET_ALL}"

    @staticmethod
    def highlight(text: str) -> str:
        """Formata um texto em destaque (negrito/brilhante).

        Args:
            text: Texto a ser formatado.

        Returns:
            O texto envolvido pelos códigos ANSI de estilo brilhante.
        """
        return f"{Style.BRIGHT}{text}{Style.RESET_ALL}"


# ==============================================================================
# EXCEÇÕES CUSTOMIZADAS
# ==============================================================================
class DownloaderError(Exception):
    """Exceção base para todos os erros tratados pelo Downloader de Músicas."""


class InvalidConfigError(DownloaderError):
    """Levantada quando o arquivo config.json contém valores inválidos."""


class LinkAnalysisError(DownloaderError):
    """Levantada quando não é possível analisar/classificar um link informado."""


class TrackDownloadError(DownloaderError):
    """Levantada quando ocorre uma falha ao baixar ou processar uma faixa."""


# ==============================================================================
# TIPO DE LINK
# ==============================================================================
class LinkType(Enum):
    """Enumera os possíveis tipos de conteúdo identificados em um link."""

    MUSIC = "música"
    ALBUM = "álbum"
    PLAYLIST = "playlist"


# ==============================================================================
# CONFIGURAÇÃO DA APLICAÇÃO
# ==============================================================================
@dataclass
class AppConfig:
    """Representa, de forma tipada, a configuração carregada de config.json.

    Attributes:
        download_path: Pasta raiz onde os downloads serão salvos.
        audio_format: Formato de áudio final (ex.: "mp3").
        audio_quality: Qualidade/bitrate do áudio final (ex.: "320").
        embed_thumbnail: Se True, baixa e incorpora a capa ao arquivo.
        embed_metadata: Se True, incorpora metadados completos ao arquivo.
    """

    download_path: Path
    audio_format: str
    audio_quality: str
    embed_thumbnail: bool
    embed_metadata: bool

    SUPPORTED_FORMATS: ClassVar[set] = {
        "mp3",
        "m4a",
        "opus",
        "vorbis",
        "wav",
        "flac",
        "aac",
    }

    def validate(self) -> None:
        """Valida os valores carregados, levantando erro se forem inválidos.

        Raises:
            InvalidConfigError: Se algum valor de configuração for inválido.
        """
        if self.audio_format.lower() not in self.SUPPORTED_FORMATS:
            raise InvalidConfigError(
                f"Formato de áudio não suportado: '{self.audio_format}'. "
                f"Formatos suportados: {sorted(self.SUPPORTED_FORMATS)}"
            )
        if not str(self.audio_quality).strip():
            raise InvalidConfigError("O campo 'audio_quality' não pode estar vazio.")


class ConfigManager:
    """Responsável por carregar, validar e, se necessário, criar o arquivo
    config.json do projeto."""

    DEFAULT_CONFIG: ClassVar[Dict[str, Any]] = {
        "download_path": "C:\\Users\\luiso\\OneDrive\\Desktop\\Músicas\\Baixadas",
        "audio_format": "mp3",
        "audio_quality": "320",
        "embed_thumbnail": True,
        "embed_metadata": True,
    }

    def __init__(self, config_path: Path, logger: logging.Logger) -> None:
        """Inicializa o gerenciador de configuração.

        Args:
            config_path: Caminho completo para o arquivo config.json.
            logger: Logger utilizado para registrar avisos/erros de leitura.
        """
        self._config_path = config_path
        self._logger = logger

    def load(self) -> AppConfig:
        """Carrega a configuração do disco, criando um arquivo padrão se
        necessário, e devolve um objeto AppConfig validado.

        Returns:
            Uma instância de AppConfig pronta para uso.
        """
        if not self._config_path.exists():
            self._logger.warning(
                f"Arquivo de configuração não encontrado em '{self._config_path}'. "
                "Criando configuração padrão."
            )
            self._write_default()

        raw: Dict[str, Any]
        try:
            with open(self._config_path, "r", encoding="utf-8-sig") as handle:
                raw = json.load(handle)
        except (json.JSONDecodeError, OSError) as exc:
            self._logger.error(
                f"Erro ao ler '{self._config_path}': {exc}. "
                "Utilizando configuração padrão em memória."
            )
            raw = dict(self.DEFAULT_CONFIG)

        merged: Dict[str, Any] = {**self.DEFAULT_CONFIG, **raw}
        expanded_path = os.path.expandvars(str(merged["download_path"]))

        config = AppConfig(
            download_path=Path(expanded_path),
            audio_format=str(merged["audio_format"]).lower(),
            audio_quality=str(merged["audio_quality"]),
            embed_thumbnail=bool(merged["embed_thumbnail"]),
            embed_metadata=bool(merged["embed_metadata"]),
        )

        try:
            config.validate()
        except InvalidConfigError as exc:
            self._logger.error(
                f"Configuração inválida ({exc}). Revertendo para mp3/320 como padrão."
            )
            config.audio_format = "mp3"
            config.audio_quality = "320"

        return config

    def _write_default(self) -> None:
        """Cria, em disco, o arquivo config.json com os valores padrão."""
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self._config_path, "w", encoding="utf-8") as handle:
            json.dump(self.DEFAULT_CONFIG, handle, indent=2, ensure_ascii=False)


# ==============================================================================
# CONFIGURAÇÃO DE LOGS
# ==============================================================================
class LoggingSetup:
    """Responsável por configurar o sistema de logs do programa.

    Cada execução do programa cria seu próprio arquivo de log, identificado
    pelo timestamp e pelo PID do processo. Isso permite que múltiplas
    instâncias do programa sejam executadas simultaneamente sem qualquer
    conflito de escrita em um mesmo arquivo.
    """

    @staticmethod
    def create_logger(logs_dir: Path) -> logging.Logger:
        """Cria e configura um logger dedicado a esta execução do programa.

        Args:
            logs_dir: Pasta onde os arquivos de log devem ser criados.

        Returns:
            Uma instância de logging.Logger já configurada com handlers de
            arquivo (nível DEBUG) e de console (apenas nível ERROR, para não
            interferir com a barra de progresso exibida na tela).
        """
        logs_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        pid = os.getpid()
        log_file = logs_dir / f"downloader_{timestamp}_{pid}.log"

        logger = logging.getLogger(f"downloader_{pid}_{id(logs_dir)}")
        logger.setLevel(logging.DEBUG)
        logger.propagate = False

        if logger.handlers:
            return logger

        file_handler = logging.FileHandler(log_file, encoding="utf-8")
        file_handler.setLevel(logging.DEBUG)
        file_formatter = logging.Formatter(
            fmt="%(asctime)s | %(levelname)-8s | %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
        file_handler.setFormatter(file_formatter)
        logger.addHandler(file_handler)

        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(logging.ERROR)
        console_formatter = logging.Formatter(fmt="[%(levelname)s] %(message)s")
        console_handler.setFormatter(console_formatter)
        logger.addHandler(console_handler)

        logger.info(f"Sessão iniciada. Arquivo de log: {log_file}")
        return logger


# ==============================================================================
# LIMPEZA DE NOMES DE ARQUIVOS/PASTAS
# ==============================================================================
class FilenameSanitizer:
    """Responsável por limpar nomes de arquivos e pastas, removendo ou
    substituindo qualquer caractere inválido no sistema de arquivos do
    Windows, além de lidar com nomes reservados e tamanhos máximos."""

    _INVALID_CHARS_PATTERN: ClassVar[re.Pattern] = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
    _RESERVED_NAMES: ClassVar[set] = {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9",
    }
    _MAX_LENGTH: ClassVar[int] = 150

    @classmethod
    def sanitize(cls, name: str, fallback: str = "sem_titulo") -> str:
        """Limpa um nome de arquivo ou pasta para que seja válido no Windows.

        Remove caracteres proibidos (``< > : " / \\ | ? *`` e caracteres de
        controle), normaliza espaços, remove pontos/espaços nas extremidades,
        evita nomes reservados pelo sistema (CON, PRN, AUX, NUL, COM1-9,
        LPT1-9) e limita o tamanho total do nome.

        Args:
            name: Nome original, possivelmente inválido.
            fallback: Nome a ser usado caso o resultado fique vazio.

        Returns:
            Um nome de arquivo/pasta seguro para uso no Windows.
        """
        if not name:
            return fallback

        cleaned = cls._INVALID_CHARS_PATTERN.sub("_", name)
        cleaned = re.sub(r"\s+", " ", cleaned)
        cleaned = cleaned.strip(" .")

        if not cleaned:
            return fallback

        if cleaned.upper() in cls._RESERVED_NAMES:
            cleaned = f"_{cleaned}"

        if len(cleaned) > cls._MAX_LENGTH:
            cleaned = cleaned[: cls._MAX_LENGTH].rstrip(" .")

        return cleaned or fallback


# ==============================================================================
# GERENCIAMENTO DE CAMINHOS DE DESTINO
# ==============================================================================
class PathManager:
    """Calcula as pastas de destino finais dos downloads, de acordo com as
    regras do projeto: músicas individuais em pastas por data, e
    álbuns/playlists em pastas próprias nomeadas conforme o título."""

    def __init__(self, config: AppConfig) -> None:
        """Inicializa o gerenciador de caminhos.

        Args:
            config: Configuração ativa do programa, contendo a pasta raiz.
        """
        self._config = config

    def base_download_dir(self) -> Path:
        """Retorna a pasta raiz configurada para os downloads.

        Returns:
            O caminho (Path) da pasta raiz de downloads, criando-a se ainda
            não existir.
        """
        directory = self._config.download_path
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def single_track_dir(self) -> Path:
        """Calcula e cria a pasta de destino para uma música individual.

        Segue o padrão ``<download_path>/AAAA-MM-DD``, sendo a data
        referente ao dia em que o download foi realizado.

        Returns:
            O caminho (Path) da pasta de destino, já criada em disco.
        """
        date_str = datetime.now().strftime("%Y-%m-%d")
        directory = self.base_download_dir() / date_str
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def playlist_dir(self, playlist_name: str) -> Path:
        """Calcula e cria a pasta de destino para um álbum ou playlist.

        Segue o padrão ``<download_path>/<Nome da Playlist>``.

        Args:
            playlist_name: Nome já sanitizado do álbum/playlist.

        Returns:
            O caminho (Path) da pasta de destino, já criada em disco.
        """
        directory = self.base_download_dir() / playlist_name
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    @staticmethod
    def ensure_unique(path: Path) -> Path:
        """Garante que um caminho de arquivo não sobrescreva um já existente.

        Caso o caminho já exista, acrescenta um sufixo numérico incremental
        (ex.: ``Música (1).mp3``, ``Música (2).mp3``) até encontrar um nome
        livre. Esta função atua como uma rede de segurança adicional: o
        fluxo principal do programa evita até mesmo iniciar o download
        quando a faixa final já existe.

        Args:
            path: Caminho de arquivo desejado.

        Returns:
            Um caminho garantidamente livre para escrita.
        """
        if not path.exists():
            return path
        stem, suffix, parent = path.stem, path.suffix, path.parent
        counter = 1
        while True:
            candidate = parent / f"{stem} ({counter}){suffix}"
            if not candidate.exists():
                return candidate
            counter += 1


# ==============================================================================
# ADAPTADOR DE LOG PARA O YT-DLP
# ==============================================================================
class YdlLoggerAdapter:
    """Adapta as chamadas de log internas do yt-dlp (debug/warning/error)
    para o sistema de logging padrão do Python, evitando que mensagens
    internas da biblioteca poluam a saída colorida do programa."""

    def __init__(self, logger: logging.Logger) -> None:
        """Inicializa o adaptador.

        Args:
            logger: Logger para o qual as mensagens do yt-dlp serão
                redirecionadas.
        """
        self._logger = logger

    def debug(self, msg: str) -> None:
        """Recebe mensagens de depuração emitidas pelo yt-dlp.

        Args:
            msg: Mensagem emitida pelo yt-dlp.
        """
        if msg.startswith("[debug] "):
            self._logger.debug(msg)
        else:
            self._logger.info(msg)

    def info(self, msg: str) -> None:
        """Recebe mensagens informativas emitidas pelo yt-dlp.

        Args:
            msg: Mensagem emitida pelo yt-dlp.
        """
        self._logger.info(msg)

    def warning(self, msg: str) -> None:
        """Recebe mensagens de aviso emitidas pelo yt-dlp.

        Args:
            msg: Mensagem emitida pelo yt-dlp.
        """
        self._logger.warning(msg)

    def error(self, msg: str) -> None:
        """Recebe mensagens de erro emitidas pelo yt-dlp.

        Args:
            msg: Mensagem emitida pelo yt-dlp.
        """
        self._logger.error(msg)


# ==============================================================================
# DOWNLOAD DE MINIATURAS/CAPAS
# ==============================================================================
class ThumbnailDownloader:
    """Responsável por selecionar e baixar a miniatura (capa) de maior
    resolução disponível para um álbum, playlist ou faixa."""

    def __init__(self, logger: logging.Logger, timeout: int = 30) -> None:
        """Inicializa o baixador de miniaturas.

        Args:
            logger: Logger utilizado para registrar sucessos/falhas.
            timeout: Tempo limite, em segundos, para as requisições HTTP.
        """
        self._logger = logger
        self._timeout = timeout

    @staticmethod
    def select_best(thumbnails: Optional[List[Dict[str, Any]]]) -> Optional[str]:
        """Seleciona, dentre uma lista de miniaturas, a de maior resolução.

        A seleção é feita, em ordem de preferência, por:
            1. Maior área (largura x altura), quando ambas as dimensões
               estão disponíveis.
            2. Maior valor de "preference", quando disponível.
            3. Último item da lista (convenção comum do yt-dlp para indicar
               a melhor miniatura quando não há outros metadados).

        Args:
            thumbnails: Lista de dicionários de miniaturas, no formato
                utilizado pelo yt-dlp (cada item pode conter as chaves
                "url", "width", "height" e "preference").

        Returns:
            A URL da melhor miniatura encontrada, ou None se a lista
            estiver vazia ou ausente.
        """
        if not thumbnails:
            return None

        with_dimensions = [
            thumb
            for thumb in thumbnails
            if thumb.get("width") and thumb.get("height")
        ]
        if with_dimensions:
            best = max(with_dimensions, key=lambda t: int(t["width"]) * int(t["height"]))
            return best.get("url")

        with_preference = [
            thumb for thumb in thumbnails if thumb.get("preference") is not None
        ]
        if with_preference:
            best = max(with_preference, key=lambda t: t["preference"])
            return best.get("url")

        return thumbnails[-1].get("url")

    def download(self, url: str, destination: Path) -> Optional[Path]:
        """Baixa a miniatura indicada e a salva no caminho de destino.

        Caso já exista um arquivo no caminho de destino, o download é
        ignorado para nunca sobrescrever arquivos existentes.

        Args:
            url: URL da miniatura a ser baixada.
            destination: Caminho completo do arquivo de destino (ex.:
                ``.../Nome da Playlist/capa.jpg``).

        Returns:
            O caminho do arquivo salvo, ou None em caso de falha.
        """
        if destination.exists():
            self._logger.info(
                f"Capa já existente em '{destination}'. Mantendo arquivo atual."
            )
            return destination

        try:
            response = requests.get(url, timeout=self._timeout, stream=True)
            response.raise_for_status()
        except requests.RequestException as exc:
            self._logger.error(f"Falha ao baixar miniatura de '{url}': {exc}")
            return None

        try:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with open(destination, "wb") as handle:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        handle.write(chunk)
        except OSError as exc:
            self._logger.error(f"Falha ao salvar miniatura em '{destination}': {exc}")
            return None

        self._logger.info(f"Miniatura salva com sucesso em '{destination}'.")
        return destination


# ==============================================================================
# INCORPORAÇÃO DE METADADOS COMPLETOS EM MP3
# ==============================================================================
class MetadataEmbedder:
    """Responsável por incorporar metadados completos (título, artista,
    álbum, faixa, disco, ano, gênero, compositor, descrição e comentários)
    em arquivos MP3, utilizando a biblioteca mutagen para manipular
    diretamente os frames ID3v2."""

    def __init__(self, logger: logging.Logger) -> None:
        """Inicializa o incorporador de metadados.

        Args:
            logger: Logger utilizado para registrar sucessos/falhas.
        """
        self._logger = logger

    def embed(
        self,
        file_path: Path,
        info: Dict[str, Any],
        track_number: Optional[int] = None,
        track_total: Optional[int] = None,
        disc_number: Optional[int] = None,
        disc_total: Optional[int] = None,
        album_override: Optional[str] = None,
        cover_bytes: Optional[bytes] = None,
    ) -> None:
        """Incorpora os metadados completos em um arquivo MP3 já existente.

        Args:
            file_path: Caminho do arquivo MP3 final, já convertido.
            info: Dicionário de metadados extraído pelo yt-dlp para a faixa.
            track_number: Número da faixa dentro do álbum/playlist, se
                aplicável.
            track_total: Quantidade total de faixas do álbum/playlist, se
                aplicável.
            disc_number: Número do disco, quando existente na origem.
            disc_total: Quantidade total de discos, quando existente.
            album_override: Nome do álbum/playlist a ser utilizado em vez do
                valor presente em ``info`` (utilizado para garantir que
                todas as faixas de uma playlist compartilhem o mesmo nome
                de álbum).
            cover_bytes: Bytes de uma imagem de capa para incorporação
                manual. Normalmente não é necessário, pois a capa já é
                incorporada automaticamente pelo pós-processador
                EmbedThumbnail do próprio yt-dlp; este parâmetro existe
                como uma via alternativa explícita de incorporação.
        """
        try:
            audio = MP3(str(file_path))
        except Exception as exc:  # noqa: BLE001 - qualquer falha de leitura é tratada
            self._logger.error(
                f"Não foi possível abrir '{file_path}' para incorporar metadados: {exc}"
            )
            return

        if audio.tags is None:
            audio.tags = ID3()
        tags = audio.tags

        title = info.get("title") or file_path.stem
        artist = self._resolve_artist(info)
        album = album_override or info.get("album") or title
        genre = info.get("genre") or self._first_or_none(info.get("genres"))
        composer = info.get("composer") or self._first_or_none(info.get("composers"))
        description = info.get("description")
        year = self._resolve_year(info)

        tags.delall("TIT2")
        tags.add(TIT2(encoding=3, text=[title]))

        if artist:
            tags.delall("TPE1")
            tags.add(TPE1(encoding=3, text=[artist]))

        if album:
            tags.delall("TALB")
            tags.add(TALB(encoding=3, text=[album]))

        if year:
            tags.delall("TDRC")
            tags.add(TDRC(encoding=3, text=[year]))
            tags.delall("TYER")
            tags.add(TYER(encoding=3, text=[year]))

        if genre:
            tags.delall("TCON")
            tags.add(TCON(encoding=3, text=[genre]))

        if composer:
            tags.delall("TCOM")
            tags.add(TCOM(encoding=3, text=[composer]))

        if track_number is not None:
            track_text = (
                f"{track_number}/{track_total}" if track_total else str(track_number)
            )
            tags.delall("TRCK")
            tags.add(TRCK(encoding=3, text=[track_text]))

        if disc_number is not None:
            disc_text = (
                f"{disc_number}/{disc_total}" if disc_total else str(disc_number)
            )
            tags.delall("TPOS")
            tags.add(TPOS(encoding=3, text=[disc_text]))

        if description:
            tags.delall("COMM")
            tags.add(COMM(encoding=3, lang="por", desc="comment", text=[description]))
            tags.delall("TXXX:DESCRIPTION")
            tags.add(TXXX(encoding=3, desc="DESCRIPTION", text=[description]))

        if cover_bytes:
            tags.delall("APIC")
            tags.add(
                APIC(
                    encoding=3,
                    mime="image/jpeg",
                    type=3,
                    desc="Cover",
                    data=cover_bytes,
                )
            )

        try:
            audio.save(v2_version=3)
        except Exception as exc:  # noqa: BLE001 - qualquer falha de escrita é tratada
            self._logger.error(f"Erro ao salvar metadados em '{file_path}': {exc}")
            return

        self._logger.info(f"Metadados incorporados com sucesso em '{file_path}'.")

    @staticmethod
    def _resolve_artist(info: Dict[str, Any]) -> Optional[str]:
        """Tenta determinar o artista da faixa a partir de diferentes
        campos possíveis no dicionário de metadados.

        Args:
            info: Dicionário de metadados da faixa.

        Returns:
            O nome do artista identificado, ou None se não for possível
            determiná-lo.
        """
        for key in ("artist", "uploader", "creator", "channel"):
            value = info.get(key)
            if value:
                return str(value)
        title = info.get("title") or ""
        if " - " in title:
            return title.split(" - ", 1)[0].strip()
        return None

    @staticmethod
    def _resolve_year(info: Dict[str, Any]) -> Optional[str]:
        """Tenta determinar o ano de lançamento da faixa a partir de
        diferentes campos possíveis no dicionário de metadados.

        Args:
            info: Dicionário de metadados da faixa.

        Returns:
            O ano identificado como string de 4 dígitos, ou None se não
            for possível determiná-lo.
        """
        release_year = info.get("release_year")
        if release_year:
            return str(release_year)
        for key in ("release_date", "upload_date"):
            value = info.get(key)
            if value and len(str(value)) >= 4:
                return str(value)[:4]
        return None

    @staticmethod
    def _first_or_none(values: Optional[List[Any]]) -> Optional[str]:
        """Retorna o primeiro elemento de uma lista, se houver.

        Args:
            values: Lista de valores, ou None.

        Returns:
            O primeiro elemento convertido em string, ou None.
        """
        if isinstance(values, list) and values:
            return str(values[0])
        return None


# ==============================================================================
# EXIBIÇÃO DE PROGRESSO, VELOCIDADE E ETA
# ==============================================================================
class ProgressReporter:
    """Responsável por exibir, em tempo real, o progresso, a velocidade e o
    tempo estimado restante (ETA) de cada download, além de mensagens de
    status durante as etapas de pós-processamento (conversão para MP3,
    incorporação de metadados e da capa)."""

    def __init__(self) -> None:
        """Inicializa o relator de progresso, sem nenhum contexto de faixa
        definido."""
        self._title: str = ""
        self._index: Optional[int] = None
        self._total: Optional[int] = None
        self._last_line_length: int = 0

    def set_context(
        self,
        title: str,
        index: Optional[int] = None,
        total: Optional[int] = None,
    ) -> None:
        """Define o contexto (faixa atual) exibido nas próximas mensagens
        de progresso.

        Args:
            title: Título da faixa atualmente em processamento.
            index: Posição da faixa dentro de uma coleção (1-based), ou
                None para uma música individual.
            total: Quantidade total de faixas da coleção, ou None para uma
                música individual.
        """
        self._title = title
        self._index = index
        self._total = total
        self._last_line_length = 0

    def progress_hook(self, status: Dict[str, Any]) -> None:
        """Callback compatível com a opção ``progress_hooks`` do yt-dlp,
        invocado continuamente durante o download bruto do arquivo de
        áudio (antes da conversão para MP3).

        Args:
            status: Dicionário de status fornecido pelo yt-dlp, contendo
                informações como bytes baixados, bytes totais, velocidade
                e tempo estimado restante.
        """
        current_status = status.get("status")
        if current_status == "downloading":
            downloaded = status.get("downloaded_bytes") or 0
            total_bytes = status.get("total_bytes") or status.get("total_bytes_estimate") or 0
            speed = status.get("speed")
            eta = status.get("eta")
            percent = (downloaded / total_bytes * 100.0) if total_bytes else 0.0
            self._print_inline(self._format_download_line(percent, speed, eta))
        elif current_status == "finished":
            self._print_inline(f"{self._build_prefix()} Download concluído. Processando...")
        elif current_status == "error":
            self._print_inline(f"{self._build_prefix()} Erro durante o download.")

    def postprocessor_hook(self, status: Dict[str, Any]) -> None:
        """Callback compatível com a opção ``postprocessor_hooks`` do
        yt-dlp, invocado durante as etapas de pós-processamento (conversão
        de áudio, conversão de miniatura, incorporação de metadados e da
        capa).

        Args:
            status: Dicionário de status fornecido pelo yt-dlp, contendo o
                nome do pós-processador em execução e o status atual
                ("started" ou "finished").
        """
        postprocessor_name = status.get("postprocessor", "")
        current_status = status.get("status")
        label = self._postprocessor_label(postprocessor_name)
        if current_status == "started":
            self._print_inline(f"{self._build_prefix()} {label}...")
        elif current_status == "finished":
            self._print_inline(f"{self._build_prefix()} {label} concluído.")

    def finish_line(self) -> None:
        """Finaliza a linha de progresso atual, movendo o cursor para a
        próxima linha do terminal."""
        sys.stdout.write("\n")
        sys.stdout.flush()
        self._last_line_length = 0

    @staticmethod
    def _postprocessor_label(postprocessor_name: str) -> str:
        """Traduz o nome técnico de um pós-processador do yt-dlp para uma
        mensagem amigável em português.

        Args:
            postprocessor_name: Nome interno do pós-processador do yt-dlp.

        Returns:
            Uma descrição amigável da etapa em execução.
        """
        mapping = {
            "FFmpegExtractAudio": "Convertendo para MP3",
            "FFmpegThumbnailsConvertor": "Convertendo miniatura",
            "FFmpegMetadata": "Incorporando metadados",
            "EmbedThumbnail": "Incorporando capa",
        }
        return mapping.get(postprocessor_name, postprocessor_name or "Processando")

    def _build_prefix(self) -> str:
        """Monta o prefixo exibido em todas as linhas de progresso,
        incluindo a posição da faixa (quando parte de uma coleção) e o seu
        título.

        Returns:
            O prefixo formatado para exibição.
        """
        truncated_title = self._truncate(self._title, 42)
        if self._index is not None and self._total is not None:
            width = max(3, len(str(self._total)))
            return f"[{self._index:0{width}d}/{self._total:0{width}d}] {truncated_title}"
        return truncated_title

    def _format_download_line(
        self, percent: float, speed: Optional[float], eta: Optional[int]
    ) -> str:
        """Formata a linha completa de progresso de download.

        Args:
            percent: Percentual concluído (0 a 100).
            speed: Velocidade de download em bytes por segundo.
            eta: Tempo estimado restante, em segundos.

        Returns:
            A linha de progresso formatada para exibição no terminal.
        """
        prefix = self._build_prefix()
        bar = self._build_bar(percent)
        speed_text = self._format_speed(speed)
        eta_text = self._format_eta(eta)
        return f"{prefix} {bar} {percent:5.1f}%  {speed_text}  ETA {eta_text}"

    @staticmethod
    def _truncate(text: str, length: int) -> str:
        """Trunca um texto para um comprimento máximo, adicionando "…" ao
        final quando necessário.

        Args:
            text: Texto original.
            length: Comprimento máximo desejado.

        Returns:
            O texto truncado, se necessário.
        """
        text = text or ""
        if len(text) <= length:
            return text.ljust(length)
        return text[: length - 1] + "…"

    @staticmethod
    def _build_bar(percent: float, width: int = 24) -> str:
        """Constrói uma barra de progresso textual.

        Args:
            percent: Percentual concluído (0 a 100).
            width: Largura total da barra, em caracteres.

        Returns:
            A barra de progresso formatada, ex.: ``[████░░░░░░░░]``.
        """
        clamped = max(0.0, min(percent, 100.0))
        filled = int(width * clamped / 100)
        return "[" + ("█" * filled) + ("░" * (width - filled)) + "]"

    @staticmethod
    def _format_speed(speed: Optional[float]) -> str:
        """Formata a velocidade de download em unidades legíveis.

        Args:
            speed: Velocidade em bytes por segundo, ou None se ainda não
                disponível.

        Returns:
            A velocidade formatada, ex.: ``1.3 MB/s``.
        """
        if not speed:
            return "--.- KB/s"
        units = ["B/s", "KB/s", "MB/s", "GB/s"]
        value = float(speed)
        index = 0
        while value >= 1024 and index < len(units) - 1:
            value /= 1024
            index += 1
        return f"{value:6.1f} {units[index]}"

    @staticmethod
    def _format_eta(eta: Optional[int]) -> str:
        """Formata o tempo estimado restante (ETA) no formato MM:SS ou
        HH:MM:SS.

        Args:
            eta: Tempo estimado restante, em segundos, ou None.

        Returns:
            O ETA formatado como texto.
        """
        if eta is None:
            return "--:--"
        eta = int(eta)
        minutes, seconds = divmod(eta, 60)
        hours, minutes = divmod(minutes, 60)
        if hours:
            return f"{hours:d}:{minutes:02d}:{seconds:02d}"
        return f"{minutes:02d}:{seconds:02d}"

    def _print_inline(self, text: str) -> None:
        """Imprime um texto na mesma linha do terminal, sobrescrevendo o
        conteúdo anterior (utilizando retorno de carro).

        Args:
            text: Texto a ser exibido.
        """
        padding = " " * max(0, self._last_line_length - len(text))
        sys.stdout.write("\r" + text + padding)
        sys.stdout.flush()
        self._last_line_length = len(text)


# ==============================================================================
# ANÁLISE E CLASSIFICAÇÃO DE LINKS
# ==============================================================================
class LinkAnalyzer:
    """Responsável por inspecionar uma URL, identificar seu tipo de
    conteúdo (música individual, álbum ou playlist) e extrair a lista de
    faixas, quando se tratar de uma coleção."""

    def __init__(self, cache_dir: Path, logger: logging.Logger) -> None:
        """Inicializa o analisador de links.

        Args:
            cache_dir: Pasta de cache utilizada internamente pelo yt-dlp.
            logger: Logger utilizado para registrar o processo de análise.
        """
        self._cache_dir = cache_dir
        self._logger = logger
        self._ydl_logger = YdlLoggerAdapter(logger)

    def probe(self, url: str) -> Dict[str, Any]:
        """Realiza uma extração rápida (sem download) para identificar o
        tipo de conteúdo apontado pela URL.

        Por padrão, uma URL que aponte simultaneamente para um vídeo e
        para uma playlist associada (por exemplo, um link de música do
        YouTube copiado de dentro de uma playlist, contendo tanto ``v=``
        quanto ``list=``) é tratada como uma música individual, pois esse
        é o caso de uso mais comum ao colar o link de uma faixa específica.
        Para baixar a playlist inteira, o usuário deve colar o link direto
        da playlist (ex.: ``.../playlist?list=...``).

        Args:
            url: URL informada pelo usuário.

        Returns:
            O dicionário de metadados (possivelmente parcial, no caso de
            coleções) retornado pelo yt-dlp.

        Raises:
            LinkAnalysisError: Se não for possível analisar a URL.
        """
        options: Dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "extract_flat": "in_playlist",
            "noplaylist": True,
            "logger": self._ydl_logger,
            "cachedir": str(self._cache_dir),
            "socket_timeout": 30,
            "retries": 5,
        }
        try:
            with yt_dlp.YoutubeDL(options) as ydl:
                info = ydl.extract_info(url, download=False)
        except YtDlpDownloadError as exc:
            raise LinkAnalysisError(str(exc)) from exc
        except Exception as exc:  # noqa: BLE001 - qualquer falha é convertida
            raise LinkAnalysisError(f"Erro inesperado ao analisar o link: {exc}") from exc

        if info is None:
            raise LinkAnalysisError("O link informado não retornou nenhuma informação.")
        return info

    @staticmethod
    def classify(info: Dict[str, Any]) -> LinkType:
        """Classifica o conteúdo identificado como música, álbum ou
        playlist.

        Como nem todos os sites distinguem explicitamente um "álbum" de
        uma "playlist" genérica, esta classificação utiliza heurísticas
        baseadas no extrator utilizado e na própria URL. Do ponto de vista
        do processamento, álbuns e playlists são tratados de forma
        idêntica (pasta própria nomeada e numeração sequencial); a
        distinção serve principalmente para fins de exibição ao usuário.

        Args:
            info: Dicionário de metadados retornado por ``probe``.

        Returns:
            O LinkType identificado.
        """
        if "entries" not in info or info.get("entries") is None:
            return LinkType.MUSIC

        extractor_key = str(info.get("extractor_key") or info.get("extractor") or "").lower()
        webpage_url = str(info.get("webpage_url") or "").lower()

        if "album" in extractor_key:
            return LinkType.ALBUM
        if "/album/" in webpage_url:
            return LinkType.ALBUM
        return LinkType.PLAYLIST

    def flatten_entries(self, info: Dict[str, Any]) -> Iterator[Dict[str, Any]]:
        """Percorre recursivamente as entradas de uma coleção, lidando com
        eventuais sub-coleções aninhadas (uma camada de profundidade).

        Args:
            info: Dicionário de metadados de uma coleção (álbum/playlist).

        Yields:
            Cada dicionário de entrada individual (faixa) encontrado.
        """
        entries = info.get("entries") or []
        for entry in entries:
            if not entry:
                continue
            if entry.get("_type") == "playlist" and entry.get("entries"):
                yield from self.flatten_entries(entry)
            else:
                yield entry


# ==============================================================================
# CONSTRUÇÃO DAS OPÇÕES DO YT-DLP
# ==============================================================================
class YdlOptionsBuilder:
    """Responsável por construir o dicionário completo de opções utilizado
    em cada chamada individual ao yt-dlp para baixar e converter uma
    faixa."""

    def __init__(
        self,
        config: AppConfig,
        cache_dir: Path,
        temp_dir: Path,
        logger_adapter: YdlLoggerAdapter,
        progress_hook: Any,
        postprocessor_hook: Any,
        ffmpeg_location: Optional[str] = None,
    ) -> None:
        """Inicializa o construtor de opções.

        Args:
            config: Configuração ativa do programa.
            cache_dir: Pasta de cache do yt-dlp.
            temp_dir: Pasta temporária de trabalho desta sessão.
            logger_adapter: Adaptador de log a ser utilizado pelo yt-dlp.
            progress_hook: Função de callback de progresso de download.
            postprocessor_hook: Função de callback de progresso de
                pós-processamento.
            ffmpeg_location: Caminho customizado para o FFmpeg, ou None
                para utilizar o FFmpeg disponível no PATH do sistema.
        """
        self._config = config
        self._cache_dir = cache_dir
        self._temp_dir = temp_dir
        self._logger_adapter = logger_adapter
        self._progress_hook = progress_hook
        self._postprocessor_hook = postprocessor_hook
        self._ffmpeg_location = ffmpeg_location

    def build(self) -> Dict[str, Any]:
        """Monta o dicionário completo de opções para uma chamada do
        ``yt_dlp.YoutubeDL``.

        Returns:
            O dicionário de opções pronto para uso.
        """
        postprocessors: List[Dict[str, Any]] = [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": self._config.audio_format,
                "preferredquality": self._config.audio_quality,
            }
        ]
        if self._config.embed_thumbnail:
            postprocessors.append({"key": "FFmpegThumbnailsConvertor", "format": "jpg"})
        if self._config.embed_metadata:
            postprocessors.append(
                {"key": "FFmpegMetadata", "add_metadata": True, "add_chapters": False}
            )
        if self._config.embed_thumbnail:
            postprocessors.append({"key": "EmbedThumbnail", "already_have_thumbnail": False})

        options: Dict[str, Any] = {
            "format": "bestaudio/best",
            "outtmpl": str(self._temp_dir / "%(id)s.%(ext)s"),
            "writethumbnail": self._config.embed_thumbnail,
            "postprocessors": postprocessors,
            "progress_hooks": [self._progress_hook],
            "postprocessor_hooks": [self._postprocessor_hook],
            "logger": self._logger_adapter,
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "ignoreerrors": False,
            "overwrites": False,
            "continuedl": True,
            "windowsfilenames": True,
            "restrictfilenames": False,
            "cachedir": str(self._cache_dir),
            "socket_timeout": 30,
            "retries": 10,
            "fragment_retries": 10,
            "concurrent_fragment_downloads": 4,
            "writeinfojson": False,
            "writesubtitles": False,
            "writeautomaticsub": False,
            "writedescription": False,
        }
        if self._ffmpeg_location:
            options["ffmpeg_location"] = self._ffmpeg_location
        return options


# ==============================================================================
# ORQUESTRAÇÃO COMPLETA DO DOWNLOAD
# ==============================================================================
class MusicDownloader:
    """Classe central do programa, responsável por orquestrar todo o
    processo de download: analisar o link, decidir a estratégia (música
    individual ou coleção), baixar e converter o áudio, incorporar
    metadados e capa, e organizar os arquivos finais nas pastas corretas."""

    def __init__(
        self,
        config: AppConfig,
        logger: logging.Logger,
        cache_dir: Path,
        temp_dir: Path,
        ffmpeg_location: Optional[str] = None,
    ) -> None:
        """Inicializa o orquestrador de downloads, criando todas as
        dependências internas necessárias.

        Args:
            config: Configuração ativa do programa.
            logger: Logger utilizado para registrar todo o processo.
            cache_dir: Pasta de cache do yt-dlp.
            temp_dir: Pasta temporária de trabalho desta sessão (exclusiva
                deste processo, para suportar múltiplas instâncias
                simultâneas sem conflito de arquivos).
            ffmpeg_location: Caminho customizado para o FFmpeg, ou None
                para utilizar o FFmpeg disponível no PATH do sistema.
        """
        self._config = config
        self._logger = logger
        self._temp_dir = temp_dir
        self._path_manager = PathManager(config)
        self._link_analyzer = LinkAnalyzer(cache_dir=cache_dir, logger=logger)
        self._reporter = ProgressReporter()
        self._metadata_embedder = MetadataEmbedder(logger=logger)
        self._thumbnail_downloader = ThumbnailDownloader(logger=logger)
        self._ydl_logger = YdlLoggerAdapter(logger=logger)
        self._options_builder = YdlOptionsBuilder(
            config=config,
            cache_dir=cache_dir,
            temp_dir=temp_dir,
            logger_adapter=self._ydl_logger,
            progress_hook=self._reporter.progress_hook,
            postprocessor_hook=self._reporter.postprocessor_hook,
            ffmpeg_location=ffmpeg_location,
        )

    def process(self, url: str) -> None:
        """Ponto de entrada principal: analisa a URL informada e decide o
        fluxo de processamento adequado (música individual ou coleção).

        Args:
            url: URL informada pelo usuário.
        """
        url = url.strip()
        if not url:
            return

        self._logger.info(f"Analisando link: {url}")
        try:
            info = self._link_analyzer.probe(url)
        except DownloaderError as exc:
            self._logger.error(f"Erro ao analisar o link '{url}': {exc}")
            print(ConsoleColors.error(f"\n[ERRO] Não foi possível analisar o link informado: {exc}"))
            return
        except Exception as exc:  # noqa: BLE001 - qualquer falha é tratada
            self._logger.exception("Erro inesperado ao analisar o link.")
            print(ConsoleColors.error(f"\n[ERRO] Erro inesperado ao analisar o link: {exc}"))
            return

        link_type = self._link_analyzer.classify(info)
        extractor_name = info.get("extractor_key") or info.get("extractor") or "desconhecido"
        self._logger.info(f"Link classificado como {link_type.value} (extrator: {extractor_name}).")
        print(ConsoleColors.info(f"\nSite detectado: {extractor_name} | Tipo identificado: {link_type.value}"))

        if link_type is LinkType.MUSIC:
            self._process_single(url, info)
        else:
            self._process_collection(url, info, link_type)

    def _process_single(self, url: str, info: Dict[str, Any]) -> None:
        """Processa o download de uma música individual.

        Args:
            url: URL da música a ser baixada.
            info: Dicionário de metadados já analisado pela LinkAnalyzer.
        """
        title = info.get("title") or "Música"
        safe_title = FilenameSanitizer.sanitize(title)
        dest_dir = self._path_manager.single_track_dir()
        filename = f"{safe_title}.{self._config.audio_format}"
        predicted_path = dest_dir / filename

        if predicted_path.exists():
            message = f"A música '{title}' já existe em '{dest_dir}'. Pulando download."
            self._logger.info(message)
            print(ConsoleColors.warning(f"[AVISO] {message}"))
            return

        print(ConsoleColors.highlight(f"\nBaixando música: {title}"))
        self._reporter.set_context(title=title)
        try:
            final_path = self._execute_download(url, dest_dir, predicted_filename=filename)
        except DownloaderError as exc:
            self._reporter.finish_line()
            self._logger.error(f"Falha ao baixar '{title}': {exc}")
            print(ConsoleColors.error(f"[ERRO] Falha ao baixar '{title}': {exc}"))
            return

        self._reporter.finish_line()
        print(ConsoleColors.success(f"✔ Música salva em: {final_path}"))

    def _process_collection(self, url: str, info: Dict[str, Any], link_type: LinkType) -> None:
        """Processa o download de um álbum ou playlist completo.

        Args:
            url: URL original do álbum/playlist informada pelo usuário.
            info: Dicionário de metadados já analisado pela LinkAnalyzer.
            link_type: Tipo de coleção identificado (ALBUM ou PLAYLIST).
        """
        label = "álbum" if link_type is LinkType.ALBUM else "playlist"
        raw_title = info.get("title") or (
            "Álbum sem nome" if link_type is LinkType.ALBUM else "Playlist sem nome"
        )
        playlist_title = FilenameSanitizer.sanitize(raw_title)
        dest_dir = self._path_manager.playlist_dir(playlist_title)

        print(ConsoleColors.highlight(f"\nDetectado {label}: {raw_title}"))
        print(ConsoleColors.info(f"Pasta de destino: {dest_dir}"))

        cover_url = self._thumbnail_downloader.select_best(info.get("thumbnails"))
        if cover_url:
            cover_path = dest_dir / "capa.jpg"
            saved_cover = self._thumbnail_downloader.download(cover_url, cover_path)
            if saved_cover:
                print(ConsoleColors.info(f"Capa do(a) {label} salva em: {saved_cover}"))
        else:
            self._logger.warning(f"Nenhuma miniatura encontrada para o(a) {label} '{raw_title}'.")

        entries = list(self._link_analyzer.flatten_entries(info))
        total = len(entries)
        if total == 0:
            message = f"Nenhuma faixa encontrada no(a) {label} '{raw_title}'."
            self._logger.warning(message)
            print(ConsoleColors.warning(f"[AVISO] {message}"))
            return

        pad_width = max(3, len(str(total)))
        print(ConsoleColors.info(f"Total de faixas encontradas: {total}\n"))

        successes = 0
        failures = 0
        skipped = 0

        for index, entry in enumerate(entries, start=1):
            entry_url = entry.get("url") or entry.get("webpage_url") or entry.get("id")
            entry_title = entry.get("title") or f"Faixa {index}"

            if not entry_url:
                failures += 1
                self._logger.warning(f"Item {index}/{total} sem URL utilizável. Pulando.")
                print(ConsoleColors.error(f"[{index:0{pad_width}d}/{total}] Sem URL utilizável. Pulando."))
                continue

            safe_entry_title = FilenameSanitizer.sanitize(entry_title)
            filename = f"{index:0{pad_width}d} - {safe_entry_title}.{self._config.audio_format}"
            predicted_path = dest_dir / filename

            if predicted_path.exists():
                skipped += 1
                self._logger.info(f"Faixa já existente, pulando: '{predicted_path}'.")
                print(f"[{index:0{pad_width}d}/{total}] '{entry_title}' já existe. Pulando.")
                continue

            self._reporter.set_context(title=entry_title, index=index, total=total)
            try:
                self._execute_download(
                    entry_url,
                    dest_dir,
                    predicted_filename=filename,
                    album_override=raw_title,
                    track_number=index,
                    track_total=total,
                )
                self._reporter.finish_line()
                successes += 1
            except DownloaderError as exc:
                self._reporter.finish_line()
                failures += 1
                self._logger.error(f"Falha ao baixar item {index}/{total} ('{entry_title}'): {exc}")
                print(ConsoleColors.error(f"[{index:0{pad_width}d}/{total}] Falha ao baixar '{entry_title}': {exc}"))
                continue
            except Exception as exc:  # noqa: BLE001 - protege o laço de coleções
                self._reporter.finish_line()
                failures += 1
                self._logger.exception(f"Erro inesperado ao baixar item {index}/{total} ('{entry_title}').")
                print(ConsoleColors.error(f"[{index:0{pad_width}d}/{total}] Erro inesperado em '{entry_title}': {exc}"))
                continue

        print(
            ConsoleColors.success(
                f"\nConcluído: {successes} baixada(s), {skipped} já existente(s), "
                f"{failures} com falha. Pasta: {dest_dir}"
            )
        )

    def _execute_download(
        self,
        url: str,
        dest_dir: Path,
        predicted_filename: str,
        album_override: Optional[str] = None,
        track_number: Optional[int] = None,
        track_total: Optional[int] = None,
    ) -> Path:
        """Executa o download e a conversão de uma única faixa, move o
        arquivo final para a pasta de destino e incorpora seus metadados.

        Args:
            url: URL direta da faixa a ser baixada (nunca uma URL de
                coleção; coleções já devem ter sido decompostas em faixas
                individuais antes de chamar este método).
            dest_dir: Pasta de destino final do arquivo.
            predicted_filename: Nome de arquivo final desejado (já
                sanitizado e numerado, se aplicável).
            album_override: Nome do álbum/playlist a ser embutido nos
                metadados, sobrescrevendo o valor original da faixa.
            track_number: Número da faixa dentro da coleção, se aplicável.
            track_total: Quantidade total de faixas da coleção, se
                aplicável.

        Returns:
            O caminho final em que o arquivo MP3 foi salvo.

        Raises:
            TrackDownloadError: Se ocorrer qualquer falha durante o
                download, a conversão ou a movimentação do arquivo.
        """
        options = self._options_builder.build()

        try:
            with yt_dlp.YoutubeDL(options) as ydl:
                info = ydl.extract_info(url, download=True)
        except YtDlpDownloadError as exc:
            raise TrackDownloadError(str(exc)) from exc
        except Exception as exc:  # noqa: BLE001 - qualquer falha é convertida
            raise TrackDownloadError(f"Erro inesperado durante o download: {exc}") from exc

        if info is None:
            raise TrackDownloadError("Nenhuma informação foi retornada pelo yt-dlp.")

        if info.get("_type") == "playlist" and info.get("entries"):
            nested_entry = next((entry for entry in info["entries"] if entry), None)
            if nested_entry is None:
                raise TrackDownloadError("O item resolvido representa uma coleção vazia.")
            info = nested_entry

        video_id = str(info.get("id") or "audio")
        temp_path = self._temp_dir / f"{video_id}.{self._config.audio_format}"

        if not temp_path.exists():
            candidates = sorted(
                self._temp_dir.glob(f"{video_id}*.{self._config.audio_format}"),
                key=lambda candidate: candidate.stat().st_mtime,
                reverse=True,
            )
            if not candidates:
                raise TrackDownloadError(
                    f"O arquivo de áudio convertido não foi encontrado para o id '{video_id}'."
                )
            temp_path = candidates[0]

        final_path = PathManager.ensure_unique(dest_dir / predicted_filename)
        try:
            final_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(temp_path), str(final_path))
        except OSError as exc:
            raise TrackDownloadError(f"Falha ao mover o arquivo final: {exc}") from exc

        if self._config.embed_metadata:
            self._metadata_embedder.embed(
                file_path=final_path,
                info=info,
                track_number=track_number,
                track_total=track_total,
                disc_number=info.get("disc_number"),
                disc_total=info.get("disc_count"),
                album_override=album_override,
            )

        self._cleanup_temp_artifacts(video_id)
        return final_path

    def _cleanup_temp_artifacts(self, video_id: str) -> None:
        """Remove quaisquer arquivos remanescentes na pasta temporária
        relacionados a um determinado id de vídeo (ex.: miniaturas
        intermediárias ou arquivos parciais que não tenham sido
        automaticamente removidos pelos pós-processadores do yt-dlp).

        Args:
            video_id: Identificador do vídeo/faixa cujos artefatos
                temporários devem ser removidos.
        """
        for leftover in self._temp_dir.glob(f"{video_id}*"):
            try:
                leftover.unlink()
            except OSError as exc:
                self._logger.debug(f"Não foi possível remover arquivo temporário '{leftover}': {exc}")


# ==============================================================================
# APLICAÇÃO PRINCIPAL (INTERAÇÃO COM O USUÁRIO)
# ==============================================================================
class Application:
    """Classe principal da aplicação: prepara o ambiente (pastas,
    configuração, logs, FFmpeg) e conduz o laço de interação com o
    usuário no terminal, permitindo baixar múltiplos links em sequência
    até que o usuário decida encerrar o programa."""

    BANNER: ClassVar[str] = (
        "\n"
        "============================================================\n"
        "          DOWNLOADER DE MUSICAS  -  yt-dlp + FFmpeg\n"
        "============================================================\n"
    )

    def __init__(self) -> None:
        """Inicializa a aplicação: cria as pastas auxiliares, configura o
        logger, carrega a configuração, prepara a pasta temporária
        exclusiva desta sessão/processo e detecta o FFmpeg disponível."""
        self.base_dir: Path = Path(__file__).resolve().parent
        self.logs_dir: Path = self.base_dir / "logs"
        self.cache_dir: Path = self.base_dir / "cache"
        self.temp_root_dir: Path = self.base_dir / "temp"

        for directory in (self.logs_dir, self.cache_dir, self.temp_root_dir):
            directory.mkdir(parents=True, exist_ok=True)

        self.logger: logging.Logger = LoggingSetup.create_logger(self.logs_dir)
        self.config_manager: ConfigManager = ConfigManager(self.base_dir / "config.json", self.logger)
        self.config: AppConfig = self.config_manager.load()

        session_id = f"sessao_{os.getpid()}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}"
        self.session_temp_dir: Path = self.temp_root_dir / session_id
        self.session_temp_dir.mkdir(parents=True, exist_ok=True)

        self.ffmpeg_location: Optional[str] = self._detect_ffmpeg()

        self.downloader: MusicDownloader = MusicDownloader(
            config=self.config,
            logger=self.logger,
            cache_dir=self.cache_dir,
            temp_dir=self.session_temp_dir,
            ffmpeg_location=self.ffmpeg_location,
        )

    def _detect_ffmpeg(self) -> Optional[str]:
        """Verifica a disponibilidade do FFmpeg no PATH do sistema ou na
        própria pasta do programa.

        Returns:
            O caminho da pasta contendo o FFmpeg local, caso ele não
            esteja no PATH mas exista na pasta do programa; ou None,
            caso o FFmpeg já esteja disponível no PATH (situação em que o
            yt-dlp o localizará automaticamente) ou não tenha sido
            encontrado em lugar algum (situação em que um aviso já terá
            sido emitido).
        """
        located = shutil.which("ffmpeg")
        if located:
            self.logger.info(f"FFmpeg encontrado no PATH: {located}")
            return None

        local_ffmpeg = self.base_dir / "ffmpeg.exe"
        if local_ffmpeg.exists():
            self.logger.info(f"FFmpeg local encontrado: {local_ffmpeg}")
            return str(self.base_dir)

        self.logger.warning("FFmpeg não foi encontrado nem no PATH nem na pasta do programa.")
        print(
            ConsoleColors.warning(
                "[AVISO] FFmpeg não foi localizado. A conversão para MP3 pode falhar.\n"
                "Instale o FFmpeg e adicione-o ao PATH, ou coloque 'ffmpeg.exe' nesta pasta.\n"
            )
        )
        return None

    def run(self) -> None:
        """Executa o laço principal de interação com o usuário: exibe o
        banner inicial e, repetidamente, solicita um link, processa o
        download correspondente e pergunta implicitamente (via novo
        prompt) se o usuário deseja continuar, até que ele digite um
        comando de saída ou interrompa o programa."""
        print(ConsoleColors.highlight(self.BANNER))
        print(f"Pasta de downloads configurada: {self.config.download_path}")
        print(f"Formato de áudio: {self.config.audio_format.upper()} | Qualidade: {self.config.audio_quality} kbps")
        print("\nCole o link de uma música, álbum ou playlist e pressione Enter.")
        print("Digite 'sair' para encerrar o programa.\n")

        try:
            while True:
                try:
                    url = input(ConsoleColors.highlight("Link: ")).strip()
                except EOFError:
                    break

                if not url:
                    continue
                if url.lower() in ("sair", "exit", "quit", "q"):
                    break

                try:
                    self.downloader.process(url)
                except KeyboardInterrupt:
                    print(ConsoleColors.warning("\nOperação interrompida pelo usuário."))
                except Exception as exc:  # noqa: BLE001 - protege o laço principal
                    self.logger.exception("Erro não tratado durante o processamento do link.")
                    print(ConsoleColors.error(f"\n[ERRO] Ocorreu um erro inesperado: {exc}"))

                print()
        except KeyboardInterrupt:
            print(ConsoleColors.warning("\nEncerrando o programa..."))
        finally:
            self._cleanup()

        print(ConsoleColors.success("Até a próxima!"))

    def _cleanup(self) -> None:
        """Remove a pasta temporária exclusiva desta sessão, liberando
        qualquer arquivo intermediário remanescente."""
        try:
            shutil.rmtree(self.session_temp_dir, ignore_errors=True)
            self.logger.info(f"Pasta temporária da sessão removida: {self.session_temp_dir}")
        except OSError as exc:
            self.logger.debug(f"Não foi possível remover a pasta temporária da sessão: {exc}")


# ==============================================================================
# PONTO DE ENTRADA
# ==============================================================================
def main() -> None:
    """Função de entrada do programa: prepara o terminal (codificação e
    cores), inicializa a aplicação e executa o laço principal."""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001 - reconfigure pode não estar disponível
        pass

    colorama_init()

    try:
        application = Application()
    except Exception as exc:  # noqa: BLE001 - falha fatal de inicialização
        print(f"[ERRO FATAL] Não foi possível iniciar o programa: {exc}")
        try:
            input("Pressione Enter para sair...")
        except EOFError:
            pass
        sys.exit(1)

    application.run()


if __name__ == "__main__":
    main()
