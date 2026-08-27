import fs from 'fs'
import path from 'path'
import os from 'os'
import { test as base, expect } from '@playwright/test'
import { ElectronApplication, Page, _electron as electron } from 'playwright'

// The Exports panel (frontend.components.exports) + the "How Kip works"
// collapsible on the home screen (frontend.components.container/kip-home-docs).
//
// Self-contained: its own Electron, a throwaway user-data dir and graph folder
// with a pre-seeded exports/ — same approach as mindmap.spec.ts (the shared
// fixtures' graph-load dance is brittle on Windows).

let app: ElectronApplication
let page: Page

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kip-exports-e2e-'))
const graphDir = path.join(tmp, 'graph')
const userDataDir = path.join(tmp, 'user-data')
const exportsDir = path.join(graphDir, 'exports')
fs.mkdirSync(exportsDir, { recursive: true })
fs.writeFileSync(path.join(exportsDir, 'q3-report.docx'), 'PK fake docx')
fs.writeFileSync(path.join(exportsDir, 'kickoff.pptx'), 'PK fake pptx')

const test = base.extend<{ page: Page }>({
  page: async ({}, use) => { await use(page) },
})

test.beforeAll(async () => {
  app = await electron.launch({
    cwd: './static',
    args: ['electron.js', `--user-data-dir=${userDataDir}`],
    locale: 'en',
    timeout: 60_000,
  })
  page = await app.firstWindow()
  page.on('pageerror', (e) => console.log('PAGEERROR:', e.message))
  await page.waitForLoadState('domcontentloaded')
  await page.waitForSelector(':has-text("Loading")', { state: 'hidden', timeout: 60_000 })

  await page.evaluate((p) => { (window as any).__MOCKED_OPEN_DIR_PATH__ = p }, graphDir)
  const chooseFolder = page.locator('strong:has-text("Choose a folder")')
  if (await chooseFolder.isVisible()) {
    await chooseFolder.click()
  } else {
    await page.click('#left-menu.button').catch(() => {})
    await page.click('#left-sidebar #repo-switch')
    await page.click('text=Add new graph')
  }
  await page.evaluate(() => { (window as any).__MOCKED_OPEN_DIR_PATH__ = '' })
  await page.waitForSelector(':has-text("Parsing files")', { state: 'hidden', timeout: 120_000 })
  await page.waitForFunction('["Logseq","Kip"].includes(document.title)', null, { timeout: 60_000 })
  await page.waitForTimeout(1500)
})

test.afterAll(async () => { await app?.close() })

test('home screen — the "How Kip works" documentation collapses open', async () => {
  const details = page.locator('details.kip-home-docs')
  await expect(details).toBeVisible()
  // starts closed
  await expect(details.locator('text=Getting started')).toBeHidden()
  await details.locator('summary:has-text("How Kip works")').click()
  await expect(details.locator('text=Getting started')).toBeVisible()
  await expect(details.locator('text=Skills — Peck\'s tools')).toBeVisible()
  await expect(details.locator('text=Whiteboards as mindmaps')).toBeVisible()
})

test('Exports panel — lists exports/, opens from the … menu', async () => {
  await page.click('#head .toolbar-dots-btn')
  await page.click('#head .dropdown-wrapper >> text=Exports')

  const panel = page.locator('.sidebar-item.item-type-exports')
  await expect(panel).toBeVisible({ timeout: 10_000 })
  await expect(panel.locator('text=2 files in exports/')).toBeVisible()
  await expect(panel.locator('button:has-text("q3-report.docx")')).toBeVisible()
  await expect(panel.locator('button:has-text("kickoff.pptx")')).toBeVisible()
})

test('Exports panel — delete asks first, cancel keeps the file', async () => {
  const panel = page.locator('.sidebar-item.item-type-exports')
  const row = panel.locator('div.group', { hasText: 'q3-report.docx' })
  await row.hover()
  // the delete (trash) button is the last action button in the row
  await row.locator('button[title="Delete"]').click()

  const modal = page.locator('.ui__confirm-modal')
  await expect(modal).toBeVisible()
  await expect(modal.locator('text=Delete q3-report.docx?')).toBeVisible()
  await modal.locator('button:has-text("Cancel")').click()

  await expect(panel.locator('button:has-text("q3-report.docx")')).toBeVisible()
  expect(fs.existsSync(path.join(exportsDir, 'q3-report.docx'))).toBe(true)
})

test('Exports panel — a new file shows up on the next poll', async () => {
  fs.writeFileSync(path.join(exportsDir, 'later.docx'), 'PK later')
  const panel = page.locator('.sidebar-item.item-type-exports')
  await expect(panel.locator('button:has-text("later.docx")')).toBeVisible({ timeout: 10_000 })
  await expect(panel.locator('text=3 files in exports/')).toBeVisible()
})
