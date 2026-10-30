# Notflix for Android TV

**This app is the TV client for [Notflix Debrid](https://github.com/jdepew88/notflix-debrid). It does nothing without a separately installed Notflix server.**

| | |
|---|---|
| **Server (required)** | [jdepew88/notflix-debrid](https://github.com/jdepew88/notflix-debrid): install it first, on a PC on your network |
| **This repository** | The native Android TV client (Kotlin, Compose for TV, Media3 ExoPlayer) |
| **Current version** | 0.5.2 (versionCode 3), debug builds only |

## How it fits together

The app is not a WebView and holds no media, no Real-Debrid account and no TMDB key. Everything goes
through the server:

```
TV (this app) ──HTTP──▶ Notflix server :8420   (sign-in, profiles, catalog, /api/playback/*)
   ExoPlayer                │
   hardware decoders        ▼
                         Media engine (loopback on the server PC) ──▶ Real-Debrid
```

* **Discovery:** the server announces `_notflix._tcp` over mDNS/DNS-SD while its LAN access is on
  ([server docs](https://github.com/jdepew88/notflix-debrid/blob/main/docs/LAN-DISCOVERY.md)). The app lists
  what it finds and verifies each server with `GET /api/health`. You can also type an address.
* **Authentication:** you sign in with a username and password from *that* server. Accounts, profiles,
  PINs and kids rules are the server's own; the app keeps only the session cookie.
* **Shared state:** resume points, Continue Watching and My List are stored on the server, in the
  same format the website uses.
* **Credentials:** Real-Debrid and TMDB keys stay on the server. The APK contains no server address.

## Status

| | |
|---|---|
| Downloadable APK | [**0.5.2 pre-release**](https://github.com/jdepew88/notflix-android-tv/releases), signed release build, from `v0.5.2` |
| Tested on | Android TV 14 (API 34) emulator, against a fresh clone of the server's `main` |
| Works in that test | `_notflix._tcp` discovery, manual address entry, sign-in, profiles |
| **Not tested in this build** | **Catalog and playback** (the test server had placeholder keys). This build is minified, so playback must be checked separately. An earlier debug build played a title on the emulator. |
| **Not tested at all** | **A physical TV**, and discovery across a real Wi-Fi or Ethernet network. The emulator can't receive LAN multicast, so a test relay carried it. |

Details: [docs/TEST-STATUS.md](docs/TEST-STATUS.md).

## Before you start

1. Install the Notflix server on a PC on your network, following its
   [quick start](https://github.com/jdepew88/notflix-debrid#quick-start-windows): run `Setup-Notflix.cmd`,
   start it with **Start Notflix**, and finish the setup wizard.
2. On the server, turn on **LAN access** and **Let TVs find this server automatically** (Settings →
   Server & storage). Automatic discovery needs multicast to reach the TV; otherwise enter the
   address by hand.

## Install it on your television

Download `notflix-android-tv-0.5.2.apk` from [Releases](https://github.com/jdepew88/notflix-android-tv/releases), or build it yourself (*Building* below;
a debug build is at `app\build\outputs\apk\debug\app-debug.apk`). It appears in the TV's app list as
**Notflix**. The commands below use the debug path; use the downloaded file instead if you have one.

### Option A — over the network with ADB (recommended)

1. **Turn on developer options on the TV.**
   *Google TV / Android TV 12+:* Settings → System → About → scroll to **Android TV OS build** →
   press OK on it **7 times** ("You are now a developer").
   *Older Android TV:* Settings → Device Preferences → About → **Build** → OK 7 times.
2. **Turn on debugging.** Settings → System (or Device Preferences) → **Developer options** →
   turn on **USB debugging**. Some TVs (e.g. NVIDIA Shield, Sony) also have **Network debugging**:
   turn that on too.
   *Google TV with Android 11+ may offer **Wireless debugging** instead:* open it, choose
   **Pair device with pairing code**, then on the PC run
   `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pair <TV-IP>:<pairing-port>` and enter
   the code. Use the IP:port shown under *Wireless debugging* in step 4.
3. **Find the TV's IP address:** Settings → Network & Internet → your network (for example
   `192.168.1.50`). The TV must be able to reach your Notflix server (for example
   `http://<server PC>:8420`) the same way a phone browser on that network does.
4. **Install** from PowerShell in this repository's folder:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\install-tv.ps1 -TvAddress 192.168.1.50
   # Wireless debugging (Android 11+): include the port it shows
   powershell -ExecutionPolicy Bypass -File scripts\install-tv.ps1 -TvAddress 192.168.1.50:37123
   ```

   The first time, the TV asks **"Allow network debugging?"**: choose *Always allow from this
   computer*, then run the command again if it timed out. The script connects, installs (`adb install
   -r`, which also upgrades an existing install) and starts the app.

   Manual equivalent:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb connect 192.168.1.50:5555
   & $adb -s 192.168.1.50:5555 install -r app\build\outputs\apk\debug\app-debug.apk
   ```

5. Optional: turn debugging off again in Developer options. The app stays installed.

### Option B — without ADB (USB stick or a downloader app)

1. Copy `app-debug.apk` to a USB stick, or put it somewhere the TV can download it.
2. On the TV install a file manager (e.g. *File Commander*, *X-plore*) or *Downloader*.
3. Settings → Apps → **Security & restrictions** → **Unknown sources** (or *Install unknown apps*) →
   allow that file manager / Downloader.
4. Open the APK in it and choose **Install**.

### First launch

1. **Welcome to NOTFLIX — Find your Notflix server.** The app searches the network for about
   10 seconds and lists every compatible server it finds, with the address and port the server
   announced (Notflix servers with LAN access on announce themselves since Phase 5.2; see
   [`docs/server-discovery.md`](docs/server-discovery.md)). Choose one with the remote; nothing is
   connected automatically, even when only one server is found.
2. If nothing is found, choose **Enter Server Address Manually** and type the address you'd open
   in a browser: `192.168.1.33`, `192.168.1.33:8420`, `http://media-pc.local:8420` or
   `https://notflix.example.com`. A bare address is tried as `http://…:8420`, then `http://…:4119`
   (older servers), as shown on screen before anything is contacted; an explicit port or a full URL
   is used exactly as typed.
3. The address is verified (`GET /api/health` must identify a compatible Notflix server) and then
   remembered. **Sign in** with your Notflix username and password from *that* server. The TV stays
   signed in like a browser with *Remember me* (30 days).
4. **Who's watching?** Pick a profile; PIN-locked profiles show a number pad (the remote's number
   keys work too). Kids/teen profiles get the same server-side filtering as on the website.
   Profiles are created and edited on the website.

**Upgrading from Phase 5** keeps an address saved in Phase 5 (and its sign-in). A Phase 5 install
that never saved an address (it used the old built-in default) opens the wizard once; the server
its session belongs to is offered at the top as *Used by this TV before the update*, and choosing
it keeps the sign-in.

### Remote control

| Where | Key | Action |
|---|---|---|
| Everywhere | ← ↑ → ↓ / OK / Back | Move, open, go back. ← from the first card opens the side menu |
| Player, controls hidden | OK | Play / pause (and show the controls) |
| | ← / → | Seek −10 s / +10 s (hold to speed up; presses are combined into one seek) |
| | ↑ / ↓ | Show the controls |
| | ⏯ ⏪ ⏩ media keys | Play/pause, −30 s, +30 s |
| Player, controls shown | ← / → on the progress bar | Seek |
| | Buttons | Play/pause, ±10 s, Next episode, Audio, Subtitles, Change video source, Stream information |
| | Back | Hide the controls; Back again leaves the player (the resume point is saved) |

**Stream information** shows the playback mode (Direct Play / Remux / Audio Transcode / Video
Transcode), the release, original video, the TV decoder in use (e.g. `c2.amlogic.hevc.decoder`), audio
format/decoder or passthrough, any server conversion and the engine's reasons.

---

## How a title is played

1. Play → `POST /api/playback/start` with the TV's capabilities and the profile's resume position.
   The server ranks Real-Debrid releases (resolution limit, size, language-verified audio, …),
   resolves the link **on the server** and opens a media-engine session.
2. The engine decides per file:

   | Engine mode | What reaches the TV | Typical case |
   |---|---|---|
   | Direct Play | the original file, range requests via `/api/playback/{id}/stream` | MP4 with H.264/HEVC + AAC/AC-3 |
   | Remux | original video/audio copied into HLS fMP4 | MKV (see note) |
   | Audio Transcode | video copied, audio → AAC | DTS/TrueHD when the TV can neither decode nor pass it through |
   | Video Transcode | NVENC H.264/HEVC | codec, bit depth or resolution the TV's hardware can't decode |

3. HLS sessions: the app reports its position every 5 s (`/status`) so the engine keeps just ahead,
   seeks go through `/seek` (native inside what is prepared, a new generation for far seeks),
   audio/subtitle changes through `/tracks`; image subtitles are drawn by the server. Text subtitles
   are fetched as WebVTT and drawn by the app. Leaving the player saves progress and `DELETE`s the
   session so FFmpeg stops.
4. If the media engine is offline the server falls back to proxying the original file, which
   ExoPlayer usually plays directly.

**Note on MKV:** the engine's decision rules come from the browser player, which can't open MKV or
switch audio tracks, so MKV is remuxed (stream copy, no quality loss, the TV still decodes the
original video). A small, backward-compatible engine change would let the TV play MKV completely
directly. It is described in [`docs/proposal-direct-play-mkv.md`](docs/proposal-direct-play-mkv.md)
and has **not** been applied.

---

## Building

Toolchain (per-user installs, no admin rights needed):

| Tool | Location |
|---|---|
| Temurin JDK 21 | `%LOCALAPPDATA%\Programs\Temurin\jdk-21.0.12.1+1` (the system Java is a JRE, which can't build) |
| Android SDK (platform 37, build-tools 37, platform-tools/adb, emulator) | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle 9.8.2 | via the wrapper (`gradlew.bat`) |

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1               # APK + unit tests
```

or directly: `$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Temurin\jdk-21.0.12.1+1"; .\gradlew.bat assembleDebug`.
There is no build-time server address any more (`-PnotflixServerUrl` / `-ServerUrl` were removed in 5.1).
Android Studio can open this folder as a project.

Stack: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00, androidx.tv:tv-material
1.1.0, Media3 1.11.1, OkHttp 5.5.1, kotlinx.serialization 1.12.0, Coil 3.6.3. `minSdk 24`,
`targetSdk 36`, `compileSdk 37`.

## Tests

* `.\gradlew.bat testDebugUnitTest`: 68 JVM tests: the 14 Phase 5 API/progress/subtitle tests unchanged, Phase 5's 3 server-address
  tests replaced, 50 from 5.1 and 4 from 5.2 (the Phase 5.2 server's health answer, manual
  candidates 8420 then 4119 without following redirects, failure messages naming every candidate,
  an explicit port or URL as the only candidate).
  * Phase 5: WebVTT parsing and cue timing; progress rules identical to the web store's
    `updateProgress()`; the API client against recorded server answers (session cookie across
    restarts, PIN-required flow, playback refusals, engine session shape, progress writes).
  * Address parsing: IPv4 in any private range or subnet size, IPv6 in URL notation, local and DNS
    host names, full http/https URLs used as typed, custom ports, invalid input.
  * Verification against a mock server: today's health answer, the proposed identity fields,
    non-Notflix services, 404/5xx, unsupported API version, redirects reported instead of followed,
    timeout, connection refused on a real closed port, HTTPS to a plain-HTTP server, a fresh
    connection per check, no cookies sent.
  * Server selection: fresh install, saved-server reconnect, offline server kept, a different
    instance at the saved address not accepted silently, switching servers drops cookies, account
    and profile state, cookies scoped to scheme + host + port (even on the same host), cancelled
    switch changes nothing, Phase 5 upgrade paths, plain HTTP blocked to anything but the selected
    server (redirects included).
  * Discovery with a scripted DNS-SD browser: one, several and no servers, discovery failure,
    unverified impostors, checks that outlive the search window, lost services, duplicates,
    re-announcements and address changes, URL choice for IPv4/IPv6/link-local.
* `.\gradlew.bat connectedDebugAndroidTest`: on a device or emulator, registers a real
  `_notflix._tcp` service through Android NSD (backed by a tiny health endpoint) plus an impostor,
  runs the app's discovery and checks it finds and verifies the first and rejects the second.
  `NsdDiscoveryTest#discoversTheRealServerThroughALanRelay` (only with `-e lanRelay host:port`)
  does the same against a real server's announcement; see [docs/TEST-STATUS.md](docs/TEST-STATUS.md).
* `.\gradlew.bat lintDebug`: no errors. Remaining warnings are the Phase 5 ones (cleartext base
  config, a targetSdk one below compileSdk, large vector drawables, allowBackup).
* Phase 5.1 results on the Android TV 14 emulator are listed in [docs/TEST-STATUS.md](docs/TEST-STATUS.md).

## Troubleshooting

* **"No Notflix server found"** — on the server PC, `scripts\notflix.ps1 status` must show
  `LAN discovery  announced as …`. If it does, multicast isn't reaching the TV (guest Wi-Fi, client
  isolation, VLANs, VPN, or Windows Firewall blocking Node.js on UDP 5353); enter the address
  instead. The app's *Network Troubleshooting* screen lists the usual causes. The app never changes
  firewall or network settings.
* **"Can't connect to your Notflix server"** — open the server's address in a browser on a phone on
  the same Wi-Fi. If that fails, the TV can't reach it either (network/VLAN, or the Windows firewall
  rule for the port). *Retry* tries the same server again; *Change Server* never drops the saved
  one until another is verified and chosen.
* **"The media engine is offline"** — start it on the server PC (`scripts\notflix.ps1 start`).
* **Stutters on 4K** — open *Stream information*. *Video Transcode* means the TV reported it can't
  decode the source; pick another release with *Change video source*, or lower the quality limit
  on the website (Settings → Playback).
* **No surround sound** — passthrough needs the TV/receiver to accept AC-3/E-AC-3/DTS over HDMI;
  otherwise the TV decodes or the server converts the audio to AAC.

## Not included yet

Creating/editing profiles, server settings and administration (done on the website); trailers;
subtitle appearance settings (subtitles use one TV-sized style); a signed release build (debug
builds are signed with the local debug key).

---

## Connecting to a server (Phase 5.1)

| | |
|---|---|
| Discovery | Android Network Service Discovery (system mDNS/DNS-SD), service type `_notflix._tcp`. No subnet scanning and no assumption about address ranges or subnet sizes: whatever network the TV is on (Wi-Fi or Ethernet), the system responder browses it. Android 14+ resolves through `registerServiceInfoCallback` (all addresses, several at once); older versions queue `resolveService`. |
| Search | About 10 s per search, off the main thread, cancelled when the screen goes to the background, stopped when the network drops and restarted when it comes back. Every listener and resolve callback is released when a search ends. |
| Trust | Every advertised service is untrusted until `GET /api/health` on its address identifies a compatible Notflix server. The check uses a separate HTTP client: no cookies, no redirects followed, 5 s connect / 10 s read timeouts, normal certificate validation. Passing it proves compatibility, not authenticity. |
| Choosing | The user always chooses; a single result is focused, not connected. Choosing re-verifies the server first. |
| Saved | Server URL, friendly name, instance ID and API version when reported, and the last successful connection, in the app's private preferences. On launch the saved server is verified again (no network search) before sign-in or the profile picker. If it fails: *Retry*, *Change Server* or *Troubleshooting*. |
| Switching | Settings → Server Connection: *Test Connection*, *Search for Servers*, *Enter Address Manually* (both cancellable). Choosing a different server clears the old session cookie, account and profile state first and requires a new sign-in; re-choosing the same server keeps it. Cancelling changes nothing. |
| Isolation | The session cookie is bound to one origin (scheme + host + port) and is neither sent to nor accepted from any other, including another port on the same host. |
| Plain HTTP | Android's network security config can't list an address chosen at run time, so it still permits cleartext (as in Phase 5); the app itself refuses plain-HTTP requests, redirects included, to anything except the selected server. HTTPS validation is never relaxed. |
| Permissions | Unchanged from Phase 5: `INTERNET`, `ACCESS_NETWORK_STATE` (plus `WAKE_LOCK` and AndroidX's private receiver permission, both from libraries). NSD needs no additional permission at targetSdk 36. |

Server-side requirements (advertisement and identity fields) are in
[`docs/server-discovery.md`](docs/server-discovery.md). Nothing on the server was changed.

## Publishing a release APK

1. Keep the release keystore outside this repository. `app/build.gradle.kts` reads it from
   `~/.notflix-signing/keystore.properties` (or `NOTFLIX_SIGNING_PROPERTIES`) with `storeFile`,
   `storePassword`, `keyAlias` and `keyPassword`. Without that file the release build is unsigned.
2. `.\gradlew.bat testDebugUnitTest lintDebug assembleRelease`.
3. Test the minified APK on an emulator and a physical TV, against the current server.
4. Tag the exact tested commit and attach the APK and its SHA-256 to a GitHub Release, listing the
   tested server commit, the devices, and anything untested.

Replacing the key means users must uninstall first, so back it up. Debug and release builds use
different application IDs and install side by side.

## License and trademarks

No license has been chosen yet, so no rights beyond GitHub's terms are granted for now. Third-party
libraries (AndroidX, Media3, OkHttp, Coil, kotlinx) are downloaded at build time under their own licenses.

Notflix is an independent project and is not affiliated with Netflix, TMDB or Real-Debrid. It ships no media.
