import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

/**
 * 行情页多市场 + 每日要闻的接线守卫。
 *
 * 这里断言的是**结构与安全点**，不是像素：多市场看板是否真的挂在行情页上、
 * 要闻是否只对 http(s) 链接渲染可点 <a>、接口路径是否对得上。
 */
test('market page mounts the multi-market board beside the gold chart', () => {
  const page = read('pages/MarketPage.vue')

  assert.match(page, /import MultiMarketBoard from '\.\.\/components\/market\/MultiMarketBoard\.vue'/)
  assert.match(page, /<MultiMarketBoard :active="active" \/>/)
  // 不能把原来的黄金主图/侧栏挤掉：原有结构仍在
  assert.match(page, /market-primary-layout/)
  assert.match(page, /marketFocusTabs/)
})

test('multi-market board consumes the existing extended-market APIs', () => {
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