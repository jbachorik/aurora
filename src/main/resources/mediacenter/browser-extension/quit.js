// Ctrl+Q from anywhere on the page asks the background worker to close the
// browser. A capturing listener on the window, at document_start, so a page
// that swallows key events never gets the chance.
window.addEventListener("keydown", event => {
    if (event.ctrlKey && !event.altKey && !event.shiftKey && !event.metaKey
            && (event.key === "q" || event.key === "Q")) {
        event.preventDefault();
        chrome.runtime.sendMessage("quit");
    }
}, true);
