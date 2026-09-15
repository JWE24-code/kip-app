// electron-builder rebuilds the app's node_modules list from its package.json
// and drops nested node_modules that aren't declared there: the retrieval
// layer's scripts/node_modules and the sidecar's sidecar/node_modules. Neither
// `files` globs nor `extraResources` filters bring them back reliably. So both
// dirs are excluded from the asar (electron-builder.yml) and copied in whole
// here, after the app is laid out — a plain recursive fs copy, no filtering.
//
// Land at resources/app.asar.unpacked/{scripts,sidecar}, the same paths the
// folder build's `asar pack --unpack-dir` produces, so electron.wiki and
// electron.sidecar are identical on both paths.

const fs = require('fs')
const path = require('path')

const TREES = [
  {
    name: 'retrieval layer',
    src: ['static', 'scripts'],
    dst: ['scripts'],
    probes: [
      'lib/skills.js',
      'node_modules/gray-matter/package.json',
      // vendored by packaging/*/build.sh (Electron-ABI, not in scripts/package.json)
      'node_modules/better-sqlite3/build/Release/better_sqlite3.node',
      'node_modules/bindings/package.json'
    ]
  },
  {
    name: 'sidecar',
    src: ['static', 'sidecar'],
    dst: ['sidecar'],
    probes: [
      'index.ts',
      'server/ws.ts',
      'node_modules/ws/package.json',
      'node_modules/zod/package.json',
      'node_modules/gray-matter/package.json',
      // vendored by packaging/*/build (Electron-ABI, not installed by gulp)
      'node_modules/better-sqlite3/build/Release/better_sqlite3.node'
    ]
  }
]

exports.default = async function afterPack (context) {
  for (const tree of TREES) {
    const src = path.join(context.packager.projectDir, ...tree.src)
    const dst = path.join(context.appOutDir, 'resources', 'app.asar.unpacked', ...tree.dst)

    if (!fs.existsSync(src)) {
      throw new Error(`after-pack: ${tree.name} not found at ${src}`)
    }
    await fs.promises.cp(src, dst, { recursive: true })

    for (const probe of tree.probes) {
      if (!fs.existsSync(path.join(dst, probe))) {
        throw new Error(`after-pack: ${probe} missing after copying ${tree.name}`)
      }
    }
    console.log(`  • ${tree.name} copied to ${path.relative(context.appOutDir, dst)}`)
  }
}
