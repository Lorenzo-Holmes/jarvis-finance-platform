<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api/client'
import AnalysisArchiveScene from '../components/analysis/AnalysisArchiveScene.vue'

const emit = defineEmits(['navigate'])

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
let bootTimer = 0
let loadVersion = 0

const selectedAsset = computed(() => assets.value.find(item => item.id === selectedId.value) || null)
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
  <section class="analysis-os" aria-label="JARVIS 研究终端">
    <div v-if="booting" class="boot-layer" @click="skipBoot">
      <div class="boot-mark" aria-hidden="true"><span></span><span></span><span></span></div>
      <p>JARVIS SYSTEM / RESEARCH ACCESS</p>
      <h1>ANALYSIS OS</h1>
      <div class="boot-line"><i></i></div>
      <small>AUTHENTICATED WORKSPACE · INITIALIZING SPATIAL MARKET ARCHIVE</small>
      <button type="button" @click.stop="skipBoot">SKIP</button>
    </div>

    <header class="os-header">
      <div>
        <p class="eyebrow">JARVIS / ANALYSIS OS</p>
        <h1>市场研究档案终端</h1>
        <p class="os-subtitle">Three.js 空间化研究入口，将真实市场目录、报价与现有 JARVIS 研究模块组织到同一工作流。</p>
      </div>
      <div class="system-strip" aria-label="系统状态">
        <span>DATA</span><strong>{{ dataStateLabel }}</strong>
        <span>OBJECTS</span><strong>{{ assets.length }}</strong>
        <span>UPDATED</span><strong>{{ lastUpdated || '—' }}</strong>
      </div>
    </header>

    <div class="os-toolbar">
      <label class="terminal-search">
        <span>SEARCH</span>
        <input v-model="query" type="search" placeholder="代码 / 名称 / 市场 / 行业" autocomplete="off" />
      </label>
      <div class="toolbar-actions">
        <button type="button" @click="resetView">RESET</button>
        <button type="button" :disabled="dataState === 'loading'" @click="loadArchiveData">SYNC DATA</button>
        <button type="button" @click="emit('navigate', '行情')">MARKET</button>
        <button type="button" @click="emit('navigate', '模拟盘')">SIM TRADE</button>
      </div>
    </div>

    <p v-if="dataError" class="data-warning">
      {{ dataError }}。已进入降级模式；占位对象不包含实时价格或投资结论。
    </p>

    <div class="os-workspace">
      <div class="archive-stage">
        <AnalysisArchiveScene
          :assets="assets"
          :selected-id="selectedId"
          :query="query"
          @select="selectAsset"
        />

        <div class="stage-caption stage-caption--top">
          <span>THREE.JS MARKET ARCHIVE ARRAY</span>
          <strong>{{ filteredAssets.length }} VISIBLE</strong>
        </div>
        <div class="stage-caption stage-caption--bottom">
          <span>SELECT A GLASS OBJECT TO OPEN RESEARCH CONTEXT</span>
          <strong>{{ selectedAsset ? 'OBJECT LOCKED' : 'ARRAY READY' }}</strong>
        </div>
      </div>

      <aside class="research-panel" :class="{ active: selectedAsset }">
        <template v-if="selectedAsset">
          <button type="button" class="panel-close" aria-label="关闭资产详情" @click="clearSelection">×</button>
          <p class="panel-overline">OBJECT / {{ selectedAsset.marketLabel }}</p>
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

          <div class="detail-grid">
            <div><span>SECTOR</span><strong>{{ selectedAsset.sector }}</strong></div>
            <div><span>RISK</span><strong>{{ selectedAsset.risk }}</strong></div>
            <div><span>DATA</span><strong>{{ selectedAsset.dataState.toUpperCase() }}</strong></div>
            <div><span>AI ROUTE</span><strong>READY</strong></div>
          </div>

          <div class="decrypt-block">
            <p>RESEARCH CONTEXT</p>
            <span>{{ selectedAsset.summary }}</span>
          </div>

          <div class="research-actions">
            <button type="button" @click="emit('navigate', '多市场')"><span>01</span>行情与技术分析</button>
            <button type="button" @click="emit('navigate', '研究助手')"><span>02</span>AI 深度研究</button>
            <button type="button" @click="emit('navigate', '财报解析')"><span>03</span>财报解析</button>
            <button type="button" @click="emit('navigate', '产业链图谱')"><span>04</span>产业链</button>
            <button type="button" @click="emit('navigate', '风险预警')"><span>05</span>风险预警</button>
            <button type="button" class="primary-action" @click="emit('navigate', '模拟盘')"><span>06</span>模拟交易</button>
          </div>
        </template>

        <div v-else class="panel-empty">
          <span class="empty-reticle" aria-hidden="true"></span>
          <p>RESEARCH OBJECT</p>
          <h2>等待选择资产</h2>
          <span>点击三维档案对象，或使用下方可访问资产列表。当前终端只负责研究导航，不直接给出交易建议。</span>
        </div>
      </aside>
    </div>

    <div class="asset-access-list" aria-label="资产列表">
      <button
        v-for="asset in filteredAssets"
        :key="asset.id"
        type="button"
        :class="{ active: selectedId === asset.id }"
        @click="selectAsset(asset.id)"
      >
        <strong>{{ asset.symbol }}</strong>
        <span>{{ asset.name }}</span>
        <small>{{ asset.price }} · {{ asset.change }}</small>
      </button>
    </div>

    <footer class="os-footer">
      <span>WEBGL / THREE.JS · DOM RESEARCH CONTROLS · JARVIS JAVA API</span>
      <strong>研究与模拟交易用途，不构成投资建议</strong>
    </footer>
  </section>
</template>

<style scoped>
.analysis-os {
  --os-bg: #070a09;
  --os-panel: rgba(12, 18, 16, .91);
  --os-line: rgba(167, 210, 193, .18);
  --os-text: #e8f3ee;
  --os-muted: #7f928b;
  --os-accent: #91ffc8;
  min-height: calc(100vh - 126px);
  margin-top: 4px;
  border: 1px solid var(--os-line);
  background:
    linear-gradient(rgba(145, 255, 200, .018) 1px, transparent 1px),
    linear-gradient(90deg, rgba(145, 255, 200, .018) 1px, transparent 1px),
    var(--os-bg);
  background-size: 36px 36px;
  color: var(--os-text);
  overflow: hidden;
  position: relative;
}
.boot-layer {
  position: fixed; inset: 0; z-index: 200;
  display: grid; place-content: center; justify-items: center;
  background: #f2f5f2; color: #101613; cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.boot-layer p { margin: 18px 0 6px; font-size: 11px; letter-spacing: .24em; }
.boot-layer h1 { margin: 0; font: 300 clamp(40px, 7vw, 88px)/1 system-ui, sans-serif; letter-spacing: .08em; }
.boot-layer small { margin-top: 10px; font-size: 9px; letter-spacing: .14em; color: #5c6862; }
.boot-layer button { margin-top: 30px; border: 0; background: transparent; font: inherit; font-size: 10px; letter-spacing: .2em; cursor: pointer; }
.boot-mark { display: flex; gap: 8px; }
.boot-mark span { display: block; width: 34px; height: 2px; background: #151d19; transform-origin: left; animation: boot-mark .75s ease both; }
.boot-mark span:nth-child(2) { transform: rotate(60deg); }
.boot-mark span:nth-child(3) { transform: rotate(-60deg); }
.boot-line { width: min(440px, 62vw); height: 1px; background: #cfd5d1; margin-top: 24px; overflow: hidden; }
.boot-line i { display: block; width: 100%; height: 100%; background: #131a16; animation: boot-line 1s ease both; }
.os-header, .os-toolbar, .os-footer { position: relative; z-index: 3; }
.os-header {
  display: flex; justify-content: space-between; gap: 24px; align-items: flex-start;
  padding: 24px 28px 18px; border-bottom: 1px solid var(--os-line);
}
.eyebrow { margin: 0 0 7px; color: var(--os-accent); font: 600 10px/1 ui-monospace, monospace; letter-spacing: .18em; }
.os-header h1 { margin: 0; font: 500 clamp(22px, 3vw, 36px)/1.05 system-ui, sans-serif; letter-spacing: -.035em; }
.os-subtitle { margin: 9px 0 0; max-width: 720px; color: var(--os-muted); font-size: 12px; line-height: 1.6; }
.system-strip { display: grid; grid-template-columns: auto auto; gap: 5px 14px; min-width: 190px; font: 9px/1.3 ui-monospace, monospace; letter-spacing: .09em; }
.system-strip span { color: #65756f; }
.system-strip strong { color: #cfe5dc; text-align: right; font-weight: 600; }
.os-toolbar { display: flex; align-items: stretch; justify-content: space-between; border-bottom: 1px solid var(--os-line); }
.terminal-search { display: flex; align-items: center; flex: 1; min-width: 0; padding-left: 28px; }
.terminal-search span { margin-right: 14px; color: #60716a; font: 9px/1 ui-monospace, monospace; letter-spacing: .14em; }
.terminal-search input { width: min(520px, 65%); border: 0; outline: 0; color: #d9ebe4; background: transparent; padding: 14px 0; font: 12px/1.2 ui-monospace, monospace; }
.terminal-search input::placeholder { color: #43514c; }
.toolbar-actions { display: flex; }
.toolbar-actions button, .research-actions button {
  border: 0; border-left: 1px solid var(--os-line); color: #849990; background: rgba(255,255,255,.012);
  cursor: pointer; transition: .16s ease;
}
.toolbar-actions button { padding: 0 18px; font: 9px/1 ui-monospace, monospace; letter-spacing: .1em; }
.toolbar-actions button:hover:not(:disabled), .research-actions button:hover { color: #e8fff5; background: rgba(145,255,200,.07); }
.toolbar-actions button:disabled { opacity: .45; cursor: default; }
.data-warning { position: relative; z-index: 4; margin: 0; padding: 8px 28px; color: #e7c787; background: rgba(126, 92, 28, .13); border-bottom: 1px solid rgba(231,199,135,.16); font-size: 11px; }
.os-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 340px; min-height: 610px; }
.archive-stage { position: relative; min-height: 610px; overflow: hidden; border-right: 1px solid var(--os-line); }
.stage-caption { position: absolute; z-index: 3; left: 18px; right: 18px; display: flex; justify-content: space-between; pointer-events: none; color: #52615b; font: 9px/1 ui-monospace, monospace; letter-spacing: .1em; }
.stage-caption strong { color: #91bca9; font-weight: 600; }
.stage-caption--top { top: 16px; }
.stage-caption--bottom { bottom: 14px; }
.research-panel { position: relative; min-height: 610px; padding: 24px; background: var(--os-panel); overflow: hidden; }
.panel-close { position: absolute; right: 14px; top: 12px; border: 0; color: #809189; background: transparent; font-size: 22px; cursor: pointer; }
.panel-overline { margin: 0 0 16px; color: #688077; font: 9px/1 ui-monospace, monospace; letter-spacing: .15em; }
.panel-title-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.panel-title-row h2 { margin: 0; font: 400 42px/1 ui-monospace, monospace; letter-spacing: -.05em; }
.panel-title-row p { margin: 7px 0 0; color: #9db1a9; font-size: 13px; }
.confidence-ring { display: grid; place-items: center; width: 60px; height: 60px; border: 1px solid rgba(145,255,200,.28); border-radius: 50%; color: var(--os-accent); font: 600 17px/1 ui-monospace, monospace; box-shadow: inset 0 0 24px rgba(145,255,200,.04); }
.confidence-ring small { font-size: 8px; color: #60766d; }
.quote-row { display: flex; align-items: baseline; gap: 14px; margin: 20px 0; padding: 16px 0; border-top: 1px solid var(--os-line); border-bottom: 1px solid var(--os-line); }
.quote-row strong { font: 500 28px/1 ui-monospace, monospace; }
.quote-row span { font: 600 12px/1 ui-monospace, monospace; }
.up { color: #8affbd; } .down { color: #ff8c90; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; background: var(--os-line); border: 1px solid var(--os-line); }
.detail-grid div { padding: 11px; background: #0b100e; }
.detail-grid span { display: block; color: #52625c; font: 8px/1 ui-monospace, monospace; letter-spacing: .12em; }
.detail-grid strong { display: block; margin-top: 6px; color: #b7c9c2; font: 500 10px/1.25 ui-monospace, monospace; }
.decrypt-block { margin-top: 18px; padding: 15px; border: 1px solid var(--os-line); background: linear-gradient(120deg, rgba(145,255,200,.04), transparent 55%); }
.decrypt-block p { margin: 0 0 9px; color: var(--os-accent); font: 8px/1 ui-monospace, monospace; letter-spacing: .14em; }
.decrypt-block span { color: #a9bbb4; font-size: 11px; line-height: 1.65; }
.research-actions { display: grid; grid-template-columns: 1fr 1fr; margin-top: 18px; border-top: 1px solid var(--os-line); border-left: 1px solid var(--os-line); }
.research-actions button { min-height: 54px; padding: 9px 10px; border-right: 1px solid var(--os-line); border-bottom: 1px solid var(--os-line); text-align: left; font-size: 11px; }
.research-actions button span { display: block; margin-bottom: 4px; color: #52645c; font: 8px/1 ui-monospace, monospace; }
.research-actions .primary-action { color: #dfffee; background: rgba(145,255,200,.06); }
.panel-empty { min-height: 520px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: #64746e; }
.empty-reticle { width: 74px; height: 74px; border: 1px solid #2d4239; border-radius: 50%; box-shadow: inset 0 0 0 12px #0a0e0d, inset 0 0 0 13px #24362f; }
.panel-empty p { margin: 22px 0 7px; font: 9px/1 ui-monospace, monospace; letter-spacing: .16em; }
.panel-empty h2 { margin: 0; color: #a8bab3; font-size: 18px; font-weight: 500; }
.panel-empty > span:last-child { margin-top: 10px; max-width: 230px; font-size: 11px; line-height: 1.6; }
.asset-access-list {
  position: relative; z-index: 3; display: grid; grid-template-columns: repeat(5, 1fr);
  border-top: 1px solid var(--os-line); background: #080c0b;
}
.asset-access-list button {
  min-width: 0; padding: 12px 14px; border: 0; border-right: 1px solid var(--os-line); border-bottom: 1px solid var(--os-line);
  color: #84958e; background: transparent; text-align: left; cursor: pointer;
}
.asset-access-list button:hover, .asset-access-list button.active { background: rgba(145,255,200,.06); color: #dfffee; }
.asset-access-list strong, .asset-access-list span, .asset-access-list small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.asset-access-list strong { font: 600 12px/1.2 ui-monospace, monospace; }
.asset-access-list span { margin-top: 4px; font-size: 10px; }
.asset-access-list small { margin-top: 6px; color: #5d6f67; font: 8px/1 ui-monospace, monospace; }
.os-footer { display: flex; justify-content: space-between; gap: 16px; padding: 11px 18px; border-top: 1px solid var(--os-line); color: #4e5c57; font: 8px/1.3 ui-monospace, monospace; letter-spacing: .08em; }
.os-footer strong { color: #6c7e76; font-weight: 500; }
@keyframes boot-line { from { transform: translateX(-100%); } to { transform: translateX(0); } }
@keyframes boot-mark { from { opacity: 0; transform: scaleX(0); } to { opacity: 1; } }
@media (max-width: 1080px) {
  .system-strip { display: none; }
  .os-workspace { grid-template-columns: 1fr; }
  .archive-stage { min-height: 560px; border-right: 0; border-bottom: 1px solid var(--os-line); }
  .research-panel { min-height: 0; }
  .panel-empty { min-height: 220px; }
  .asset-access-list { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 700px) {
  .analysis-os { min-height: calc(100vh - 112px); }
  .os-header { padding: 18px 16px 14px; }
  .os-subtitle { font-size: 11px; }
  .os-toolbar { align-items: stretch; flex-direction: column; }
  .terminal-search { padding: 0 16px; border-bottom: 1px solid var(--os-line); }
  .terminal-search input { width: 100%; }
  .toolbar-actions { min-height: 42px; overflow-x: auto; }
  .toolbar-actions button { flex: 1 0 auto; border-left: 0; border-right: 1px solid var(--os-line); }
  .archive-stage { min-height: 480px; }
  .research-panel { padding: 18px 16px; }
  .asset-access-list { grid-template-columns: repeat(2, 1fr); }
  .os-footer { flex-direction: column; }
}
@media (max-width: 430px) {
  .archive-stage { min-height: 430px; }
  .asset-access-list { grid-template-columns: 1fr 1fr; }
  .panel-title-row h2 { font-size: 34px; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; }
}
</style>
