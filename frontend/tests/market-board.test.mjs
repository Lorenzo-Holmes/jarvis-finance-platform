import test from 'node:test'
import assert from 'node:assert/strict'

import {
  MARKET_BOARD_ORDER,
  formatNewsTime,
  groupInstrumentsByMarket,
  marketLabel,
  newsHost,
  normalizeNewsItem,
  pickNewsItems,
  quoteClass,
  quoteDirection,
} from '../src/utils/marketBoard.js'

// 与 ExtendedMarketDataService 的默认目录同构
const CATALOG = [
  { market: 'a_share', symbol: 'sh600519', name: '贵州茅台', currency: 'CNY', source: 'Tencent' },
  { market: 'a_share', symbol: 'sz000001', name: '平安银行', currency: 'CNY', source: 'Tencent' },
  { market: 'us_stock', symbol: 'AAPL', name: 'Apple', currency: 'USD', source: 'Yahoo Finance' },
  { market: 'crypto', symbol: 'BTCUSDT', name: 'Bitcoin', currency: 'USDT', source: 'Binance' },
]

test('目录按市场分组，顺序为 A股 → 美股 → 加密', () => {
  const groups = groupInstrumentsByMarket(CATALOG)
  assert.deepEqual(groups.map(group => group.key), MARKET_BOARD_ORDER)
  assert.equal(groups[0].label.zh, 'A 股')
  assert.equal(groups[0].instruments.length, 2)
  assert.equal(groups[1].instruments[0].symbol, 'AAPL')
  assert.equal(groups[2].label.zh, '加密货币')
})

test('分组不丢标的：未知市场原序排在最后，脏数据才跳过', () => {
  const groups = groupInstrumentsByMarket([
    ...CATALOG,
    { market: 'hk_stock', symbol: '00700', name: '腾讯控股' },
    { market: 'a_share', symbol: '', name: '无代码' },
    { symbol: 'NOMARKET' },
    null,
  ])
  assert.deepEqual(groups.map(group => group.key), ['a_share', 'us_stock', 'crypto', 'hk_stock'])
  assert.equal(groups.at(-1).label.zh, 'hk_stock')
  // 缺 symbol 的那条被跳过，但同组的合法标的仍在
  assert.equal(groups[0].instruments.length, 2)
  const symbols = groups.flatMap(group => group.instruments.map(item => item.symbol))
  assert.equal(symbols.includes('00700'), true)
  assert.equal(symbols.includes('NOMARKET'), false)
})

test('空输入与非法输入不抛错', () => {
  assert.deepEqual(groupInstrumentsByMarket(undefined), [])
  assert.deepEqual(groupInstrumentsByMarket([]), [])
  assert.deepEqual(groupInstrumentsByMarket([null, 0, 'x']), [])
  assert.equal(marketLabel('').zh, '未知市场')
  assert.equal(marketLabel('a_share').zh, 'A 股')
})

test('涨跌方向：0 与非法值都算平盘，不能误标上涨', () => {
  assert.equal(quoteDirection(1.2), 'up')
  assert.equal(quoteDirection('-1.2'), 'down')
  assert.equal(quoteDirection(0), 'flat')
  assert.equal(quoteDirection('0.00'), 'flat')
  assert.equal(quoteDirection(null), 'flat')
  assert.equal(quoteDirection('abc'), 'flat')
  assert.equal(quoteClass(2), 'pos')
  assert.equal(quoteClass(-2), 'neg')
  assert.equal(quoteClass(0), '')
})

test('资讯规范化：保留中英双标题，且只有 http(s) 才可跳转', () => {
  const ok = normalizeNewsItem({ title: 'Fed sends a signal', title_original: 'Fed sends a signal', title_zh: '美联储释放信号', url: 'https://www.example.com/a?b=1', source: 'example', published: '2026-09-17T08:30:00+08:00' })
  assert.equal(ok.linkable, true)
  assert.equal(ok.host, 'example.com')
  assert.equal(ok.titleOriginal, 'Fed sends a signal')
  assert.equal(ok.titleZh, '美联储释放信号')

  const js = normalizeNewsItem({ title: 'x', url: 'javascript:alert(1)' })
  assert.equal(js.linkable, false)
  const relative = normalizeNewsItem({ title: 'y', url: '/moment/1' })
  assert.equal(relative.linkable, false)
  const empty = normalizeNewsItem({ title: 'z' })
  assert.equal(empty.linkable, false)
  assert.equal(empty.host, '')
  // 没有标题的条目直接丢弃
  assert.equal(normalizeNewsItem({ url: 'https://example.com' }), null)
})

test('资讯列表容忍两种外壳，且不改动时间顺序', () => {
  const payload = { data: [
    { title: '第一条', url: 'https://a.example.com/1', published: '2026-09-17T10:00:00+08:00' },
    { title: '第二条', url: 'https://b.example.com/2' },
    { title: '' },
  ] }
  const items = pickNewsItems(payload, 5)
  assert.equal(items.length, 2)
  assert.deepEqual(items.map(item => item.title), ['第一条', '第二条'])
  assert.equal(pickNewsItems([{ title: '裸数组' }]).length, 1)
  assert.deepEqual(pickNewsItems(null), [])
  // limit 只截断，不重排
  assert.equal(pickNewsItems(payload, 1)[0].title, '第一条')
})

test('资讯时间格式化对异常输入保持稳定', () => {
  assert.equal(formatNewsTime(''), '')
  assert.equal(formatNewsTime('not-a-date'), 'not-a-date')
  const formatted = formatNewsTime('2026-09-17T08:30:00+08:00')
  assert.match(formatted, /^09-17 \d{2}:\d{2}$/)
  // 链接主机名解析
  assert.equal(newsHost('https://www.qq.com/news/1'), 'qq.com')
  assert.equal(newsHost('not a url'), '')
})