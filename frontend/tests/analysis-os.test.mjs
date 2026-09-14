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

test('analysis OS keeps the product brand original and exposes research routing', () => {
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(page, /JARVIS \/ ANALYSIS OS/)
  assert.match(page, /市场研究档案终端/)
  assert.match(page, /AI 深度研究/)
  assert.match(page, /财报解析/)
  assert.match(page, /产业链/)
  assert.match(page, /风险预警/)
  assert.match(page, /模拟交易/)
  assert.doesNotMatch(page, /莱茵生命|明日方舟|RHINE LAB/i)
})

test('analysis OS labels PoC numbers as non-real-time placeholders', () => {
  const page = read('pages/AnalysisOsPage.vue')
  assert.match(page, /行情数字用于界面占位，不作为实时价格或投资依据/)
  assert.match(page, /prefers-reduced-motion/)
})
