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
chrome.runtime.onMessage.addListener((message, sender) => {
    if (message === "quit") {
        quit();
    }
    if (message === "fullscreen-from-top" && sender.tab) {
        chrome.tabs.sendMessage(sender.tab.id, "fullscreen-from-top", { frameId: 0 });
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
