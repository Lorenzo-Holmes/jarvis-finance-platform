import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('business modules use the archive workspace shell instead of the legacy tab chrome', () => {
  const app = read('App.vue')
  const shell = read('analysis-os/components/ArchiveWorkspaceShell.vue')

  assert.match(app, /ArchiveWorkspaceShell/)
  assert.match(app, /v-if="activeModule"/)
  assert.match(app, /@return="returnToArchive"/)
  assert.match(app, /@navigate-module="navigateWorkspace"/)
  assert.match(app, /<AppTabs v-if="activeTab === '管理'"/)
  assert.doesNotMatch(app, /<AppTabs v-if="activeTab !== '研究终端'"/)
  assert.match(shell, /RETURN TO ARCHIVE/)
  assert.match(shell, /ARCHIVE WORKSPACE/)
  assert.match(shell, /workspace-module-index/)
  assert.match(shell, /navigate-module/)
  assert.match(shell, /--panel: #efebe3/)
  assert.match(shell, /--radius: 0px/)
})

test('global research context is shared by market and execution workspaces', () => {
  const app = read('App.vue')
  const store = read('analysis-os/state/researchContext.js')
  const market = read('pages/MarketPage.vue')
  const cross = read('components/CrossMarketView.vue')
  const sim = read('components/SimTradeView.vue')

  assert.match(store, /const context = ref\(null\)/)
  assert.match(store, /function setContext/)
  assert.match(app, /:context="researchContext"/)
  assert.match(app, /@context-change="setResearchContext"/)
  assert.match(market, /context-change/)
  assert.match(cross, /context-change/)
  assert.match(sim, /context-change/)
})

test('archive transition is progress-driven and preserves the extracted card for workspace return', () => {
  const transition = read('analysis-os/motion/useArchiveTransition.js')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(transition, /EXTRACTING/)
  assert.match(transition, /WORKSPACE_ACTIVE/)
  assert.match(transition, /RETURN_ALIGN/)
  assert.match(transition, /RETURN_DESCEND/)
  assert.match(scene, /0\.42 \+ extraction \* \(4\.05 - 0\.42\)/)
  assert.match(scene, /camera\.position\.copy\(cameraBase\)\.lerp\(cameraDetailBase, detail\)/)
  assert.match(page, /archiveTransition\.enter/)
  assert.match(page, /archiveTransition\.returnToArchive/)
})

test('sleep and wake remain a visual layer over the logical archive selection', () => {
  const idle = read('analysis-os/motion/useArchiveIdle.js')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(idle, /dimStartMs: 8_000/)
  assert.match(idle, /motionStartMs: 12_000/)
  assert.match(idle, /sleepStartMs: 18_000/)
  assert.match(idle, /SLEEP_DRIFT/)
  assert.match(scene, /sleepOffsets/)
  assert.match(scene, /captureSleepPosition/)
  assert.match(page, /__jarvisArchiveDebug/)
})

test('representative workspace pages use the archive information language', () => {
  assert.match(read('pages/MarketPage.vue'), /MARKET \/ LIVE FEED/)
  assert.match(read('components/CrossMarketView.vue'), /CROSS MARKET \/ OBSERVATORY/)
  assert.match(read('pages/BacktestPage.vue'), /BACKTEST \/ STRATEGY LABORATORY/)
  assert.match(read('components/SimTradeView.vue'), /EXECUTION \/ SIM TRADING/)
  assert.match(read('components/AiCenter.vue'), /RESEARCH INTELLIGENCE \/ ANALYSIS DESK/)
  assert.match(read('pages/SentimentPage.vue'), /BULL \/ BEAR DOSSIER/)
  assert.match(read('pages/FinancialReportPage.vue'), /COMPANY FILE \/ FINANCIAL FILING/)
  assert.match(read('pages/ChainPage.vue'), /INDUSTRY GRAPH \/ NODE DOSSIER/)
  assert.match(read('pages/RiskPage.vue'), /RISK SURVEILLANCE \/ ALERT TERMINAL/)
  assert.match(read('pages/StrategyPage.vue'), /STRATEGY FOUNDRY \/ OBJECTIVE BUILDER/)
  assert.match(read('components/OpsView.vue'), /SYSTEM OPERATIONS \/ SERVICE TOPOLOGY/)
})
