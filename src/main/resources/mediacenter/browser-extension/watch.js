// The "Watch in Aurora" button: a page with a player gets one small button in
// its corner, and pressing it (or W) hands the page's address back to the
// media center. Aurora extracts the stream with yt-dlp and plays it in VLC —
// native fullscreen, the same keys as every film — closing this browser on
// the way. When nothing can be extracted the answer says so, the button shows
// it briefly, and the page stays: the site's own player remains the way in.
//
// Top frame only: the address worth resolving is the page the viewer sees,
// and yt-dlp follows the embeds inside it by itself.

if (window === window.top) {

    const IDLE_LABEL = "▶ Watch in Aurora";
    const ASKING_LABEL = "Asking Aurora…";

    /** A player worth offering to resolve: a video or embed of real size. */
    function hasAPlayer() {
        return [...document.querySelectorAll("video, iframe")].some(element => {
            const box = element.getBoundingClientRect();
            return box.width >= 320 && box.height >= 180;
        });
    }

    let button = null;
    let asking = false;

    function makeButton() {
        const element = document.createElement("button");
        element.textContent = IDLE_LABEL;
        // Inline styles with !important-free properties the page is unlikely
        // to reach: the button lives outside any layout the site manages.
        Object.assign(element.style, {
            position: "fixed",
            top: "16px",
            right: "16px",
            zIndex: "2147483647",
            padding: "10px 18px",
            borderRadius: "999px",
            border: "none",
            background: "rgba(20, 20, 25, 0.85)",
            color: "#fff",
            font: "600 15px system-ui, sans-serif",
            cursor: "pointer",
            opacity: "0.85",
        });
        element.addEventListener("mouseenter", () => { element.style.opacity = "1"; });
        element.addEventListener("mouseleave", () => { element.style.opacity = "0.85"; });
        element.addEventListener("click", ask);
        return element;
    }

    function ask() {
        if (asking || !button) {
            return;
        }
        asking = true;
        button.disabled = true;
        button.textContent = ASKING_LABEL;
        chrome.runtime.sendMessage({ watchInAurora: location.href }, answer => {
            // On success there is nothing to restore: Aurora is already
            // closing this browser. Everything else resets the button after
            // the reason has been on screen long enough to read.
            if (answer && answer.ok) {
                return;
            }
            button.textContent = (answer && answer.error) || "Aurora is not reachable";
            setTimeout(() => {
                asking = false;
                button.disabled = false;
                button.textContent = IDLE_LABEL;
            }, 5000);
        });
    }

    /** The button follows the player: appearing with it, going with it. */
    function refreshButton() {
        if (!document.body) {
            return;
        }
        if (hasAPlayer()) {
            if (!button) {
                button = makeButton();
                document.body.appendChild(button);
            }
        } else if (button && !asking) {
            button.remove();
            button = null;
        }
    }

    // W, the same way F works: the physical key too, so a Cyrillic layout
    // still lands it, and only while the button is showing — on a page
    // without a player the key stays the page's own.
    window.addEventListener("keydown", event => {
        if (event.code !== "KeyW" && event.key !== "w" && event.key !== "W") {
            return;
        }
        if (event.ctrlKey || event.altKey || event.metaKey || !button) {
            return;
        }
        const target = event.target;
        if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA"
                || target.tagName === "SELECT" || target.isContentEditable)) {
            return;
        }
        event.preventDefault();
        event.stopImmediatePropagation();
        ask();
    }, true);

    // Players arrive late on script-heavy sites; a slow look every two
    // seconds costs nothing a television notices.
    document.addEventListener("DOMContentLoaded", refreshButton);
    setInterval(refreshButton, 2000);
}
