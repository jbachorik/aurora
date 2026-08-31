// Closing every window ends the browser: the kiosk profile has exactly one,
// and nothing in it keeps the process alive afterwards. The media center is
// waiting on the process and returns the moment it is gone.
function quit() {
    chrome.windows.getAll({}, windows =>
        windows.forEach(w => chrome.windows.remove(w.id)));
}

// The content script's Ctrl+Q, from any ordinary page — and the fullscreen
// hand-off: frames cannot message each other, so a player frame that may not
// fullscreen itself asks here, and the request is passed to the top frame
// (frame 0), which can put the frame on the screen from outside.
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message === "quit") {
        quit();
    }
    if (message === "fullscreen-from-top" && sender.tab) {
        chrome.tabs.sendMessage(sender.tab.id, "fullscreen-from-top", { frameId: 0 });
    }
    // "Watch in Aurora": the fetch lives here because only the worker holds
    // the permission for the media center's local address. The verdict goes
    // back as a pushed message rather than as this message's response: the
    // media center answers only after running yt-dlp — the better part of a
    // minute on an old machine — and an older Chromium (109, the last one
    // Windows 7 gets) stops a waiting worker at thirty seconds, closing a
    // response channel nobody has answered yet. Acknowledge now, report later.
    if (message && message.watchInAurora && sender.tab) {
        const tab = sender.tab.id;
        const frame = sender.frameId;
        fetch("http://127.0.0.1:8765/api/watch", {
            method: "POST",
            body: JSON.stringify({ url: message.watchInAurora }),
        })
            .then(response => response.json()
                .then(body => ({ ok: response.ok, error: body.error })))
            .catch(() => ({ ok: false, error: "Aurora is not reachable — is the media center running?" }))
            .then(outcome => chrome.tabs.sendMessage(tab, { watchOutcome: outcome }, { frameId: frame }))
            .catch(() => { /* the page is already gone: Aurora took the screen */ });
        sendResponse({ accepted: true });
    }
});

// Belt to the braces: the browser-level command still fires on the few pages
// content scripts cannot run on (error pages, the PDF viewer).
if (chrome.commands) {
    chrome.commands.onCommand.addListener(command => {
        if (command === "quit-browser") {
            quit();
        }
    });
}
