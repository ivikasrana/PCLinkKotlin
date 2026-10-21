# PCLink

[![license](https://img.shields.io/badge/license-MIT%20%2B%20Commercial-blue.svg)](#license)

Share your Android phone's storage with your PC over the local network. No cable, no cloud.

PCLink runs a small server on the phone that your PC can reach as a **WebDAV drive**, a
**web page in the browser**, or an **FTP server**. It also lets you send text between the PC
and phone, mirror phone notifications, and install APKs by copying them to the phone.

## Features

- **WebDAV** (default port `1099`): map the phone as a network drive in Windows Explorer,
  macOS Finder or any WebDAV client.
- **HTTP + browser UI** (default port `1199`): browse, upload, download, create folders and
  delete files from any browser.
- **FTP** (default port `1299`): use FileZilla, WinSCP or any FTP client.
- **Resumable chunked uploads** through a JSON API for large files.
- **Text to phone**: text sent from the PC shows as a notification; tap it to copy.
- **Notification mirror**: read the phone's notifications from the PC (optional, needs
  notification access).
- **APK install**: drop an `.apk` into the `_install` folder to start installing it.
- **LAN discovery**: the phone answers UDP discovery requests on port `1198`.
- **QR pairing**: scan a `pclink://` code from the PC to set ports, user and password.
- **Runs in the background** as a foreground service, with optional start on boot.
- Basic authentication with a random password generated on first launch.

## Requirements

- Android 11 (API 30) or newer.
- Phone and PC on the same network.
- "All files access" permission, so the phone storage can be shared.

## Getting started

1. Install the APK and open PCLink.
2. Grant the requested permissions and start the server.
3. On the PC, connect with the address, user and password shown in the app:
   - Browser: `http://<phone-ip>:1199`
   - WebDAV: `http://<phone-ip>:1099` (Windows: *This PC → Map network drive*)
   - FTP: `ftp://<phone-ip>:1299`

## HTTP API

All endpoints use the same Basic authentication as the file server.

| Method | Path                 | Purpose                                         |
| ------ | -------------------- | ----------------------------------------------- |
| GET    | `/api/info`          | Device and server information                   |
| GET    | `/api/manifest`      | Recursive file list with size and modified time |
| GET    | `/api/chunk`         | Status of a partial upload                      |
| PUT    | `/api/chunk`         | Upload one chunk of a file                      |
| POST   | `/api/text`          | Send text to the phone                          |
| GET    | `/api/outbox`        | Fetch text queued on the phone for the PC       |
| GET    | `/api/notifications` | Mirrored notifications (`?since=<ms>`)          |

## Building

Requires JDK 17 and the Android SDK (path in `local.properties` as `sdk.dir=...`).

```bash
./gradlew :app:assembleDebug
```

On Windows the helper script builds and copies the APK to `dist/`:

```powershell
./build.ps1            # debug, plus release when signing is configured
./build.ps1 -Release   # signed release only
./build.ps1 -Install   # build and install with adb
```

### Release signing

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=your-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Tech stack

Kotlin, Jetpack Compose (Material 3), NanoHTTPD, CameraX with ML Kit barcode scanning,
Kotlin coroutines.

## Security

PCLink is meant for trusted local networks. Traffic is plain HTTP/FTP and is not
encrypted, so do not run the server on public Wi-Fi. Change the generated password if
you share it, and stop the server when you do not need it.

## License

PCLink is dual licensed:

- **[MIT License](LICENSE)**: free for personal, open source and commercial use, with
  attribution and no warranty.
- **[Commercial License](COMMERCIAL-LICENSE.md)**: for use without attribution, with
  warranty, support or custom terms.

Contributions are accepted under the MIT License.
