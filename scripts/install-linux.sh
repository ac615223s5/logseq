#!/bin/bash

# Logseq Linux Installer Script
# This script installs or updates Logseq (this fork's account-less DB build) on Linux systems
# Usage: ./install-linux.sh [version]

set -e  # Exit on any error

# Default values
DEFAULT_VERSION="latest"
DEFAULT_REPO="ac615223s5/logseq"
INSTALL_DIR="/opt/logseq"
BIN_DIR="/usr/local/bin"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

show_help() {
    cat << HELP
Logseq Linux Installer

This script installs Logseq on Linux systems.

Re-running it over an existing installation updates it in place.

USAGE:
    $0 [VERSION] [OPTIONS]
    $0 uninstall

COMMANDS:
    install (default)   Install or update Logseq
    uninstall           Removes Logseq installation (keeps user data)

ARGUMENTS:
    VERSION    Release tag to install (e.g., "account-less-2.0.2"). Default: latest

OPTIONS:
    --help, -h          Show this help message
    --repo OWNER/NAME   GitHub repo to install from (default: $DEFAULT_REPO)
    --prefix DIR        Installation prefix (default: /opt/logseq)
    --user              Install for current user only
    --no-desktop        Skip desktop integration
    --verbose, -v       Verbose output

EXAMPLES:
    $0                              # Install/update to the latest release
    $0 account-less-2.0.2           # Install a specific release tag
    $0 --user                       # Install for current user
    $0 --prefix ~/.local/share/logseq  # Custom install location

For more information, visit: https://github.com/$DEFAULT_REPO
HELP
}

uninstall() {
    log_info "Searching for Logseq installations..."
    
    local user_removed=false
    local system_removed=false
    
    # User installation paths
    local -a user_paths=(
        "$HOME/.local/share/logseq"
        "$HOME/.local/bin/logseq"
        "$HOME/.local/share/applications/logseq.desktop"
        "$HOME/.local/share/icons/hicolor/512x512/apps/logseq.png"
    )
    
    # System installation paths
    local -a system_paths=(
        "/opt/logseq"
        "/usr/local/bin/logseq"
        "/usr/share/applications/logseq.desktop"
        "/usr/share/icons/hicolor/512x512/apps/logseq.png"
    )
    
    # Remove user installation
    log_info "Checking user installation..."
    for path in "${user_paths[@]}"; do
        if [[ -e "$path" ]] || [[ -L "$path" ]]; then
            log_info "Removing: $path"
            rm -rf "$path"
            user_removed=true
        fi
    done
    
    # Remove system installation
    log_info "Checking system-wide installation..."
    for path in "${system_paths[@]}"; do
        if [[ -e "$path" ]] || [[ -L "$path" ]]; then
            if [[ "$EUID" -ne 0 ]]; then
                log_warn "System-wide installation found at $path, but root privileges required"
                log_warn "Run with sudo to uninstall system-wide installation"
            else
                log_info "Removing: $path"
                rm -rf "$path"
                system_removed=true
            fi
        fi
    done
    
    # Update desktop databases
    if [[ "$user_removed" == true ]] && [[ -d "$HOME/.local/share/applications" ]]; then
        update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
    fi
    
    if [[ "$system_removed" == true ]]; then
        update-desktop-database /usr/share/applications 2>/dev/null || true
    fi
    
    # Final status message
    if [[ "$user_removed" == true ]] || [[ "$system_removed" == true ]]; then
        log_info "Logseq has been uninstalled successfully!"
    else
        log_warn "No Logseq installation found in default locations"
    fi
}

# Parse command line arguments
VERSION="$DEFAULT_VERSION"
REPO="$DEFAULT_REPO"
USER_INSTALL=false
SKIP_DESKTOP=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)
            show_help
            exit 0
            ;;
        --repo)
            REPO="$2"
            shift 2
            ;;
        --prefix)
            INSTALL_DIR="$2"
            shift 2
            ;;
        --user)
            USER_INSTALL=true
            shift
            ;;
        --no-desktop)
            SKIP_DESKTOP=true
            shift
            ;;
        --verbose|-v)
            VERBOSE=true
            shift
            ;;
        uninstall)
            uninstall
            exit 0
            ;;
        -*)
            log_error "Unknown option: $1"
            show_help
            exit 1
            ;;
        *)
            VERSION="$1"
            shift
            ;;
    esac
done

# Set installation paths based on user/system install
if [[ "$USER_INSTALL" == true ]]; then
    INSTALL_DIR="${INSTALL_DIR/#\/opt\/logseq/$HOME/.local/share/logseq}"
    BIN_DIR="$HOME/.local/bin"
    mkdir -p "$BIN_DIR"
    
    # Add local bin to PATH if not already there
    if ! echo "$PATH" | grep -q "$HOME/.local/bin"; then
        log_info "Adding $HOME/.local/bin to PATH..."
        export PATH="$HOME/.local/bin:$PATH"
        echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
    fi
fi

log_info "Installing Logseq $VERSION to $INSTALL_DIR"

# Check if running as root for system-wide installation
if [[ "$USER_INSTALL" == false && $EUID -ne 0 ]]; then
    log_warn "System-wide installation requires root privileges"
    log_warn "Run with sudo or use --user for user-specific installation"
    exit 1
fi

# Create temporary directory
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
if [[ "$VERBOSE" == true ]]; then
    log_info "Using temporary directory: $TEMP_DIR"
fi

cd "$TEMP_DIR"

# Determine download URL
api_get() {
    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H 'Accept: application/vnd.github+json' "$1"
    else
        curl -fsSL -H 'Accept: application/vnd.github+json' "$1"
    fi
}

if [[ "$VERSION" == "latest" ]]; then
    log_info "Fetching latest release information from $REPO..."
    # /releases/latest skips prereleases, and this fork marks some builds that
    # way, so take the newest published release from the full list instead
    RELEASE_JSON=$(api_get "https://api.github.com/repos/$REPO/releases?per_page=10" || true)
else
    log_info "Fetching release '$VERSION' from $REPO..."
    RELEASE_JSON=$(api_get "https://api.github.com/repos/$REPO/releases/tags/$VERSION" || true)
fi

if [[ -z "$RELEASE_JSON" ]]; then
    log_error "Could not read release information for '$VERSION' from $REPO"
    rm -rf "$TEMP_DIR"
    exit 1
fi

# Asset names differ across releases (Logseq-linux-x64-<v>.zip and
# Logseq-linux-x86_64-<v>.zip have both been used); arm64 must not match here.
DOWNLOAD_URL=$(printf '%s' "$RELEASE_JSON" \
    | grep -o '"browser_download_url":[[:space:]]*"[^"]*"' \
    | cut -d'"' -f4 \
    | grep -E 'Logseq-linux-(x64|x86_64)-[^/]*\.zip$' \
    | head -1 || true)

if [[ -z "$DOWNLOAD_URL" ]]; then
    log_error "No Linux x64 .zip asset found on release '$VERSION' in $REPO"
    rm -rf "$TEMP_DIR"
    exit 1
fi

RESOLVED_TAG=$(printf '%s' "$DOWNLOAD_URL" | awk -F/ '{print $(NF-1)}')
log_info "Release: $RESOLVED_TAG"
log_info "Download URL: $DOWNLOAD_URL"

# Download Logseq
log_info "Downloading Logseq..."
if ! wget -q --show-progress -O logseq.zip "$DOWNLOAD_URL"; then
    log_error "Failed to download Logseq $VERSION"
    log_error "Please check if version $VERSION exists on GitHub releases"
    rm -rf "$TEMP_DIR"
    exit 1
fi

# Extract archive
log_info "Extracting archive..."
unzip -q logseq.zip -d extracted

# Find the extracted app: releases are either flat (app at the archive root) or
# wrapped in a single top-level directory
EXTRACTED_DIR="$TEMP_DIR/extracted"
if [[ ! -d "$EXTRACTED_DIR/resources" ]]; then
    NESTED=$(find "$EXTRACTED_DIR" -maxdepth 2 -type d -name resources | head -1 || true)
    if [[ -z "$NESTED" ]]; then
        log_error "Could not find the Logseq app inside the archive"
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    EXTRACTED_DIR=$(dirname "$NESTED")
fi

# Current releases name the binary lowercase, older ones capitalised
APP_BIN="logseq"
if [[ ! -f "$EXTRACTED_DIR/logseq" ]]; then
    APP_BIN="Logseq"
fi
if [[ ! -f "$EXTRACTED_DIR/$APP_BIN" ]]; then
    log_error "Could not find the Logseq executable inside the archive"
    rm -rf "$TEMP_DIR"
    exit 1
fi

# Note whether the app is running before we replace the files it is executing
APP_RUNNING=false
if command -v pgrep >/dev/null 2>&1; then
    # match on the executable path, so a copy running from somewhere else
    # (another prefix, a dev build) doesn't trigger the warning
    for pid in $({ pgrep -x logseq; pgrep -x Logseq; } 2>/dev/null); do
        if [[ "$(readlink -f "/proc/$pid/exe" 2>/dev/null)" == "$INSTALL_DIR"/* ]]; then
            APP_RUNNING=true
            break
        fi
    done
fi

# Install files by staging a full copy next to the target and swapping it in.
# Copying straight over an existing install fails with "Text file busy" when the
# app is running, and a failure halfway through would leave a mix of two
# versions behind; a directory rename is atomic and also drops stale files.
log_info "Installing files..."
mkdir -p "$(dirname "$INSTALL_DIR")"
STAGE_DIR="${INSTALL_DIR}.new-$$"
OLD_DIR="${INSTALL_DIR}.old-$$"
rm -rf "$STAGE_DIR" "$OLD_DIR"
cp -a "$EXTRACTED_DIR" "$STAGE_DIR"
chmod +x "$STAGE_DIR/$APP_BIN"
# Launchers and desktop entries from older installs point at the capitalised name
if [[ "$APP_BIN" == "logseq" ]]; then
    ln -sf "$INSTALL_DIR/logseq" "$STAGE_DIR/Logseq"
fi

# Sandbox permissions have to be set before the swap: the helper must be setuid
# root or Electron refuses to start without --no-sandbox
if [[ "$USER_INSTALL" == false && -f "$STAGE_DIR/chrome-sandbox" ]]; then
    log_info "Setting sandbox permissions..."
    chown root:root "$STAGE_DIR/chrome-sandbox"
    chmod 4755 "$STAGE_DIR/chrome-sandbox"
fi

if [[ -d "$INSTALL_DIR" ]]; then
    mv "$INSTALL_DIR" "$OLD_DIR"
fi
mv "$STAGE_DIR" "$INSTALL_DIR"
rm -rf "$OLD_DIR"

ln -sf "$INSTALL_DIR/$APP_BIN" "$BIN_DIR/logseq"

# Desktop integration
if [[ "$SKIP_DESKTOP" == false ]]; then
    log_info "Creating desktop integration..."

    DESKTOP_FILE="/usr/share/applications/logseq.desktop"
    if [[ "$USER_INSTALL" == true ]]; then
        mkdir -p ~/.local/share/applications/
        DESKTOP_FILE="$HOME/.local/share/applications/logseq.desktop"
    fi

    # Copy icon to standard location
    if [[ "$USER_INSTALL" == true ]]; then
        ICON_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"
    else
        ICON_DIR="/usr/share/icons/hicolor/512x512/apps"
    fi
    
    # Ensure the directory exists regardless of install type
    mkdir -p "$ICON_DIR"
    
    # Create desktop file
    cat > "$DESKTOP_FILE" << DESKTOP_EOF
[Desktop Entry]
Version=1.0
Name=Logseq
Comment=Logseq - A privacy-first, open-source platform for knowledge management and collaboration
Exec=$INSTALL_DIR/$APP_BIN $([ "$USER_INSTALL" = true ] && echo "--no-sandbox") %U
Icon=$ICON_DIR/logseq.png
Terminal=false
Type=Application
Categories=Office;Productivity;Utility;TextEditor;
MimeType=application/x-logseq;
StartupWMClass=Logseq
DESKTOP_EOF
    
    # Make desktop file executable
    chmod +x "$DESKTOP_FILE"
    
    # 1. Find and copy the icon (try current paths first, then fallbacks)
    if [[ -f "$INSTALL_DIR/resources/app.asar.unpacked/icons/logseq.png" ]]; then
        cp "$INSTALL_DIR/resources/app.asar.unpacked/icons/logseq.png" "$ICON_DIR/logseq.png"
    elif [[ -f "$INSTALL_DIR/resources/app/icons/logseq.png" ]]; then
        cp "$INSTALL_DIR/resources/app/icons/logseq.png" "$ICON_DIR/logseq.png"
    elif [[ -f "$INSTALL_DIR/resources/app.asar.unpacked/dist/icon.png" ]]; then
        cp "$INSTALL_DIR/resources/app.asar.unpacked/dist/icon.png" "$ICON_DIR/logseq.png"
    elif [[ -f "$INSTALL_DIR/resources/app/icon.png" ]]; then
        cp "$INSTALL_DIR/resources/app/icon.png" "$ICON_DIR/logseq.png"
    fi
    
    # 2. Update desktop file to use purely the icon name for system-wide installs
    if [[ "$USER_INSTALL" == false ]]; then
        sed -i "s|Icon=$ICON_DIR/logseq.png|Icon=logseq|" "$DESKTOP_FILE"
    fi
    
    # Update desktop database
    if [[ "$USER_INSTALL" == false ]]; then
        update-desktop-database /usr/share/applications/ 2>/dev/null || true
    else
        update-desktop-database ~/.local/share/applications/ 2>/dev/null || true
    fi
fi

# Clean up
rm -rf "$TEMP_DIR"

# Verify installation
if command -v logseq >/dev/null 2>&1; then
    # Report the release we just installed; the app's own package.json lives inside
    # app.asar, and launching the Electron binary to ask it would hang the script.
    INSTALLED_VERSION="$RESOLVED_TAG"

    log_info "Logseq installed successfully!"
    log_info "Version: $INSTALLED_VERSION"
    log_info "Location: $INSTALL_DIR"
    log_info "Command: logseq"

    if [[ "$APP_RUNNING" == true ]]; then
        log_warn "Logseq was running during the update; restart it to load this version"
    fi
    
    if [[ "$SKIP_DESKTOP" == false ]]; then
        log_info "Desktop integration: Enabled"
        log_info "You can find Logseq in your applications menu"
    fi
else
    log_error "Installation completed but 'logseq' command not found in PATH"
    log_info "You may need to restart your terminal or add $BIN_DIR to your PATH"
fi

log_info "Installation completed successfully!"
