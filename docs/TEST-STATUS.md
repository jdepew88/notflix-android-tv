# Test status

The results below were recorded when each version was built. They predate this repository's split
from the original project, and addresses and machine details were replaced with examples.

## 0.5.2 release build, 2026-10-30

APK built from a fresh public clone at `1f92bec`, on the Android TV 14 (API 34) emulator, against a
fresh public clone of the server at `9f18efe`. Placeholder TMDB and Real-Debrid keys.

| Check | Result |
|---|---|
| Unit tests / lint | 68 passed / no errors |
| Instrumented discovery, controlled announcement (debug build, real Android NSD) | Passed |
| Instrumented discovery of the real server through the relay, name and port checked | Passed |
| Wizard lists the real server (release APK, relay) | Passed |
| Manual entry `10.0.2.2:8601` | Verified, sign-in opened |
| Sign-in, session kept after relaunch, *Who's watching?*, profile opens | Passed |
| Catalog | **Not tested.** The placeholder TMDB key gives "TMDB error: Unauthorized", shown cleanly |
| Playback | **Not tested in this build** |
| Physical TV, real multicast between devices | **Not tested** |

The relay is `scripts/mdns-relay.mjs`; its header explains how to run it.

## Phase 5.1 checkpoint

| | |
|---|---|
| APK | `app\build\outputs\apk\debug\app-debug.apk` (17,946,366 bytes) |
| Version | `0.5.1-debug`, versionCode 2 (Phase 5: `0.5.0-debug`, versionCode 1). Same application ID (`com.notflix.tv`, `.debug` for debug builds) and the same local debug key, so it installs over Phase 5 as an upgrade. |
| Phase 5 APK | The Phase 5 APK was only read, never rewritten. |

Tested on the Android TV 14 (API 34) emulator; **not tested on physical TV hardware**:

| Scenario | Result |
|---|---|
| Fresh install, no server | Wizard opens and searches; "No Notflix server found" after the search window. |
| Controlled advertisement (instrumented test, real NSD) | Found, verified with name, instance ID and API version; impostor rejected. |
| Controlled advertisement → UI | Wizard listed **NOTFLIX — Living Room …** as *Available*, focus moved to it; OK connected; sign-in, profiles and home loaded through it. |
| Manual: invalid IPv4, HTTPS DNS host that isn't Notflix, unreachable address | Clear errors, field stays editable, retry without restarting. |
| Manual: closed port / non-Notflix service on the host | Shown as a timeout on the emulator (its NAT doesn't pass "refused"); "connection refused" is covered by the JVM test. |
| Manual connection to `http://192.168.1.20:4119` (the existing server) | Verified and selected; the sign-in screen loaded from it. Only unauthenticated reads were made (health, `/api/auth/me`, sign-in backdrops); no sign-in, nothing changed on that server. |
| Upgrade Phase 5 → 5.1 with a saved server and sign-in | Went straight to *Who's watching?*; session kept and bound to its server. |
| Settings → Search for Servers → Back | Cancelled; same server, still signed in. |
| Switch to another server | New sign-in required; stored cookies for the old server: 0. |
| Kids profile | Kids catalog shown. |
| Back on the first-run wizard | Leaves the app. |
| Playback | **Not completed on the emulator in this session.** Against the test copy server (which was answering slowly: 3-7 s per health check) both 5.1 and the original Phase 5 APK stayed on "Finding the best source…" for over two minutes, while the same request from the PC answered in 20 s. Because the Phase 5 build behaves identically and the playback code is unchanged, this looks environmental, but playback on 5.1 still needs a check on the TV. |

Known limitations:

* Automatic discovery of a real Notflix server needs the server-side advertisement described in
  `docs/server-discovery.md`; until then only manual connection works against real servers.
  End-to-end discovery against a real Notflix server has **not** been verified.
* Today's server reports no name or instance ID, so cards and Settings show the DNS-SD name or
  "Notflix server", and a replaced server at the same address can't be detected.
* The pre-Android-14 resolve path (`resolveService` queue) compiles and is unit-tested only through
  the shared discovery logic; it wasn't run on an older Android version.
* `.local` host names in manual entry depend on the TV's resolver; some TVs don't resolve mDNS
  names outside NSD. The IP address always works.
* Link-local IPv6 addresses (`fe80::…%iface`) can't be used: URLs can't carry the interface.
* Newer Android versions are adding local-network access protections; raising targetSdk later may
  require requesting that permission. This build targets API 36 like Phase 5.

---

## Phase 5.2: automatic discovery of the Windows server

The Windows server (Notflix Debrid) now announces itself with mDNS/DNS-SD while its LAN access is
on: service `_notflix._tcp`, its friendly name, the port it actually listens on (8420 by default)
and its address on the TV's network. The TV app's discovery and wizard are unchanged; it was
already browsing `_notflix._tcp` and using the SRV port. Server side:
`docs/LAN-DISCOVERY.md` in the server repository.

App changes in 5.2 (small):

* Manual entry: a bare address tries port **8420** (current servers), then **4119** (older ones),
  one `GET /api/health` each without cookies or redirects; an explicit port or URL is the only
  candidate. The failure message names every address tried.
* Texts that said "Notflix normally uses 4119" now say 8420 (older versions 4119); the
  troubleshooting list mentions the server's *Let TVs find this server automatically* setting and
  UDP 5353.
* A Phase 5 session still upgrades only for `http://<its host>:4119` (unchanged behaviour; the
  legacy port is now explicit rather than the default).
* `NsdDiscoveryTest`: a LAN relay mode for testing against a real server from the emulator.
* `versionCode` 3, `versionName` 0.5.2. Same application ID and debug key: installs over 5.1 and 5.

## Phase 5.2 checkpoint

| | |
|---|---|
| APK | `app\build\outputs\apk\debug\app-debug.apk` |
| Server | Notflix Debrid at the mDNS commit `0af59a3`, now part of `main` |

Tested on this PC and the Android TV 14 (API 34) emulator; **not tested on physical TV hardware**.
The servers were throwaway instances of the 5.2 server code on ports 8520–8540 (their own data
folders; TMDB/Real-Debrid keys borrowed through `prepare-test-instance.mjs --credentials-from`,
never printed). No existing installation was changed or restarted.

The emulator's NAT doesn't carry LAN multicast, so the emulator reached the real announcement
through a small test relay (a script on the PC plus `NsdDiscoveryTest#holdLanRelay`): Android's own
NSD queries were sent from the PC's LAN address, the Windows server's answers were multicast back
inside the emulator, and Android's NSD resolved them. The HTTP traffic went directly to
`192.168.1.20`. (Separately, the emulator's NAT also forwards its multicast queries to the PC as
ordinary UDP; the server answered those as legacy unicast queries with the address of the PC's
default multicast adapter, which the emulator could also reach.)

| Scenario | Result |
|---|---|
| Server announces with the current port | `notflix.ps1 status`: announced, answers a live test query with the right port. Windows' own DNS-SD API (`DnsServiceBrowse`/`DnsServiceResolve`) found and resolved it: SRV port 8520, TXT `app=notflix api=1 scheme=http id=…`. |
| Android NSD discovery (instrumented test through the relay) | Found `Notflix Test 52` at `http://192.168.1.20:8520`, verified with name, instance ID and API 1. |
| Fresh install → wizard → select → sign in → profile → home → play | Wizard listed **NOTFLIX — Notflix Test 52 · 192.168.1.20:8520 · HTTP · API 1 · Available**; sign-in, *Who's watching?*, home (real catalog) and playback (*Bad Apples*, video transcode, 1:03 played) worked. |
| No credentials in the announcement | Raw mDNS answers and `/api/health` searched for the TMDB key, Real-Debrid token, session and engine secrets, account names, password hashes, data folder and engine port: none present. TXT keys: `app, api, scheme, id`. |
| Stop | Goodbye packets (TTL 0) reached the emulator; nothing answers afterwards; no Notflix process or status file left. |
| Restart / second `start` while running | Same name and instance ID announced again; "already running", still one web server process and one announcement. |
| Port changed while running (8520 → 8530) | Goodbye for 8520, announcement on 8530 within a second; old port closed. The TV's saved 8520 failed with *Can't connect*; *Change Server* found 8530. Moving to a port in use left the server and its announcement on the old port. |
| Two servers, same name | The second announced itself as `Notflix Test 52 (2)` and reports that name in `/api/health`; the TV listed both with their own ports (8530, 8540). |
| Discovery unavailable (announcement off) | *No Notflix server found*; manual `192.168.1.20:8530` connected, sign-in and profiles worked. |
| Manual bare address | `192.168.1.20` shows *will try http://192.168.1.20:8420, then http://192.168.1.20:4119*; the JVM tests cover connecting through the second candidate. |
| Automated tests | Android: 68 JVM tests, `connectedDebugAndroidTest` (4, two of them manual helpers) and `lintDebug` (no errors). Server: 86 web unit tests (16 new: responder, probing/renaming, goodbyes, port changes, subnet filtering, legacy unicast, failures, settings, instance ID), TypeScript, lint of the changed files (the 12 lint errors on `main` are unchanged), 130 media engine tests (engine unchanged). |

Known limitations:

* **Not tested on a physical TV or across a real Wi-Fi/Ethernet LAN to another device.** The
  emulator path used the relay described above. Whether the home network passes multicast between
  the TV and the PC (and whether Windows Firewall lets Node.js receive UDP 5353 on that network
  profile) can only be confirmed on the TV.
* After the server's port or address changes, the TV asks to sign in again (the session cookie
  stays bound to the old origin by design).
* IPv4 only (the server listens on IPv4).
