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
depends on anything downloaded at build time. See [Installing a Full JDK
locally](#installing-a-full-jdk-locally) for how to get one — `run` and `test`
work on any JDK 25, only the packaging tasks need the Full variant.

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


Building the application images
-------------------------------

A `jlink` runtime image only runs on the platform it was linked for, so there is
no cross-building: each platform is built on its own runner with its own
Liberica JDK 25 Full.

| Runner            | Artifact                           | Runs on                |
|-------------------|------------------------------------|------------------------|
| `windows-2022`    | `Aurora-MediaCenter-windows-x64`   | Windows x64            |
| `macos-15`        | `Aurora-MediaCenter-macos-aarch64` | Apple Silicon Macs     |
| `macos-15-intel`  | `Aurora-MediaCenter-macos-x64`     | Intel Macs             |
| `ubuntu-22.04`    | `Aurora-MediaCenter-linux-x64`     | Linux x64              |

Both macOS architectures are built because an arm64 image cannot run on an Intel
Mac at all — Rosetta translates x86_64 to arm64, not the other way round.

Two workflows, because the images are expensive and nothing in CI consumes
them:

| Workflow | Runs on | Does |
|---|---|---|
| `ci.yml` | pull requests, pushes to `master` | compile, test and `jlink` on all three platforms |
| `release.yml` | a `v*` tag, or run by hand | full validation, then `jpackage` + zip attached to a release |

CI stops at `jlink` on purpose: linking is what catches a broken module graph or
a JDK without the JavaFX jmods, and it costs seconds.

### Cutting a release

Tag a commit as a release candidate and let the pipeline decide whether it
becomes a release:

```bash
git tag v1.2.3-rc1 && git push origin v1.2.3-rc1
```

```
v1.2.3-rc1
    ↓  full test suite + application image on every supported platform
    ↓  each image checked: bundled runtime executes, JavaFX natives present
    ↓  all three pass
tag the same commit v1.2.3
    ↓
GitHub release v1.2.3, notes generated from the commits and merged PRs
    ↓
Aurora-MediaCenter-{windows-x64,macos-aarch64,macos-x64,linux-x64}.zip attached
```

If any platform fails, nothing is promoted and no release appears — fix it and
tag `-rc2`. The images attached to the release are the ones that were
validated, not a rebuild of them.

Tagging `v1.2.3` directly skips the candidate step and does the same thing in
one go.

Running the workflow by hand builds the images and leaves them as workflow
artifacts **without creating a release** — that is the way to get a build onto
the laptop without cutting one. GitHub lets a manual run pick a tag rather than
a branch, so publishing from one is possible, but it has to be asked for by
ticking `publish`; fetching a build for testing cannot create a release by
accident.

One thing worth knowing before editing `release.yml`: the promoted `v1.2.3` tag
is created with `GITHUB_TOKEN`, and GitHub deliberately does not start a
workflow for such a tag. That is why validation and publication live in the same
run rather than the release tag triggering a second one.

Locally, `./gradlew packageZip` always builds for the machine you are on and
names the archive after it.

Download `Aurora-MediaCenter-windows-x64.zip` from the release, copy it to the
laptop, unzip, and run `MediaCenter.exe`. No JDK, JRE, `JAVA_HOME`, PATH change
or JavaFX installation is needed on the target machine.

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

### Installing a Full JDK locally

`jlink` and `jpackage` fail with **No JavaFX jmods found** when the selected
JDK is a plain one. The standard Liberica build ships no `javafx.*.jmod` — only
the "Full" variant does, and on SDKMAN those carry `.fx` in the identifier:

```bash
sdk install java 25.0.4.fx-librca
ls "$(sdk home java 25.0.4.fx-librca)/jmods" | grep javafx   # expect 7 files
```

Gradle detects every SDKMAN candidate and picks the highest matching Java 25,
so installing it is normally all it takes. The one thing that overrides that is
`JAVA_HOME`: whenever it points at a JDK that satisfies the toolchain, Gradle
uses it and ignores the rest. A shell left on a plain JDK 25 by `sdk use` keeps
failing, so either switch the shell or name the jmods directory explicitly:

```bash
sdk use java 25.0.4.fx-librca                                       # this shell
./gradlew packageZip -PjavafxJmods="$(sdk home java 25.0.4.fx-librca)/jmods"
```

The override is a plain directory of jmods; it does not have to come from the
JDK doing the linking, so a Gluon JavaFX jmods download works the same way.

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
