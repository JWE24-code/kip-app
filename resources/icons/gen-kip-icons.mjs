#!/usr/bin/env node
/**
 * Regenerate Kip's desktop app icons from the Split Roost mark (the cobalt tile
 * + white egg — the same mark the PWA and website use). Run this when the mark
 * changes; the outputs are committed so a build never needs the tooling.
 *
 *   node resources/icons/gen-kip-icons.mjs
 *
 * Needs `rsvg-convert` (librsvg) and `magick` (ImageMagick 7) on PATH.
 *
 * Writes:
 *   build/icon.ico            Windows app / installer / uninstaller (multi-res)
 *   build/icon.png            Linux AppImage / tar.gz  (512)
 *   resources/icon.png        Linux installer + PKGBUILD hicolor icon (1024)
 *   resources/icon_monochrome.png  flat cobalt egg silhouette (1024)
 *   resources/icons/logseq.{png,ico}       the runtime / electron-forge icons *
 *   resources/icons/logseq_big_sur.{png,ico}  (same, kept for the forge config) *
 *   resources/img/logo.png    the web-UI favicon / apple-touch-icon (192)
 *   resources/img/folder-logo.png  the "open a folder" onboarding graphic
 *
 * (*) the `logseq*` filenames are unchanged — electron main hardcodes
 *     `icons/logseq.png` and resources/forge.config.js names the rest; only
 *     the bytes change.
 */
import { mkdtemp, writeFile, rm, copyFile, readFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

const COBALT = '#0148c6'

/** The Split Roost mark — a full-bleed cobalt tile with a white egg + cobalt
 *  yolk. The 24×24 viewBox scales to any target size. */
function markSvg (size) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24">
  <rect width="24" height="24" fill="${COBALT}"/>
  <path d="M12 5.2c-2.2 0-4.6 3.4-4.6 7 0 2.8 2 4.6 4.6 4.6s4.6-1.8 4.6-4.6c0-3.6-2.4-7-4.6-7Z" fill="#fff" fill-opacity="0.92"/>
  <circle cx="12" cy="12.4" r="1.7" fill="${COBALT}"/>
</svg>`
}

/** A cobalt folder with the white egg rising out of it — the onboarding
 *  "open a folder" graphic. */
function folderSvg (w, h) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 366 244">
  <path d="M183 18c-25 0-47 42-47 78 0 31 22 52 47 52s47-21 47-52c0-36-22-78-47-78z" fill="#fff" fill-opacity="0.92"/>
  <circle cx="183" cy="92" r="11" fill="${COBALT}"/>
  <path d="M44 96h92l20 22h126a20 20 0 0 1 20 20v72a20 20 0 0 1-20 20H44a20 20 0 0 1-20-20v-94a20 20 0 0 1 20-20z" fill="${COBALT}"/>
</svg>`
}

const tmp = await mkdtemp(join(tmpdir(), 'kip-icons-'))

async function png (svg, size, out) {
  const svgFile = join(tmp, `s${size}.svg`)
  await writeFile(svgFile, svg)
  execFileSync('rsvg-convert', ['-w', String(size), '-h', String(size), '-o', out, svgFile])
  console.log(`  ${out.replace(ROOT + '/', '')}  ${size}px`)
}

// square PNGs
await png(markSvg(512), 512, join(tmp, 'sq-512.png'))
await png(markSvg(1024), 1024, join(ROOT, 'resources', 'icon.png'))
await copyFile(join(tmp, 'sq-512.png'), join(ROOT, 'build', 'icon.png'))
await png(markSvg(192), 192, join(ROOT, 'resources', 'img', 'logo.png'))

for (const name of ['logseq.png', 'logseq_big_sur.png', join('canary', 'logseq.png'), join('canary', 'logseq_big_sur.png')]) {
  await copyFile(join(tmp, 'sq-512.png'), join(ROOT, 'resources', 'icons', name))
}

// monochrome silhouette (cobalt egg on transparent) — tray / template use
{
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 24 24">
  <path d="M12 5.2c-2.2 0-4.6 3.4-4.6 7 0 2.8 2 4.6 4.6 4.6s4.6-1.8 4.6-4.6c0-3.6-2.4-7-4.6-7Z" fill="${COBALT}"/>
</svg>`
  const f = join(tmp, 'mono.svg')
  await writeFile(f, svg)
  execFileSync('rsvg-convert', ['-w', '1024', '-h', '1024', '-o', join(ROOT, 'resources', 'icon_monochrome.png'), f])
  console.log('  resources/icon_monochrome.png  1024px')
}

// Windows .ico — assembled by hand as an all-PNG ICO (valid on Windows Vista+
// and what electron-builder / NSIS expect for the 256). ImageMagick on Linux
// won't PNG-compress ICO entries, so we pack the directory ourselves.
async function buildIco (outPath, sizes) {
  const entries = []
  for (const s of sizes) {
    const p = join(tmp, `icoe-${s}.png`)
    await png(markSvg(s), s, p)
    entries.push({ s, data: await readFile(p) })
  }
  const header = Buffer.alloc(6 + entries.length * 16)
  header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2)
  header.writeUInt16LE(entries.length, 4)
  let offset = header.length
  entries.forEach((e, i) => {
    const d = 6 + i * 16
    header.writeUInt8(e.s >= 256 ? 0 : e.s, d)
    header.writeUInt8(e.s >= 256 ? 0 : e.s, d + 1)
    header.writeUInt16LE(1, d + 4) // planes
    header.writeUInt16LE(32, d + 6) // bpp
    header.writeUInt32LE(e.data.length, d + 8)
    header.writeUInt32LE(offset, d + 12)
    offset += e.data.length
  })
  await writeFile(outPath, Buffer.concat([header, ...entries.map((e) => e.data)]))
}
for (const out of ['build/icon.ico', 'resources/icons/logseq.ico', 'resources/icons/logseq_big_sur.ico', 'resources/icons/canary/logseq.ico', 'resources/icons/canary/logseq_big_sur.ico']) {
  await buildIco(join(ROOT, out), [16, 24, 32, 48, 64, 128, 256])
}
console.log('  *.ico  256/128/64/48/32/24/16 (all-PNG)')

// macOS .icns (resources/icons/logseq*.icns) are NOT regenerated here — a
// proper .icns needs `iconutil` (macOS) or `png2icns`, and Kip has no macOS
// build, so the stale files are inert. Generate them on a Mac if that changes.

// onboarding folder graphic (wide)
{
  const svgFile = join(tmp, 'folder.svg')
  await writeFile(svgFile, folderSvg(732, 488))
  execFileSync('rsvg-convert', ['-w', '732', '-h', '488', '-o', join(ROOT, 'resources', 'img', 'folder-logo.png'), svgFile])
  console.log('  resources/img/folder-logo.png  732x488')
}

await rm(tmp, { recursive: true, force: true })
console.log('done — commit the changed files under build/ and resources/')
