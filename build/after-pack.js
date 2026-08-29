// electron-builder rebuilds the app's node_modules list from its package.json
// and drops the retrieval layer's OWN nested node_modules (scripts/node_modules
// — gray-matter, @anthropic-ai/…, argparse, …). Neither `files` globs nor
// `extraResources` filters bring it back reliably. So `scripts/` is excluded
// from the asar (electron-builder.yml `!scripts`) and copied in whole here,
// after the app is laid out — a plain recursive fs copy, no filtering.
//
// Lands at resources/app.asar.unpacked/scripts, the same path the folder
// build's `asar pack --unpack-dir scripts` produces, so electron.wiki is
// identical on both.

const fs = require('fs')
const path = require('path')

exports.default = async function afterPack (context) {
  const src = path.join(context.packager.projectDir, 'static', 'scripts')
  const dst = path.join(context.appOutDir, 'resources', 'app.asar.unpacked', 'scripts')

  if (!fs.existsSync(src)) {
    throw new Error(`after-pack: retrieval layer not found at ${src}`)
  }
  await fs.promises.cp(src, dst, { recursive: true })

  for (const probe of [
    'lib/skills.js',
    'node_modules/gray-matter/package.json',
    // vendored by packaging/*/build.sh (Electron-ABI, not in scripts/package.json)
    'node_modules/better-sqlite3/build/Release/better_sqlite3.node',
    'node_modules/bindings/package.json'
  ]) {
    if (!fs.existsSync(path.join(dst, probe))) {
      throw new Error(`after-pack: ${probe} missing after copy`)
    }
  }
  console.log(`  • retrieval layer copied to ${path.relative(context.appOutDir, dst)}`)
}
