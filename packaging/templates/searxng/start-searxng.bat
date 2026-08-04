@echo off
setlocal
set DIR=%~dp0
set VENV=%DIR%venv
set SRC=%DIR%src
set PORT=8888
if not exist "%VENV%\Scripts\activate.bat" (
  echo SearXNG venv missing. Run setup-venv.bat during build.
  exit /b 1
)
if not exist "%SRC%\searx\__init__.py" (
  echo Official SearXNG source missing. Run setup-venv.bat during build.
  exit /b 1
)
call "%VENV%\Scripts\activate.bat"
set SEARXNG_SETTINGS_PATH=%DIR%settings.yml
set PYTHONPATH=%SRC%
cd /d "%DIR%"
granian --interface wsgi searx.webapp:app --host 127.0.0.1 --port %PORT%
