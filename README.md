# PhoneSambaPhotoSolution

A small solution for syncing phone photos and videos to a Samba share and browsing local and remote media.

## Folders

- `Android/` - native Android app for local photo/video browsing, Samba upload, and remote Samba photo/video viewing.
- `SambaTools/` - helper scripts for preparing fast remote photo and video thumbnails on the Samba folder.

## Android App

Build from the Android folder:

```powershell
cd Android
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
Android/app/build/outputs/apk/debug/app-debug.apk
```

## Samba Thumbnails

The Android app checks for prebuilt thumbnails in `.phonesamba_thumbs/` inside the configured Samba photo folder. To generate them, run:

```powershell
cd SambaTools
.\run.bat "\\server\share\Photos"
```

You can also run `run.bat` without an argument and paste the folder path when prompted.
