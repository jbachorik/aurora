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
 * The video if this frame has one; otherwise the biggest iframe, which is how a
 * site that embeds someone else's player looks from the outside — a parent may
 * fullscreen a cross-origin frame even though it cannot see inside it. Failing
 * both, the page itself, which is the best that can be done for a player built
 * out of ordinary elements.
 */
function playerElement() {
    return largestVisible("video")
        ?? largestVisible("iframe")
        ?? document.documentElement;
}

window.addEventListener("keydown", event => {
    if (event.key !== "f" && event.key !== "F") {
        return;
    }
    if (event.ctrlKey || event.altKey || event.metaKey || isTyping(event.target)) {
        return;
    }
    event.preventDefault();
    if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => { /* already on the way out */ });
        return;
    }
    const target = playerElement();
    if (target) {
        // Rejection is normal and not worth a dialog on a television: a frame
        // whose permissions policy withholds fullscreen simply stays as it was.
        target.requestFullscreen().catch(() => { /* the page keeps its size */ });
    }
}, true);
