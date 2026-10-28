# AGENTS.md

Instructions for coding agents (Claude Code, Codex, Cursor and others) working on this repository: an
AirPlay screen-mirroring receiver for Android TV, built for one Philips set at home. Everything below was
found out the hard way on that TV; check it against the code before relying on it.

## What this is

- App `dev.okhotnikov.airplay`: an iPhone or Mac mirrors its screen to the TV through Control Centre ›
  Screen Mirroring. The TV shows up under its factory name (`Settings.Global "device_name"`, falling back
  to `Build.MODEL`), or a name set in the app's settings.
- The AirPlay protocol is [UxPlay](https://github.com/FDH2/UxPlay)'s core (`third_party/UxPlay/lib`), built
  with the NDK. Its GStreamer renderers are not used: `app/src/main/cpp/renderer.c` decodes into the
  platform decoders instead.
- Target TV: Philips 58PUS8507/60, board PH1M_EA_9970A (MediaTek MT5895), Android TV 11 (API 30),
  firmware RTT2.211108.001. Its userspace is 32-bit only, hence `armeabi-v7a` is the only ABI.
- Licence: UxPlay is GPL-3.0, so this app is too. There is no `LICENSE` file yet; add the GPL-3.0 text
  before publishing.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/cpp/airplay_jni.c` | JNI bridge: starts the mDNS responder and RAOP server, implements every `raop_callbacks_t` entry, posts events to Java |
| `app/src/main/cpp/renderer.c` | Video: `AMediaCodec` into the `ANativeWindow`, output on its own thread, SPS parser, keyframe replay buffer. Audio: AAC-ELD/AAC-LC through `AMediaCodec` into AAudio, volume applied in software |
| `app/src/main/cpp/hostname.h` | `airplaytv_gethostname`, swapped in for `gethostname` in `lib/mdnsd` (see below) |
| `app/src/main/cpp/CMakeLists.txt` | Builds libplist (C part), UxPlay `lib` + `playfair` + `llhttp` + `mdnsd`, links static `libcrypto` |
| `AirPlayService.java` | Foreground service that owns the server; opens `MainActivity` when a sender connects |
| `MainActivity.java` | The screen: letterboxed `SurfaceView`, or the idle screen; OK on the remote opens settings |
| `SettingsActivity.java` | Name in Screen Mirroring (with "back to factory name") and "Работать в фоне" |
| `Prefs.java` | Settings, the receiver's random but stable MAC-style identity, the mDNS host label |
| `BootReceiver.java` | Starts the service at boot when background mode is on |
| `UrlPlayer.java` | A sender's "play this URL" with ExoPlayer on its own SurfaceView; publishes position for the sender's polls |
| `app/src/main/cpp/alac_bridge.cpp` | C face for Apple's ALAC decoder (`third_party/alac`, Apache 2.0) |
| `patches/` | Local fixes to UxPlay, applied by CMake |
| `AuroraView.java` | Idle background: four soft colour blobs drifting and changing hue, ~30 fps, stopped while video plays |
| `scripts/build-openssl.sh` | Static OpenSSL `libcrypto` for `armeabi-v7a` into `third_party/openssl-android/` |
| `third_party/UxPlay`, `third_party/libplist`, `third_party/alac` | Git submodules: UxPlay `534e2cc`, libplist tag `2.6.0`, Apple ALAC |

## Build

Toolchain that is known to work: JDK 17 (Homebrew `openjdk@17`; Gradle 8.9 fails on newer JDKs), Android
SDK platform 34, NDK `27.2.12479018`, CMake `3.22.1`, AGP 8.5.2.

```bash
git submodule update --init
# OpenSSL source is not vendored: fetch it once, then build libcrypto (about 15 s on an M-series Mac)
curl -sSL https://github.com/openssl/openssl/releases/download/openssl-3.0.15/openssl-3.0.15.tar.gz \
  | tar xz -C third_party
scripts/build-openssl.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --no-daemon assembleRelease
```

The release APK is signed with the debug key on purpose (a home prototype, no keystore). After a build,
check the native library before installing: `llvm-nm -D --undefined-only` on `libairplaytv.so` should list
only system libraries (`libmediandk`, `libaaudio`, `libandroid`, `liblog`, libc); `getentropy` is weak.

## Installing on the TV

There is no `adb` binary on the dev Mac. The ADB client and the chunked uploader come from the
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) checkout next door:

```bash
TV_HOST=<tv ip> ../cleanyourtv/tools/venv/bin/python ../cleanyourtv/tools/b64push.py \
  app/build/outputs/apk/release/app-release.apk /data/local/tmp/airplay.apk
printf '%s\n' 'pm install -r /data/local/tmp/airplay.apk' 'rm /data/local/tmp/airplay.apk' \
  'appops set dev.okhotnikov.airplay SYSTEM_ALERT_WINDOW allow' \
  | TV_HOST=<tv ip> ../cleanyourtv/tools/venv/bin/python ../cleanyourtv/tools/tv.py --batch
```

Find the TV with `dns-sd -B _googlecast._tcp` and resolve it, rather than scanning the network. Check the
receiver from the Mac with `dns-sd -B _airplay._tcp` and `dns-sd -B _raop._tcp`.

## Facts that cost time to find

- **libplist on Bionic** needs `HAVE_STRNDUP` (Bionic has `strndup`; libplist's fallback is a static
  redefinition and fails to compile) and `PACKAGE_VERSION`. No `config.h` is needed.
- **mDNS host name.** `lib/mdnsd` builds its A record from `gethostname()`, which is `localhost` on
  Android; iPhones then cannot reach the receiver. `CMakeLists.txt` compiles `dnssd_mdnsd.c` with
  `-Dgethostname=airplaytv_gethostname`; do not patch UxPlay for it.
- **Multicast.** Without a `WifiManager.MulticastLock` the Wi-Fi driver drops the queries iPhones send, and
  the TV never appears in Screen Mirroring.
- **The picture size comes from the SPS, and only from it.** The MediaTek decoder resizes silently
  (adaptive playback) and does not reliably report `OUTPUT_FORMAT_CHANGED`; using both sources made the
  layout flip between padded and real sizes. `renderer.c` parses every keyframe's SPS (with cropping) and
  reports it every time, unchanged or not. The decoder's output format is logged only.
- **iOS restarts the session in the middle of a video**, so the service reopens the screen several times in
  a few minutes. The size can arrive before the new screen attaches: the service keeps the last size,
  `MainActivity` applies it in `onStart` and again on `VIDEO_ON`, and the service clears it on disconnect.
  Skipping any of these brings back the "stretches to full screen after a while" bug.
- **Late screens need the keyframe.** iOS sends a keyframe when mirroring starts and seldom after, and the
  stream can begin before the screen exists. `renderer.c` keeps every access unit since the last keyframe
  (up to 600 frames / 24 MB) and replays them into a new decoder.
- **Decoder (`OMX.MTK.VIDEO.DECODER.AVC`)**: features `adaptive-playback` and `tunneled-playback`, no
  `low-latency`. The app sets max size 1920×1920, `priority` 0 and `operating-rate` 120, and still sends
  `low-latency` for decoders that have it. Output runs on its own thread so a frame shows without waiting
  for the next input.
- **Sender settings** (`raop_set_plist`): 1920×1080, refresh 60, `maxFPS` 60. Feature bits follow UxPlay's
  defaults for mirroring with audio; HLS (bits 0 and 4) and H.265 (bit 42) are off.
- **Audio**: mirroring audio is AAC-ELD (`ct=8`, `csd-0 = f8 e8 50 00`, 44.1 kHz stereo); AAC-LC is
  `ct=4`, `csd-0 = 12 10`. ALAC (`ct=2`, music-only AirPlay) has no Android decoder, so it is silent.
- **Opening the screen from the background** needs the `SYSTEM_ALERT_WINDOW` app-op. TV Settings has no
  screen for it; grant it over ADB (see Installing). Without it the service logs the connection and the
  screen stays closed.
- **Foreground service type** is `connectedDevice` (with `FOREGROUND_SERVICE_CONNECTED_DEVICE`); its runtime
  prerequisite is met by `CHANGE_WIFI_MULTICAST_STATE`.
- **Idle cost** of the service: about 25 MB PSS and no measurable CPU.
- **Standby on this Philips** only turns the panel off (`dumpsys power`: `mWakefulness=Asleep`): Android,
  Wi-Fi, ADB and the foreground service keep running, and the TV still answers mDNS. So a sender finds a
  sleeping TV, and `AirPlayService` wakes it on connect (`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, plus
  `setTurnScreenOn` on the screen) when "Будить телевизор при подключении" is on (the default). The firmware
  opens Live TV (`org.droidtv.playtv`) at every power-on, so the service brings its screen back 3 s and 7 s
  later if it is not in front. An app cannot put the TV back to sleep (`goToSleep` needs `DEVICE_POWER`).
- **After an update** the service is killed and `START_STICKY` does not bring it back; `BootReceiver` also
  listens for `MY_PACKAGE_REPLACED`.
- **Video by URL** (a player's AirPlay button). Without feature bits 0 and 4, iOS treats the TV as a speaker
  and sends the video's sound only, as ALAC (`ct=2`), which is why Apple's ALAC decoder is built in. With
  them, UxPlay hands over either a local HLS proxy URL (`http://localhost:<port>/….m3u8`, YouTube) or the
  player's own http(s) URL (VK Video: `https://…okcdn.ru/…/video.m3u8`). The platform `MediaPlayer` failed on
  VK twice: `-1004` until the request carried an AppleCoreMedia user agent, then `-1007` (its HLS cannot read
  fMP4 segments). ExoPlayer (Media3 1.4.1; newer needs compileSdk 35+) plays it; cleartext is allowed for the
  localhost proxy.
- **Switching videos in VK Video** goes through the queue, not `/play`: `POST /action playlistRemove` (the
  current video) then `POST /action playlistInsert` (the next one, with its `Content-Location`), and iOS waits
  for the receiver to start it. Upstream UxPlay answers 400 to any `/action` once `playlistRemove` has cleared
  the current video, and does not implement `playlistInsert` (`FIXME`), so iOS gave up and sent sound only.
  `patches/uxplay-0001-playlist-insert.patch` lets `/action` run with no current video, answers 200 to what
  needs one, and plays an inserted plain http(s) item as `/play` does. Other items (YouTube's HLS) are logged
  with their keys only.
- **UxPlay patches** live in `patches/uxplay-*.patch`; `CMakeLists.txt` applies each one to the submodule once
  at configure time and fails the build if one does not apply. `.gitmodules` sets `ignore = dirty` for it.
  On the dev Mac the rtk hook rewrites `git diff` into a summary: make patches with `rtk proxy git diff`.
- **Network on the target TV**: 5 GHz, about −54 dBm, 780 Mbit/s link; the built-in Ethernet port is
  unused. The network is not the bottleneck; a cable still removes one wireless hop.
- **`logcat` on this TV holds about 3000 lines**: receiver logs rotate out within minutes. Pull them
  straight after reproducing a problem (`logcat -d | grep -E "AirPlayTV|UxPlay"`).
- **String resources**: XML collapses runs of spaces, so layout spacing inside a string does not survive;
  use `\n` or separate views.

## Decisions already made by the owner

- Idle screen: the four drifting blobs in `AuroraView` stay. An Ambilight frame round the screen edge and a
  "gooey" liquid effect were both tried and rejected; do not bring them back without being asked.
- The receiver name defaults to the factory name, not a family name.
- Background mode is on by default; turning it off limits AirPlay to while the app is open.
- No PIN or password: anyone on the home Wi-Fi may mirror.

## Known gaps

- No A/V sync. Video by URL depends on UxPlay's HLS support, which lacks `playlistInsert` (queueing the next
  video).
- H.265 needs an HEVC SPS parser in `renderer.c` before bit 42 can be turned on.
- Latency has not been measured. Measure before and after any change that claims to improve it (a camera
  filming a stopwatch on the iPhone next to the TV is enough).
- No Wi-Fi P2P (AWDL): Android cannot do it, so mirroring always goes through the router.

## Working on this repo

- Comment only the non-obvious why, in one or two lines; match the code around you.
- Never commit IP addresses, Wi-Fi names, device identifiers or the TV's ADB key.
- Commit titles: `area: what changed` (`renderer: …`, `service: …`, `docs: …`).
