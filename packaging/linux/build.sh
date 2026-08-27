#!/usr/bin/env bash
#
# Build a runnable Kip folder for Linux (x64) -> app/out/Kip-linux-x64/.
#
# Runs on a Linux machine (Omarchy / Arch) or an ubuntu-latest GitHub runner.
# Mirrors the Windows hand-assembly in docs/BUILD.md — electron-forge's packager
# is unreliable on recent Node, and better-sqlite3 is a native addon that must
# be built on the target (no cross-compile).
#
# Layout it expects:  <root>/app  (this repo)  and  <root>/scripts  (the kip
# retrieval-layer repo) as siblings — gulp's syncScripts reads ../scripts.
#
# Prereqs (Arch):  pacman -S --needed nodejs npm yarn jdk-openjdk clojure rsync git base-devel
#
# Env:
#   SKIP_DEPS=1        reuse an already-installed app/node_modules (skip `yarn` in app/)
#   KIP_CLJS=release   use `clojure -M:cljs release` instead of `compile` (optimized,
#                      but may deadlock on some machines — default is `compile`)
#
set -euo pipefail

ELECTRON_VERSION="41.7.1"
ARCH="x64"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$here/../.." && pwd)"        # .../app
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"       # sibling of app/ — must contain scripts/
OUT="$APP_DIR/out/Kip-linux-$ARCH"
CLJS_MODE="${KIP_CLJS:-compile}"

step () { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

[[ -d "$ROOT_DIR/scripts" ]] || { echo "FATAL: no scripts/ next to app/ (checkout the kip repo there)"; exit 1; }

# --- 1. app deps ---------------------------------------------------------------
if [[ "${SKIP_DEPS:-}" != "1" ]]; then
  step "app deps (app/)"
  ( cd "$APP_DIR" && yarn install --frozen-lockfile --network-timeout 600000 )
fi

# --- 2. gulp: clean static/, sync resources + assets + bundled scripts + css --
# Runs BEFORE the cljs compile: `gulp build` starts with `clean`, wiping
# static/** (keeps only node_modules + yarn.lock). Also copies resources/* ->
# static/ (incl. package.json, electron.html, forge.config.js) and runs
# `npm install --omit=dev` in static/scripts for the bundled retrieval layer.
step "gulp build"
( cd "$APP_DIR" && NODE_ENV=production npx gulp build )

# --- 3. static/ runtime deps + Electron ------------------------------------
# static/package.json now exists (gulp copied it). Its deps are the app's
# Electron runtime + the forge/electron/rebuild toolchain; the `electron`
# package's postinstall downloads the Electron binary, and `install-app-deps`
# (electron-builder, a postinstall here) rebuilds better-sqlite3 against
# Electron's ABI.
# NOT --frozen-lockfile: static/package.json is regenerated from
# resources/package.json each build and the committed static/yarn.lock can lag it.
step "static/ deps + Electron $ELECTRON_VERSION"
( cd "$APP_DIR/static" && yarn install --network-timeout 600000 )

# --- 4. compile ClojureScript ---------------------------------------------
# Default `compile` (not `release`): the :app :release asset-path points at a
# CDN, and the packaged electron.js is a dev loader that reads
# .shadow-cljs/builds/electron/dev/out/cljs-runtime. The Windows 0.1 build is
# a compile build too (docs/BUILD.md). Set KIP_CLJS=release to try optimized.
step "cljs $CLJS_MODE :app + :electron"
( cd "$APP_DIR" && clojure -J-Xmx5g -M:cljs "$CLJS_MODE" app electron )

# --- 5. belt-and-suspenders: better-sqlite3 for the Electron ABI ----------
# static/'s postinstall (install-app-deps) usually handles this; re-run to be sure.
step "rebuild better-sqlite3 for Electron $ELECTRON_VERSION"
( cd "$APP_DIR/static" && npx --yes "@electron/rebuild@4.0.1" -v "$ELECTRON_VERSION" -f --only better-sqlite3 )

# --- 6. assemble out/Kip-linux-x64/ -------------------------------------
step "assemble $OUT"
DIST="$APP_DIR/static/node_modules/electron/dist"
[[ -d "$DIST" ]] || { echo "FATAL: electron dist missing at $DIST"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"
cp -a "$DIST"/. "$OUT"/
mv "$OUT/electron" "$OUT/kip"
chmod +x "$OUT/kip"
rm -f "$OUT/resources/default_app.asar"

APP="$OUT/resources/app"
mkdir -p "$APP"
rsync -a --delete \
  --exclude='*.map' \
  --exclude='/out/' \
  --exclude='/tests.js' \
  --exclude='/gen-malli-kondo-config.js' \
  --exclude='/.shadow-cljs/' \
  --exclude='/node_modules/electron/' \
  --exclude='/node_modules/@electron-forge/' \
  --exclude='/node_modules/@electron/' \
  --exclude='/node_modules/electron-forge/' \
  --exclude='/node_modules/electron-builder/' \
  --exclude='/node_modules/app-builder-bin/' \
  --exclude='/node_modules/typescript/' \
  --exclude='/node_modules/webpack/' \
  "$APP_DIR/static/" "$APP/"

# the dev electron.js loader reads <resources>/.shadow-cljs/.../cljs-runtime
if [[ "$CLJS_MODE" == "compile" ]]; then
  step "copy electron cljs-runtime (compile build)"
  RT="$OUT/resources/.shadow-cljs/builds/electron/dev/out"
  mkdir -p "$RT"
  cp -a "$APP_DIR/.shadow-cljs/builds/electron/dev/out/cljs-runtime" "$RT/"
fi

VERSION="$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+' "$APP_DIR/src/main/frontend/version.cljs" | head -1)"
node -e "const p='$APP/package.json',j=require(p);j.version='$VERSION';require('fs').writeFileSync(p,JSON.stringify(j,null,2)+'\n')"

step "done — $OUT  (Kip $VERSION, cljs:$CLJS_MODE)"
echo "run:      $OUT/kip"
echo "install:  bash $here/install.sh"
