#!/usr/bin/env bash
#
# Installs what the media center needs at runtime on Linux or macOS:
#
#   vlc       plays everything the media center hands over
#   chromium  (or any Chromium-family browser) opens website tiles;
#             skipped when one is already installed
#   yt-dlp    resolves a page's stream for "Watch in Aurora"; skipped
#             when already installed
#
# Idempotent: everything already present is left alone, so running it
# again after an update costs nothing. The build-time dependency — a
# Liberica JDK 25 Full — is deliberately not handled here; see
# "Installing a Full JDK locally" in the README.

set -euo pipefail

say() { printf '%s\n' "$*"; }

# Root runs the package manager directly; anyone else goes through sudo.
SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
fi

have_browser() {
    for browser in chromium chromium-browser google-chrome google-chrome-stable \
            microsoft-edge brave-browser vivaldi opera; do
        if command -v "$browser" >/dev/null 2>&1; then
            return 0
        fi
    done
    # macOS applications are bundles, not commands on the PATH.
    for bundle in "Google Chrome" "Chromium" "Microsoft Edge" "Brave Browser" "Vivaldi"; do
        if [ -d "/Applications/$bundle.app" ]; then
            return 0
        fi
    done
    return 1
}

have_vlc() {
    command -v vlc >/dev/null 2>&1 || [ -d "/Applications/VLC.app" ]
}

have_ytdlp() {
    command -v yt-dlp >/dev/null 2>&1 || [ -x "$HOME/.local/bin/yt-dlp" ]
}

# The standalone official binary, for distributions whose packaged yt-dlp
# is missing or too old to know today's sites. ~/.local/bin is one of the
# places the media center looks without any configuration.
install_ytdlp_binary() {
    say "yt-dlp: downloading the standalone binary to ~/.local/bin ..."
    mkdir -p "$HOME/.local/bin"
    url="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$HOME/.local/bin/yt-dlp"
    else
        wget -qO "$HOME/.local/bin/yt-dlp" "$url"
    fi
    chmod +x "$HOME/.local/bin/yt-dlp"
}

install_with_apt() {
    # The index refresh is only worth its time when apt will install something;
    # a missing yt-dlp alone is served by the binary download below.
    if ! have_vlc || ! have_browser; then
        $SUDO apt-get update
    fi
    if ! have_vlc; then $SUDO apt-get install -y vlc; fi
    if ! have_browser; then
        # Debian calls it chromium; Ubuntu answers to chromium-browser.
        $SUDO apt-get install -y chromium 2>/dev/null \
            || $SUDO apt-get install -y chromium-browser
    fi
    if ! have_ytdlp; then
        # Debian and Ubuntu package yt-dlp, but a media site outruns a stable
        # distribution within months; the standalone binary self-updates.
        install_ytdlp_binary
    fi
}

install_with_dnf() {
    # VLC is in Fedora proper since Fedora 41; older releases need RPM Fusion
    # first (https://rpmfusion.org), and the install below will say so.
    if ! have_vlc; then $SUDO dnf install -y vlc; fi
    if ! have_browser; then $SUDO dnf install -y chromium; fi
    if ! have_ytdlp; then install_ytdlp_binary; fi
}

install_with_pacman() {
    if ! have_vlc; then $SUDO pacman -S --noconfirm --needed vlc; fi
    if ! have_browser; then $SUDO pacman -S --noconfirm --needed chromium; fi
    if ! have_ytdlp; then $SUDO pacman -S --noconfirm --needed yt-dlp; fi
}

install_with_zypper() {
    if ! have_vlc; then $SUDO zypper --non-interactive install vlc; fi
    if ! have_browser; then $SUDO zypper --non-interactive install chromium; fi
    if ! have_ytdlp; then $SUDO zypper --non-interactive install yt-dlp; fi
}

install_with_brew() {
    if ! have_vlc; then brew install --cask vlc; fi
    if ! have_browser; then
        # The Chromium cask is an unsigned build without automatic updates;
        # installing Chrome, Edge or Brave by hand works just as well — the
        # media center takes any Chromium-family browser.
        brew install --cask chromium
    fi
    if ! have_ytdlp; then brew install yt-dlp; fi
}

case "$(uname -s)" in
    Darwin)
        if ! command -v brew >/dev/null 2>&1; then
            say "Homebrew is needed on macOS: https://brew.sh — install it and rerun."
            exit 1
        fi
        install_with_brew
        ;;
    Linux)
        if command -v apt-get >/dev/null 2>&1; then install_with_apt
        elif command -v dnf >/dev/null 2>&1; then install_with_dnf
        elif command -v pacman >/dev/null 2>&1; then install_with_pacman
        elif command -v zypper >/dev/null 2>&1; then install_with_zypper
        else
            say "No known package manager found (apt, dnf, pacman, zypper)."
            say "Install by hand: vlc, a Chromium-family browser, and yt-dlp"
            say "(https://github.com/yt-dlp/yt-dlp)."
            exit 1
        fi
        ;;
    *)
        say "Unsupported system: $(uname -s). On Windows run scripts/install-dependencies.ps1."
        exit 1
        ;;
esac

say ""
say "Done. Installed and found:"
have_vlc     && say "  vlc      ✓" || say "  vlc      ✗ (install it by hand)"
have_browser && say "  browser  ✓" || say "  browser  ✗ (install a Chromium-family one)"
have_ytdlp   && say "  yt-dlp   ✓" || say "  yt-dlp   ✗ (install it by hand)"
say ""
say "The media center finds VLC and yt-dlp by itself; choose the browser in Settings."
