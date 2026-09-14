const fs = require('fs')
const utils = require('util')
const cp = require('child_process')
const exec = utils.promisify(cp.exec)
const path = require('path')
const gulp = require('gulp')
const cleanCSS = require('gulp-clean-css')
const del = require('del')
const ip = require('ip')

const outputPath = path.join(__dirname, 'static')
const resourcesPath = path.join(__dirname, 'resources')
const publicStaticPath = path.join(__dirname, 'public/static')
const sourcePath = path.join(__dirname, 'src/main/frontend')
const resourceFilePath = path.join(resourcesPath, '**')
const outputFilePath = path.join(outputPath, '**')

// Kip's coop-maintenance scripts live in ../scripts (a sibling of app/). The
// in-app Peck/Hatch/Groom features spawn them (electron.wiki), so they must
// ride along inside the package — synced to static/scripts, which becomes
// <app>/scripts once packaged. Source (*.js + lib/ + skills/ — SKILL.md and
// templates included) only; their deps are installed into static/scripts/
// node_modules from scripts/package.json by syncScripts.
const scriptsSrcPath = path.join(__dirname, '..', 'scripts')
const scriptsGlob = [
  path.join(scriptsSrcPath, '*.js'),
  path.join(scriptsSrcPath, 'lib', '**', '*.js'),
  path.join(scriptsSrcPath, 'skills', '**', '*'),
  path.join(scriptsSrcPath, 'package.json')
]

// The persistent WS sidecar (kip-app#147) lives in the kip repo's sidecar/
// (a sibling of its scripts/). It ships at <app>/sidecar — next to scripts/ so
// its `require('../scripts/lib/paths.js')` resolves, and its own node_modules
// install locally because a plain Node child spawned from app.asar.unpacked
// can't resolve modules out of the packed asar. Skip the tests.
const sidecarSrcPath = path.join(__dirname, '..', 'sidecar')
const sidecarGlob = [
  path.join(sidecarSrcPath, '**', '*.ts'),
  path.join(sidecarSrcPath, '**', '*.cjs'),
  '!' + path.join(sidecarSrcPath, 'test', '**', '*')
]

// The sidecar's runtime deps are derived from the sidecar's own source (the
// bare imports in its *.ts/*.cjs), with versions read from the kip repo's
// package.json — so a new import can't ship without its dependency, and a
// version bump in the kip repo flows through without editing this file.
// better-sqlite3 is native and deliberately excluded: under Electron it must be
// the Electron-ABI build, which packaging/*/build vendors next to the app (same
// as scripts/), never a fresh Node-ABI install in sidecar/node_modules.
const KIP_ROOT_PKG = path.join(__dirname, '..', 'package.json')
const NATIVE_SIDECAR_DEPS = new Set(['better-sqlite3'])
const SIDECAR_DEPS_FALLBACK = ['@anthropic-ai/sdk', 'gray-matter', 'isomorphic-git', 'ws', 'zod']

function readKipDependencyVersions () {
  try {
    return JSON.parse(fs.readFileSync(KIP_ROOT_PKG, 'utf8')).dependencies || {}
  } catch {
    return {}
  }
}

function walkSidecarFiles (dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'test') continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walkSidecarFiles(full))
    else if (/\.(ts|cjs|mjs)$/.test(entry.name)) out.push(full)
  }
  return out
}

function sidecarDependencyNames () {
  if (!fs.existsSync(sidecarSrcPath)) return []
  let files
  try {
    files = walkSidecarFiles(sidecarSrcPath)
  } catch {
    return SIDECAR_DEPS_FALLBACK
  }
  const names = new Set()
  const importRe = /(?:from\s+|require\(\s*)['"](@?[^'"./][^'"]*)['"]/g
  for (const file of files) {
    const src = fs.readFileSync(file, 'utf8')
    let m
    while ((m = importRe.exec(src)) !== null) {
      const raw = m[1]
      const name = raw.startsWith('@') ? raw.split('/').slice(0, 2).join('/') : raw.split('/')[0]
      if (!name.startsWith('node:') && !NATIVE_SIDECAR_DEPS.has(name)) names.add(name)
    }
  }
  const found = [...names].sort()
  return found.length ? found : SIDECAR_DEPS_FALLBACK
}

function sidecarDeps () {
  const versions = readKipDependencyVersions()
  const deps = {}
  for (const name of sidecarDependencyNames()) {
    deps[name] = versions[name] || '*'
  }
  return deps
}

const css = {
  watchCSS () {
    return cp.spawn(`yarn css:watch`, {
      shell: true,
      stdio: 'inherit'
    })
  },

  buildCSS (...params) {
    return gulp.series(
      () => exec(`yarn css:build`, {}),
      css._optimizeCSSForRelease
    )(...params)
  },

  _optimizeCSSForRelease () {
    return gulp.src(path.join(outputPath, 'css', 'style.css'))
      .pipe(cleanCSS())
      .pipe(gulp.dest(path.join(outputPath, 'css')))
  }
}

const common = {
  clean () {
    return del(['./static/**/*', '!./static/yarn.lock', '!./static/node_modules'])
  },

  syncResourceFile () {
    return gulp.src(resourceFilePath).pipe(gulp.dest(outputPath))
  },

  // NOTE: All assets from node_modules are copied to the output directory
  syncAssetFiles (...params) {
    return gulp.series(
      () => gulp.src([
        './node_modules/@excalidraw/excalidraw/dist/excalidraw-assets/**',
        '!**/*/i18n-*.js'
      ]).pipe(gulp.dest(path.join(outputPath, 'js', 'excalidraw-assets'))),
      () => gulp.src([
        'node_modules/katex/dist/katex.min.js',
        'node_modules/katex/dist/contrib/mhchem.min.js',
        'node_modules/html2canvas/dist/html2canvas.min.js',
        'node_modules/interactjs/dist/interact.min.js',
        'node_modules/photoswipe/dist/umd/*.js',
        'node_modules/reveal.js/dist/reveal.js',
        'node_modules/shepherd.js/dist/js/shepherd.min.js',
        'node_modules/marked/marked.min.js',
        'node_modules/@highlightjs/cdn-assets/highlight.min.js',
        'node_modules/@isomorphic-git/lightning-fs/dist/lightning-fs.min.js',
        'packages/amplify/dist/amplify.js'
      ]).pipe(gulp.dest(path.join(outputPath, 'js'))),
      () => gulp.src([
        'node_modules/pdfjs-dist/legacy/build/pdf.mjs',
        'node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs',
        'node_modules/pdfjs-dist/legacy/web/pdf_viewer.mjs',
      ]).pipe(gulp.dest(path.join(outputPath, 'js', 'pdfjs'))),
      () => gulp.src([
        'node_modules/pdfjs-dist/cmaps/*.*',
      ]).pipe(gulp.dest(path.join(outputPath, 'js', 'pdfjs', 'cmaps'))),
      () => gulp.src([
        'node_modules/@tabler/icons/iconfont/tabler-icons.min.css',
        'node_modules/inter-ui/inter.css',
        'node_modules/reveal.js/dist/theme/fonts/source-sans-pro/**',
      ]).pipe(gulp.dest(path.join(outputPath, 'css'))),
      () => gulp.src('node_modules/inter-ui/Inter (web)/*.*')
        .pipe(gulp.dest(path.join(outputPath, 'css', 'Inter (web)'))),
      () => gulp.src([
        'node_modules/@tabler/icons/iconfont/fonts/**',
        'node_modules/katex/dist/fonts/*.woff2'
      ]).pipe(gulp.dest(path.join(outputPath, 'css', 'fonts'))),
    )(...params)
  },

  keepSyncResourceFile () {
    return gulp.watch(resourceFilePath, { ignoreInitial: true }, common.syncResourceFile)
  },

  syncScripts (...params) {
    const dest = path.join(outputPath, 'scripts')
    return gulp.series(
      // nodir: skills/**/* matches directories too; skip them (dest is created by the files).
      // All skill files that ship are text (SKILL.md, *.js, *.json); docx/pptx
      // templates are user-supplied in the coop, not bundled here — so no
      // { encoding: false } needed. Add it if a binary asset is ever vendored.
      () => gulp.src(scriptsGlob, { base: scriptsSrcPath, nodir: true }).pipe(gulp.dest(dest)),
      (cb) => {
        // Pure-JS runtime deps (gray-matter/dotenv/@anthropic-ai/edn-data) —
        // better-sqlite3 is deliberately NOT here; the bundled scripts resolve
        // it from the app's own (Electron-ABI) node_modules. Re-install when the
        // tree is missing OR package.json changed since the last install (a new
        // dependency was added), otherwise reuse it.
        const lock = path.join(dest, 'node_modules', '.package-lock.json')
        const stale = !fs.existsSync(lock) ||
          fs.statSync(path.join(dest, 'package.json')).mtimeMs > fs.statSync(lock).mtimeMs
        if (stale) {
          cp.execSync('npm install --omit=dev --no-audit --no-fund --loglevel=error', { cwd: dest, stdio: 'inherit' })
        }
        cb()
      }
    )(...params)
  },

  keepSyncScripts () {
    return gulp.watch(scriptsGlob, { ignoreInitial: true }, common.syncScripts)
  },

  syncSidecar (...params) {
    const dest = path.join(outputPath, 'sidecar')
    const deps = sidecarDeps()
    return gulp.series(
      // nodir: the source tree is all files (no shipped subdirs to preserve
      // beyond the glob structure). test/ is excluded above.
      () => gulp.src(sidecarGlob, { base: sidecarSrcPath, nodir: true }).pipe(gulp.dest(dest)),
      (cb) => {
        if (!Object.keys(deps).length) {
          // No sidecar/ next to app/ (a plain dev tree): leave the app to fall
          // back to :wikiChat rather than installing an unrelated dep set.
          console.warn('[syncSidecar] no sidecar/ source found next to app/ — skipping dependency install')
          return cb()
        }
        // Rebuild the sidecar's package.json only when the dep set changes, so
        // the mtime-vs-lock staleness check below doesn't reinstall every run.
        const pkgPath = path.join(dest, 'package.json')
        const pkg = {
          name: 'kip-sidecar',
          version: '0.0.0',
          private: true,
          // The sidecar is ESM (.ts run through Node's type stripping).
          type: 'module',
          dependencies: deps
        }
        const next = JSON.stringify(pkg, null, 2) + '\n'
        const prev = fs.existsSync(pkgPath) ? fs.readFileSync(pkgPath, 'utf8') : ''
        if (prev !== next) fs.writeFileSync(pkgPath, next)

        const lock = path.join(dest, 'node_modules', '.package-lock.json')
        const stale = !fs.existsSync(lock) ||
          fs.statSync(pkgPath).mtimeMs > fs.statSync(lock).mtimeMs
        if (stale) {
          cp.execSync('npm install --omit=dev --no-audit --no-fund --loglevel=error', { cwd: dest, stdio: 'inherit' })
        }
        cb()
      }
    )(...params)
  },

  keepSyncSidecar () {
    return gulp.watch(sidecarGlob, { ignoreInitial: true }, common.syncSidecar)
  },

  syncAllStatic () {
    return gulp.src([
      outputFilePath,
      '!' + path.join(outputPath, 'node_modules/**')
    ]).pipe(gulp.dest(publicStaticPath))
  },

  syncJS_CSSinRt () {
    return gulp.src([
      path.join(outputPath, 'js/**'),
      path.join(outputPath, 'css/**')
    ], { base: outputPath }).pipe(gulp.dest(publicStaticPath))
  },

  keepSyncStaticInRt () {
    return gulp.watch([
      path.join(outputPath, 'js/**'),
      path.join(outputPath, 'css/**')
    ], { ignoreInitial: true }, common.syncJS_CSSinRt)
  },

  async runCapWithLocalDevServerEntry (cb) {
    const mode = process.env.PLATFORM || 'ios'

    const IP = ip.address()
    const LOGSEQ_APP_SERVER_URL = `http://${IP}:3001`

    if (typeof global.fetch === 'function') {
      try {
        await fetch(LOGSEQ_APP_SERVER_URL)
      } catch (e) {
        return cb(new Error(`/* ❌ Please check if the service is ON. (${LOGSEQ_APP_SERVER_URL}) ❌ */`))
      }
    }

    console.log(`------ Cap ${mode.toUpperCase()} -----`)
    console.log(`Dev serve at: ${LOGSEQ_APP_SERVER_URL}`)
    console.log(`--------------------------------------`)

    cp.execSync(`npx cap sync ${mode}`, {
      stdio: 'inherit',
      env: Object.assign(process.env, {
        LOGSEQ_APP_SERVER_URL
      })
    })

    cp.execSync(`rm -rf ios/App/App/public/static/out`, {
      stdio: 'inherit'
    })


    cp.execSync(`npx cap run ${mode} --external`, {
      stdio: 'inherit',
      env: Object.assign(process.env, {
        LOGSEQ_APP_SERVER_URL
      })
    })

    cb()
  }
}

exports.electron = () => {
  if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
    cp.execSync('yarn', {
      cwd: outputPath,
      stdio: 'inherit'
    })
  }

  cp.execSync('yarn electron:dev', {
    cwd: outputPath,
    stdio: 'inherit'
  })
}

exports.electronMaker = async () => {
  cp.execSync('yarn cljs:release-electron', {
    stdio: 'inherit'
  })

  const pkgPath = path.join(outputPath, 'package.json')
  const pkg = require(pkgPath)
  const version = fs.readFileSync(path.join(__dirname, 'src/main/frontend/version.cljs'))
    .toString().match(/[0-9.]{3,}/)[0]

  if (!version) {
    throw new Error('release version error in src/**/*/version.cljs')
  }

  pkg.version = version
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2))

  if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
    cp.execSync('yarn', {
      cwd: outputPath,
      stdio: 'inherit'
    })
  }

  cp.execSync('yarn electron:make', {
    cwd: outputPath,
    stdio: 'inherit'
  })
}

exports.cap = common.runCapWithLocalDevServerEntry
exports.clean = common.clean
exports.watch = gulp.series(common.syncResourceFile, common.syncAssetFiles, common.syncAllStatic, common.syncScripts, common.syncSidecar,
  gulp.parallel(common.keepSyncResourceFile, common.keepSyncScripts, common.keepSyncSidecar, css.watchCSS))
exports.build = gulp.series(common.clean, common.syncResourceFile, common.syncAssetFiles, common.syncScripts, common.syncSidecar, css.buildCSS)

// Like electronMaker but produces an unpackaged, directly-runnable app folder
// (static/out/Kip-win32-x64/) instead of an installer — for local testing.
exports.electronPackage = async () => {
  cp.execSync('yarn cljs:release-electron', { stdio: 'inherit' })

  const pkgPath = path.join(outputPath, 'package.json')
  const pkg = require(pkgPath)
  const version = fs.readFileSync(path.join(__dirname, 'src/main/frontend/version.cljs'))
    .toString().match(/[0-9.]{3,}/)[0]
  if (!version) throw new Error('release version error in src/**/*/version.cljs')
  pkg.version = version
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2))

  if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
    cp.execSync('yarn', { cwd: outputPath, stdio: 'inherit' })
  }

  cp.execSync('npx electron-forge package', { cwd: outputPath, stdio: 'inherit' })
}
