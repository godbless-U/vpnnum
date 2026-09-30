# GitHub / Android build guide

## GitHub

1. Create an empty GitHub repository.
2. Upload the contents of this folder to the repository root.
3. Push to `main` or `master`.
4. Open **Actions → Build Android APK**.
5. Download `vpn-manager-release-apk` from the workflow run.

## Local

Use JDK 17+, Android SDK 36 and Gradle 8.13:

```bash
gradle assembleRelease --no-daemon
```

The APK is created at:

`app/build/outputs/apk/release/app-release.apk`

## VPN permission

Android displays the VPN permission screen the first time the app starts a real WireGuard tunnel. The app must not silently obtain this permission.
