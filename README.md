# Lucy

Lucy is a lightweight native Android music player for browsing and playing audio stored on your device.

## Features

- Browse a device music library by track, artist, album, or playlist.
- Play tracks, albums, and playlists, with a persistent in-app mini player and a now-playing screen.
- Create playlists, add tracks, remove tracks, and play a selected collection.
- Edit title, artist, album, and lyrics in Lucy's local library database.
- View saved plain-text or LRC lyrics.

Tag and lyric edits are stored locally by Lucy and do not rewrite the original audio files. Audio playback and library scanning happen on-device.

## Build

Open the project in Android Studio or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow builds debug and unsigned release APKs on pushes, pull requests, and manual runs, and publishes them as the `lucy-apks` workflow artifact.