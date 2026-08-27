import fs from 'fs'
import path from 'path'
import os from 'os'
import { test as base, expect } from '@playwright/test'
import { ElectronApplication, Page, _electron as electron } from 'playwright'
import { modKey } from './util/basic'

// Keyboard mindmap mode (frontend.handler.mindmap): Tab = child, Enter = sibling,
// Shift+Tab / arrows = navigate, Backspace = prune leaf, Mod+Shift+M = arrange.
//
// Self-contained: launches its own Electron with a throwaway user-data dir and a
// fresh graph folder, so it doesn't depend on the shared fixtures' graph-load
// dance (which is brittle on Windows).

let app: ElectronApplication
let page: Page

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kip-mindmap-e2e-'))
const graphDir = path.join(tmp, 'graph')
const userDataDir = path.join(tmp, 'user-data')
fs.mkdirSync(graphDir, { recursive: true })

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

  // open the throwaway graph folder via the mocked directory picker
  await page.evaluate((p) => { (window as any).__MOCKED_OPEN_DIR_PATH__ = p }, graphDir)
  const chooseFolder = page.locator('strong:has-text("Choose a folder")')
  if (await chooseFolder.isVisible()) {
    await chooseFolder.click()
  } else {
    // already in a graph — add ours
    await page.click('#left-menu.button').catch(() => {})
    await page.click('#left-sidebar #repo-switch')
    await page.click('text=Add new graph')
  }
  await page.evaluate(() => { (window as any).__MOCKED_OPEN_DIR_PATH__ = '' })
  await page.waitForSelector(':has-text("Parsing files")', { state: 'hidden', timeout: 120_000 })
  await page.waitForFunction('["Logseq","Kip"].includes(document.title)', null, { timeout: 60_000 })
  await page.waitForTimeout(1500)

  // make sure the left sidebar (with the Whiteboards nav entry) is open
  const sidebar = page.locator('#left-sidebar')
  if (!/is-open/.test((await sidebar.getAttribute('class')) || '')) {
    await page.click('#left-menu.button')
    await expect(sidebar).toHaveClass(/is-open/)
  }
})

test.afterAll(async () => { await app?.close() })

// Read the active tldraw app's state from the page.
const tldraw = (fn: string) => page.evaluate(`(() => {
  const el = document.querySelector('.logseq-tldraw[data-tlapp]');
  const app = window.tlapps[el.dataset.tlapp];
  return (${fn})(app);
})()`)

const selectedLabels = () => tldraw(`a => a.selectedShapesArray.map(s => s.props.label)`)

const lineEdges = () => tldraw(`a => {
  const p = a.currentPage;
  const boxes = Object.fromEntries(p.shapes.filter(s => s.props.type === 'box').map(s => [s.id, s.props.label]));
  return p.shapes.filter(s => s.props.type === 'line').map(line => {
    const sb = p.bindings[line.props.handles.start.bindingId];
    const eb = p.bindings[line.props.handles.end.bindingId];
    return boxes[sb.toId] + ' -> ' + boxes[eb.toId];
  }).sort();
}`)

test('enable whiteboards + open a new board with one node', async () => {
  if ((await page.$('.nav-header .whiteboard')) === null) {
    await page.click('#head .toolbar-dots-btn')
    await page.click('#head .dropdown-wrapper >> text=Settings')
    await page.click('.settings-modal a[data-id=features]')
    await page.click('text=Whiteboards >> .. >> .ui__toggle')
    await page.waitForTimeout(500)
    await page.keyboard.press('Escape')
  }
  // The very first whiteboard gets seeded with onboarding sample shapes.
  // Make one to absorb that, then work in a second, clean board.
  await page.locator('.nav-header .whiteboard').scrollIntoViewIfNeeded()
  await page.click('.nav-header .whiteboard')
  await page.waitForSelector('#tl-create-whiteboard', { timeout: 20_000 })
  await page.click('#tl-create-whiteboard')
  await page.waitForSelector('.logseq-tldraw', { timeout: 20_000 })
  await page.waitForTimeout(2500) // let onboarding population settle
  await page.click('.nav-header .whiteboard')
  await page.click('#tl-create-whiteboard')
  const canvas = await page.waitForSelector('.logseq-tldraw')
  await page.waitForTimeout(1500)
  await expect(page.locator('.tl-box-container')).toHaveCount(0) // clean board
  const b = (await canvas.boundingBox())!

  await page.mouse.click(b.x + 40, b.y + 40) // focus canvas
  await page.keyboard.type('wr')             // rectangle tool
  await page.mouse.move(b.x + 150, b.y + 170)
  await page.mouse.down()
  await page.mouse.move(b.x + 230, b.y + 185, { steps: 5 })
  await page.mouse.move(b.x + 280, b.y + 220, { steps: 5 })
  await page.mouse.up()

  try {
    await expect(page.locator('.tl-box-container')).toHaveCount(1, { timeout: 10_000 })
  } catch (e) {
    await page.screenshot({ path: 'test-results/mindmap-draw-fail.png' })
    throw e
  }
  await expect(page.locator('.tl-text-label-textarea')).toBeVisible()
  await page.keyboard.type('Root')
  await page.keyboard.press('Escape')
  await expect(page.locator('.tl-text-label-textarea')).toBeHidden()
  expect(await selectedLabels()).toEqual(['Root'])
})

test('Tab adds a connected child and starts editing it', async () => {
  await page.keyboard.press('Tab')
  await expect(page.locator('.tl-box-container')).toHaveCount(2)
  await expect(page.locator('.tl-line-container')).toHaveCount(1)
  await expect(page.locator('.tl-text-label-textarea')).toBeVisible()
  await page.keyboard.type('Child A')
  await page.keyboard.press('Escape')
  expect(await selectedLabels()).toEqual(['Child A'])
})

test('Enter adds a sibling under the same parent', async () => {
  await page.keyboard.press('Enter')
  await expect(page.locator('.tl-box-container')).toHaveCount(3)
  await expect(page.locator('.tl-line-container')).toHaveCount(2)
  await page.keyboard.type('Child B')
  await page.keyboard.press('Escape')
  expect(await lineEdges()).toEqual(['Root -> Child A', 'Root -> Child B'])
})

test('Shift+Tab selects the parent; arrows walk the tree', async () => {
  await page.keyboard.press('Shift+Tab')          // from Child B -> Root
  expect(await selectedLabels()).toEqual(['Root'])
  await page.keyboard.press('ArrowRight')         // Root -> a child
  expect(['Child A', 'Child B']).toContain((await selectedLabels())[0])
  await page.keyboard.press('ArrowLeft')          // back to Root
  expect(await selectedLabels()).toEqual(['Root'])
})

test('Mod+Shift+M arranges the tree without losing shapes', async () => {
  const before = await tldraw(`a => a.currentPage.shapes.filter(s => s.props.type==='box').map(s => s.props.point.join(','))`)
  await page.keyboard.press(`${modKey}+Shift+m`)
  await page.waitForTimeout(300)
  await expect(page.locator('.tl-box-container')).toHaveCount(3)
  const after = await tldraw(`a => a.currentPage.shapes.filter(s => s.props.type==='box').map(s => s.props.point.join(','))`)
  expect(after).not.toEqual(before)
  const layout = await tldraw(`a => {
    const by = Object.fromEntries(a.currentPage.shapes.filter(s=>s.props.type==='box').map(s=>[s.props.label, s.props.point[0]]));
    return { rootLeftOfA: by['Root'] < by['Child A'], rootLeftOfB: by['Root'] < by['Child B'] };
  }`)
  expect(layout).toEqual({ rootLeftOfA: true, rootLeftOfB: true })
})

test('Backspace on a leaf prunes it and reselects the parent', async () => {
  await tldraw(`a => { const b = a.currentPage.shapes.find(s => s.props.label === 'Child B'); a.api.selectShapes(b.id); }`)
  await page.waitForTimeout(50)
  await page.keyboard.press('Backspace')
  await expect(page.locator('.tl-box-container')).toHaveCount(2)
  await expect(page.locator('.tl-line-container')).toHaveCount(1)
  expect(await selectedLabels()).toEqual(['Root'])
})
