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

test('subscription configuration can collapse without discarding draft state', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const subscriptionOpen = ref\(true\)/)
  assert.match(page, /v-show="subscriptionOpen"/)
  assert.match(page, /:aria-expanded="subscriptionOpen"/)
  assert.match(page, /收起配置/)
  assert.match(page, /展开配置/)
})

test('source choices visualize existing credibility without recomputing it', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /class="credibility-meter"/)
  assert.match(page, /Number\(source\.credibility\)/)
  assert.match(page, /\.credibility-meter em/)
})

test('topic filters retain checkbox semantics with chip interactions', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /:class="\{ active: selectedTopics\.includes\(topic\.key\) \}"/)
  assert.match(page, /\.topic-choice input \{ position: absolute; opacity: 0/)
  assert.match(page, /\.topic-choice\.active/)
  assert.match(page, /\.topic-choice:focus-within/)
})

test('news feedback uses non-layout-shifting floating toasts', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /\.notice \{ position: fixed/)
  assert.match(page, /@keyframes news-toast-in/)
  assert.match(page, /backdrop-filter: blur\(16px\)/)
})

test('news center ignores stale async preference and digest responses', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /let preferenceRequestSeq = 0/)
  assert.match(page, /let digestRequestSeq = 0/)
  assert.match(page, /requestSeq !== preferenceRequestSeq/)
  assert.match(page, /requestSeq !== digestRequestSeq/)
  assert.match(page, /onBeforeUnmount/)
})

test('refreshing keeps existing news visible with an in-place progress signal', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /class="feed-panel panel" :class="\{ refreshing \}"/)
  assert.match(page, /v-if="refreshing && articles\.length"/)
  assert.match(page, /正在重新整理精选/)
  assert.match(page, /\.feed-panel\.refreshing \.article-list/)
})

test('AI analysis keeps summary visible and discloses secondary details on demand', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const expandedAnalysis = ref\(\[\]\)/)
  assert.match(page, /function toggleAnalysis/)
  assert.match(page, /分析详情/)
  assert.match(page, /:aria-expanded="analysisOpen\(article\)"/)
  assert.match(page, /class="analysis-details"/)
})

test('news center filters the current result set locally without mutating backend ranking', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const feedQuery = ref\(''\)/)
  assert.match(page, /const filteredArticles = computed/)
  assert.match(page, /v-for="(?:article|\(article, index\)) in filteredArticles"/)
  assert.match(page, /aria-label="筛选当前资讯"/)
  assert.match(page, /当前结果中没有匹配项/)
})

test('news center offers comfortable and compact reading density without changing data', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const feedDensity = ref\('comfortable'\)/)
  assert.match(page, /aria-label="资讯阅读密度"/)
  assert.match(page, /紧凑/)
  assert.match(page, /:class="\{ compact: feedDensity === 'compact' \}"/)
  assert.match(page, /\.article-list\.compact/)
})

test('news surfaces support keyboard search and visible focus states', () => {
  const page = read('pages/NewsCenterPage.vue')
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(page, /const feedSearchRef = ref\(null\)/)
  assert.match(page, /event\.key === '\/'/)
  assert.match(page, /event\.key === 'Escape'/)
  assert.match(page, /aria-keyshortcuts="\/"/)
  assert.match(page, /window\.removeEventListener\('keydown', handleNewsShortcut\)/)
  assert.match(page, /button:focus-visible/)
  assert.match(board, /button:focus-visible/)
})

test('news surfaces share workspace motion tokens and respect reduced motion', () => {
  const page = read('pages/NewsCenterPage.vue')
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(page, /--news-motion-surface: var\(--ds-motion-surface/)
  assert.match(page, /\.feed-panel > \.panel-title \{ position: sticky/)
  assert.match(page, /prefers-reduced-motion: reduce/)
  assert.match(board, /--news-motion-surface: var\(--ds-motion-surface/)
  assert.match(board, /prefers-reduced-motion: reduce/)
})

test('news center ranking control uses a sliding selection lens', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /--ranking-index/)
  assert.match(page, /\.ranking-switch::before/)
  assert.match(page, /transform: translateX\(calc\(var\(--ranking-index\) \* 100%\)\)/)
})

test('market ranking control mirrors the sliding selection lens language', () => {
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(board, /--ranking-index/)
  assert.match(board, /\.ranking-switch::before/)
  assert.match(board, /translateX\(calc\(var\(--ranking-index\) \* 100%\)\)/)
})

test('reading density control uses a continuous selection lens', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /--density-index/)
  assert.match(page, /\.density-switch::before/)
  assert.match(page, /translateX\(calc\(var\(--density-index\) \* 100%\)\)/)
})

test('news articles use a bounded staggered reveal', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /--article-index/)
  assert.match(page, /Math\.min\(index, 8\)/)
  assert.match(page, /@keyframes news-article-in/)
  assert.match(page, /18ms/)
})

test('news feed animates article insertion removal and reordering', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /<TransitionGroup name="article-flow"/)
  assert.match(page, /\.article-flow-move/)
  assert.match(page, /\.article-flow-leave-active \{ position: absolute/)
})
