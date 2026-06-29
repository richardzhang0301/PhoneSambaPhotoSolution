@echo off
setlocal
cd /d "%~dp0"

set "PHOTO_DIR=%~1"
set "PY_CMD=.venv\Scripts\python.exe"
set "REQ_FILE=%~dp0requirements.txt"
set "VENV_CREATED=0"

if not exist "%REQ_FILE%" (
    echo Missing dependency file: %REQ_FILE%
    exit /b 1
)

if exist "%PY_CMD%" (
    rem Use the existing virtual environment.
) else (
    echo Creating Python virtual environment in .venv...
    where py >nul 2>nul
    if not errorlevel 1 (
        py -3 -m venv .venv
    )

    if not exist "%PY_CMD%" (
        python -m venv .venv
    )

    if not exist "%PY_CMD%" (
        echo Failed to create Python virtual environment.
        exit /b 1
    )

    set "VENV_CREATED=1"
)

if "%VENV_CREATED%"=="1" (
    echo Installing Python dependencies...
    "%PY_CMD%" -m pip install -r "%REQ_FILE%"
    if errorlevel 1 (
        echo Failed to install Python dependencies.
        exit /b 1
    )
)

"%PY_CMD%" -c "import PIL, imageio_ffmpeg" >nul 2>nul
if errorlevel 1 (
    echo Installing missing Python dependencies...
    "%PY_CMD%" -m pip install -r "%REQ_FILE%"
    if errorlevel 1 (
        echo Failed to install Python dependencies.
        exit /b 1
    )

    "%PY_CMD%" -c "import PIL, imageio_ffmpeg" >nul 2>nul
    if errorlevel 1 (
        echo Python dependencies are still missing after install.
        exit /b 1
    )
)

if "%PHOTO_DIR%"=="" (
    echo Enter the mounted or UNC Samba photo folder.
    echo Example: \\server\share\Photos
    set /p "PHOTO_DIR=Photo folder: "
)

if "%PHOTO_DIR%"=="" (
    echo No folder provided.
    exit /b 1
)

"%PY_CMD%" "%~dp0generate_thumbnails.py" "%PHOTO_DIR%" --size 384 --quality 82 --workers 4 --prune
pause
