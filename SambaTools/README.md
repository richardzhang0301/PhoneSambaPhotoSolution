# SambaTools

Tools for preparing fast remote thumbnails for the Android Samba tab.

## Generate Thumbnails

Run from Windows with a mounted drive or UNC path:

```bat
run.bat "\\server\share\Photos"
```

The tool creates a hidden `.phonesamba_thumbs` folder inside the photo folder. The Android app looks there first, so the Samba tab can show tiny JPEG previews instead of reading full-size photos over Wi-Fi.

`run.bat` creates a local `.venv` when needed, uses it on later runs, and can install Pillow into that environment if it is missing.
