<#
Build a runnable Kip folder for Windows (x64) -> app\out\Kip-win32-x64\.

Runs on Windows (or a windows-latest GitHub runner). Mirrors packaging/linux/
build.sh and the hand-assembly in docs/BUILD.md — electron-forge's packager is
unreliable on recent Node, and better-sqlite3 is a native addon that must be
built on the target.

Layout it expects:  <root>\app (this repo) and <root>\scripts (the kip
retrieval-layer repo) as siblings — gulp's syncScripts reads ..\scripts.

Prereqs: Node 20, Yarn, a JDK, the Clojure CLI, MSVC build tools (for the
better-sqlite3 native rebuild).

Env:
  SKIP_DEPS=1        reuse app\node_modules (skip `yarn` in app\)
  KIP_CLJS=release   `clojure -M:cljs release` instead of `compile` (optimized;
                     may deadlock on some machines — default is `compile`)
#>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ELECTRON_VERSION = '41.7.1'
$here    = $PSScriptRoot
$APP_DIR = (Resolve-Path "$here\..\..").Path                 # ...\app
$ROOT    = (Resolve-Path "$APP_DIR\..").Path                 # sibling of app\
$OUT     = "$APP_DIR\out\Kip-win32-x64"
$CLJS    = if ($env:KIP_CLJS) { $env:KIP_CLJS } else { 'compile' }

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function RC {
  param([Parameter(Mandatory)][string]$Src,
        [Parameter(Mandatory)][string]$Dst,
        [string[]]$Flags = @())
  & robocopy $Src $Dst @Flags /NFL /NDL /NJH /NJS /NP | Out-Null
  $code = $LASTEXITCODE
  $global:LASTEXITCODE = 0
  if ($code -ge 8) { throw "robocopy '$Src' -> '$Dst' failed (exit $code)" }
}

if (-not (Test-Path "$ROOT\scripts")) { throw "no scripts\ next to app\ (checkout the kip repo there)" }

# --- 1. app deps -------------------------------------------------------------
if ($env:SKIP_DEPS -ne '1') {
  Step 'app deps (app\)'
  Push-Location $APP_DIR; yarn install --frozen-lockfile --network-timeout 600000; Pop-Location
  if ($LASTEXITCODE) { throw 'yarn (app) failed' }
}

# --- 2. gulp build (clean static\, sync resources/assets/scripts/css) ------
Step 'gulp build'
Push-Location $APP_DIR
$env:NODE_ENV = 'production'
npx gulp build
Pop-Location
if ($LASTEXITCODE) { throw 'gulp build failed' }

# --- 3. static\ runtime deps + Electron ----------------------------------
# --ignore-scripts: skip every install/postinstall — several static\ deps
# (electron-deeplink, canvas, exe-icon-extractor) run node-gyp on install and
# fail on a bare CI runner (their bundled node-pre-gyp can't spawn under Node
# 22, or need VS detection that doesn't work). We don't need their native
# parts: electron-deeplink's binding is macOS-only, canvas is optional, and
# better-sqlite3 is rebuilt explicitly below. Electron's own binary download
# (its postinstall) is re-run by hand.
# NOT --frozen-lockfile: static\package.json is regenerated from
# resources\package.json each build and the committed static\yarn.lock can lag it.
Step "static\ deps"
Push-Location "$APP_DIR\static"; yarn install --ignore-scripts --network-timeout 600000; Pop-Location
if ($LASTEXITCODE) { throw 'yarn (static) failed' }
Step "download Electron $ELECTRON_VERSION"
Push-Location "$APP_DIR\static"; node node_modules/electron/install.js; Pop-Location
if ($LASTEXITCODE) { throw 'electron download failed' }

# --- 4. compile ClojureScript ------------------------------------------
# see packaging/linux/build.sh for the compile-vs-release rationale.
Step "cljs $CLJS :app + :electron"
Push-Location $APP_DIR
if ($CLJS -eq 'release') {
  clojure -J-Xmx5g -M:cljs release app electron --debug
} else {
  clojure -J-Xmx5g -M:cljs compile app electron
}
Pop-Location
if ($LASTEXITCODE) { throw "cljs $CLJS failed" }

# --- 5. better-sqlite3 for the Electron ABI ---------------------------
Step "rebuild better-sqlite3 for Electron $ELECTRON_VERSION"
Push-Location "$APP_DIR\static"
npx --yes "@electron/rebuild@4.0.1" -v $ELECTRON_VERSION -f --only better-sqlite3
Pop-Location
if ($LASTEXITCODE) { throw 'better-sqlite3 rebuild failed' }

# --- 6. assemble out\Kip-win32-x64\ -----------------------------------
Step "assemble $OUT"
$DIST = "$APP_DIR\static\node_modules\electron\dist"
if (-not (Test-Path "$DIST\electron.exe")) { throw "electron dist missing at $DIST" }

if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Force $OUT | Out-Null
RC "$DIST" "$OUT" @('/E')
Rename-Item "$OUT\electron.exe" 'Kip.exe'
Remove-Item "$OUT\resources\default_app.asar" -Force -ErrorAction SilentlyContinue

# English locale only — Electron falls back to en-US; the other ~50 .pak
# files are ~12 MB of dead weight (#37).
if (Test-Path "$OUT\locales") {
  Get-ChildItem "$OUT\locales" -Filter *.pak | Where-Object Name -ne 'en-US.pak' | Remove-Item -Force
}

$APP = "$OUT\resources\app"
New-Item -ItemType Directory -Force $APP | Out-Null
RC "$APP_DIR\static" "$APP" @(
  '/MIR', '/XF', '*.map',
  '/XD',
    "$APP_DIR\static\out",
    "$APP_DIR\static\.shadow-cljs",
    "$APP_DIR\static\node_modules\electron",
    "$APP_DIR\static\node_modules\@electron-forge",
    "$APP_DIR\static\node_modules\@electron",
    "$APP_DIR\static\node_modules\electron-forge",
    "$APP_DIR\static\node_modules\electron-builder",
    "$APP_DIR\static\node_modules\app-builder-bin",
    "$APP_DIR\static\node_modules\typescript",
    "$APP_DIR\static\node_modules\webpack"
)
Remove-Item "$APP\tests.js","$APP\gen-malli-kondo-config.js" -Force -ErrorAction SilentlyContinue

if ($CLJS -eq 'compile') {
  Step 'copy electron cljs-runtime (compile build)'
  $RT = "$OUT\resources\.shadow-cljs\builds\electron\dev\out\cljs-runtime"
  New-Item -ItemType Directory -Force (Split-Path $RT) | Out-Null
  RC "$APP_DIR\.shadow-cljs\builds\electron\dev\out\cljs-runtime" "$RT" @('/E')
}

$VERSION = (Select-String -Path "$APP_DIR\src\main\frontend\version.cljs" -Pattern '\d+\.\d+\.\d+').Matches[0].Value
$pkg = "$APP\package.json"
$j = Get-Content $pkg -Raw | ConvertFrom-Json
$j.version = $VERSION
($j | ConvertTo-Json -Depth 20) + "`n" | Set-Content $pkg -NoNewline

# --- 7. pack resources\app -> app.asar -----------------------------------
# One archive instead of ~15k loose files: faster to unzip, faster to load.
# scripts\ stays unpacked (electron.wiki spawns `node scripts\*.js` by path —
# can't cwd into or exec out of an asar) and so do native .node addons
# (better-sqlite3). asar auto-creates app.asar.unpacked\ for the --unpack* set.
Step 'pack app.asar'
# @electron/asar is a static\ devDep -> its bin lands at a stable path. Run it
# through node (the .bin\ shim isn't linked the same on every OS). If any of
# this goes wrong we MUST fail loudly: a missing app.asar makes upload-artifact
# silently drop the now-empty resources\ dir and ship a codeless app.
$ASAR_JS = "$APP_DIR\static\node_modules\@electron\asar\bin\asar.js"
if (-not (Test-Path $ASAR_JS)) { throw "@electron/asar CLI missing at $ASAR_JS" }
node "$ASAR_JS" pack "$APP" "$OUT\resources\app.asar" `
  --unpack-dir "{scripts,node_modules/better-sqlite3}" --unpack "*.node"
if ($LASTEXITCODE) { throw "asar pack failed (exit $LASTEXITCODE)" }
if (-not (Test-Path "$OUT\resources\app.asar")) { throw 'asar pack produced no app.asar' }
if (-not (Test-Path "$OUT\resources\app.asar.unpacked\scripts\hatch-all.js")) {
  throw 'scripts\ was not unpacked from the asar'
}
Remove-Item "$APP" -Recurse -Force

# --- 8. Authenticode sign (optional) --------------------------------------
# Set KIP_SIGN_CMD to a signing command; each target path is appended to it.
# e.g. Azure Trusted Signing:
#   KIP_SIGN_CMD='signtool sign /v /fd SHA256 /tr http://timestamp.acs.microsoft.com /td SHA256 /dlib "C:\...\Azure.CodeSigning.Dlib.dll" /dmdf "C:\...\metadata.json"'
# See .claude/RELEASE-signing.md. No-op when unset.
if ($env:KIP_SIGN_CMD) {
  Step 'Authenticode sign (Kip.exe + root DLLs)'
  $targets = @("$OUT\Kip.exe") + (Get-ChildItem "$OUT" -Filter *.dll | ForEach-Object FullName)
  foreach ($t in $targets) {
    cmd /c "$env:KIP_SIGN_CMD `"$t`""
    if ($LASTEXITCODE) { throw "signing failed for $t (exit $LASTEXITCODE)" }
  }
  Write-Host "signed $($targets.Count) file(s)"
} else {
  Write-Host "not signed (KIP_SIGN_CMD unset) — see .claude/RELEASE-signing.md"
}

Step "done — $OUT  (Kip $VERSION, cljs:$CLJS)"
Write-Host "run: $OUT\Kip.exe"
