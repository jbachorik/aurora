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
| `W`                | toggle watched (on a folder: clear the marks below it) |

A video that has been played carries a **watched mark**: a check instead of the
play symbol and a dimmed title, so what is still unseen stands out. Playing a
file marks it automatically; `W` on a video line flips the mark by hand, and
`W` on a folder line clears every mark inside that folder and all of its
subfolders — handy before a rewatch of a whole series.

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

The **Sleep** tile suspends the computer and takes effect at once — there is
no confirmation, because waking it again is one keypress. The media center is
still running, and still on the home screen, when the machine comes back.
macOS sleeps through `pmset`, Linux through `systemctl suspend` (falling back
to `loginctl suspend` where there is no systemd), and Windows through
`powrprof`. One Windows caveat is outside the media center's control: where
hibernation is enabled, Windows hibernates instead of sleeping.


Slow network shares
-------------------

Before a video plays, the media center **measures how fast its file can
actually be read** — a bounded probe of a couple of seconds at most, invisible
on a healthy link. The measured rate is compared against the file's own
bitrate (its size over the duration read from the MP4 or Matroska header), and
what happens next depends on the verdict:

* **Fast enough** — the file plays from where it is, as always.
* **Too slow** — the file is copied into a local **network media cache**, and
  playback starts as soon as the head start is large enough that the player
  can never catch up with the copy. The maths covers the **next episode in the
  queue too**, so an evening of episodes survives a share that cannot quite
  keep up. Progress shows as a "Buffering from the network…" banner.
* **Far too slow** — when building that head start would take more than a
  couple of minutes, the film starts immediately over the share, stutters and
  all, with a banner saying so — and the cache quietly takes a full copy in
  the background, so the *next* viewing plays from the local disk.

Independently of the pre-play buffering, a network file that has been played
**twice or more** earns a permanent local copy — so the titles a household
keeps returning to stop depending on the network (or its restrictions)
altogether. Whenever a fresh local copy exists, it is preferred automatically;
a copy is dropped the moment the original changes on the share, and the
least-recently-used copies are evicted when space runs short.

The cache is the **Network media cache** row in Settings: Off, 5, 10 (the
default) or 25 GB. Off stops all copying; existing copies are still served
until the space is reclaimed. Copies live under the application data directory
(see [Where it keeps things](#where-it-keeps-things)) and one copy runs at a
time, so buffering never competes with itself for the bandwidth that was
already scarce.


Website tiles
-------------

Websites can sit on the home screen as first-class tiles — for catalogues that
live on the web rather than on a disk. The worked example is
[Mosfilm](https://cinema.mosfilm.ru), whose entire back catalogue is streamed
free and officially by the studio itself: add a tile named `Mosfilm` with the
address `cinema.mosfilm.ru` (Settings → **Websites** → **Add**; `https://` is
assumed) and it appears in a **Websites** row under the home actions.

Activating a tile behaves like playback: the media center steps out of the
way, the configured browser opens the site **full screen in app mode**, and
when the browser is closed the media center returns. Three things make the
page sofa-friendly:

* **Browser scale** (Settings, default 150%) asks the browser to draw
  everything larger — a device-scale hint, so a desktop page reads from
  across the room.
* The site opens in a **profile of the media center's own**, so the
  television keeps its own logins and cookies, apart from anyone's desktop
  browsing — and so the launch is never swallowed by a browser instance that
  is already running, which would ignore the kiosk flags.
* App mode drops the tabs and the address bar; the page is all there is.

The full treatment needs a Chromium-family browser (Chrome, Chromium, Edge,
Brave, Vivaldi, Opera) configured in Settings. Firefox is opened in its kiosk
mode without the scale hint; any other browser just gets the address. Sites
that require DRM (the big subscription services) work precisely *because*
this is a real browser and not an embedded engine.

**Full screen on a site:** `F` puts the player on the whole screen. The
site's own fullscreen button often cannot: the kiosk hands the page a window
that already fills the screen, which fools any player that reads its
fullscreen state off the window instead of off `document.fullscreenElement` —
the VK player Mosfilm embeds is one of them, and its button then does nothing
at all. `F` asks for fullscreen directly, going around the site's own idea of
the matter. Press it again to come back. It comes from the same bundled
extension as `Ctrl+Q`, so it shares that extension's limits below.

**Leaving a site:** `Ctrl+Q` closes the browser and the media center returns
— the same key that quits VLC, provided by a tiny bundled extension the
kiosk launch loads into its own profile. `Ctrl+W` (close window) works
everywhere as the fallback, including branded Google Chrome, which has
stopped honouring `--load-extension`, and Firefox, which does not sideload
extensions at all.


Remote control
--------------

The home screen shows a **QR code** in its bottom-right corner. Scanning it
with a phone on the same network opens a small web page with two operations:
paste an address and **open it on the TV** in the same kiosk browser the
website tiles use, or **stop** whatever is showing and return to the main
menu. Opening a second address simply replaces the first.

Underneath sits a small REST API, so the remote can grow without the page:

```
GET  /api/status          what the kiosk browser is showing
POST /api/open            {"url": "https://…"} — open it full screen
POST /api/stop            close the browser, back to the menu
```

The server listens on port **8765** and needs the same browser configured in
Settings as the website tiles. There is deliberately no authentication — it
is reachable only from the home network, and the worst a caller can do is
open a web page on the television. If the QR code does not appear, the
machine had no LAN address to advertise (or the port was taken); the log
says which.


Photographs
-----------

A folder that contains photographs — directly, or in any folder beneath it —
gets a **Slideshow** tile first in its grid. Activating it walks every
photograph under that folder, depth first in folder order, showing each one
full screen and advancing on its own; once the walk has reached the end it
loops back to the first.

The walk does not follow symbolic links to directories — a link pointing back
up the tree would let it go on for ever — so a folder whose only photographs
live under a symlinked subdirectory gets no Slideshow tile, even though the
grid still lets you browse into that subfolder and see them there.

`Enter` on a single photograph opens it the same way, except the arrows move
only through the photographs in *that* folder, there is no auto-advance, and
reaching either end simply stops rather than wrapping around.

| Key                 | In the viewer                          |
|---------------------|-----------------------------------------|
| `← →`               | previous / next photograph             |
| `Enter`, `Space`    | toggle auto-advance (slideshows only)  |
| `Esc`, `Backspace`  | back to the grid                       |

A manual `←`/`→` resets the interval rather than cancelling it, so pausing to
look at one picture does not make the next automatic advance arrive early. The
caption shows the file name and a counter: `2 of 380+` means there are at
least that many, either because a slideshow is still walking the folder tree
in the background or because the walk stopped short of the whole library;
`2 of 380` — no `+` — means the walk finished and that really is all of them.

The interval is a **Slideshow** row in Settings offering 5s and 10s; a
hand-edited `config.json` can hold anything, but the stored value is clamped to
2–60 seconds rather than rejected outright.

Supported formats are JPEG, PNG, GIF and BMP — the ones JavaFX itself can
decode. **HEIC is not shown at all.** That is what a recent iPhone produces by
default, and JavaFX cannot decode it, so a HEIC file is invisible rather than
shown as a broken frame — worth knowing before "my photos are missing" turns
into a support question. EXIF orientation is honoured, so a portrait
photograph taken on a phone displays the right way up rather than sideways.

A film's artwork — poster, folder and cover images, and any per-film sidecar —
is never listed as a photograph, wherever it sits in the folder tree.


Series and movie identification (optional)
------------------------------------------

Folders on a **TV** or **Movies** root can be identified online as they are
browsed, so a shelf of ripper-named folders gains real posters and synopses
without anyone renaming anything. It is **off by default** — scraping sends
folder names to services on the internet — and lives behind a **Series
scraper** row in Settings. Which pipeline a folder goes down is decided by
the root it sits under: the root's type has already said out loud what its
folders hold, which is the same declaration episode chaining trusts.

Three steps, each falling back gracefully to the next:

1. **What is on disk.** A TV folder is read into evidence: episodes per
   season (from `Season 1`-style folders or from `S01E01` tags) and a few
   episode file names; a folder with no episode structure is not a series. A
   Movies folder must be the one-folder-per-film shape — exactly one video
   (a ripper's `sample.mkv` is ignored), no season folders, no episode tag —
   and yields the folder name, the file name, and the release year when
   either carries one ("(2017)", or the trailing year of a dotted name;
   never a leading one, so "2001 - A Space Odyssey" keeps its title).
   Anything that qualifies as neither is never sent anywhere.
2. **What it is called.** An Ollama model reads those names into a clean
   title — `Breaking.Bad.S01-S05.COMPLETE.1080p.x265` becomes "Breaking
   Bad", `BR2049.2160p.HDR.x265-GRP` becomes "Blade Runner 2049". The
   endpoint is Ollama's hosted service by default (its free tier needs the
   API key), or point it at an Ollama in the house (`http://localhost:11434`,
   no key). With neither, the cleaned folder name is the search term.
3. **What TheTVDB knows.** The title is searched on TheTVDB (v4 API key
   required — the one thing there is no scraping without; the same key
   covers series and films) and every leading candidate is cross-checked
   against what the disk showed. A series is checked by **shape**: a season
   holding more episodes than the candidate ever aired rules it out, which
   is how the American *The Office* is told from the British one. A film has
   no shape, so it is checked by **year**, which is what tells *Dune* (2021)
   from *Dune* (1984) — and a much-remade title with no year anywhere is
   left unidentified on purpose. Where VLC is configured, a film gets one
   more witness: libVLC — the same library the built-in player binds — reads
   the file's **running time** without playing a frame, and it is weighed
   against each candidate's official runtime. Leniently, because cuts and
   credits move runtimes honestly: close enough counts for a candidate,
   twice the length counts against it, and the extended-edition middle
   ground says nothing — it separates two candidates the names cannot,
   never overrules an exact title. No VLC, no opinion, everything else
   still works. Either way, a match that is not clearly ahead of the
   runner-up is discarded — a wrong poster is worse than none.

What was learned is written into the title's folder itself: a hand-editable
`aurora-series.json` or `aurora-movie.json` (title, year, overview, status,
TheTVDB id) and a `poster.jpg` — but never over artwork that is already
there. That file is the whole database: the metadata travels with the folder,
every machine that can see the share sees it, and deleting the file is how
you ask for a re-scrape. Scrapes run one at a time in the background; a
folder that found no confident match is retried on the next start, when the
missing season — or the year a rename adds — may have arrived.

Films that sit as **loose video files** directly on a Movies shelf get
folders of their own first: `Movies/Heat.1995.mkv` becomes
`Movies/Heat.1995/Heat.1995.mkv` — the folder named exactly after the file,
no cleverness — with its subtitles and sidecar artwork moved in alongside,
and the new folder queued for identification right away. Watched marks and
the recently-played list follow the moved file, so tidying never un-watches
anything. It is a pure rename on the same volume, and the caution is broader
still than the scraper's: a file with an ordering prefix or episode tag is
part of a run and never folded away, a folder *named for* one of its videos
is already that film's home — extras beside it and all — and is left whole,
and samples, collisions and any doubt leave a file exactly where it was.

### Setting up Ollama

Ollama is optional — without it, folder names are searched on TheTVDB as they
are, which is fine for tidy libraries and hopeless for
`BrBa.COMPLETE.720p.x264-GRP`. There are two ways to have it, and the settings
dialog (**Settings → Series scraper → Configure…**) takes either:

**The hosted service (the default endpoint).** Nothing to install; a title
guess is one tiny request, so the free tier's limits are far more than this
feature ever uses.

1. Create an account at [ollama.com](https://ollama.com).
2. Create an API key at [ollama.com/settings/keys](https://ollama.com/settings/keys).
3. Paste it into **Ollama API key**. Leave the endpoint
   (`https://ollama.com`) and the model (`gpt-oss:20b`) as they are — any
   other model the hosted service offers works too.

Without a key the hosted endpoint turns requests away, and the media center
knows it: it skips the call entirely rather than waiting out a timeout per
folder, and scrapes with the folder name alone.

**An Ollama in the house (no account, no key).** The
[open-source Ollama](https://ollama.com/download) running on any machine that
is on when the media center is — a desktop, the NAS if it has the memory. A
small model is entirely enough for reading folder names:

```bash
ollama pull llama3.2     # ~2 GB; qwen3:4b works too
```

Then set **Ollama endpoint** to where it listens — `http://localhost:11434`
on the same machine, `http://desktop:11434` or `http://192.168.1.20:11434`
across the LAN — put that model's name into **Ollama model**, and leave the
API key empty. One caveat for the across-the-LAN case: Ollama binds to
localhost unless told otherwise, so on the machine running it set
`OLLAMA_HOST=0.0.0.0` (an environment variable) before starting it.

Either way, a quick check that the endpoint answers, from any machine that
can reach it:

```bash
curl http://desktop:11434/api/tags        # local: lists the pulled models
```

Nothing else is needed — the media center speaks Ollama's standard chat API,
which is the same for both.


How it is put together
----------------------

```
JavaFX Media Center
        |
        +-- MediaScanner ........... local disks, UNC/SMB paths
        +-- ArtworkResolver ........ local poster/folder/cover images
        +-- ScrapeService .......... Ollama title guess, TheTVDB metadata
        +-- PlaybackHistory ........ recently played
        +-- PlayerLauncher ......... external VLC process
        +-- PlatformServices ....... VLC discovery, sleep, data directory
```

```
src/main/java/
    module-info.java               module media.center
    mediacenter/                   Main, MediaCenterApp, Logging
    mediacenter/ui/                views, shell, tile grid, artwork cache
    mediacenter/ui/components/     tiles, grid, motion, activation gate
    mediacenter/media/             MediaRoot, MediaItem, MediaScanner, artwork
    mediacenter/scrape/            series+movie evidence, Ollama, TheTVDB, matchers
    mediacenter/playback/          PlayerLauncher, VlcPlayerLauncher, service
    mediacenter/platform/          Windows / macOS / Linux services
    mediacenter/remote/            remote-control HTTP server, QR encoder
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
* **VLC does the playing.** By default VLC is started as its own process, with
  a `ProcessBuilder` argument list — never a composed shell string — so spaces,
  Unicode names and UNC paths need no quoting. Settings can instead switch on
  the **built-in player**, which binds the same install's libVLC through
  `java.lang.foreign` (no JNA, no extra modules) and draws each decoded frame
  into the page itself — same codecs, same subtitles, but the queue, the
  overlay and per-episode history belong to the application. It answers to
  `Ctrl+Q` as well as `Esc`, so leaving whatever has taken the screen — VLC's
  own window, the kiosk browser, or this page — is one key everywhere.
  Photographs are the one thing played by neither: a still image is not
  playback, and the application decodes and draws them itself.
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
  contains 12 modules (`java.net.http`, the series scraper's client side,
  being the latest addition).


Where it keeps things
---------------------

| Platform | Directory                                        |
|----------|--------------------------------------------------|
| Windows  | `%APPDATA%\SimpleMediaCenter\`                   |
| macOS    | `~/Library/Application Support/SimpleMediaCenter/` |
| Linux    | `$XDG_CONFIG_HOME/SimpleMediaCenter/`            |

```
config.json      VLC path, browser, full screen, theme, media roots, website tiles
history.json     recently played
media-cache/     local copies of network media, and the index that tracks them
logs/            application.log (rotating, 3 x 1 MiB)
```

`config.json`:

```json
{
  "vlcPath": "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
  "fullScreen": true,
  "theme": "DARK",
  "slideshowSeconds": 5,
  "browserScalePercent": 150,
  "mediaRoots": [
    { "id": "…", "name": "Movies", "path": "\\\\synology\\video\\Movies", "type": "MOVIES" }
  ],
  "websites": [
    { "id": "…", "name": "Mosfilm", "url": "https://cinema.mosfilm.ru" }
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

### The icon

Two different icons, for two different things. The taskbar button of a *running*
window comes from the application itself, which loads the PNGs under
`src/main/resources/mediacenter/ui/icon/` — several sizes, because handing a
window manager only the large one leaves it to squeeze 256 pixels into 16. The
installed application, its shortcut and its `.exe` take the icon `jpackage`
embeds, and every desktop insists on its own container:

| Platform | File                      |
|----------|---------------------------|
| Windows  | `packaging/MediaCenter.ico`  |
| macOS    | `packaging/MediaCenter.icns` |
| Linux    | `packaging/MediaCenter.png`  |

All of them are generated from `packaging/icon-source.png`. On a Mac, `sips`
resizes and `iconutil -c icns` builds the macOS container; the `.ico` is a
handful of PNGs in an icon directory, which Windows has read since Vista.

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
the laptop without cutting one. Each artifact is named for its platform and the
time the run started, `Aurora-MediaCenter-windows-x64-20260823-1530`, so two
downloads of the same commit can be told apart in a downloads folder.

GitHub wraps every artifact in a zip of its own. Windows therefore uploads the
image unwrapped, and its download is a single zip holding `MediaCenter/`. macOS
and Linux cannot do the same: the uploader drops the executable bit and follows
the symbolic links inside a `.app` bundle, so those two upload the zip the build
made and their download is a zip inside a zip. Release assets are always the
plain per-platform zip. GitHub lets a manual run pick a tag rather than
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


### Running the macOS image

The macOS images carry only jpackage's ad-hoc signature, because a Developer ID
certificate requires a paid Apple Developer Program membership. macOS refuses to
open such an application when it has been *quarantined*.

Quarantine is applied by whatever downloaded the file, and command-line tools do
not apply it — so downloading a release without a browser sidesteps the problem
entirely, and is the recommended way to fetch a build:

```bash
gh release download v1.2.3 -p 'Aurora-MediaCenter-macos-*.zip'

unzip Aurora-MediaCenter-macos-aarch64.zip     # Apple Silicon
unzip Aurora-MediaCenter-macos-x64.zip         # Intel

open MediaCenter.app
```

`curl -LO <asset-url>` works the same way. Pick the archive that matches the
machine: an arm64 image cannot run on an Intel Mac at all, and Rosetta does not
help — it translates x86_64 to arm64, not the other way round.

#### If it was downloaded with a browser

Clear the attribute once, after unzipping:

```bash
xattr -dr com.apple.quarantine MediaCenter.app
open MediaCenter.app
```

That is once per download, not once per launch: the attribute is stored on disk,
so the unzipped application keeps working afterwards, including after moving it
to `/Applications` and after a reboot. `-r` matters, because the files inside the
bundle carry the attribute too, not just the `.app` itself.

#### "Damaged" is a different problem

If macOS calls the application *damaged* rather than *from an unidentified
developer*, quarantine is not the cause: the bundle itself is broken, usually
because archiving lost the symbolic links or the executable bits and invalidated
the ad-hoc signature. These tell the two apart:

```bash
codesign --verify --deep --strict MediaCenter.app ; echo "exit: $?"
ls -l MediaCenter.app/Contents/MacOS/MediaCenter
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

### Looking at the interface

The tests cover no layout — there is no rendered scene in them — so a screen is
checked by having the application draw itself into a PNG and quit:

```bash
./gradlew run --args="--snapshot=/tmp/home.png"
./gradlew run --args="--snapshot=/tmp/browse.png --snapshot-keys=ENTER,ENTER"
```

`--snapshot-keys` presses those keys first, a second and a half
apart, which is how a screen below the home page is reached. The image comes
from the scene rather than from the desktop, so nothing has to be watching: no
screen-recording permission is involved — macOS refuses that outright to an
unsigned parent process — and the television the application really runs on has
nobody sitting in front of it.

`mediacenter.ui.PngWriter` encodes the file by hand. `ImageIO` would have meant
adding `java.desktop` to the module graph, and a debugging flag is no reason to
grow the runtime image.

The screen still has to be awake. JavaFX draws through the window server, so a
display that has gone to sleep — `ioreg -n IODisplayWrangler -r -d 1` reporting
`CurrentPowerState=0` — produces no image and the run hangs until it is killed.

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
