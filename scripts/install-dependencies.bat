@echo off
rem Installs what the media center needs at runtime on Windows:
rem
rem   VLC      plays everything the media center hands over
rem   yt-dlp   resolves a page's stream for "Watch in Aurora"
rem
rem No browser is installed: Windows ships Microsoft Edge, which is a
rem Chromium-family browser the media center's kiosk mode takes as it is --
rem point Settings at msedge.exe. Idempotent: anything already present is
rem left alone. A batch file rather than PowerShell so a double click just
rem runs it, with no execution policy in the way.
rem
rem The installs come through winget (App Installer), which every maintained
rem Windows 10/11 has built in. On the original Windows 7 target there is no
rem winget: install VLC from https://www.videolan.org and drop yt-dlp.exe from
rem https://github.com/yt-dlp/yt-dlp/releases anywhere on the PATH (or point
rem "ytDlpPath" in config.json at it) by hand.

setlocal

where winget >nul 2>nul
if errorlevel 1 goto nowinget

rem VLC's installer does not put vlc.exe on the PATH, so the standard install
rem folders are checked as well -- the same places the media center looks.
set VLC_FOUND=
if exist "%ProgramFiles%\VideoLAN\VLC\vlc.exe" set VLC_FOUND=1
if exist "%ProgramFiles(x86)%\VideoLAN\VLC\vlc.exe" set VLC_FOUND=1
where vlc >nul 2>nul
if not errorlevel 1 set VLC_FOUND=1

if defined VLC_FOUND (
    echo VLC is already installed.
) else (
    echo Installing VLC ...
    winget install --id VideoLAN.VLC --exact --accept-source-agreements --accept-package-agreements
    if errorlevel 1 goto failed
)

where yt-dlp >nul 2>nul
if not errorlevel 1 (
    echo yt-dlp is already installed.
) else (
    echo Installing yt-dlp ...
    winget install --id yt-dlp.yt-dlp --exact --accept-source-agreements --accept-package-agreements
    if errorlevel 1 goto failed
)

echo.
echo Done. The media center finds VLC and yt-dlp by itself (a fresh install may
echo need a new terminal or sign-in for the PATH to catch up); for website tiles
echo choose Edge -- msedge.exe -- or any other Chromium-family browser in Settings.
exit /b 0

:nowinget
echo winget was not found. Install "App Installer" from the Microsoft Store and
echo rerun, or install by hand: VLC (videolan.org) and yt-dlp
echo (github.com/yt-dlp/yt-dlp/releases).
exit /b 1

:failed
echo.
echo An install failed; the winget output above says why. Rerun after fixing it --
echo whatever already succeeded is skipped next time.
exit /b 1
