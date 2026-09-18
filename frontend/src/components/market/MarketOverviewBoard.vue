<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import { formatNumber, formatPercent } from '../../utils/formatters'
import { formatNewsTime, pickNewsItems, quoteClass } from '../../utils/marketBoard'

const props = defineProps({ active: { type: Boolean, default: true } })
const emit = defineEmits(['select-gold'])

const INDEXES = [
  { market: 'a_share', symbol: 'sh000001', name: '上证指数', short: '沪指' },
  { market: 'a_share', symbol: 'sz399001', name: '深证成指', short: '深成指' },
  { market: 'a_share', symbol: 'sz399006', name: '创业板指', short: '创业板' },
]

const indexQuotes = ref({})
const coreQuotes = ref(null)
const jdQuotes = ref(null)
const loading = ref(true)
const error = ref('')
const news = ref([])
const newsState = ref('loading')
const newsReason = ref('')
const newsStamp = ref('')
let timer = 0
let newsTick = 0

const goldCards = computed(() => [
  {
    key: 'gold_etf',
    name: '黄金ETF华夏',
    symbol: '518850',
    source: coreQuotes.value?.gold_etf?.source || '核心行情',
    quote: coreQuotes.value?.gold_etf || null,
  },
  {
    key: 'london_gold',
    name: '伦敦金',
    symbol: 'XAU',
    source: coreQuotes.value?.london_gold?.source || '核心行情',
    quote: coreQuotes.value?.london_gold || null,
  },
  {
    key: 'jd',
    jdMarket: 'zheshang',
    name: '浙商积存金',
    symbol: 'JD-ZS',
    source: '积存金',
    quote: jdQuotes.value?.zheshang || null,
  },
  {
    key: 'jd',
    jdMarket: 'minsheng',
    name: '民生积存金',
    symbol: 'JD-MS',
    source: '积存金',
    quote: jdQuotes.value?.minsheng || null,
  },
])

function indexQuote(item) {
  return indexQuotes.value[`${item.market}:${item.symbol}`] || null
}

function priceText(quote) {
  return formatNumber(quote?.price)
}

function changeText(quote) {
  return formatPercent(quote?.change_pct)
}

function changeClass(quote) {
  return quoteClass(quote?.change_pct)
}

async function loadOverview() {
  if (!props.active) return
  loading.value = !Object.keys(indexQuotes.value).length && !coreQuotes.value
  error.value = ''
  const [coreResult, jdResult, ...indexResults] = await Promise.allSettled([
    api.marketPrices(),
    api.jdPrices(),
    ...INDEXES.map(item => api.marketAssetQuote(item.market, item.symbol)),
  ])

  if (coreResult.status === 'fulfilled' && coreResult.value?.code === 200) {
    coreQuotes.value = coreResult.value.data || null
  }
  if (jdResult.status === 'fulfilled' && jdResult.value?.code === 200) {
    jdQuotes.value = jdResult.value.data || null
  }

  const nextIndexes = { ...indexQuotes.value }
  indexResults.forEach((result, index) => {
    if (result.status !== 'fulfilled' || result.value?.code !== 200 || !result.value?.data) return
    const item = INDEXES[index]
    nextIndexes[`${item.market}:${item.symbol}`] = result.value.data
  })
  indexQuotes.value = nextIndexes

  const hasAny = Boolean(coreQuotes.value || jdQuotes.value || Object.keys(nextIndexes).length)
  if (!hasAny) error.value = '大盘与贵金属行情暂时不可用'
  loading.value = false
}

async function loadNews(force = false) {
  if (!news.value.length) newsState.value = 'loading'
  try {
    const response = await api.newsDaily(8, force)
    const payload = response?.data
    if (!payload || payload.available === false) {
      news.value = []
      newsState.value = 'unavailable'
      newsReason.value = payload?.reason === 'ai_service_unavailable'
        ? '资讯服务暂时不可用'
        : '今日要闻暂时不可用'
      return
    }
    news.value = pickNewsItems(payload, 8)
    newsStamp.value = payload.generated_at || ''
    newsState.value = news.value.length ? 'ready' : 'empty'
    newsReason.value = news.value.length ? '' : '暂未抓取到今日要闻'
  } catch (e) {
    news.value = []
    newsState.value = 'unavailable'
    newsReason.value = e?.message || '今日要闻暂时不可用'
  }
}

function selectGold(card) {
  emit('select-gold', { key: card.key, jdMarket: card.jdMarket || null })
}

function tick() {
  if (!props.active) return
  loadOverview()
  newsTick += 1
  if (newsTick % 10 === 0) loadNews()
}

onMounted(() => {
  loadOverview()
  loadNews()
  timer = window.setInterval(tick, 30000)
})

watch(() => props.active, active => {
  if (active) loadOverview()
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="market-overview">
    <section class="overview-panel">
      <header class="overview-head">
        <div>
          <span>MARKET OVERVIEW</span>
          <h2>大盘与贵金属</h2>
          <p>指数看市场方向，黄金看避险与贵金属定价。</p>
        </div>
        <button type="button" :disabled="loading" @click="loadOverview">{{ loading ? '刷新中…' : '刷新' }}</button>
      </header>

      <p v-if="error" class="overview-notice">{{ error }}</p>

      <div class="overview-block">
        <div class="block-title"><b>主要指数</b><span>A 股大盘</span></div>
        <div class="quote-grid index-grid">
          <article v-for="item in INDEXES" :key="item.symbol" class="quote-card">
            <div class="card-title">
              <span>{{ item.short }}</span>
              <small>{{ item.symbol }}</small>
            </div>
            <strong>{{ priceText(indexQuote(item)) }}</strong>
            <em :class="changeClass(indexQuote(item))">{{ changeText(indexQuote(item)) }}</em>
            <footer>{{ indexQuote(item)?.source || '公开行情源' }}</footer>
          </article>
        </div>
      </div>

      <div class="overview-block">
        <div class="block-title"><b>黄金价格</b><span>点击切换上方主图</span></div>
        <div class="quote-grid gold-grid">
          <button v-for="card in goldCards" :key="`${card.key}:${card.jdMarket || ''}`" type="button"
                  class="quote-card gold-card" @click="selectGold(card)">
            <div class="card-title">
              <span>{{ card.name }}</span>
              <small>{{ card.symbol }}</small>
            </div>
            <strong>{{ priceText(card.quote) }}</strong>
            <em :class="changeClass(card.quote)">{{ changeText(card.quote) }}</em>
            <footer>{{ card.source }}</footer>
          </button>
        </div>
      </div>
    </section>

    <section class="brief-panel">
      <header class="overview-head compact">
        <div>
          <span>DAILY BRIEFING</span>
          <h2>市场要闻</h2>
          <p>指数与黄金之外的事件背景。</p>
        </div>
        <button type="button" :disabled="newsState === 'loading'" @click="loadNews(true)">抓取</button>
      </header>

      <p v-if="newsState === 'loading'" class="overview-notice">正在载入市场要闻…</p>
      <p v-else-if="newsState !== 'ready'" class="overview-notice">{{ newsReason }}</p>

      <ul v-else class="news-list">
        <li v-for="item in news" :key="item.id">
          <a v-if="item.linkable" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
          <span v-else>{{ item.title }}</span>
          <footer>
            <b>{{ item.source || item.host || '来源未知' }}</b>
            <time v-if="item.published">{{ formatNewsTime(item.published) }}</time>
          </footer>
        </li>
      </ul>
      <p v-if="newsStamp" class="stamp">更新于 {{ formatNewsTime(newsStamp) }}</p>
    </section>
  </div>
</template>

<style scoped>
.market-overview { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, .72fr); gap: 12px; margin-top: 14px; }
.overview-panel, .brief-panel { min-width: 0; border: 1px solid var(--line); background: var(--workspace-panel-wash, var(--panel)); }
.overview-panel { padding: 14px; }
.brief-panel { padding: 14px 15px; }
.overview-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.overview-head > div { min-width: 0; }
.overview-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.overview-head h2 { margin: 5px 0 0; color: var(--text); font-size: 15px; font-weight: 670; }
.overview-head p { margin: 5px 0 0; color: var(--muted); font-size: 9px; }
.overview-head button { flex: 0 0 auto; min-height: 28px; border: 1px solid var(--line-strong); background: transparent; color: var(--muted); padding: 4px 10px; cursor: pointer; font-size: 9px; }
.overview-head button:hover:not(:disabled) { color: var(--text); border-color: var(--text); }
.overview-head button:disabled { opacity: .45; }
.overview-block { margin-top: 13px; }
.block-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 7px; }
.block-title b { color: var(--text); font-size: 10px; font-weight: 650; }
.block-title span { color: var(--subtle); font-size: 8px; }
.quote-grid { display: grid; gap: 1px; background: var(--line); border: 1px solid var(--line); }
.index-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.gold-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.quote-card { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: baseline; gap: 6px 8px; padding: 10px 11px; border: 0; background: var(--workspace-panel-wash, var(--panel)); color: var(--text); text-align: left; }
.gold-card { cursor: pointer; }
.gold-card:hover { background: var(--workspace-action-hover, var(--surface)); }
.card-title { min-width: 0; display: flex; align-items: baseline; gap: 7px; grid-column: 1 / -1; }
.card-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); font-size: 10px; font-weight: 650; }
.card-title small { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.quote-card strong { color: var(--text); font: 680 15px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.quote-card em { justify-self: end; font: 650 9px/1 ui-monospace, monospace; font-style: normal; }
.quote-card footer { grid-column: 1 / -1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--subtle); font-size: 8px; }
.pos { color: var(--ok, #27c46b); }
.neg { color: var(--bad, #ef5350); }
.flat { color: var(--muted); }
.overview-notice { margin: 12px 0 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.news-list { list-style: none; margin: 9px 0 0; padding: 0; }
.news-list li { padding: 9px 0; border-bottom: 1px solid var(--line); }
.news-list li:last-child { border-bottom: 0; }
.news-list a, .news-list > li > span { display: block; color: var(--text); font-size: 10px; line-height: 1.5; text-decoration: none; }
.news-list a:hover { color: var(--accent-strong); }
.news-list footer { display: flex; justify-content: space-between; gap: 8px; margin-top: 5px; color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; }
.news-list footer b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.news-list time { flex: 0 0 auto; }
.stamp { margin: 9px 0 0; color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; }
@media (max-width: 1180px) { .market-overview { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .index-grid { grid-template-columns: 1fr; } .gold-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 460px) { .gold-grid { grid-template-columns: 1fr; } }
</style>
