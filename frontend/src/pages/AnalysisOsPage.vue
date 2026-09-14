<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api/client'
import AnalysisArchiveScene from '../components/analysis/AnalysisArchiveScene.vue'

const emit = defineEmits(['navigate'])

const props = defineProps({
  active: { type: Boolean, default: false },
})

const FALLBACK_ASSETS = [
  { id: 'fallback:XAU', symbol: 'XAU', name: '伦敦金', market: 'global', marketLabel: 'GLOBAL', sector: 'METALS', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 72 },
  { id: 'fallback:518850', symbol: '518850', name: '黄金 ETF', market: 'a_share', marketLabel: 'CN · SSE', sector: 'COMMODITY', price: '—', change: '—', direction: 'up', risk: 'LOW', confidence: 76 },
  { id: 'fallback:NVDA', symbol: 'NVDA', name: 'NVIDIA', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'AI COMPUTE', price: '—', change: '—', direction: 'up', risk: 'HIGH', confidence: 82 },
  { id: 'fallback:AAPL', symbol: 'AAPL', name: 'Apple', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'CONSUMER TECH', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 78 },
  { id: 'fallback:BTC', symbol: 'BTC', name: 'Bitcoin', market: 'crypto', marketLabel: 'CRYPTO', sector: 'DIGITAL ASSET', price: '—', change: '—', direction: 'up', risk: 'HIGH', confidence: 68 },
  { id: 'fallback:MSFT', symbol: 'MSFT', name: 'Microsoft', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'CLOUD / AI', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 80 },
  { id: 'fallback:600519', symbol: '600519', name: '贵州茅台', market: 'a_share', marketLabel: 'CN · SSE', sector: 'CONSUMER', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 74 },
  { id: 'fallback:ETH', symbol: 'ETH', name: 'Ethereum', market: 'crypto', marketLabel: 'CRYPTO', sector: 'SMART CONTRACT', price: '—', change: '—', direction: 'up', risk: 'HIGH', confidence: 66 },
  { id: 'fallback:TSLA', symbol: 'TSLA', name: 'Tesla', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'MOBILITY / AI', price: '—', change: '—', direction: 'up', risk: 'HIGH', confidence: 64 },
  { id: 'fallback:000001', symbol: '000001', name: '平安银行', market: 'a_share', marketLabel: 'CN · SZSE', sector: 'FINANCIALS', price: '—', change: '—', direction: 'up', risk: 'LOW', confidence: 70 },
  { id: 'fallback:META', symbol: 'META', name: 'Meta', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'PLATFORM / AI', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 79 },
  { id: 'fallback:JD_GOLD', symbol: '积存金', name: '银行积存金', market: 'a_share', marketLabel: 'CN · OTC', sector: 'GOLD SAVING', price: '—', change: '—', direction: 'up', risk: 'LOW', confidence: 72 },
  { id: 'fallback:AMD', symbol: 'AMD', name: 'AMD', market: 'us_stock', marketLabel: 'US · NASDAQ', sector: 'SEMICONDUCTOR', price: '—', change: '—', direction: 'up', risk: 'HIGH', confidence: 75 },
  { id: 'fallback:0700', symbol: '0700.HK', name: '腾讯控股', market: 'a_share', marketLabel: 'HK · HKEX', sector: 'INTERNET', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 77 },
  { id: 'fallback:SPY', symbol: 'SPY', name: 'S&P 500 ETF', market: 'us_stock', marketLabel: 'US · NYSE', sector: 'INDEX', price: '—', change: '—', direction: 'up', risk: 'MEDIUM', confidence: 78 },
].map(item => ({
  ...item,
  dataState: 'fallback',
  summary: '实时研究数据暂不可用；当前对象仅作为导航与空间终端降级展示，不提供价格或投资结论。',
}))

const assets = ref(FALLBACK_ASSETS)
const selectedId = ref('')
const query = ref('')
const booting = ref(true)
const dataState = ref('loading')
const dataError = ref('')
const lastUpdated = ref('')
const indexOpen = ref(false)
let bootTimer = 0
let loadVersion = 0

const selectedAsset = computed(() => assets.value.find(item => item.id === selectedId.value) || null)
const focusAsset = computed(() => selectedAsset.value || filteredAssets.value[0] || assets.value[0] || null)
const selectedIndex = computed(() => Math.max(0, assets.value.findIndex(item => item.id === selectedId.value)))
const archiveNumber = computed(() => String((selectedAsset.value ? selectedIndex.value : 0) + 1).padStart(2, '0'))
const archiveTotal = computed(() => String(Math.max(assets.value.length, 1)).padStart(2, '0'))
const normalizedQuery = computed(() => query.value.trim().toLowerCase())
const filteredAssets = computed(() => assets.value.filter(matchesQuery))
const dataStateLabel = computed(() => ({
  loading: 'SYNCING',
  live: 'LIVE API',
  catalog: 'CATALOG',
  fallback: 'FALLBACK',
}[dataState.value] || 'UNKNOWN'))

function marketLabel(market) {
  return {
    a_share: 'CN · A SHARE',
    us_stock: 'US · STOCK',
    crypto: 'CRYPTO',
  }[market] || String(market || 'MARKET').toUpperCase()
}

function inferSector(item) {
  return item.sector || item.industry || item.type || item.category || 'RESEARCH OBJECT'
}

function inferRisk(market) {
  if (market === 'crypto') return 'HIGH'
  if (market === 'a_share') return 'MEDIUM'
  return 'MEDIUM'
}

function confidenceFor(item, quote) {
  if (!quote) return 60
  let score = 72
  if (quote.stale) score -= 12
  if (item.market === 'crypto') score -= 4
  return Math.max(45, Math.min(88, score))
}

function formatPrice(value) {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '—'
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

function formatChangePct(value) {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '—'
  const n = Number(value)
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

function buildLiveAsset(item, quote) {
  const changePct = Number(quote?.change_pct)
  const hasChange = Number.isFinite(changePct)
  const id = `${item.market}:${item.symbol}`
  return {
    id,
    symbol: item.symbol,
    name: item.name || item.symbol,
    market: item.market,
    marketLabel: marketLabel(item.market),
    sector: inferSector(item),
    price: formatPrice(quote?.price),
    change: hasChange ? formatChangePct(changePct) : '—',
    direction: hasChange && changePct < 0 ? 'down' : 'up',
    confidence: confidenceFor(item, quote),
    risk: inferRisk(item.market),
    dataState: quote ? (quote.stale ? 'stale' : 'live') : 'catalog',
    summary: quote
      ? `${item.name || item.symbol} 的市场报价已由 JARVIS Java API 载入。进一步判断请进入行情、财报、产业链、风险或 AI 研究页面查看可复现数据与分析。`
      : `${item.name || item.symbol} 已从 JARVIS 市场目录载入；当前报价暂不可用，可继续进入对应研究页面。`,
  }
}

function uniqueInstruments(items) {
  const seen = new Set()
  return items.filter(item => {
    if (!item?.market || !item?.symbol) return false
    const key = `${item.market}:${item.symbol}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function matchesQuery(asset) {
  if (!normalizedQuery.value) return true
  return [asset.symbol, asset.name, asset.marketLabel, asset.sector]
    .filter(Boolean)
    .some(value => String(value).toLowerCase().includes(normalizedQuery.value))
}

async function loadArchiveData() {
  const version = ++loadVersion
  dataState.value = 'loading'
  dataError.value = ''
  try {
    const [instrumentResult, preferenceResult] = await Promise.allSettled([
      api.marketInstruments(),
      api.marketPreferences(),
    ])
    const instrumentResponse = instrumentResult.status === 'fulfilled' ? instrumentResult.value : null
    if (instrumentResponse?.code !== 200 || !Array.isArray(instrumentResponse?.data)) {
      throw new Error(instrumentResponse?.message || '市场标的目录加载失败')
    }

    const serverWatchlist = preferenceResult.status === 'fulfilled'
      && preferenceResult.value?.code === 200
      && Array.isArray(preferenceResult.value?.data?.watchlist)
      ? preferenceResult.value.data.watchlist
      : []

    const candidates = uniqueInstruments([
      ...serverWatchlist,
      ...instrumentResponse.data,
    ]).slice(0, 15)

    if (!candidates.length) throw new Error('市场标的目录为空')

    const quoteResults = await Promise.allSettled(
      candidates.map(item => api.marketAssetQuote(item.market, item.symbol))
    )
    if (version !== loadVersion) return

    let liveCount = 0
    const nextAssets = candidates.map((item, index) => {
      const result = quoteResults[index]
      const response = result?.status === 'fulfilled' ? result.value : null
      const quote = response?.code === 200 && response?.data ? response.data : null
      if (quote) liveCount += 1
      return buildLiveAsset(item, quote)
    })

    assets.value = nextAssets
    dataState.value = liveCount ? 'live' : 'catalog'
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    if (selectedId.value && !nextAssets.some(item => item.id === selectedId.value)) {
      selectedId.value = ''
    }
  } catch (error) {
    if (version !== loadVersion) return
    assets.value = FALLBACK_ASSETS
    dataState.value = 'fallback'
    dataError.value = error?.message || '研究终端数据同步失败'
    lastUpdated.value = ''
  }
}

function selectAsset(id) {
  selectedId.value = selectedId.value === id ? '' : id
}

function clearSelection() {
  selectedId.value = ''
}

function resetView() {
  query.value = ''
  selectedId.value = ''
}

function moveSelection(delta) {
  if (!assets.value.length) return
  const current = selectedAsset.value ? selectedIndex.value : 0
  const next = (current + delta + assets.value.length) % assets.value.length
  selectedId.value = assets.value[next].id
}

function skipBoot() {
  booting.value = false
}

onMounted(() => {
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  bootTimer = window.setTimeout(() => {
    booting.value = false
  }, reduced ? 80 : 1100)
  loadArchiveData()
})

onBeforeUnmount(() => {
  loadVersion += 1
  if (bootTimer) window.clearTimeout(bootTimer)
})
</script>

<template>
  <section class="analysis-os" :class="{ 'detail-open': selectedAsset }" aria-label="JARVIS 研究终端">
    <div v-if="booting" class="boot-layer" @click="skipBoot">
      <div class="boot-orbit" aria-hidden="true"><span></span><i></i></div>
      <p>JARVIS SYSTEM</p>
      <h1>RESEARCH ACCESS</h1>
      <div class="boot-line"><i></i></div>
      <small>AUTHENTICATED · SPATIAL MARKET ARCHIVE</small>
      <button type="button" @click.stop="skipBoot">SKIP INTRO</button>
    </div>

    <div class="archive-stage">
      <AnalysisArchiveScene
        :assets="assets"
        :selected-id="selectedId"
        :query="query"
        :active="props.active"
        @select="selectAsset"
      />
    </div>

    <header class="terminal-brand" aria-label="JARVIS Analysis OS">
      <h1>JARVIS</h1>
      <div>FINANCIAL RESEARCH</div>
      <p>ANALYSIS <b>OS</b></p>
    </header>

    <nav class="terminal-nav" aria-label="研究终端导航">
      <button type="button" @click="indexOpen = !indexOpen">⌕&nbsp; ARCHIVE INDEX</button>
      <button type="button" :disabled="dataState === 'loading'" @click="loadArchiveData">↻&nbsp; SYNC</button>
      <button type="button" @click="emit('navigate', '行情')">MARKET ↗</button>
    </nav>

    <div class="system-strip" aria-label="系统状态">
      <span>DATA</span><strong>{{ dataStateLabel }}</strong>
      <span>OBJECTS</span><strong>{{ assets.length }}</strong>
      <span>UPDATED</span><strong>{{ lastUpdated || '—' }}</strong>
    </div>

    <p v-if="dataError" class="data-warning">
      {{ dataError }} · 已进入降级模式，占位对象不包含实时价格或投资结论。
    </p>

    <section v-if="focusAsset && !selectedAsset" class="archive-callout" aria-label="当前档案">
      <p>INTERNAL MARKET DATABASE <i>/</i> 市场档案</p>
      <h2>FILE NUMBER: {{ focusAsset.symbol }}</h2>
      <div class="callout-rule"><span></span></div>
      <div class="callout-meta">
        <strong>{{ focusAsset.name }}</strong>
        <span>{{ focusAsset.marketLabel }} · {{ focusAsset.sector }}</span>
      </div>
      <button type="button" @click="selectAsset(focusAsset.id)">ACCESS RESEARCH <span>→</span></button>
    </section>

    <div class="archive-counter" aria-live="polite">
      <span>MARKET ARCHIVE</span>
      <div class="counter-line">
        <strong>{{ archiveNumber }}</strong><i>/ {{ archiveTotal }}</i>
      </div>
      <div class="archive-navigation">
        <button type="button" aria-label="上一个资产" @click="moveSelection(-1)">←</button>
        <button type="button" aria-label="下一个资产" @click="moveSelection(1)">→</button>
      </div>
    </div>

    <div class="archive-hint" aria-hidden="true">
      <span>DRAG / MOVE</span><i></i><span>WHEEL / DEPTH</span><i></i><span>CLICK / EXTRACT</span>
    </div>

    <div class="column-navigation" aria-label="档案列导航">
      <button type="button" aria-label="上一列" @click="moveSelection(-1)">←</button>
      <div><span>COLUMN 03 / 05</span><strong>{{ focusAsset?.marketLabel || 'MARKET ARCHIVE' }}</strong></div>
      <button type="button" aria-label="下一列" @click="moveSelection(1)">→</button>
    </div>

    <section v-if="indexOpen" class="asset-index">
      <header>
        <span>ARCHIVE INDEX</span>
        <button type="button" aria-label="关闭档案索引" @click="indexOpen = false">×</button>
      </header>
      <label class="index-search">
        <span aria-hidden="true"></span>
        <input v-model="query" type="search" placeholder="检索代码、名称、市场或行业" autocomplete="off" />
      </label>
      <div class="index-list" aria-label="资产索引">
        <button
          v-for="asset in filteredAssets"
          :key="asset.id"
          type="button"
          :class="{ active: selectedId === asset.id }"
          @click="selectAsset(asset.id); indexOpen = false"
        >
          <strong>{{ asset.symbol }}</strong>
          <span>{{ asset.name }}</span>
          <small>{{ asset.price }} · {{ asset.change }}</small>
        </button>
      </div>
    </section>

    <aside v-if="selectedAsset" class="research-panel">
      <button type="button" class="panel-close" aria-label="关闭资产详情" @click="clearSelection">×</button>
      <p class="panel-overline">ARCHIVE / {{ selectedAsset.marketLabel }}</p>
      <div class="panel-title-row">
        <div>
          <h2>{{ selectedAsset.symbol }}</h2>
          <p>{{ selectedAsset.name }}</p>
        </div>
        <span class="confidence-ring">{{ selectedAsset.confidence }}<small>%</small></span>
      </div>

      <div class="quote-row">
        <strong>{{ selectedAsset.price }}</strong>
        <span :class="selectedAsset.direction">{{ selectedAsset.change }}</span>
      </div>

      <div class="detail-rule"></div>
      <div class="detail-grid">
        <div><span>SECTOR</span><strong>{{ selectedAsset.sector }}</strong></div>
        <div><span>RISK</span><strong>{{ selectedAsset.risk }}</strong></div>
        <div><span>DATA</span><strong>{{ selectedAsset.dataState.toUpperCase() }}</strong></div>
        <div><span>RESEARCH</span><strong>READY</strong></div>
      </div>

      <div class="decrypt-block">
        <p>RESEARCH CONTEXT</p>
        <span>{{ selectedAsset.summary }}</span>
      </div>

      <div class="research-actions">
        <button type="button" @click="emit('navigate', '多市场')">行情 / 技术分析</button>
        <button type="button" @click="emit('navigate', '研究助手')">AI 深度研究</button>
        <button type="button" @click="emit('navigate', '财报解析')">财报解析</button>
        <button type="button" @click="emit('navigate', '产业链图谱')">产业链图谱</button>
        <button type="button" @click="emit('navigate', '风险预警')">风险预警</button>
        <button type="button" class="primary-action" @click="emit('navigate', '模拟盘')">进入模拟交易</button>
      </div>
    </aside>

    <footer class="system-footer">
      <span><i></i> SESSION AUTHORIZED</span>
      <span>WEBGL / LIVE MARKET DATA</span>
      <strong>仅供研究参考，不构成投资建议</strong>
    </footer>
    <div class="powered">POWERED BY <b>JARVIS</b><i></i></div>
  </section>
</template>

<style scoped>
.analysis-os {
  --paper: #e8e5e1;
  --ink: #20221d;
  --muted-ink: #77736a;
  --faint-ink: #aaa398;
  --rule: #bcb6ab;
  --accent: #8a7657;
  position: relative;
  width: 100%;
  height: 100dvh;
  min-height: 620px;
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  background: var(--paper);
  font-family: "MiSans", "Mi Sans", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
}
.archive-stage { position: absolute; inset: 0; z-index: 0; }
.boot-layer {
  position: fixed; inset: 0; z-index: 200;
  display: grid; place-content: center; justify-items: center;
  background: radial-gradient(ellipse at 51% 48%, #ecebe6 0%, #e5e2dc 76%, #e4dfdb 100%);
  color: #171914; cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.boot-layer p { margin: 22px 0 8px; font-size: 11px; letter-spacing: .24em; }
.boot-layer h1 { margin: 0; font: 300 clamp(35px, 5vw, 68px)/1 system-ui, sans-serif; letter-spacing: .06em; }
.boot-layer small { margin-top: 11px; font-size: 9px; letter-spacing: .15em; color: #716e66; }
.boot-layer button { margin-top: 32px; border: 0; background: transparent; font: inherit; font-size: 9px; letter-spacing: .18em; cursor: pointer; color: #77736a; }
.boot-orbit { position: relative; width: 112px; height: 112px; border: 1px solid #9d9a90; border-radius: 50%; }
.boot-orbit::before { content: ''; position: absolute; inset: 16px; border: 1px solid #c1bdb4; border-radius: 50%; }
.boot-orbit span { position: absolute; left: 50%; top: -8px; width: 1px; height: 128px; background: #8b877e; transform: rotate(36deg); animation: orbit-line 1s cubic-bezier(.22,1,.36,1) both; }
.boot-orbit i { position: absolute; left: 50%; top: 50%; width: 8px; height: 8px; margin: -4px; background: #252720; border-radius: 50%; }
.boot-line { width: min(440px, 62vw); height: 1px; background: #cac5ba; margin-top: 25px; overflow: hidden; }
.boot-line i { display: block; width: 100%; height: 100%; background: #24261f; animation: boot-line 1s ease both; }

.terminal-brand {
  position: absolute; z-index: 6; left: 48px; top: 54px; width: 290px;
  line-height: 1; user-select: none;
}
.terminal-brand h1 { margin: 0; font-size: 36px; line-height: 38px; letter-spacing: 2.2px; font-weight: 760; }
.terminal-brand > div { margin-top: 1px; font-size: 15px; line-height: 19px; letter-spacing: .55px; font-weight: 620; }
.terminal-brand p { margin: 1px 0 0; font-size: 28px; line-height: 32px; display: flex; justify-content: space-between; width: 205px; letter-spacing: .2px; }
.terminal-brand p b { font-weight: 760; letter-spacing: 2px; }

.terminal-nav {
  position: absolute; z-index: 7; right: 44px; top: 42px;
  display: flex; align-items: center; gap: 27px;
}
.terminal-nav button, .archive-navigation button, .column-navigation button {
  border: 0; background: transparent; color: #65625b; cursor: pointer;
  font: 600 9px/1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .13em;
}
.terminal-nav button:hover, .column-navigation button:hover { color: #171914; }
.terminal-nav button:disabled { opacity: .35; cursor: default; }
.system-strip {
  position: absolute; z-index: 6; right: 44px; top: 83px;
  display: grid; grid-template-columns: auto auto; gap: 4px 13px; min-width: 174px;
  font: 8px/1.25 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .11em;
}
.system-strip span { color: #9a958b; }
.system-strip strong { color: #59564f; text-align: right; font-weight: 600; }

.data-warning { position: absolute; z-index: 8; right: 44px; top: 128px; width: min(420px, 44vw); margin: 0; color: #8b6944; font-size: 10px; line-height: 1.45; text-align: right; }

.archive-callout {
  position: absolute; z-index: 6; left: 51%; top: 42%; width: min(510px, 38vw);
  color: #20221d; pointer-events: auto;
}
.archive-callout > p { margin: 0 0 17px; color: #77736a; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .11em; }
.archive-callout > p i { margin: 0 13px; color: #aaa398; font-style: normal; }
.archive-callout h2 { margin: 0; font-size: clamp(23px, 2.1vw, 33px); font-weight: 700; letter-spacing: -.025em; }
.callout-rule { position: relative; height: 1px; margin: 24px 0 17px 48px; background: #8e897f; }
.callout-rule::before { content: ''; position: absolute; left: -48px; top: -3px; width: 5px; height: 5px; background: #30322b; }
.callout-rule span { position: absolute; right: 0; top: -9px; color: #8f8a80; }
.callout-meta { margin-left: 48px; display: flex; justify-content: space-between; gap: 20px; align-items: baseline; }
.callout-meta strong { font-size: 14px; font-weight: 600; }
.callout-meta span { color: #8a857b; font: 500 8px/1 ui-monospace, monospace; letter-spacing: .08em; text-transform: uppercase; }
.archive-callout > button { margin: 31px 0 0 48px; border: 0; background: transparent; color: #24261f; padding: 0; font: 600 10px/1 ui-monospace, monospace; letter-spacing: .08em; cursor: pointer; }
.archive-callout > button span { margin-left: 55px; font-size: 18px; vertical-align: -2px; }
.archive-callout > button:hover { color: #8a7657; }

.archive-counter { position: absolute; z-index: 7; left: 49px; bottom: 77px; }
.archive-counter > span { color: #807c73; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .14em; }
.counter-line { display: flex; align-items: baseline; gap: 14px; margin-top: 12px; }
.counter-line strong { font-size: 52px; line-height: .9; font-weight: 380; letter-spacing: -2px; }
.counter-line i { font-style: normal; color: #8f8a80; font-size: 24px; font-weight: 300; }
.archive-navigation { display: flex; gap: 5px; margin-top: 14px; }
.archive-navigation button { width: 38px; height: 34px; border: 1px solid #c0baaf; font-size: 17px; letter-spacing: 0; }
.archive-navigation button:hover { background: #ddd3c4; color: #20221d; }

.archive-hint {
  position: absolute; z-index: 5; left: 345px; bottom: 46px;
  display: flex; align-items: center; gap: 11px; color: #918c82;
  font: 500 8px/1 ui-monospace, monospace; letter-spacing: .11em; pointer-events: none;
}
.archive-hint i { width: 20px; height: 1px; background: #bcb6ab; }
.column-navigation {
  position: absolute; z-index: 6; left: 53%; bottom: 69px;
  display: flex; align-items: center; gap: 18px; min-width: 290px;
}
.column-navigation button { width: 35px; height: 35px; font-size: 18px; letter-spacing: 0; }
.column-navigation > div { display: grid; gap: 6px; min-width: 140px; }
.column-navigation span { color: #99948a; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .11em; }
.column-navigation strong { color: #44463f; font-size: 11px; font-weight: 500; }
.system-footer {
  position: absolute; z-index: 5; left: 49px; right: 44px; bottom: 21px;
  display: flex; align-items: center; gap: 22px; color: #9a958b;
  font: 500 8px/1 ui-monospace, monospace; letter-spacing: .08em; pointer-events: none;
}
.system-footer strong { margin-left: auto; color: #7c786f; font-weight: 500; }
.system-footer > span:first-child i { display: inline-block; width: 4px; height: 4px; margin-right: 8px; background: #8b8f75; vertical-align: 1px; }
.powered {
  position: absolute; z-index: 6; right: 44px; bottom: 52px;
  display: flex; align-items: center; gap: 5px; color: #4e4d47; font-size: 12px;
}
.powered b { color: #20221d; font-weight: 760; }
.powered i { display: inline-block; width: 23px; height: 5px; margin-left: 9px; background: #24261f; }

.asset-index {
  position: absolute; z-index: 30; right: 40px; top: 82px; width: min(760px, 66vw);
  padding: 22px 24px 24px; background: rgba(239,236,228,.98);
  border: 1px solid #c7c1b6; box-shadow: 0 30px 100px rgba(83,72,55,.17);
}
.asset-index > header { display: flex; align-items: center; justify-content: space-between; color: #5e5b54; font: 650 10px/1 ui-monospace, monospace; letter-spacing: .14em; }
.asset-index > header button { width: 30px; height: 30px; border: 0; background: transparent; color: #77736a; font-size: 21px; cursor: pointer; }
.index-search { height: 52px; margin-top: 12px; display: flex; align-items: center; gap: 14px; border-bottom: 1px solid #7e796f; }
.index-search > span { position: relative; width: 17px; height: 17px; flex: 0 0 auto; border: 1px solid #77736a; border-radius: 50%; }
.index-search > span::after { content: ''; position: absolute; width: 7px; height: 1px; background: #77736a; left: -5px; bottom: -2px; transform: rotate(-45deg); }
.index-search input { min-width: 0; flex: 1; height: 100%; border: 0; outline: 0; background: transparent; color: #20221d; font-size: 13px; }
.index-search input::placeholder { color: #9a958b; }
.index-list {
  width: 100%; margin-top: 17px;
  display: grid; grid-template-columns: repeat(5, minmax(0, 1fr));
  border-top: 1px solid #d0cabf; border-left: 1px solid #d0cabf;
}
.index-list button { min-width: 0; padding: 12px 13px; border: 0; border-right: 1px solid #d0cabf; border-bottom: 1px solid #d0cabf; background: transparent; color: #6b675f; text-align: left; cursor: pointer; }
.index-list button:hover, .index-list button.active { background: #dcd2c3; color: #20221d; }
.index-list strong, .index-list span, .index-list small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.index-list strong { font: 650 11px/1.2 ui-monospace, monospace; }
.index-list span { margin-top: 4px; font-size: 10px; }
.index-list small { margin-top: 6px; color: #948f85; font: 8px/1 ui-monospace, monospace; }

.research-panel {
  position: absolute; z-index: 10; right: 0; top: 0; bottom: 0; width: min(710px, 48vw);
  padding: 188px 66px 60px 158px; overflow: auto;
  background: linear-gradient(90deg, rgba(232,229,225,0) 0%, rgba(232,229,225,.67) 24%, rgba(232,229,225,.98) 43%, #e8e5e1 100%);
}
.panel-close { position: absolute; right: 48px; top: 134px; width: 38px; height: 38px; border: 1px solid #bdb7ac; background: rgba(232,229,225,.72); color: #6f6b63; font-size: 23px; cursor: pointer; }
.panel-overline { margin: 0 0 22px; color: #8b877d; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .15em; }
.panel-title-row { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.panel-title-row h2 { margin: 0; color: #191b17; font: 500 clamp(30px, 3.2vw, 46px)/1 ui-monospace, monospace; letter-spacing: -.045em; }
.panel-title-row p { margin: 7px 0 0; color: #6f6b63; font-size: 15px; }
.confidence-ring { display: grid; place-items: center; width: 62px; height: 62px; border: 1px solid #a8a196; border-radius: 50%; color: #5f6255; font: 600 17px/1 ui-monospace, monospace; }
.confidence-ring small { font-size: 8px; color: #99948a; }
.quote-row { display: flex; align-items: baseline; gap: 17px; margin: 22px 0 24px; }
.quote-row strong { color: #20221d; font: 500 31px/1 ui-monospace, monospace; }
.quote-row span { font: 650 12px/1 ui-monospace, monospace; }
.up { color: #66745e; } .down { color: #9d5f55; }
.detail-rule { height: 2px; background: #282a24; margin-bottom: 20px; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0; border-top: 1px solid #c2bcb0; border-left: 1px solid #c2bcb0; }
.detail-grid div { min-width: 0; padding: 11px 12px; border-right: 1px solid #c2bcb0; border-bottom: 1px solid #c2bcb0; }
.detail-grid span { display: block; color: #948f85; font: 8px/1 ui-monospace, monospace; letter-spacing: .12em; }
.detail-grid strong { display: block; margin-top: 6px; color: #575950; font: 600 10px/1.25 ui-monospace, monospace; overflow: hidden; text-overflow: ellipsis; }
.decrypt-block { margin-top: 18px; padding: 15px 0; border-bottom: 1px solid #c2bcb0; }
.decrypt-block p { margin: 0 0 9px; color: #77736a; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .14em; }
.decrypt-block span { color: #66645c; font-size: 11px; line-height: 1.68; }
.research-actions { display: grid; grid-template-columns: 1fr 1fr; margin-top: 20px; border-top: 1px solid #bbb5aa; border-left: 1px solid #bbb5aa; }
.research-actions button { min-height: 48px; padding: 9px 10px; border: 0; border-right: 1px solid #bbb5aa; border-bottom: 1px solid #bbb5aa; background: rgba(237,234,226,.64); color: #66645d; text-align: left; font-size: 10px; cursor: pointer; }
.research-actions button:hover { background: #ddd3c4; color: #20221d; }
.research-actions .primary-action { background: #30332b; color: #f0eee5; }
.research-actions .primary-action:hover { background: #484a40; color: #fff; }

@keyframes boot-line { from { transform: translateX(-100%); } to { transform: translateX(0); } }
@keyframes orbit-line { from { opacity: 0; transform: rotate(-70deg) scaleY(.2); } to { opacity: 1; transform: rotate(36deg) scaleY(1); } }

@media (max-width: 1050px) {
  .terminal-brand { left: 30px; top: 34px; transform: scale(.82); transform-origin: top left; }
  .terminal-nav { right: 28px; top: 30px; gap: 17px; }
  .system-strip { right: 28px; top: 68px; }
  .data-warning { right: 28px; top: 112px; }
  .archive-callout { left: 48%; top: 40%; width: 43vw; }
  .archive-counter { left: 30px; bottom: 72px; }
  .archive-hint { left: 280px; }
  .column-navigation { left: 49%; }
  .asset-index { right: 28px; top: 72px; width: min(700px, 80vw); }
  .research-panel { width: min(620px, 58vw); padding-left: 135px; padding-right: 42px; }
  .system-footer { left: 30px; right: 28px; }
  .powered { right: 28px; }
}

@media (max-width: 700px) {
  .analysis-os { min-height: 100dvh; }
  .terminal-brand { left: 18px; top: 22px; transform: scale(.62); }
  .terminal-nav { left: 17px; right: 17px; top: auto; bottom: 18px; justify-content: space-between; gap: 8px; padding-top: 10px; border-top: 1px solid #c7c1b6; }
  .terminal-nav button { font-size: 7px; }
  .system-strip { display: none; }
  .data-warning { right: 17px; top: 72px; width: 66vw; font-size: 8px; }
  .archive-callout { left: 18px; right: 18px; top: 24%; width: auto; }
  .archive-callout h2 { font-size: 22px; }
  .callout-rule { margin-top: 17px; margin-bottom: 13px; }
  .archive-callout > button { margin-top: 20px; }
  .archive-counter { left: 18px; bottom: 72px; transform: scale(.76); transform-origin: bottom left; }
  .archive-hint, .system-footer, .column-navigation, .powered { display: none; }
  .asset-index { left: 17px; right: 17px; top: 78px; width: auto; max-height: 72vh; overflow: auto; padding: 17px; }
  .index-list { grid-template-columns: repeat(2, 1fr); }
  .research-panel {
    top: auto; left: 0; right: 0; bottom: 0; width: auto; height: 58vh;
    padding: 78px 19px 62px; background: linear-gradient(180deg, rgba(232,229,225,0) 0%, rgba(232,229,225,.94) 18%, #e8e5e1 33%);
  }
  .panel-close { right: 18px; top: 42px; }
  .panel-title-row h2 { font-size: 30px; }
  .confidence-ring { width: 52px; height: 52px; }
  .quote-row { margin: 16px 0; }
  .research-actions button { min-height: 42px; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; }
}
</style>
