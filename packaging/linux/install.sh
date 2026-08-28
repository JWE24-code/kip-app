#!/usr/bin/env bash
#
# Install a built Kip (out/Kip-linux-x64/) into the user's home — no root.
#   binary + resources -> ~/.local/opt/kip
#   launcher           -> ~/.local/bin/kip     (must be on PATH)
#   desktop entry      -> ~/.local/share/applications/kip.desktop
#   icon               -> ~/.local/share/icons/hicolor/512x512/apps/kip.png
#
# Run app/packaging/linux/build.sh first. Re-run any time to update in place.
# Uninstall:  app/packaging/linux/install.sh --uninstall
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$here/../.." && pwd)"
SRC="$APP_DIR/out/Kip-linux-x64"

PREFIX="$HOME/.local"
KIP_HOME="$PREFIX/opt/kip"
BIN="$PREFIX/bin/kip"
DESKTOP="$PREFIX/share/applications/kip.desktop"
ICON="$PREFIX/share/icons/hicolor/512x512/apps/kip.png"

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -rf "$KIP_HOME"; rm -f "$BIN" "$DESKTOP" "$ICON"
  update-desktop-database "$PREFIX/share/applications" 2>/dev/null || true
  echo "uninstalled."
  exit 0
fi

[[ -x "$SRC/kip" ]] || { echo "no build at $SRC — run build.sh first"; exit 1; }

echo "==> $KIP_HOME"
mkdir -p "$KIP_HOME"
rsync -a --delete "$SRC/" "$KIP_HOME/"

echo "==> $BIN"
mkdir -p "$(dirname "$BIN")"
install -m755 "$here/kip.sh" "$BIN"

echo "==> $ICON"
mkdir -p "$(dirname "$ICON")"
# build.sh stages a 512x512 icon.png next to app.asar for exactly this
cp -f "$KIP_HOME/resources/icon.png" "$ICON" 2>/dev/null || true

echo "==> $DESKTOP"
mkdir -p "$(dirname "$DESKTOP")"
install -m644 "$here/kip.desktop" "$DESKTOP"
update-desktop-database "$PREFIX/share/applications" 2>/dev/null || true

case ":$PATH:" in
  *":$PREFIX/bin:"*) ;;
  *) echo "note: add $PREFIX/bin to PATH (Omarchy's ~/.config/fish or ~/.bashrc)";;
esac

echo "done. launch: kip   (or from the app launcher)"
