# Downloader de Músicas

Programa de linha de comando, escrito em **Python 3.12**, para baixar músicas,
álbuns e playlists de praticamente qualquer site de música/vídeo suportado
pelo **yt-dlp** (YouTube, YouTube Music, SoundCloud, Bandcamp, Vimeo,
Mixcloud, Audiomack e centenas de outros), convertendo automaticamente o
áudio para **MP3** na melhor qualidade disponível, com **FFmpeg**, e
incorporando metadados completos e a capa em alta resolução.

Você só precisa executar **um único arquivo**: `Baixar Música.bat`.

---

## Índice

1. [Visão geral](#visão-geral)
2. [Requisitos](#requisitos)
3. [Instalação](#instalação)
4. [Como usar](#como-usar)
5. [Estrutura de pastas](#estrutura-de-pastas)
6. [Onde os arquivos são salvos](#onde-os-arquivos-são-salvos)
7. [Metadados incorporados](#metadados-incorporados)
8. [Arquivo config.json](#arquivo-configjson)
9. [Atualizando o programa](#atualizando-o-programa)
10. [Múltiplas instâncias simultâneas](#múltiplas-instâncias-simultâneas)
11. [Logs](#logs)
12. [Solução de problemas](#solução-de-problemas)
13. [Aviso sobre direitos autorais](#aviso-sobre-direitos-autorais)
14. [Créditos](#créditos)

---

## Visão geral

O programa identifica automaticamente, a partir do link colado pelo usuário,
se o conteúdo é:

- uma **música individual**;
- um **álbum**;
- uma **playlist**.

Em seguida baixa sempre o **melhor áudio disponível**, converte para **MP3**
usando o **FFmpeg**, incorpora os **metadados completos** (título, artista,
álbum, faixa, disco, ano, gênero, compositor, descrição e comentários,
quando existentes) e a **capa em alta resolução**, organizando tudo
automaticamente em pastas, sem nunca sobrescrever músicas já baixadas.

O programa utiliza **somente**:

- **Python** (orquestração de todo o processo);
- **yt-dlp** (extração e download);
- **FFmpeg** (conversão de áudio e incorporação da capa).

Nenhum outro gerenciador de downloads (como `aria2c`) é utilizado.

---

## Requisitos

- **Windows 10 ou superior**.
- **Python 3.12** (ou superior) — [python.org/downloads](https://www.python.org/downloads/)
  - Durante a instalação, marque a opção **"Add python.exe to PATH"**.
- **FFmpeg** — [gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/)
  - Adicione a pasta `bin` do FFmpeg ao **PATH** do Windows, **ou**
  - copie o arquivo `ffmpeg.exe` diretamente para a pasta deste projeto.
- Conexão com a internet.

---

## Instalação

1. Instale o **Python 3.12+** e o **FFmpeg**, conforme a seção
   [Requisitos](#requisitos).
2. Extraia esta pasta `Downloader/` em qualquer local do seu computador.
3. Dê um duplo clique em **`Baixar Música.bat`**.

Na primeira execução, o programa verifica automaticamente se as bibliotecas
Python necessárias (`yt-dlp`, `mutagen`, `requests`, `colorama`) estão
instaladas. Caso não estejam, elas são instaladas automaticamente a partir
do arquivo `requirements.txt` — não é necessário rodar nenhum comando manual.

Se preferir instalar manualmente, abra um terminal dentro da pasta do
projeto e execute:

```bat
python -m pip install -r requirements.txt
```

---

## Como usar

1. Dê duplo clique em **`Baixar Música.bat`**.
2. Cole o link de uma música, álbum ou playlist quando solicitado e
   pressione **Enter**.
3. Acompanhe o progresso, a velocidade e o tempo estimado (ETA) na tela.
4. Ao final, cole outro link para continuar baixando, ou digite `sair` para
   encerrar o programa.

Exemplo de interação:

```
Link: https://www.youtube.com/watch?v=XXXXXXXXXXX

Site detectado: youtube | Tipo identificado: música
Baixando música: Nome da Música
[001/001] Nome da Música [████████████████████████] 100.0%   1.8 MB/s  ETA 00:00
✔ Música salva em: C:\Users\luiso\OneDrive\Desktop\Músicas\Baixadas\2026-06-27\Nome da Música.mp3
```

---

## Estrutura de pastas

```
Downloader/
│
├── Baixar Música.bat      -> arquivo que você executa para usar o programa
├── Atualizar.bat           -> atualiza o yt-dlp e as demais dependências
├── downloader.py            -> código-fonte completo do programa
├── requirements.txt         -> lista de dependências Python
├── config.json               -> arquivo de configuração editável
├── README.md                 -> este arquivo
├── cache/                    -> cache interno do yt-dlp (gerado automaticamente)
├── logs/                     -> arquivos de log de cada execução
└── temp/                      -> arquivos temporários durante o download/conversão
```

As pastas `cache/`, `logs/` e `temp/` são criadas automaticamente pelo
programa caso não existam, e podem ser apagadas com segurança quando o
programa não estiver em execução — elas serão recriadas na próxima
execução.

---

## Onde os arquivos são salvos

A pasta raiz de downloads é definida no campo `download_path` do arquivo
`config.json` (por padrão,
`C:\Users\luiso\OneDrive\Desktop\Músicas\Baixadas`).

### Músicas individuais

São salvas em uma subpasta nomeada com a **data do download**, no formato
`AAAA-MM-DD`:

```
C:\Users\luiso\OneDrive\Desktop\Músicas\Baixadas\2026-06-27\Nome da Música.mp3
```

### Álbuns e playlists

É criada automaticamente uma pasta com o **nome do álbum/playlist**, e as
faixas são salvas numeradas sequencialmente:

```
C:\Users\luiso\OneDrive\Desktop\Músicas\Baixadas\Nome da Playlist\
    001 - Primeira Música.mp3
    002 - Segunda Música.mp3
    003 - Terceira Música.mp3
    ...
    capa.jpg
```

O arquivo `capa.jpg`, salvo na maior resolução disponível, representa a
capa do álbum/playlist como um todo.

> Observação: nem todos os sites distinguem explicitamente um "álbum" de uma
> "playlist" genérica. Quando essa distinção não é informada pelo site de
> origem, o programa identifica o conteúdo como "playlist" — o
> comportamento de download e organização de pastas é idêntico em ambos os
> casos.

### Nunca sobrescrever

Antes de baixar qualquer faixa, o programa verifica se um arquivo com o
mesmo nome final já existe na pasta de destino. Se já existir, a faixa é
**pulada** (não é baixada novamente), e isso é informado na tela. Isso
permite, por exemplo, colar o link de uma playlist novamente no futuro
para baixar apenas as faixas novas, sem duplicar as que você já possui.

---

## Metadados incorporados

Sempre que disponíveis na origem, os seguintes metadados são incorporados
automaticamente ao arquivo MP3:

- Título
- Artista
- Álbum
- Número da faixa (e total de faixas, em playlists/álbuns)
- Número do disco (quando informado pela origem)
- Ano
- Gênero
- Compositor
- Descrição
- Comentários
- Capa (miniatura na maior resolução disponível)

A capa é baixada e incorporada automaticamente pelo próprio `yt-dlp` em
conjunto com o FFmpeg; os demais metadados textuais são incorporados
diretamente nos frames ID3v2 do MP3 através da biblioteca `mutagen`, o que
garante compatibilidade com o Windows Explorer, Windows Media Player,
Winamp, iTunes, VLC e demais leitores de MP3.

---

## Arquivo config.json

```json
{
  "download_path": "C:\\Users\\luiso\\OneDrive\\Desktop\\Músicas\\Baixadas",
  "audio_format": "mp3",
  "audio_quality": "320",
  "embed_thumbnail": true,
  "embed_metadata": true
}
```

| Campo             | Descrição                                                                 |
|-------------------|----------------------------------------------------------------------------|
| `download_path`   | Pasta raiz onde os downloads serão organizados.                           |
| `audio_format`    | Formato de áudio final (recomendado: `mp3`).                              |
| `audio_quality`   | Qualidade/bitrate do áudio em kbps (ex.: `128`, `192`, `256`, `320`).      |
| `embed_thumbnail` | Se `true`, baixa e incorpora a capa ao arquivo final.                     |
| `embed_metadata`  | Se `true`, incorpora todos os metadados (título, artista, álbum, etc.).   |

Você pode editar `config.json` com qualquer editor de texto (Notepad, VS
Code, etc.). Se o arquivo for removido ou ficar corrompido, o programa o
recria automaticamente com os valores padrão na próxima execução.

---

## Atualizando o programa

Os sites de vídeo/música mudam constantemente, e o `yt-dlp` é atualizado
com muita frequência para acompanhar essas mudanças. Caso os downloads
comecem a falhar, execute:

**`Atualizar.bat`**

Esse arquivo atualiza automaticamente:

- o `pip`;
- o `yt-dlp` (sempre para a versão mais recente disponível);
- as demais dependências do projeto (`mutagen`, `requests`, `colorama`).

---

## Múltiplas instâncias simultâneas

Você pode dar duplo clique em `Baixar Música.bat` quantas vezes quiser,
abrindo várias janelas ao mesmo tempo — cada uma é um processo
independente, com sua própria pasta temporária e seu próprio arquivo de
log, permitindo baixar vários links em paralelo sem qualquer conflito
entre as instâncias.

---

## Logs

Cada execução do programa cria um arquivo de log próprio dentro da pasta
`logs/`, nomeado com o horário de início e o identificador do processo
(PID), por exemplo:

```
logs/downloader_20260627_153000_18452.log
```

Esses arquivos contêm o histórico detalhado de cada análise de link,
download, conversão e eventual erro ocorrido, sendo úteis para
diagnosticar problemas.

---

## Solução de problemas

**"Python não foi encontrado no PATH do sistema"**
Reinstale o Python marcando a opção "Add python.exe to PATH", ou adicione
manualmente a pasta de instalação do Python ao PATH do Windows.

**"FFmpeg não foi localizado"**
Baixe o FFmpeg em https://www.gyan.dev/ffmpeg/builds/, extraia o `.zip` e
adicione a pasta `bin` ao PATH do Windows, ou copie apenas o arquivo
`ffmpeg.exe` para dentro desta pasta do projeto.

**Falha ao instalar as dependências**
Verifique sua conexão com a internet e tente executar manualmente:
`python -m pip install -r requirements.txt`

**Um link específico falha ao ser baixado**
Alguns vídeos/faixas podem ser privados, removidos, bloqueados por região
ou exigir login. Consulte o arquivo de log correspondente em `logs/` para
mais detalhes sobre o erro específico.

**Erro de permissão ao salvar na pasta do OneDrive**
Verifique se a pasta configurada em `download_path` existe e se o OneDrive
não está bloqueando o acesso (por exemplo, por estar pausado ou sem
espaço). Você pode alterar `download_path` em `config.json` para qualquer
outra pasta local.

---

## Aviso sobre direitos autorais

Este programa é uma ferramenta de uso pessoal para download de áudio. É de
responsabilidade do usuário utilizá-la em conformidade com os termos de
uso das plataformas de origem e com a legislação de direitos autorais
aplicável em sua região.

---

## Créditos

- [**yt-dlp**](https://github.com/yt-dlp/yt-dlp) — motor de extração e
  download.
- [**FFmpeg**](https://ffmpeg.org/) — conversão de áudio e incorporação de
  capa.
- [**mutagen**](https://mutagen.readthedocs.io/) — leitura/escrita de
  metadados em MP3.
- [**requests**](https://requests.readthedocs.io/) — download das
  miniaturas/capas.
- [**colorama**](https://github.com/tartley/colorama) — saída colorida no
  terminal do Windows.
