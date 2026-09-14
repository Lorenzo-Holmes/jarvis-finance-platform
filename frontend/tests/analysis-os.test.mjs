import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')

function read(relativePath) {
  return fs.readFileSync(path.join(src, relativePath), 'utf8')
}

test('analysis OS is the authenticated workspace entry without replacing existing routes', () => {
  const tabs = read('composables/useWorkspaceTabs.js')
  const app = read('App.vue')

  assert.match(tabs, /const DEFAULT_TAB = '研究终端'/)
  assert.match(tabs, /'行情'/)
  assert.match(tabs, /'模拟盘'/)
  assert.match(tabs, /'研究助手'/)
  assert.match(app, /AnalysisOsPage/)
  assert.match(app, /@navigate="switchTab"/)
  assert.match(app, /<SimTradeView :user="user"/)
})

test('analysis OS uses Three.js while preserving original JARVIS branding', () => {
  const page = read('pages/AnalysisOsPage.vue')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')

  assert.match(page, /JARVIS \/ ANALYSIS OS/)
  assert.match(page, /市场研究档案终端/)
  assert.match(page, /AnalysisArchiveScene/)
  assert.match(scene, /from 'three'/)
  assert.match(scene, /WebGLRenderer/)
  assert.match(scene, /Raycaster/)
  assert.match(scene, /ResizeObserver/)
  assert.match(scene, /forceContextLoss/)
  assert.doesNotMatch(page, /莱茵生命|明日方舟|RHINE LAB/i)
  assert.doesNotMatch(scene, /莱茵生命|明日方舟|RHINE LAB/i)
})

test('analysis OS consumes existing market APIs and keeps a labeled fallback', () => {
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(page, /api\.marketInstruments\(\)/)
  assert.match(page, /api\.marketPreferences\(\)/)
  assert.match(page, /api\.marketAssetQuote/)
  assert.match(page, /已进入降级模式/)
  assert.match(page, /不包含实时价格或投资结论/)
  assert.match(page, /prefers-reduced-motion/)
})

test('analysis OS exposes existing research and trading routes', () => {
  const page = read('pages/AnalysisOsPage.vue')
  for (const route of ['多市场', '研究助手', '财报解析', '产业链图谱', '风险预警', '模拟盘']) {
    assert.match(page, new RegExp(route))
  }
})
