/**
 * 主行情页的多市场看板 + 每日要闻的数据整形。
 *
 * 抽成纯函数是为了能被真正测到：分组会不会漏标的、涨跌方向与样式、
 * 新闻链接是否可安全跳转——这些都属于"错了界面上看不出来"的一类。
 */

/** 支持的市场与显示名。unknown 市场不会被丢弃（见 groupInstrumentsByMarket）。 */
export const MARKET_LABELS = {
  gold_etf: { en: 'GOLD ETF', zh: '黄金 ETF' },
  london_gold: { en: 'LONDON GOLD', zh: '伦敦金' },
  a_share: { en: 'A SHARE', zh: 'A 股' },
  us_stock: { en: 'US STOCK', zh: '美股' },
  crypto: { en: 'CRYPTO', zh: '加密货币' },
}

/** 看板里非黄金市场的展示顺序（黄金由页面主图区承担，不重复占位）。 */
export const MARKET_BOARD_ORDER = ['a_share', 'us_stock', 'crypto']

export function marketLabel(market) {
  const key = String(market || '').trim()
  if (MARKET_LABELS[key]) return MARKET_LABELS[key]
  return { en: key ? key.toUpperCase() : 'UNKNOWN', zh: key || '未知市场' }
}

/**
 * 把 /api/market/instruments 的目录按市场分组。
 *
 * 两条不变量：**不丢标的**（缺 market/symbol 的脏数据跳过，但没在偏好顺序里的
 * 市场会原序排在最后，而不是被静默吞掉）、**组内保持后端顺序**。
 */
export function groupInstrumentsByMarket(instruments, order = MARKET_BOARD_ORDER) {
  const buckets = new Map()
  for (const item of Array.isArray(instruments) ? instruments : []) {
    const market = String(item?.market || '').trim()
    const symbol = String(item?.symbol || '').trim()
    if (!market || !symbol) continue
    if (!buckets.has(market)) buckets.set(market, [])
    buckets.get(market).push({ ...item, market, symbol })
  }
  const preferred = order.filter(key => buckets.has(key))
  const rest = [...buckets.keys()].filter(key => !order.includes(key))
  return [...preferred, ...rest].map(key => ({
    key,
    label: marketLabel(key),
    instruments: buckets.get(key),
  }))
}

/** 涨跌方向：无法解析或恰好为 0 都算平盘，不要误标成上涨。 */
export function quoteDirection(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num === 0) return 'flat'
  return num > 0 ? 'up' : 'down'
}

export function quoteClass(value) {
  const direction = quoteDirection(value)
  return direction === 'up' ? 'pos' : direction === 'down' ? 'neg' : ''
}

/* ---------------------------------------------------------------- 每日要闻 */

/**
 * 规范化一条资讯。**只有 http(s) 链接才算可跳转**：
 * RSS 里出现过 javascript:、相对路径、空链接，直接塞进 <a href> 是注入风险。
 */
export function normalizeNewsItem(raw, index = 0) {
  const title = String(raw?.title || '').trim()
  const url = String(raw?.url || raw?.link || '').trim()
  const source = String(raw?.source || raw?.source_name || raw?.source_id || '').trim()
  const published = String(raw?.published || raw?.published_at || raw?.added_at || '').trim()
  if (!title) return null
  return {
    id: `${index}:${url || title}`,
    title,
    url,
    source,
    published,
    linkable: /^https?:\/\//i.test(url),
    host: newsHost(url),
  }
}

/** 取链接主机名用于显示来源；解析失败返回空串，绝不抛错。 */
export function newsHost(url) {
  const match = /^https?:\/\/([^/?#]+)/i.exec(String(url || '').trim())
  if (!match) return ''
  return match[1].replace(/^www\./i, '')
}

/**
 * 从接口返回里取资讯列表，容忍 {data:[...]} 与裸数组两种外壳。
 * limit 只做节流，不改变顺序（资讯按时间新→旧，顺序就是语义）。
 */
export function pickNewsItems(payload, limit = 12) {
  const list = Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.items)
      ? payload.items
      : Array.isArray(payload?.data)
        ? payload.data
        : []
  const cap = Number.isFinite(limit) && limit > 0 ? limit : list.length
  return list
    .slice(0, cap)
    .map((item, index) => normalizeNewsItem(item, index))
    .filter(Boolean)
}

/** 资讯时间只显示到分钟，且对异常输入保持稳定（原样返回，不抛错）。 */
export function formatNewsTime(value) {
  const text = String(value || '').trim()
  if (!text) return ''
  const parsed = new Date(text)
  if (Number.isNaN(parsed.getTime())) return text.slice(0, 16)
  const pad = number => String(number).padStart(2, '0')
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}