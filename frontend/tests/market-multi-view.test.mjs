import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { pickNewsItems } from '../src/utils/marketBoard.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

/**
 * 行情页与多市场工作台的接线守卫。
 *
 * 行情页只保留大盘/贵金属总览与黄金主图；A股/美股/加密标的 CRUD 必须独立在“多市场”。
 */
test('market page mounts market overview instead of the multi-market workbench', () => {
  const page = read('pages/MarketPage.vue')
  const app = read('App.vue')

  assert.match(page, /import MarketOverviewBoard from '\.\.\/components\/market\/MarketOverviewBoard\.vue'/)
  assert.match(page, /<MarketOverviewBoard :active="active" @select="handleResearchTarget" \/>/)
  assert.match(page, /<MarketWatchlistBoard :active="active" :user="user" @context-change="handleResearchTarget" \/>/)
  assert.match(page, /<MarketNewsBoard :active="active" \/>/)
  assert.doesNotMatch(page, /MultiMarketBoard/)
  assert.match(app, /'多市场': \(\) => import\('\.\/components\/CrossMarketView\.vue'\)/)
  // 原来的黄金主图/侧栏仍保留。
  assert.match(page, /market-primary-layout/)
  assert.match(page, /<QuoteStrip[\s\S]*@select="handleQuoteSelect"/)
  assert.match(page, /class="market-rail market-inspector"/)
})

test('market overview uses the aggregated global pulse endpoint and horizontal carousel', () => {
  const board = read('components/market/MarketOverviewBoard.vue')
  const client = read('api/client.js')

  assert.match(board, /api\.marketOverview\(\)/)
  assert.match(client, /marketOverview: \(\) => get\(API_BASE, '\/api\/market\/overview'\)/)
  assert.match(board, /class="pulse-track"/)
  assert.match(board, /window\.setInterval[\s\S]*scrollByCard\(1\)/)
  assert.match(board, /emit\('select'/)
})

test('market home watchlist supports direct code add, file import and research-context promotion', () => {
  const board = read('components/market/MarketWatchlistBoard.vue')

  assert.match(board, /aria-label="输入股票代码"/)
  assert.match(board, /placeholder="输入代码：600519 \/ AAPL \/ BTCUSDT"/)
  assert.match(board, /async function addCode\(\)/)
  assert.match(board, /api\.resolveMarketInstrument\(market, symbol\)/)
  assert.match(board, /从文件导入/)
  assert.match(board, /accept="\.json,\.csv,\.txt/)
  assert.match(board, /function parseImport\(text\)/)
  assert.match(board, /api\.resolveMarketInstrument\(row\.market, row\.symbol\)/)
  assert.match(board, /api\.saveMarketPreferences\(value\)/)
  assert.match(board, /emit\('context-change'/)
  assert.match(board, /sourceModule: 'market-watchlist'/)
})

test('market news accepts the Java daily-news payload shape instead of dropping data.items', () => {
  const items = pickNewsItems({
    available: true,
    items: [
      {
        title: 'Market headline',
        url: 'https://example.com/news/1',
        source: 'Example Wire',
        published: '2026-09-18T17:08:01Z',
      },
    ],
  }, 12)

  assert.equal(items.length, 1)
  assert.equal(items[0].title, 'Market headline')
  assert.equal(items[0].source, 'Example Wire')
  assert.equal(items[0].linkable, true)
})

test('market news is a dedicated section below the watchlist', () => {
  const board = read('components/market/MarketNewsBoard.vue')

  assert.match(board, /NEWS_FETCH_LIMIT = 40/)
  assert.match(board, /PAGE_SIZE = 8/)
  assert.match(board, /api\.newsDaily\(NEWS_FETCH_LIMIT, force, rankingMode\.value\)/)
  assert.match(board, /api\.newsTranslate\(titles\)/)
  assert.match(board, /translationSequence/)
  assert.match(board, /正在中文化标题/)
  assert.match(board, /pickNewsItems/)
  assert.match(board, /MARKET BRIEFING/)
  assert.match(board, /市场要闻/)
  assert.match(board, /visibleItems/)
  assert.match(board, /上一页/)
  assert.match(board, /下一页/)
  assert.match(board, /showOriginal = ref\(false\)/)
  assert.match(board, /显示原文/)
  assert.match(board, /显示中文/)
})

test('market news links are external-safe and guarded by the normalized linkable flag', () => {
  const board = read('components/market/MarketNewsBoard.vue')

  const anchor = board.match(/<a\s[^>]*>/g) || []
  assert.equal(anchor.length, 1, '市场要闻区应只有一处 <a> 渲染')
  assert.match(anchor[0], /v-if="item\.linkable"/)
  assert.match(anchor[0], /target="_blank"/)
  assert.match(anchor[0], /rel="noopener noreferrer"/)
  assert.doesNotMatch(board, /<a\s+:href="item\.url"(?![^>]*v-if)/)
})

test('api client exposes daily news and non-blocking title translation endpoints', () => {
  const client = read('api/client.js')

  assert.match(client, /newsDaily: \(limit = 12, force = false, ranking = 'smart'\) => get\(API_BASE, '\/api\/news\/daily'/)
  // 必须带 refresh/force，否则后端不会触发抓取，前端"抓取"按钮会假成功
  assert.match(client, /refresh: true, force, ranking/)
  assert.match(client, /newsTranslate: \(titles\) => post\(API_BASE, '\/api\/news\/translate'/)
})

test('cross-market instrument CRUD and selection are wired to persisted preferences', () => {
  const view = read('components/CrossMarketView.vue')
  const list = read('components/market/InstrumentList.vue')

  // Create / Read(search) / Update / Delete 都必须存在。
  assert.match(view, /新增标的/)
  assert.match(view, /resolveCustomInstrument/)
  assert.match(list, /placeholder="搜索名称 \/ 代码"/)
  assert.match(view, /saveEditedInstrument/)
  assert.match(view, /removeFromWatchlist/)
  assert.match(view, /api\.saveMarketPreferences\(preferences\)/)

  // 切换标的时先废弃旧请求，再立即加载新标的；不能只靠 watcher 间接刷新。
  assert.match(view, /async function selectInstrument\(symbol\)/)
  assert.match(view, /latestDataRequest\.invalidate\(\)/)
  assert.match(view, /await nextTick\(\)[\s\S]*await loadData\(\)/)
  assert.match(view, /async function reconcileSelection\(\)/)
  assert.match(view, /await reconcileSelection\(\)/)
  assert.doesNotMatch(view, /watch\(\[selectedSymbol, interval\]/)
  assert.match(view, /market\.value !== requestMarket[\s\S]*selectedSymbol\.value !== requestSymbol/)
})

test('unavailable market pulse and news degrade independently', () => {
  const overview = read('components/market/MarketOverviewBoard.vue')
  const news = read('components/market/MarketNewsBoard.vue')

  assert.match(overview, /item\.available === false/)
  assert.match(overview, /error\.value = e\?\.message/)
  assert.match(news, /payload\.available === false/)
  assert.match(news, /state\.value = 'unavailable'/)
  assert.match(news, /state\.value = items\.value\.length \? 'ready' : 'empty'/)
})
