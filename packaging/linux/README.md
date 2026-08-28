# Kip on Linux (Omarchy / Arch + Hyprland)

Kip ships packaged for Windows only. There is no cross-build: `better-sqlite3`
is a native addon, so a Linux build has to be produced **on a Linux machine**.
These scripts do that, mirroring the Windows hand-assembly in `docs/BUILD.md`
(electron-forge's packager is unreliable on current Node).

## Prereqs

```bash
sudo pacman -S --needed nodejs npm yarn jdk-openjdk clojure rsync git base-devel
```

## Build + run

```bash
cd app
chmod +x packaging/linux/*.sh     # once (git may not preserve the mode from Windows)
packaging/linux/build.sh          # -> app/out/Kip-linux-x64/
out/Kip-linux-x64/kip             # run it directly
```

`build.sh` compiles the ClojureScript (a `compile`, not a `release` — the
release asset-path points at a CDN), runs `gulp build` to sync the bundled
`scripts/`, rebuilds `better-sqlite3` for the Electron ABI, and assembles the
runnable folder.

## Install (no root)

```bash
packaging/linux/install.sh        # -> ~/.local/opt/kip + ~/.local/bin/kip + .desktop + icon
packaging/linux/install.sh --uninstall
```

Make sure `~/.local/bin` is on `PATH` (Omarchy default shell is fish:
`fish_add_path ~/.local/bin`).

## Install (system package)

```bash
cd app/packaging/linux && makepkg -si   # -> /opt/kip + /usr/bin/kip
```

## Wayland / Hyprland

The `kip` launcher passes `--ozone-platform-hint=auto` (Wayland when
`WAYLAND_DISPLAY` is set, else XWayland) and `--enable-features=WaylandWindowDecorations`.
Override per-run with `KIP_FLAGS`:

```bash
KIP_FLAGS="--ozone-platform=x11" kip        # force XWayland
KIP_FLAGS="--ozone-platform=wayland --disable-gpu" kip
```

If windows have no titlebar under Hyprland that's expected — add a rule in
`~/.config/hypr/` or use the in-app window controls.

## `kip://` links

The `.desktop` file registers the `x-scheme-handler/kip` MIME type. After
install, `xdg-mime default kip.desktop x-scheme-handler/kip` if links don't
open.

## Verifying the bundled retrieval layer

```bash
ELECTRON_RUN_AS_NODE=1 KIP_COOP_ROOT=/path/to/a/graph \
  ~/.local/opt/kip/kip ~/.local/opt/kip/resources/app.asar.unpacked/scripts/hatch-all.js --preview
```

Then launch the GUI and exercise Peck / Hatch sources / Deep groom / LLM
settings — those all shell out to `resources/app.asar.unpacked/scripts/` and were the parts
that broke on Linux before the path-separator fix (`electron.wiki` /
`electron.llm` / `electron.skills` now use `path.join`, not `\\`).
