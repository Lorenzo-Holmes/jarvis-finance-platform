import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('news center exposes smart/latest ranking switch through API client', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const rankingMode = ref\('smart'\)/)
  assert.match(page, /api\.newsDaily\(24, force, rankingMode\.value\)/)
  assert.match(page, /智能精选/)
  assert.match(page, /最新发布/)
  assert.match(page, /aria-pressed/)
})

test('market news board keeps ranking mode aligned with paged feed', () => {
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(board, /const rankingMode = ref\('smart'\)/)
  assert.match(board, /api\.newsDaily\(NEWS_FETCH_LIMIT, force, rankingMode\.value\)/)
  assert.match(board, /async function changeRanking/)
  assert.match(board, /page\.value = 1/)
  assert.match(board, /aria-label="市场要闻排序方式"/)
})

test('news center surfaces quality telemetry without requiring it', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const qualityMetrics = ref\(null\)/)
  assert.match(page, /data\.quality_metrics/)
  assert.match(page, /跨源确认/)
  assert.match(page, /重复折叠/)
  assert.match(page, /来源覆盖/)
  assert.match(page, /v-if="qualityMetrics"/)
})

test('market news normalization preserves explainable intelligence fields', () => {
  const utils = read('utils/marketBoard.js')
  assert.match(utils, /sourceIds:/)
  assert.match(utils, /sourceCount:/)
  assert.match(utils, /rankScore:/)
  assert.match(utils, /hybridScore:/)
  assert.match(utils, /selectionReason:/)
})

test('market news renders optional score and cross-source confirmation badges', () => {
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(board, /function displayScore/)
  assert.match(board, /精选 \{\{ displayScore\(item\) \}\}/)
  assert.match(board, /多源 ×\{\{ item\.sourceCount \}\}/)
  assert.match(board, /class="intelligence-badges"/)
})

test('news center exposes selection reasons as an on-demand disclosure', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const expandedReasons = ref\(\[\]\)/)
  assert.match(page, /function toggleReason/)
  assert.match(page, /为什么入选/)
  assert.match(page, /:aria-expanded="reasonOpen\(article\)"/)
  assert.match(page, /article\.selection_reason/)
  assert.match(page, /class="reason-panel"/)
})

test('news rows use restrained hover and focus-within interaction rails', () => {
  const page = read('pages/NewsCenterPage.vue')
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(page, /\.article-row::before/)
  assert.match(page, /\.article-row:hover, \.article-row:focus-within/)
  assert.match(board, /\.news-item::before/)
  assert.match(board, /\.news-item:hover, \.news-item:focus-within/)
})

test('subscription editor exposes deterministic unsaved state', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const savedSources = ref\(\[\]\)/)
  assert.match(page, /const subscriptionDirty = computed/)
  assert.match(page, /有未保存更改/)
  assert.match(page, /:disabled="saving \|\| !subscriptionDirty"/)
})
