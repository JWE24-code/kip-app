#!/usr/bin/env bash
#
# PATH launcher for Kip. Installed as `kip`; the real binary is $KIP_HOME/kip.
#
# Omarchy runs Hyprland (Wayland). Electron 41 speaks Wayland through Ozone —
# --ozone-platform-hint=auto uses Wayland when WAYLAND_DISPLAY is set and falls
# back to X11/XWayland otherwise. WaylandWindowDecorations gives proper
# client-side decorations under wlroots. Override with KIP_FLAGS= if needed
# (e.g. KIP_FLAGS="--ozone-platform=x11" to force XWayland).
#
set -euo pipefail

KIP_HOME="${KIP_HOME:-/opt/kip}"
[[ -x "$KIP_HOME/kip" ]] || KIP_HOME="$HOME/.local/opt/kip"

DEFAULT_FLAGS="--ozone-platform-hint=auto --enable-features=WaylandWindowDecorations"
exec "$KIP_HOME/kip" ${KIP_FLAGS:-$DEFAULT_FLAGS} "$@"
