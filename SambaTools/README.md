# SambaTools

Tools for preparing fast remote photo and video thumbnails for the Android Samba tab.

## Generate Thumbnails

Run from Windows with a mounted drive or UNC path:

```bat
run.bat "\\server\share\Photos"
```

The tool creates a hidden `.phonesamba_thumbs` folder inside the photo folder. The Android app looks there first, so the Samba tab can show tiny JPEG previews instead of reading full-size photos or videos over Wi-Fi.

`run.bat` creates a local `.venv` when needed, installs Python dependencies from `requirements.txt` immediately, and reuses that environment on later runs. Video thumbnails use `ffmpeg` from `PATH` or the installed `imageio-ffmpeg` package, and include a semi-transparent play overlay.
