Media Center
============

A lightweight, full-screen **10-foot media-center frontend** for an older
Windows 7 laptop connected to a TV.

It is **not a media player**. It provides sofa-friendly navigation over local
disks and SMB/UNC network shares, hands the selected file to VLC, waits for VLC
to exit, and then brings itself back with the previous selection focused.

```
Movies → choose title → Enter → watch → quit VLC → choose another title
```

* Target machine: **Windows 7 SP1 x64**, no Java installed
* Development: **macOS** or Linux
* Runtime: **Java 25 + JavaFX**, shipped as a self-contained `jlink` image


Quick start
-----------

```bash
./gradlew run          # run the media center
./gradlew test         # unit tests
./gradlew packageZip   # self-contained application image + ZIP
```

The build needs a **Liberica JDK 25 Full** distribution — the "Full" variant
bundles the JavaFX modules *and* the JavaFX jmods that `jlink` requires, so the
project itself declares no JavaFX dependency and the production image never
depends on anything downloaded at build time.

On first run open **Settings** → **Media folders** → **Add**, then either browse
for a folder or type a network path such as `\\synology\video\Movies` (a share
Windows has not mapped cannot be browsed to, so both ways are supported). VLC is
located automatically; if it is somewhere unusual, point Settings at `vlc.exe`.


Using it
--------

| Key                | Action           |
|--------------------|------------------|
| `← ↑ ↓ →`          | move selection   |
| `Enter`            | activate / play  |
| `Esc`, `Backspace` | back             |
| `Home`             | home screen      |
| `F5`               | refresh          |

Single click selects, double click plays. Nothing important is mouse-only, so
a cheap USB or Bluetooth remote that emulates a keyboard is enough. The pointer
hides itself after a few seconds of stillness and comes back on the first
movement.

It starts **full screen with no window decorations**, which is the default and
can be turned off in Settings. `Esc` is the Back key here, so it deliberately
does not drop out of full screen; leave with the **Exit** tile or `Alt+F4`. If
full screen ever misbehaves on the target machine there is an escape hatch that
does not need Settings:

```text
MediaCenter.exe --windowed
```


How it is put together
----------------------

```
JavaFX Media Center
        |
        +-- MediaScanner ........... local disks, UNC/SMB paths
        +-- ArtworkResolver ........ local poster/folder/cover images
        +-- PlaybackHistory ........ recently played
        +-- PlayerLauncher ......... external VLC process
        +-- PlatformServices ....... VLC discovery, desktop, data directory
```

```
src/main/java/
    module-info.java               module media.center
    mediacenter/                   Main, MediaCenterApp, Logging
    mediacenter/ui/                views, shell, tile grid, artwork cache
    mediacenter/ui/components/     tiles, grid, motion, activation gate
    mediacenter/media/             MediaRoot, MediaItem, MediaScanner, artwork
    mediacenter/playback/          PlayerLauncher, VlcPlayerLauncher, service
    mediacenter/platform/          Windows / macOS / Linux services
    mediacenter/config/            ApplicationSettings, SettingsStore, Theme
    mediacenter/history/           PlaybackHistory, HistoryStore
    mediacenter/json/              small JSON reader/writer
```

**Dark by default, light available.** A media center is normally used in a dim
room, the player it hands over to is fullscreen black, and posters read better
against a dark page — so dark is the default, as it is in Kodi, Plex and every
TV interface. Light suits a bright room. Switch it in Settings; the choice is
remembered.

The layout rules live in `mediacenter.css` and carry no colours of their own:
every colour is a `-mc-*` variable supplied by `theme-dark.css` or
`theme-light.css`, exactly one of which is applied alongside it. Focus is shown
as a tinted fill plus a coloured outline plus a small lift, because on a light
page an outline alone does not carry across a room.

Design rules the code follows:

* **The JavaFX thread never does I/O.** Scanning, artwork lookup, waiting for
  VLC and saving settings all run on virtual threads and marshal results back
  through `FxTasks`. A disconnected NAS cannot freeze or crash the UI.
* **VLC does the playing.** No libVLC, no decoding, no rendering. VLC is started
  with a `ProcessBuilder` argument list — never a composed shell string — so
  spaces, Unicode names and UNC paths need no quoting.
* **No dependencies at runtime.** The JDK and JavaFX cover everything; the
  handful of small JSON files are read and written by `mediacenter.json`.
* **Errors are readable from the sofa.** Stack traces go to
  `logs/application.log`, the screen gets one plain sentence.
* **Input is treated as remote input.** Activation ignores key auto-repeat, so
  a held Enter — or a remote whose button sticks — cannot relaunch a film the
  moment the media center returns from playback. See `ActivationGate`.
* **Motion is feedback, never decoration.** Focus lifts, pages slide the way
  you travelled, artwork fades in when it finishes decoding. Everything is
  under a quarter of a second and nothing delays input.
* **Only the modules actually used.** In particular `javafx.media` is *not*
  required — this application never decodes media. The linked runtime image
  contains 11 modules.


Where it keeps things
---------------------

| Platform | Directory                                        |
|----------|--------------------------------------------------|
| Windows  | `%APPDATA%\SimpleMediaCenter\`                   |
| macOS    | `~/Library/Application Support/SimpleMediaCenter/` |
| Linux    | `$XDG_CONFIG_HOME/SimpleMediaCenter/`            |

```
config.json      VLC path, browser, full screen, theme, media roots
history.json     recently played
logs/            application.log (rotating, 3 x 1 MiB)
```

`config.json`:

```json
{
  "vlcPath": "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
  "fullScreen": true,
  "theme": "DARK",
  "mediaRoots": [
    { "id": "…", "name": "Movies", "path": "\\\\synology\\video\\Movies", "type": "MOVIES" }
  ]
}
```

A corrupt file is moved aside as `config.json.corrupt` and the application
starts with defaults rather than refusing to run.

SMB credentials are **not** handled here: Windows owns share connectivity, and
nothing resembling a credential is ever logged.


Building the Windows artifact
-----------------------------

Runtime images are platform-specific, so the Windows artifact is built on
Windows — GitHub Actions does it on a pinned `windows-2022` runner:

```
push → GitHub Actions → windows-2022 + Liberica JDK 25 Full
     → test → jlink → jpackage → MediaCenter-windows-x64.zip
```

Download the `MediaCenter-windows-x64` artifact, copy it to the laptop, unzip,
and run `MediaCenter.exe`. No JDK, JRE, `JAVA_HOME`, PATH change or JavaFX
installation is needed on the target machine.

CI can prove that the Windows build links, packages and is structurally
self-contained. It **cannot** prove Windows 7 compatibility — the runners are
modern Windows Server. Smoke-test a new artifact by hand on the laptop:

```
launch → UNC share opens → browse → play → quit VLC → UI returns → desktop
```


Development notes
-----------------

`./gradlew run` and `./gradlew test` work on any JDK 25: when the selected JDK
does not bundle JavaFX, the build falls back to the OpenJFX artifacts from Maven
Central. That fallback is for development only — `jlink` needs real jmods:

```bash
./gradlew packageZip                                  # Liberica Full JDK
./gradlew packageZip -PjavafxJmods=/path/to/jmods     # any other JDK
```

To build and test against a different Java release (for example on a machine
that has no JDK 25 yet):

```bash
./gradlew test -PjavaLanguageVersion=21 -PjavafxVersion=21.0.12
```

The tests cover the logic that does not need a rendered scene: extension
recognition, display-name normalization, artwork resolution, settings and
history serialization, media-root configuration, error messages, and the whole
playback lifecycle through a fake `PlayerLauncher`. They never need a NAS or a
real VLC installation.


Not implemented on purpose
--------------------------

No online metadata or scraping, no Plex/Jellyfin/DLNA, no SMB client, no
transcoding, no subtitle downloading, no user accounts, and no exact
resume-position tracking — VLC handles its own resume. Series parsing, search
and favourites are possible later additions.

---

`Generate HS GK Report.tamper.js` in this repository is an unrelated userscript
that predates the media center.
