# AirConsole MultiDisplay

An Android proof of concept for playing AirConsole on a primary screen and mirroring it to selected secondary displays. The app loads AirConsole in a WebView and uses Android screen capture and display presentations to show view-only mirrors.

## Features

- Open AirConsole in the app.
- Discover and select available secondary displays.
- Mirror the primary display to supported displays.
- Update display selections while mirroring is active.

## Requirements

- Android 10 (API 29) or later.
- A device with secondary displays that support Android presentations.
- Android Studio and the Android SDK to build the project.

Android requires user consent to capture the screen. Some devices restrict app presentations on secondary displays, so display support depends on the device and its manufacturer.

## Build

Open the project in Android Studio and run the `app` configuration, or build a debug APK from PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The APK is generated at `app\build\outputs\apk\debug\app-debug.apk`.
