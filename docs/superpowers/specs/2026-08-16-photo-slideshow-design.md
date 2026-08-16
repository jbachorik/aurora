# Photos and recursive slideshow

Design for showing photographs in the media center, and for playing a folder of
them as a slideshow that descends into subfolders.

Status: agreed, not yet implemented.

## Why

The media center browses video and hands it to VLC. A shelf of holiday
photographs spread across a tree of folders has no way in at all today, and
VLC is a poor photo viewer. The sofa case is "put the 2019 holiday on the
television", where the photographs sit in `Holidays/2019/Crete`,
`Holidays/2019/Athens` and so on, and nobody wants to open each folder in turn.

## What changes about the project's premise

The README states a design rule: *"VLC does the playing. No libVLC, no
decoding, no rendering."* This feature renders media itself. A still image is
not playback and VLC has nothing useful to offer here, but the rule is written
down and this contradicts it, so the README changes with the feature rather
than being quietly left wrong.

The rule that does **not** change is the one about the JavaFX thread never
doing I/O. Every walk and every decode happens off it.

## Decisions

| Question | Decision |
|---|---|
| How a slideshow starts | A **Slideshow tile**, first in the folder's grid, shown only when photos exist in this folder or any folder below it |
| Enter on a photo | Opens that photo full screen; arrows move within **its own folder**; no auto-advance |
| Order | Folder order, **depth first** — each folder's photos by name, then into its subfolders |
| End of the run | **Loops** back to the first |
| Gathering the set | **Streaming**: show the first photo at once and keep walking behind it |
| HEIC | **Not recognised as a photo**; JavaFX cannot decode it |
| EXIF orientation | **Honoured**; parsed here, since a library is not an option |

### Why streaming rather than gathering first

Gathering the whole tree before showing anything is simpler — Left, looping and
"42 of 380" all fall out of a finished list. It is also how Kodi behaves, and
waiting the better part of a minute in front of an expectant room is exactly
the experience being avoided.

Depth-first folder order finds the first photograph after listing a single
directory, so there is no reason to wait for the rest. The concurrency this
appears to introduce is avoided by the split used everywhere else in this
codebase: the walker runs on a background thread and **publishes batches to the
JavaFX thread**, which is the only thread that ever touches the list. The
viewer indexes an ordinary `ArrayList` with no locking.

Two rules follow from the set being incomplete while it is being shown:

* Reaching the end **does not loop** while the walk is still running; the last
  collected photograph is held until more arrive. "Back to the first" is
  meaningless before the last is known.
* The counter reads `42 of 380+` while collecting and `42 of 380` once the walk
  has finished, so a paused-looking show can be told from a finished one.

## Components

### `mediacenter.media.PhotoFiles`

The twin of `VideoFiles`: which extensions are photographs, and the same junk
rules. Recognises what JavaFX can actually decode — JPEG, PNG, GIF, BMP — and
deliberately not HEIC, WebP or TIFF.

### `MediaItemType.IMAGE`

A third kind of browsable entry beside `DIRECTORY` and `VIDEO`. `MediaScanner`
emits photographs as items whose artwork is the photograph itself, so the
existing `MediaTile` renders them with no new drawing code.

### `mediacenter.media.PhotoWalker`

Depth-first traversal in folder order, streaming its results. One class serves
two jobs:

* run to completion, it produces the slideshow set;
* stopped at the first hit, it answers "are there photographs beneath this
  folder?" for the Slideshow tile.

It does not follow symbolic links — a link pointing back up the tree would walk
forever. Hidden files and the existing junk names are skipped. An unreachable
share raises the existing `MediaAccessException` rather than hanging.

### `mediacenter.ui.PhotoView`

One class, two modes:

* **Slideshow** — the recursive set, auto-advancing, looping once the walk is
  complete.
* **Single** — the photograph that was activated, arrows moving within its own
  folder, no auto-advance, and **stopping at either end** rather than wrapping,
  so it is apparent that the folder has been seen. Looping belongs to a show
  that is meant to run unattended, not to looking at one picture.

| Key | Action | Slideshow | Single |
|---|---|---|---|
| `←` `→` | Previous / next | loops once the walk is done | stops at the ends |
| `Enter`, `Space` | Toggle auto-advance | yes | ignored — there is nothing to advance |
| `Esc`, `Backspace` | Back to the grid, focus on the tile it started from | yes | yes |

A manual arrow **resets** the interval rather than cancelling it, so skipping to
a photograph gives a full dwell on it. An overlay shows the file name and the
counter on each change, then fades.

### `View.fullBleed()`

The shell puts views in the centre of a `BorderPane` between a header and a
hint bar. A photograph wants the whole screen, so `View` gains a flag —
defaulting to `false` — and the shell hides both when it is set, restoring them
on the way out.

### `mediacenter.ui.components.PhotoCache`

Holds exactly three decoded images: previous, current, next. Each is requested
at scene size, so JavaFX downscales *during* decode and a 24-megapixel file
never becomes 96MB in memory. At 1920×1080 each is about 8.3MB, so the whole
cache is about 25MB — comfortable on the target laptop. Neighbours are
prefetched on JavaFX's background loader. Nothing else is retained; this is
deliberately not an LRU that can grow.

The existing `ArtworkCache` is not reused: it is a shared cache tuned for
thumbnails, and pushing full-screen frames through it would evict every
thumbnail on the home screen and blow the memory budget.

### Settings

`ApplicationSettings` gains a slideshow interval, default 5 seconds. This
touches the record, its witherer, the JSON reader and writer, and the Settings
screen — including its `navigationRows`, so the new row is reachable by arrow
key like every other.

The packaged image gains an explicit maximum heap. It is unspecified today,
which means a large photograph fails unpredictably rather than predictably.

## Error handling

* A photograph that cannot be decoded — corrupt, truncated, or a format that
  slipped through — shows a placeholder and the show **advances past it**. A
  slideshow that stops dead on one bad file in front of a room is the failure
  worth designing against.
* A share that disappears mid-walk ends the walk with what was collected; the
  show continues over those.
* No photographs found beneath a folder means no Slideshow tile, so the case
  cannot be entered at all.

## Formats that will disappoint someone

**HEIC.** Every photograph taken on a recent iPhone is HEIC and JavaFX cannot
decode any of them. They are excluded from recognition entirely, so they never
enter a set and never appear as a broken frame. The README says so plainly,
because "my photos are missing" is otherwise an unanswerable support question.

**EXIF orientation.** JavaFX ignores the orientation tag, so portrait
photographs from any phone appear on their side. The tag is parsed here — the
orientation field alone, not a general EXIF reader — and applied as a rotation
on the view. Roughly sixty lines, and testable against crafted bytes.

## Testing

Unit tested: `PhotoFiles` recognition; the walker against `@TempDir` trees for
ordering, recursion, junk, hidden files and symlink loops; the EXIF orientation
parser; the counter text in both collecting and finished states.

Verified by screenshot, using the existing `--snapshot=` and `--snapshot-keys=`
flags: the Slideshow tile appearing first in a folder that has photographs
beneath it, the viewer full-bleed with no header or hint bar, and arrow
movement between photographs.

Not verifiable here: memory behaviour and decode speed on the Windows 7 laptop.
That needs the target machine.

## Out of scope

Editing, rotating and deleting photographs. Zoom and pan. Transitions beyond
the existing fade. Metadata, faces, tags, dates as anything other than file
order. Music behind the slideshow. Network photo services.
