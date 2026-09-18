import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

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
  assert.match(page, /<MarketOverviewBoard :active="active" @select-gold="handleQuoteSelect" \/>/)
  assert.doesNotMatch(page, /MultiMarketBoard/)
  assert.match(app, /'多市场': \(\) => import\('\.\/components\/CrossMarketView\.vue'\)/)
  // 原来的黄金主图/侧栏仍保留。
  assert.match(page, /market-primary-layout/)
  assert.match(page, /<QuoteStrip[\s\S]*@select="handleQuoteSelect"/)
  assert.match(page, /class="market-rail market-inspector"/)
})

 test('market overview reads broad indexes, core gold quotes and daily news', () => {
  const board = read('components/market/MarketOverviewBoard.vue')

  assert.match(board, /sh000001/)
  assert.match(board, /sz399001/)
  assert.match(board, /sz399006/)
  assert.match(board, /api\.marketAssetQuote\(item\.market, item\.symbol\)/)
  assert.match(board, /api\.marketPrices\(\)/)
  assert.match(board, /api\.jdPrices\(\)/)
  assert.match(board, /api\.newsDaily\(8, force\)/)
  assert.match(board, /@click="selectGold\(card\)"/)
})

test('legacy multi-market board still consumes the existing extended-market APIs', () => {
  const board = read('components/market/MultiMarketBoard.vue')

  assert.match(board, /api\.marketInstruments\(\)/)
  assert.match(board, /api\.marketAssetQuote\(/)
  assert.match(board, /api\.newsDaily\(/)
  // 分组与涨跌样式必须走被单测覆盖的纯函数，不在组件里另写一套
  assert.match(board, /groupInstrumentsByMarket/)
  assert.match(board, /pickNewsItems/)
  assert.match(board, /quoteClass/)
  // 单个标的报价失败不能拖垮整块看板
  assert.match(board, /Promise\.allSettled/)
})

test('news links are external-safe and guarded by the linkable flag', () => {
  const board = read('components/market/MultiMarketBoard.vue')

  // 可点链接必须新窗口 + noopener noreferrer
  const anchor = board.match(/<a[^>]*>/g) || []
  assert.equal(anchor.length, 1, '要闻区应只有一处 <a> 渲染')
  assert.match(anchor[0], /target="_blank"/)
  assert.match(anchor[0], /rel="noopener noreferrer"/)
  // 且必须受 linkable 守卫，非 http(s) 走纯文本分支
  assert.match(board, /<a v-if="item\.linkable"/)
  assert.match(board, /v-else class="news-plain"/)
  // 反向控制：不允许出现无条件渲染的 href 绑定
  assert.doesNotMatch(board, /<a\s+:href="item\.url"(?![^>]*v-if)/)
})

test('api client exposes the daily news endpoint', () => {
  const client = read('api/client.js')

  assert.match(client, /newsDaily: \(limit = 12, force = false\) => get\(API_BASE, '\/api\/news\/daily'/)
  // 必须带 refresh/force，否则后端不会触发抓取，前端"抓取"按钮会假成功
  assert.match(client, /refresh: true, force/)
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
  assert.doesNotMatch(view, /watch\(\[selectedSymbol, interval\]/)
  assert.match(view, /market\.value !== requestMarket[\s\S]*selectedSymbol\.value !== requestSymbol/)
})

test('unavailable news and unavailable board degrade independently', () => {
  const board = read('components/market/MultiMarketBoard.vue')

  // available=false 走不可用分支，而不是当成空列表渲染
  assert.match(board, /payload\.available === false/)
  assert.match(board, /boardState === 'error'/)
  assert.match(board, /newsState\.value = 'unavailable'/)
  assert.match(board, /newsState\.value = 'empty'/)
  // 模板：非 ready 一律显示提示文案（把 unavailable 与 empty 都覆盖到）
  assert.match(board, /v-else-if="newsState !== 'ready'"/)
})
