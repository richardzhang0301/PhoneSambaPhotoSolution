@echo off
setlocal
cd /d "%~dp0"

set "PHOTO_DIR=%~1"
if "%PHOTO_DIR%"=="" (
    echo Enter the mounted or UNC Samba photo folder.
    echo Example: \\server\share\Photos
    set /p "PHOTO_DIR=Photo folder: "
)

if "%PHOTO_DIR%"=="" (
    echo No folder provided.
    exit /b 1
)

if exist ".venv\Scripts\python.exe" (
    set "PY_CMD=.venv\Scripts\python.exe"
) else (
    where py >nul 2>nul
    if not errorlevel 1 (
        set "PY_CMD=py -3"
    ) else (
        set "PY_CMD=python"
    )
)

%PY_CMD% -c "import PIL" >nul 2>nul
if errorlevel 1 (
    echo Pillow is required to decode photos.
    echo.
    set /p "INSTALL=Install Pillow now? [Y/N]: "
    if /I "%INSTALL%"=="Y" (
        %PY_CMD% -m pip install Pillow
    ) else (
        echo Install later with: %PY_CMD% -m pip install Pillow
        exit /b 1
    )
)

%PY_CMD% "%~dp0generate_thumbnails.py" "%PHOTO_DIR%" --size 384 --quality 82 --workers 4 --prune
pause
