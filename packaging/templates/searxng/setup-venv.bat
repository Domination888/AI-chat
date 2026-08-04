@echo off
setlocal
set DIR=%~dp0
set VENV=%DIR%venv
set SRC=%DIR%src
if "%SEARXNG_REF%"=="" set SEARXNG_REF=master

if not exist "%VENV%\Scripts\python.exe" py -3.12 -m venv "%VENV%"
call "%VENV%\Scripts\activate.bat"
python -m pip install -U pip
if exist "%SRC%\.git" (
  git -C "%SRC%" fetch --depth 1 origin "%SEARXNG_REF%" || exit /b 1
  git -C "%SRC%" checkout --detach FETCH_HEAD || exit /b 1
) else (
  if exist "%SRC%" rmdir /s /q "%SRC%"
  git clone --depth 1 --branch "%SEARXNG_REF%" https://github.com/searxng/searxng.git "%SRC%" || exit /b 1
)
python -m pip install -r "%SRC%\requirements.txt" -r "%SRC%\requirements-server.txt" granian || exit /b 1
set PYTHONPATH=%SRC%
python -c "import searx; print('SearXNG source ready:', searx.__file__)" || exit /b 1
echo SearXNG venv and official source ready
