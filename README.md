# Phone Samba Photo

A small native Android app for browsing phone photos and sending them to a saved Samba share on the local network.

## Features

- Shows local photos from Android MediaStore in a fast thumbnail grid.
- Shows remote photos from the saved Samba folder on a separate Samba tab.
- Saves one Samba destination for repeat use.
- One-tap sync for unsynced photos.
- Multi-select upload for specific photos.
- Skips remote files that already exist with the same size.

## Samba Destination

The app asks for:

- Host or IP, for example `192.168.1.20`
- Share, for example `Photos`
- Optional folder, for example `Phone/Camera`
- Optional domain, username, and password

The final location is built as:

```text
smb://host/share/folder/
```

## Build

Open this folder in Android Studio, or build from this folder:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```



