# VPN Manager for Android

A GitHub-ready Android VPN manager focused on **real WireGuard tunnel connections**, GitHub config discovery, validation, sorting, batch testing, import/export, favorites, and safe deletion.

## Features

- Real WireGuard connection using the official WireGuard Android tunnel library (`com.wireguard.android:tunnel`).
- Android VPN permission flow through `VpnService.prepare()`.
- GitHub source URLs that can be added/removed without recompiling.
- Fetch and parse WireGuard `.conf` and OpenVPN `.ovpn` text for catalog/import purposes.
- Duplicate removal by protocol + content.
- Automatic score sorting, ping sorting, country sorting, favorites sorting.
- Pages of 50 or 100 entries.
- Test current page batch.
- Connect to one selected VPN manually.
- Favorite/unfavorite.
- Delete one config.
- Delete invalid configs.
- Delete all configs.
- Import multiple files.
- Export all stored configs as a ZIP with `vpn-list.json`.
- GitHub Actions workflow for release APK builds.

## Important protocol note

The release in this repository implements **actual tunnel control for WireGuard**. OpenVPN profiles are recognized, imported, and exported, but an OpenVPN runtime is not falsely presented as connected. Adding a native OpenVPN 3 runtime is a separate native/JNI integration and should be done before enabling an OpenVPN Connect button.

## GitHub Actions

Push the repository to GitHub. The workflow installs JDK 17, Android SDK 36, and Gradle 8.13, then builds a release APK. The APK is uploaded as a workflow artifact.

You can also trigger the workflow manually from **Actions → Build Android APK**.

## Build locally

Requirements: JDK 17+, Android SDK 36, Gradle 8.13.

```bash
gradle assembleRelease
```

APK:

```text
app/build/outputs/apk/release/app-release.apk
```

## First run

1. Import a `.conf` WireGuard profile or add a GitHub source.
2. Select a profile.
3. Press **اتصال**.
4. Accept Android's VPN permission dialog.
5. The selected WireGuard tunnel is brought up by the embedded WireGuard backend.

Only use VPN configurations you are authorized to use. Public GitHub configuration lists can disappear or become invalid at any time.
