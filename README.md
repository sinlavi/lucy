# Lucy

Lucy is a lightweight native Android music player for browsing and playing audio stored on your device.

## Features

- Browse a device music library by track, artist, album, or playlist.
- Play tracks, albums, and playlists, with a persistent in-app mini player and a now-playing screen.
- Manage the playback queue, play tracks next, and use shuffle and repeat controls.
- Like tracks and browse smart playlists for liked, recently played, most played, and recently added songs.
- Create playlists, add tracks, remove tracks, and play a selected collection.
- Customize the theme, accent color, and library sort order in Settings.
- Edit title, artist, album, lyrics, and file name together on one editor page.
- Write tag and lyric changes into supported audio files, and rename them from the editor.
- Search iTunes metadata by file name or current tags, and fetch plain or synced lyrics from LRCLIB.
- Install on Android 6.0 (API 23) and newer, including Android 9.

Audio playback, library scanning, and tag editing happen on-device. Editing a shared media file may require approval from Android. Metadata lookups send the entered file name or tags to Apple's iTunes Search API; lyric searches send track details to LRCLIB. Audio files are not uploaded. Tag editing uses the Android-compatible [Jaudiotagger](https://github.com/Adonai/jaudiotagger-android) library.

## Build

Open the project in Android Studio or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow builds debug and release APKs on pushes, pull requests, and manual runs, and publishes them as the `lucy-apks` workflow artifact. Both APKs can be installed for testing; the release build currently uses Android's shared debug signing key so it can be sideloaded. Replace this with a private release keystore before publishing, and do not distribute a debug-signed build as a production release. CI caches Gradle dependencies and build outputs.