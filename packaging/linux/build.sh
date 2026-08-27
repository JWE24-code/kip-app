#!/usr/bin/env bash
#
# Build a runnable Kip folder for Linux (x64) — out/Kip-linux-x64/.
#
# Run this ON the Linux machine (Omarchy / Arch). It mirrors the Windows
# hand-assembly documented in docs/BUILD.md, because electron-forge's packager
# is unreliable on recent Node. Nothing here is cross-compilable from Windows:
# better-sqlite3 is a native addon and has to be built on the target.
#
# Prereqs (Arch / Omarchy):  pacman -S --needed nodejs npm yarn jdk-openjdk \
#                                     clojure rsync git base-devel
#
# Usage:   app/packaging/linux/build.sh            # full build
#          SKIP_DEPS=1 app/packaging/linux/build.sh  # reuse installed deps
#
set -euo pipefail

ELECTRON_VERSION="41.7.1"          # keep in sync with static/node_modules/electron
ARCH="x64"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$here/../.." && pwd)"        # .../app
REPO_DIR="$(cd "$APP_DIR/.." && pwd)"       # repo root (has scripts/, package.json)
OUT="$APP_DIR/out/Kip-linux-$ARCH"

step () { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# --- 1. dependencies -------------------------------------------------------
if [[ "${SKIP_DEPS:-}" != "1" ]]; then
  step "retrieval-layer deps (repo root)"
  ( cd "$REPO_DIR" && npm install --no-audit --no-fund )

  step "app deps (app/)"
  ( cd "$APP_DIR" && yarn --frozen-lockfile )
fi

# --- 2. gulp: clean static/, sync bundled scripts + assets + css ----------
# Must run BEFORE the cljs compile: `gulp build` starts with `clean`, which
# wipes static/** (keeping only node_modules + yarn.lock). Same order as the
# canonical `yarn release` (run-s gulp:build cljs:*).
step "gulp build (clean + syncScripts + assets + css)"
( cd "$APP_DIR" && NODE_ENV=production npx gulp build )

# --- 3. compile ClojureScript -------------------------------------------------
# compile, NOT release: the :app :release asset-path points at asset.logseq.com,
# which is wrong for a local Electron bundle, and the packaged electron.js is a
# dev loader that reads .shadow-cljs/builds/electron/dev/out/cljs-runtime. The
# Windows 0.1 build is a compile build too — see docs/BUILD.md.
step "compile :app + :electron"
( cd "$APP_DIR" && clojure -M:cljs compile app electron )

# --- 4. native addon for the Electron ABI ---------------------------------
step "rebuild better-sqlite3 for Electron $ELECTRON_VERSION"
( cd "$APP_DIR/static" && npx "@electron/rebuild@4.0.1" -v "$ELECTRON_VERSION" -f --only better-sqlite3 )

# --- 5. assemble out/Kip-linux-x64/ --------------------------------------
step "assemble $OUT"
DIST="$APP_DIR/static/node_modules/electron/dist"
[[ -d "$DIST" ]] || { echo "electron dist missing at $DIST — run 'yarn' in app/ first"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"
cp -a "$DIST"/. "$OUT"/
mv "$OUT/electron" "$OUT/kip"                       # the main binary
chmod +x "$OUT/kip"
rm -f "$OUT/resources/default_app.asar"             # so Electron loads resources/app

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
  --exclude='/node_modules/typescript/' \
  --exclude='/node_modules/webpack/' \
  "$APP_DIR/static/" "$APP/"

# electron.js is the shadow-cljs dev loader: it reads
# <resources>/.shadow-cljs/builds/electron/dev/out/cljs-runtime at runtime.
step "copy electron cljs-runtime"
RT="$OUT/resources/.shadow-cljs/builds/electron/dev/out"
mkdir -p "$RT"
cp -a "$APP_DIR/.shadow-cljs/builds/electron/dev/out/cljs-runtime" "$RT/"

# stamp the version from version.cljs
VERSION="$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+' "$APP_DIR/src/main/frontend/version.cljs" | head -1)"
node -e "const p='$APP/package.json',j=require(p);j.version='$VERSION';require('fs').writeFileSync(p,JSON.stringify(j,null,2))"

step "done — $OUT  (Kip $VERSION)"
echo "run it:   $OUT/kip"
echo "install:  app/packaging/linux/install.sh"
