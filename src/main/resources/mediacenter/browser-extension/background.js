// Closing every window ends the browser: the kiosk profile has exactly one,
// and nothing in it keeps the process alive afterwards. The media center is
// waiting on the process and returns the moment it is gone.
function quit() {
    chrome.windows.getAll({}, windows =>
        windows.forEach(w => chrome.windows.remove(w.id)));
}

// The content script's Ctrl+Q, from any ordinary page.
chrome.runtime.onMessage.addListener(message => {
    if (message === "quit") {
        quit();
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
