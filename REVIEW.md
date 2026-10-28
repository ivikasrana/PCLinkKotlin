# Code review: fixes, refactors and performance work

Branch: `claude/zealous-maxwell-dy0cq0` in both repositories.

**Status: not compiled or run.** The Android SDK and .NET SDK could not be downloaded in the
review environment. Changes were read twice and checked for bracket balance only. Build both
projects (`build.ps1` for PCLink.Net, `./gradlew assembleDebug` for PCLinkKotlin) and run
`SelfTest` against a phone before merging.

## PCLinkKotlin

### Bugs fixed
| Issue | Impact | Fix |
|---|---|---|
| MOVE/COPY with the destination an ancestor of the source (`/a/b` to `/a`) | Destination was deleted first, erasing the source (data loss) | Rejected in `ShareServer.transfer` and `transferFile` (also covers FTP `RNTO`) |
| `PUT` with `Transfer-Encoding: chunked` | Read as an empty body, creating a 0-byte file | Answers `411 Length Required` |
| FTP `USER` after login | Login state was kept | `USER` clears it |

### Performance and refactor
- Replaced per-entry `SimpleDateFormat` (PROPFIND, FTP LIST/MDTM, activity log) with shared
  thread-safe `DateTimeFormatter`s.
- Directory listing and PROPFIND stat each entry once instead of once per sort comparison.
- Case-insensitive name sorting without per-comparison allocation.
- FTP pre-login command set built once.
- Removed the second Wi-Fi lock (HIGH_PERF maps to LOW_LATENCY on API 29+; minSdk is 30).

## PCLink.Net

- `PhoneMonitor` kept one `HttpClient` per connection settings instead of creating a new
  client and TCP connection every 5 seconds. It retries once on a fresh client if a kept-alive
  connection is stale.
- Uploads share one client per drop instead of one per file.
- Upload, APK install and folder sync run on the thread pool, so large folders no longer
  freeze the UI.
- Async file handles and a 256 KB copy buffer for transfers; JSON parsed from the stream.
- Diagnostics checks run concurrently.
- `ProcessRunner` times out after 60 seconds instead of hanging on `sc`/`net`/`schtasks`.
- Open FTP / Open web page ask for the phone address instead of launching a broken URL.

## Not changed (known limitations)
- No rate limiting on failed logins (8-character random password, trusted-LAN design).
- `/api/manifest` stops silently at 50,000 files; sync re-uploads the missing ones.
- Sync compares names case-sensitively; case-only duplicates on the phone can overwrite each
  other on Windows.
- Tray icon handle is never freed (one-time leak).
