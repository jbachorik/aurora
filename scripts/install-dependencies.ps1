# Installs what the media center needs at runtime on Windows:
#
#   VLC      plays everything the media center hands over
#   yt-dlp   resolves a page's stream for "Watch in Aurora"
#
# No browser is installed: Windows ships Microsoft Edge, which is a
# Chromium-family browser the media center's kiosk mode takes as it is —
# point Settings at msedge.exe. Idempotent: anything already present is
# left alone.
#
# Needs winget (App Installer), which every maintained Windows 10/11 has.
# On the original Windows 7 target there is no winget: install VLC from
# https://www.videolan.org and drop yt-dlp.exe from
# https://github.com/yt-dlp/yt-dlp/releases next to it or anywhere on the
# PATH (or point "ytDlpPath" in config.json at it).

$ErrorActionPreference = "Stop"

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Host "winget was not found. Install 'App Installer' from the Microsoft Store and rerun,"
    Write-Host "or install by hand: VLC (videolan.org) and yt-dlp (github.com/yt-dlp/yt-dlp)."
    exit 1
}

function Install-IfMissing([string]$Command, [string]$WingetId, [string]$Name) {
    if (Get-Command $Command -ErrorAction SilentlyContinue) {
        Write-Host "$Name is already installed."
        return
    }
    Write-Host "Installing $Name ..."
    winget install --id $WingetId --exact --accept-source-agreements --accept-package-agreements
}

Install-IfMissing "vlc"    "VideoLAN.VLC"  "VLC"
Install-IfMissing "yt-dlp" "yt-dlp.yt-dlp" "yt-dlp"

Write-Host ""
Write-Host "Done. The media center finds VLC and yt-dlp by itself (a fresh install may"
Write-Host "need a new terminal or sign-in for the PATH to catch up); for website tiles"
Write-Host "choose Edge - msedge.exe - or any other Chromium-family browser in Settings."
