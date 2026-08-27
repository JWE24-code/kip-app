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
function RC($args) {
  & robocopy @args | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE): $($args -join ' ')" }
  $global:LASTEXITCODE = 0
}

if (-not (Test-Path "$ROOT\scripts")) { throw "no scripts\ next to app\ (checkout the kip repo there)" }

# --- 1. app deps -------------------------------------------------------------
if ($env:SKIP_DEPS -ne '1') {
  Step 'app deps (app\)'
  Push-Location $APP_DIR; yarn install --frozen-lockfile; Pop-Location
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
Step "static\ deps + Electron $ELECTRON_VERSION"
Push-Location "$APP_DIR\static"; yarn install --frozen-lockfile; Pop-Location
if ($LASTEXITCODE) { throw 'yarn (static) failed' }

# --- 4. compile ClojureScript ------------------------------------------
Step "cljs $CLJS :app + :electron"
Push-Location $APP_DIR; clojure -M:cljs $CLJS app electron; Pop-Location
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
RC @($DIST, $OUT, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
Rename-Item "$OUT\electron.exe" 'Kip.exe'
Remove-Item "$OUT\resources\default_app.asar" -Force -ErrorAction SilentlyContinue

$APP = "$OUT\resources\app"
New-Item -ItemType Directory -Force $APP | Out-Null
RC @("$APP_DIR\static", $APP, '/MIR', '/XF', '*.map',
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
       "$APP_DIR\static\node_modules\webpack",
     '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
Remove-Item "$APP\tests.js","$APP\gen-malli-kondo-config.js" -Force -ErrorAction SilentlyContinue

if ($CLJS -eq 'compile') {
  Step 'copy electron cljs-runtime (compile build)'
  $RT = "$OUT\resources\.shadow-cljs\builds\electron\dev\out\cljs-runtime"
  New-Item -ItemType Directory -Force (Split-Path $RT) | Out-Null
  RC @("$APP_DIR\.shadow-cljs\builds\electron\dev\out\cljs-runtime", $RT, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
}

$VERSION = (Select-String -Path "$APP_DIR\src\main\frontend\version.cljs" -Pattern '\d+\.\d+\.\d+').Matches[0].Value
$pkg = "$APP\package.json"
$j = Get-Content $pkg -Raw | ConvertFrom-Json
$j.version = $VERSION
($j | ConvertTo-Json -Depth 20) + "`n" | Set-Content $pkg -NoNewline

Step "done — $OUT  (Kip $VERSION, cljs:$CLJS)"
Write-Host "run: $OUT\Kip.exe"
