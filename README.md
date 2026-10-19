# Lucy

Lucy is a lightweight native Android music player for browsing and playing audio stored on your device.

## Features

- Browse a device music library by track, artist, album, or playlist.
- Play tracks, albums, and playlists, with a persistent in-app mini player and a now-playing screen.
- Create playlists, add tracks, remove tracks, and play a selected collection.
- Edit title, artist, album, lyrics, and file name together on one editor page.
- Write tag and lyric changes into supported audio files, and rename them from the editor.
- Search iTunes metadata by file name or current tags, and fetch plain or synced lyrics from LRCLIB.

Audio playback, library scanning, and tag editing happen on-device. Editing a shared media file may require approval from Android. Metadata lookups send the entered file name or tags to Apple's iTunes Search API; lyric searches send track details to LRCLIB. Audio files are not uploaded. Tag editing uses the Android-compatible [Jaudiotagger](https://github.com/Adonai/jaudiotagger-android) library.

## Build

Open the project in Android Studio or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow builds debug and unsigned release APKs on pushes, pull requests, and manual runs, and publishes them as the `lucy-apks` workflow artifact. CI caches Gradle dependencies and build outputs; Android SDK setup installs only the required platform/build tools.