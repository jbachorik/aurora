// F puts the player on the whole screen, because the site's own button often
// cannot.
//
// A page that is already the size of the screen — which is what --start-fullscreen
// hands it — fools players that infer their fullscreen state from the window
// rather than from document.fullscreenElement. VK's player, the one Mosfilm
// embeds, is one of them: it decides it is already fullscreen and its button
// becomes a no-op. Asking for fullscreen ourselves goes around the site's own
// idea of the matter entirely.
//
// A keydown is user activation, which is all requestFullscreen needs; the
// content script runs in every frame, so whichever one has the focus can answer
// for the player inside it.

/** Ignore the key while someone is typing — a page search box swallows nothing. */
function isTyping(target) {
    if (!target) {
        return false;
    }
    const tag = target.tagName;
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

function isFullscreenKey(event) {
    // The physical key as well as the letter: a layout that types something
    // other than "f" on that key — Cyrillic, say — reports it only in
    // event.code, while the players clashing over the key read the legacy
    // layout-independent keyCode and fire regardless. Matching the letter
    // alone would stand aside exactly when the broken handler does not.
    if (event.code !== "KeyF" && event.key !== "f" && event.key !== "F") {
        return false;
    }
    return !event.ctrlKey && !event.altKey && !event.metaKey && !isTyping(event.target);
}

/**
 * Takes the key away from the page entirely.
 *
 * <p>preventDefault alone was not enough: the event still reached the site's own
 * handlers, and F is the fullscreen key those players bind too. Having decided it
 * is already fullscreen, VK's handler reads F as "leave" and undid the request in
 * the same keystroke that made it. Registered at document_start, this listener is
 * the first on the window, so stopping propagation here means the page never sees
 * the key at all.
 */
function swallow(event) {
    event.preventDefault();
    event.stopImmediatePropagation();
}

function areaOf(element) {
    const box = element.getBoundingClientRect();
    return box.width * box.height;
}

/** The largest one on the page that is actually showing. */
function largestVisible(selector) {
    return [...document.querySelectorAll(selector)]
        .filter(element => areaOf(element) > 1)
        .sort((a, b) => areaOf(b) - areaOf(a))[0];
}

/**
 * What to put on the screen.
 *
 * <p>A frame that is not the top one is an embedded player, and the page that
 * embedded it has already sized the frame to be exactly the player: the whole
 * document is the right thing to take, and it brings the player's own controls
 * with it. Fullscreening the bare video inside would leave them behind, and a
 * film with no way to pause is worse than one in a window.
 *
 * <p>In the top frame the video is the player; failing that the biggest iframe,
 * which is how a site that embeds someone else's player looks from the outside —
 * a parent may fullscreen a cross-origin frame even though it cannot see inside
 * it. Failing both, the page itself, which is the best that can be done for a
 * player built out of ordinary elements.
 */
function playerElement() {
    if (window !== window.top) {
        return document.documentElement;
    }
    return largestVisible("video")
        ?? largestVisible("iframe")
        ?? document.documentElement;
}

// The page binds F as well, and a keypress or keyup it still receives is enough
// for it to act on. All three go.
for (const type of ["keypress", "keyup"]) {
    window.addEventListener(type, event => {
        if (isFullscreenKey(event)) {
            swallow(event);
        }
    }, true);
}

function toggleFullscreen() {
    if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => { /* already on the way out */ });
        return;
    }
    const target = playerElement();
    if (target) {
        target.requestFullscreen().catch(() => {
            // A frame whose permissions policy withholds fullscreen cannot
            // take the screen itself, but the page that embedded it may put
            // the frame there from outside — the keypress's user activation
            // reaches ancestor frames too. Only the background worker can
            // carry the request across the frame boundary.
            if (window !== window.top) {
                chrome.runtime.sendMessage("fullscreen-from-top");
            }
        });
    }
}

window.addEventListener("keydown", event => {
    if (isFullscreenKey(event)) {
        swallow(event);
        toggleFullscreen();
    }
}, true);

// The relayed request from a frame that was not allowed to fullscreen itself.
// Delivered to the top frame alone, where the largest visible iframe is that
// player seen from outside; on a second press the toggle exits instead.
chrome.runtime.onMessage.addListener(message => {
    if (message === "fullscreen-from-top" && window === window.top) {
        toggleFullscreen();
    }
});
