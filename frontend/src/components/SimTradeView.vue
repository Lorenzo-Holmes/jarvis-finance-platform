<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import InstrumentList from './market/InstrumentList.vue'
import TradingTerminal from './trading/TradingTerminal.vue'
import { usePolling } from '../composables/usePolling'
import {
  hasMarketPreferences,
  marketPreferencesKey,
  normalizeMarketPreferences,
  readMarketPreferences,
  writeMarketPreferences,
} from '../utils/marketPreferences'

const props = defineProps({
  user: { type: Object, default: null },
})
const emit = defineEmits(['context-change', 'navigate-module'])

const LEGACY_INSTRUMENTS = [
  {
    market: 'a_share', legacyMarket: 'gold_etf', symbol: 'sh518850', ticker: '518850',
    name: '黄金ETF华夏', kind: 'market', quoteKey: 'gold_etf', currency: 'CNY', source: 'Java行情',
  },
]
const marketOptions = [
  { value: 'a_share', label: 'A股' },
  { value: 'us_stock', label: '美股' },
  { value: 'crypto', label: '加密货币' },
]

const account = ref(null)
const realtimePrices = ref(null)
const jdPrices = ref(null)
const openOrders = ref([])
const trades = ref([])
const tradeTotal = ref(0)
const instruments = ref([])
const watchlist = ref([])
const hiddenDefaultKeys = ref([])
const market = ref('a_share')
const selectedSymbol = ref('sh518850')
const session = ref(null)
const initializing = ref(true)
const submitting = ref(false)
const msg = ref('')
const msgType = ref('info')
const initializeError = ref('')
const customQuery = ref('')
const resolveLoading = ref(false)
const resolveError = ref('')
const preferenceSaving = ref(false)
const preferenceError = ref('')
let pendingOrderAttempt = null
let closePriceStream = null
let preferenceSaveChain = Promise.resolve()
let preferenceSaveVersion = 0

const currentMarketLabel = computed(() => marketOptions.find(item => item.value === market.value)?.label || '市场')
const allDefaults = computed(() => [...LEGACY_INSTRUMENTS, ...instruments.value])
const currentInstruments = computed(() => allDefaults.value.filter(item => item.market === market.value))
const currentWatchlist = computed(() => watchlist.value.filter(item => item.market === market.value))
const watchlistKeys = computed(() => new Set(currentWatchlist.value.map(instrumentKey)))
const currentDefaults = computed(() => currentInstruments.value.filter(item =>
  !hiddenDefaultKeys.value.includes(instrumentKey(item)) && !watchlistKeys.value.has(instrumentKey(item))))
const displayedInstruments = computed(() => [...currentWatchlist.value, ...currentDefaults.value])
const marketOpen = computed(() => market.value === 'crypto' || session.value?.is_open === true)
const selectedInstrument = computed(() => displayedInstruments.value.find(item => item.symbol === selectedSymbol.value) || null)

function instrumentKey(item) {
  return `${item.market}:${item.symbol}`
}

function applyPreferences(preferences) {
  watchlist.value = preferences.watchlist
  hiddenDefaultKeys.value = preferences.hiddenDefaultKeys
}

function currentPreferences() {
  return { watchlist: watchlist.value, hiddenDefaultKeys: hiddenDefaultKeys.value }
}

function persistLocalPreferences(preferences = currentPreferences()) {
  if (typeof window === 'undefined') return false
  return writeMarketPreferences(window.localStorage, marketPreferencesKey(props.user), preferences)
}

async function persistPreferences() {
  const preferences = normalizeMarketPreferences(currentPreferences())
  if (!persistLocalPreferences(preferences)) preferenceError.value = '浏览器本地存储不可用，无法保留偏好'
  const version = ++preferenceSaveVersion
  preferenceSaving.value = true
  preferenceSaveChain = preferenceSaveChain
    .catch(() => {})
    .then(async () => {
      const response = await api.saveMarketPreferences(preferences)
      if (response.code !== 200) throw new Error(response.message || '标的偏好保存失败')
      if (version === preferenceSaveVersion) preferenceError.value = ''
    })
    .catch(saveError => {
      if (version === preferenceSaveVersion) preferenceError.value = saveError?.message || '标的偏好未同步'
    })
  await preferenceSaveChain
  if (version === preferenceSaveVersion) preferenceSaving.value = false
}

async function loadPreferences() {
  if (typeof window === 'undefined') return
  const localPreferences = readMarketPreferences(window.localStorage, marketPreferencesKey(props.user))
  applyPreferences(localPreferences)
  try {
    const response = await api.marketPreferences()
    if (response.code !== 200 || !response.data) return
    const serverPreferences = normalizeMarketPreferences(response.data)
    if (response.data.persisted) {
      applyPreferences(serverPreferences)
      persistLocalPreferences(serverPreferences)
      return
    }
    if (hasMarketPreferences(localPreferences)) await persistPreferences()
  } catch (_) {
    // 后端暂时不可用时保留本地偏好。
  }
}

async function loadInstruments() {
  const response = await api.marketInstruments()
  if (response.code !== 200 || !Array.isArray(response.data)) {
    throw new Error(response.message || '标的列表加载失败')
  }
  instruments.value = response.data
  const availableKeys = new Set(allDefaults.value.map(instrumentKey))
  const cleanedHiddenKeys = hiddenDefaultKeys.value.filter(key => availableKeys.has(key))
  if (cleanedHiddenKeys.length !== hiddenDefaultKeys.value.length) {
    hiddenDefaultKeys.value = cleanedHiddenKeys
    await persistPreferences()
  }
  chooseDefaultSymbol()
}

async function loadSession() {
  try {
    const response = await api.marketSession(market.value)
    if (response.code !== 200 || !response.data) throw new Error(response.message || '交易状态加载失败')
    session.value = response.data
  } catch (_) {
    session.value = null
  }
}

async function loadWorkspace() {
  const [accountResponse, ordersResponse, tradesResponse, marketResponse, jdResponse] = await Promise.all([
    api.simAccount(), api.simOpenOrders(), api.simTrades(50).catch(() => null), api.marketPrices().catch(() => null), api.jdPrices().catch(() => null),
  ])
  if (accountResponse?.code !== 200) throw new Error(accountResponse?.message || '模拟账户加载失败')
  if (ordersResponse?.code !== 200) throw new Error(ordersResponse?.message || '挂单加载失败')
  account.value = accountResponse.data
  openOrders.value = Array.isArray(ordersResponse.data) ? ordersResponse.data : []
  if (tradesResponse?.code === 200) {
    trades.value = Array.isArray(tradesResponse?.data?.trades) ? tradesResponse.data.trades : []
    tradeTotal.value = Number(tradesResponse?.data?.total || 0)
  }
  if (marketResponse?.data) realtimePrices.value = marketResponse.data
  if (jdResponse?.code === 200 && jdResponse.data) jdPrices.value = jdResponse.data
}

function startPriceStream() {
  if (closePriceStream) return
  closePriceStream = api.marketPriceStream(payload => {
    if (payload?.market && Object.keys(payload.market).length) realtimePrices.value = payload.market
    if (payload?.jd && Object.keys(payload.jd).length) jdPrices.value = payload.jd
  })
}

function selectInstrument(symbol) {
  selectedSymbol.value = symbol
  const item = displayedInstruments.value.find(candidate => candidate.symbol === symbol)
  if (item) {
    emit('context-change', {
      market: item.market,
      symbol: item.symbol,
      name: item.name || item.symbol,
      sourceModule: 'sim-trade',
    })
  }
}

function chooseDefaultSymbol() {
  const available = displayedInstruments.value
  if (!available.some(item => item.symbol === selectedSymbol.value)) {
    selectedSymbol.value = available[0]?.symbol || ''
  }
}

async function resolveCustomInstrument() {
  const query = customQuery.value.trim()
  if (!query || resolveLoading.value) return
  resolveLoading.value = true
  resolveError.value = ''
  try {
    const response = await api.resolveMarketInstrument(market.value, query)
    if (response.code !== 200 || !response.data?.symbol) throw new Error(response.message || '标的解析失败')
    const item = response.data
    const existingIndex = watchlist.value.findIndex(candidate => instrumentKey(candidate) === instrumentKey(item))
    if (existingIndex >= 0) watchlist.value.splice(existingIndex, 1, item)
    else watchlist.value.push(item)
    await persistPreferences()
    selectInstrument(item.symbol)
    customQuery.value = ''
  } catch (resolveErrorValue) {
    resolveError.value = resolveErrorValue?.message || String(resolveErrorValue)
  } finally {
    resolveLoading.value = false
  }
}

async function addToWatchlist(item) {
  if (!item) return
  const existingIndex = watchlist.value.findIndex(candidate => instrumentKey(candidate) === instrumentKey(item))
  if (existingIndex >= 0) watchlist.value.splice(existingIndex, 1, item)
  else watchlist.value.push(item)
  await persistPreferences()
  selectInstrument(item.symbol)
}

async function removeFromWatchlist(item) {
  if (!item) return
  watchlist.value = watchlist.value.filter(candidate => instrumentKey(candidate) !== instrumentKey(item))
  await persistPreferences()
  chooseDefaultSymbol()
}

async function removeDefault(item) {
  if (!item) return
  const key = instrumentKey(item)
  if (!hiddenDefaultKeys.value.includes(key)) hiddenDefaultKeys.value.push(key)
  await persistPreferences()
  chooseDefaultSymbol()
}

async function restoreDefaults() {
  const marketPrefix = `${market.value}:`
  hiddenDefaultKeys.value = hiddenDefaultKeys.value.filter(key => !key.startsWith(marketPrefix))
  await persistPreferences()
  chooseDefaultSymbol()
}

function sameAttempt(a, b) {
  return a && a.side === b.side && a.symbol === b.symbol && a.quantity === b.quantity
    && a.leverage === b.leverage && a.orderType === b.orderType
    && Number(a.stopPrice || 0) === Number(b.stopPrice || 0) && a.timeInForce === b.timeInForce
}

async function submitOrder(payload) {
  if (submitting.value) return
  if (!marketOpen.value) {
    msg.value = '当前市场非交易时段，模拟盘仅允许在开市时间成交。'
    msgType.value = 'error'
    return
  }
  msg.value = ''
  submitting.value = true
  const current = {
    side: payload.side, symbol: payload.symbol, quantity: Number(payload.quantity),
    leverage: payload.side === 'SELL' ? 1 : Number(payload.leverage || 1),
    orderType: payload.orderType || 'MARKET', stopPrice: payload.stopPrice,
    timeInForce: payload.timeInForce || 'DAY',
  }
  if (!sameAttempt(pendingOrderAttempt, current)) pendingOrderAttempt = { ...current, id: crypto.randomUUID() }
  try {
    const response = await api.simOrder(
      current.side, current.symbol, current.quantity, current.leverage, pendingOrderAttempt.id,
      { orderType: current.orderType, stopPrice: current.stopPrice, timeInForce: current.timeInForce },
    )
    if (response.code !== 200) throw new Error(response.message || '订单提交失败')
    pendingOrderAttempt = null
    msg.value = current.orderType === 'STOP_MARKET' ? '止损挂单已提交' : (response.data?.message || response.message || '成交成功')
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (submitError) {
    msg.value = submitError?.message || String(submitError)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

async function updateOrder(payload) {
  if (submitting.value) return
  submitting.value = true
  msg.value = ''
  try {
    const response = await api.simUpdateOrder(payload.id, Number(payload.stopPrice))
    if (response.code !== 200) throw new Error(response.message || '挂单更新失败')
    msg.value = '止损价已更新'
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (updateError) {
    msg.value = updateError?.message || String(updateError)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

async function cancelOrder(orderId) {
  if (submitting.value) return
  submitting.value = true
  msg.value = ''
  try {
    const response = await api.simCancelOrder(orderId)
    if (response.code !== 200) throw new Error(response.message || '撤单失败')
    msg.value = '挂单已撤销'
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (cancelError) {
    msg.value = cancelError?.message || String(cancelError)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

const polling = usePolling(async () => {
  try {
    await Promise.all([loadWorkspace(), loadSession()])
  } catch (_) {
    // 保留最后一次有效账户与行情。
  }
}, 30000)

const RISK_STATUS = {
  SAFE: { text: '安全', cls: 'safe' },
  WARN: { text: '预警', cls: 'warn' },
  DANGER: { text: '危险', cls: 'risk' },
  NONE: { text: '无持仓', cls: 'info' },
}

function fmtMoney(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—'
}

function fmtPct(value) {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(2)}%` : '—'
}

const todayTradeCount = computed(() => {
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  return trades.value.filter(t => String(t.createdAt || '').slice(0, 10) === today).length
})

const overviewCards = computed(() => {
  const a = account.value
  if (!a) return []
  return [
    { label: '净权益', value: fmtMoney(a.netEquity) },
    { label: '总资产', value: fmtMoney(a.totalAssets) },
    { label: '持仓标的', value: Array.isArray(a.positions) ? a.positions.length : Object.keys(a.positions || {}).length },
    { label: '累计收益率', value: fmtPct(a.totalReturnPct), signed: Number(a.totalReturnPct) || 0 },
    { label: '今日成交', value: todayTradeCount.value },
    { label: '维持担保比例', value: fmtPct(a.maintMarginPct) },
  ]
})

async function initialize() {
  initializing.value = true
  initializeError.value = ''
  try {
    await loadPreferences()
    await loadInstruments()
    await Promise.all([loadWorkspace(), loadSession()])
  } catch (initializeError) {
    const message = initializeError?.message || String(initializeError)
    msg.value = message
    msgType.value = 'error'
    // 初始化错误单独保存，避免后续订单提示覆盖恢复界面的故障原因。
    // eslint-disable-next-line no-use-before-define
    setInitializeError(message)
  } finally { initializing.value = false }
}

function setInitializeError(message) {
  initializeError.value = message || '无法读取模拟账户。'
}

function continueWithMarket() {
  emit('navigate-module', '行情')
}

function continueWithResearch() {
  emit('navigate-module', '研究助手')
}

watch(market, async () => {
  chooseDefaultSymbol()
  await loadSession()
})

watch(selectedInstrument, item => {
  if (!item) return
  emit('context-change', {
    market: item.market,
    symbol: item.symbol,
    name: item.name || item.symbol,
    sourceModule: 'sim-trade',
  })
})

onMounted(async () => {
  await initialize()
  if (account.value) {
    startPriceStream()
    polling.start()
  }
})

onBeforeUnmount(() => {
  polling.stop()
  closePriceStream?.()
  closePriceStream = null
})
</script>

<template>
  <div class="sim-terminal-page">
    <DataState v-if="initializing && !account" state="loading" title="正在加载模拟交易终端"
               message="正在同步账户、自选标的、实时行情与挂单。" />
    <section v-else-if="!account" class="terminal-recovery" aria-live="polite">
      <div class="recovery-mark" aria-hidden="true">!</div>
      <div class="recovery-copy">
        <span class="recovery-eyebrow">模拟盘暂不可用</span>
        <h1>交易账户未能加载</h1>
        <p>{{ initializeError || msg || '当前无法读取模拟账户，但行情与研究工作区仍可继续使用。' }}</p>
        <div class="recovery-actions">
          <button type="button" class="recovery-primary" @click="initialize">重新加载</button>
          <button type="button" @click="continueWithMarket">返回行情</button>
          <button type="button" @click="continueWithResearch">继续研究</button>
        </div>
      </div>
      <aside class="recovery-context">
        <span>当前状态</span>
        <strong>{{ initializeError?.includes('登录') ? '会话需要恢复' : '账户服务不可用' }}</strong>
        <small>不会清除当前研究对象、自选标的或已打开的研究页面。</small>
      </aside>
    </section>

    <template v-if="account">
      <header class="sim-toolbar">
        <div class="sim-title">
          <span class="sim-kicker">SIMULATION</span>
          <h1>操作终端</h1>
          <span>行情、持仓与订单执行集中在同一视图</span>
        </div>
        <div class="sim-toolbar-right">
          <div class="market-switch" role="tablist" aria-label="模拟盘市场切换">
            <button v-for="item in marketOptions" :key="item.value" type="button" class="market-tab"
                    role="tab" :aria-selected="market === item.value" :class="{ active: market === item.value }"
                    @click="market = item.value">{{ item.label }}</button>
          </div>
          <span class="session-readout" :class="{ open: marketOpen }">
            <i></i>{{ marketOpen ? '市场开放' : '市场休市' }}
          </span>
        </div>
      </header>

      <div class="symbol-parser">
        <span class="parser-label">标的搜索</span>
        <input v-model="customQuery" class="parser-input"
               :placeholder="market === 'a_share' ? '输入 600519 / SH600519' : market === 'us_stock' ? '输入 AAPL / BRK.B' : '输入 BTC / BTCUSDT'"
               @keyup.enter="resolveCustomInstrument" />
        <button type="button" class="parser-btn" :disabled="resolveLoading || !customQuery.trim()" @click="resolveCustomInstrument">
          {{ resolveLoading ? '解析中…' : '解析并关注' }}
        </button>
        <span class="parser-hint">解析后加入操作端自选，并保持当前市场上下文</span>
      </div>
      <div class="preference-status" aria-live="polite">
        <span v-if="preferenceSaving">自选标的保存中…</span>
        <span v-else-if="preferenceError" class="parser-error">{{ preferenceError }}</span>
        <span v-if="resolveError" class="parser-error">{{ resolveError }}</span>
      </div>

      <div class="sim-content">
        <InstrumentList
          :market-label="currentMarketLabel"
          :default-instruments="currentDefaults"
          :watchlist-instruments="currentWatchlist"
          :hidden-default-count="currentInstruments.filter(item => hiddenDefaultKeys.includes(instrumentKey(item))).length"
          :selected-symbol="selectedSymbol"
          @select="selectInstrument"
          @add-to-watchlist="addToWatchlist"
          @remove-watchlist="removeFromWatchlist"
          @remove-default="removeDefault"
          @restore-defaults="restoreDefaults"
        />
        <TradingTerminal
          :account="account"
          :realtime-prices="realtimePrices"
          :jd-prices="jdPrices"
          :open-orders="openOrders"
          :instruments="displayedInstruments"
          :selected-symbol="selectedSymbol"
          :session-open="marketOpen"
          :submitting="submitting"
          :message="msg"
          :message-type="msgType"
          @select-symbol="selectInstrument"
          @submit="submitOrder"
          @update-order="updateOrder"
          @cancel-order="cancelOrder"
        />
      </div>

      <section class="account-overview" aria-label="账户运营概览">
        <header>
          <div><strong>账户运营概览</strong><span>当前账户权益与风控 · 30 秒自动刷新</span></div>
          <span class="risk-pill" :class="RISK_STATUS[account.riskStatus]?.cls || 'info'">风控: {{ RISK_STATUS[account.riskStatus]?.text || account.riskStatus || '—' }}</span>
        </header>
        <div class="overview-cards">
          <div v-for="card in overviewCards" :key="card.label" class="overview-card">
            <span class="overview-label">{{ card.label }}</span>
            <b class="overview-value" :class="card.signed != null ? (card.signed >= 0 ? 'pos' : 'neg') : ''">{{ card.value }}</b>
          </div>
        </div>
      </section>

      <section class="trade-history" aria-label="模拟成交历史">
        <header><div><strong>成交历史</strong><span>最近 50 笔 / 共 {{ tradeTotal }} 笔</span></div><button type="button" @click="loadWorkspace">刷新</button></header>
        <p v-if="!trades.length" class="trade-empty">暂无成交记录。</p>
        <div v-else class="trade-table-wrap">
          <table class="trade-table">
            <thead><tr><th>时间</th><th>方向</th><th>标的</th><th>价格</th><th>数量</th><th>金额</th><th>杠杆</th></tr></thead>
            <tbody>
              <tr v-for="trade in trades" :key="trade.id">
                <td>{{ String(trade.createdAt || '').replace('T', ' ').slice(0, 19) || '—' }}</td>
                <td><span :class="trade.type === 'BUY' ? 'trade-buy' : 'trade-sell'">{{ trade.type }}</span></td>
                <td><strong>{{ trade.symbol }}</strong></td>
                <td>{{ trade.price }}</td>
                <td>{{ trade.quantity }}</td>
                <td>{{ trade.amount }}</td>
                <td>{{ trade.leverage || 1 }}x</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.sim-terminal-page { display: flex; flex-direction: column; gap: 8px; margin: 0; min-width: 0; }
.sim-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 58px; padding: 0 0 10px; border-bottom: 1px solid color-mix(in srgb, var(--line) 72%, transparent); }
.sim-title { display: grid; gap: 5px; }
.sim-kicker { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.sim-title h1 { margin: 0; color: var(--text); font-size: 18px; line-height: 1; font-weight: 650; letter-spacing: -.02em; }
.sim-title > span:last-child { color: var(--muted); font-size: 9px; }
.sim-toolbar-right { display: flex; align-items: flex-end; gap: 24px; }
.market-switch { display: flex; align-items: stretch; gap: 20px; align-self: stretch; }
.market-tab { position: relative; border: 0; background: transparent; color: var(--muted); padding: 0 1px 9px; font-size: 9px; font-weight: 620; cursor: pointer; white-space: nowrap; transition: color var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.market-tab.active { color: var(--text); font-weight: 650; }
.market-tab:hover { color: var(--text); }
.market-tab:active { transform: translateY(1px); }
.market-tab.active::after { content: ''; position: absolute; left: 0; width: 18px; bottom: -1px; height: 2px; border-radius: 2px; background: var(--accent); }
.session-readout { display: inline-flex; align-items: center; gap: 6px; min-height: 26px; color: var(--muted); font-size: 8px; font-weight: 600; white-space: nowrap; }
.session-readout i { width: 5px; height: 5px; border-radius: 50%; background: var(--bad); }
.session-readout.open i { background: var(--ok); }
.symbol-parser { display: grid; grid-template-columns: auto minmax(180px, 270px) auto minmax(0, 1fr); align-items: center; gap: 9px; min-height: 38px; padding: 0; background: transparent; border: 0; border-bottom: 1px solid color-mix(in srgb, var(--line) 68%, transparent); border-radius: 0; }
.parser-label { color: var(--subtle); font-size: 8px; font-weight: 600; white-space: nowrap; }
.parser-input { flex: 0 1 260px; min-width: 140px; height: 29px; background: color-mix(in srgb, var(--workspace-control-bg, var(--surface)) 54%, transparent); border: 1px solid color-mix(in srgb, var(--line) 76%, transparent); border-radius: 7px; color: var(--text); padding: 0 8px; font-size: 9px; outline: none; transition: border-color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease; }
.parser-input:focus { border-color: var(--workspace-focus); background: var(--workspace-control-bg, var(--surface)); }
.parser-btn { min-height: 29px; border: 1px solid color-mix(in srgb, var(--accent) 44%, var(--line)); border-radius: 7px; background: color-mix(in srgb, var(--accent) 6%, transparent); color: var(--accent-strong); padding: 5px 10px; cursor: pointer; font-size: 8px; font-weight: 650; white-space: nowrap; transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.parser-btn:hover:not(:disabled) { background: color-mix(in srgb, var(--accent) 10%, transparent); }
.parser-btn:active:not(:disabled) { transform: scale(.98); }
.parser-btn:disabled { opacity: .45; cursor: not-allowed; }
.parser-hint, .preference-status { color: var(--subtle); font-size: 9px; }
.preference-status { min-height: 10px; display: flex; gap: 10px; }
.parser-error { color: var(--warn); }
.sim-content { display: grid; grid-template-columns: clamp(148px, 10.5vw, 164px) minmax(0, 1fr); gap: 0; align-items: stretch; min-width: 0; border: 0; border-top: 1px solid color-mix(in srgb, var(--line) 72%, transparent); border-bottom: 1px solid color-mix(in srgb, var(--line) 72%, transparent); }
.account-overview { margin-top: 4px; border: 1px solid color-mix(in srgb, var(--line) 72%, transparent); }
.account-overview > header { min-height: 40px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 10px; border-bottom: 1px solid var(--line); }
.account-overview > header > div { display: flex; align-items: baseline; gap: 8px; }
.account-overview header strong { color: var(--text); font-size: 9px; }
.account-overview header span:not(.risk-pill) { color: var(--subtle); font-size: 7px; }
.risk-pill { display: inline-flex; align-items: center; height: 22px; padding: 0 9px; border: 1px solid var(--line); color: var(--muted); font: 650 8px/1 ui-monospace, monospace; white-space: nowrap; }
.risk-pill.safe { border-color: transparent; background: color-mix(in srgb, var(--ok) 14%, transparent); color: var(--ok); }
.risk-pill.warn { border-color: transparent; background: color-mix(in srgb, var(--warn) 14%, transparent); color: var(--warn); }
.risk-pill.risk { border-color: transparent; background: color-mix(in srgb, var(--bad) 14%, transparent); color: var(--bad); }
.risk-pill.info { border-color: transparent; background: color-mix(in srgb, var(--accent) 12%, transparent); color: var(--accent-strong); }
.overview-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(128px, 1fr)); gap: 1px; background: var(--line); }
.overview-card { display: flex; flex-direction: column; gap: 6px; padding: 13px 12px; background: var(--workspace-surface, var(--surface-2, transparent)); }
.overview-label { color: var(--subtle); font-size: 8px; letter-spacing: .06em; }
.overview-value { color: var(--text); font: 650 15px/1.1 ui-monospace, monospace; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.overview-value.pos { color: var(--ok); }
.overview-value.neg { color: var(--bad); }
.trade-history { margin-top: 4px; border: 1px solid color-mix(in srgb, var(--line) 72%, transparent); }
.trade-history > header { min-height: 40px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 10px; border-bottom: 1px solid var(--line); }
.trade-history > header > div { display: flex; align-items: baseline; gap: 8px; }
.trade-history header strong { color: var(--text); font-size: 9px; }
.trade-history header span { color: var(--subtle); font-size: 7px; }
.trade-history header button { min-height: 24px; padding: 0 7px; border: 1px solid var(--line); background: transparent; color: var(--muted); cursor: pointer; font-size: 7px; }
.trade-table-wrap { overflow-x: auto; }
.trade-table { width: 100%; min-width: 720px; border-collapse: collapse; font-size: 8px; }
.trade-table th, .trade-table td { padding: 8px 10px; border-bottom: 1px solid var(--line); color: var(--muted); text-align: left; }
.trade-table th { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; }
.trade-table tbody tr:last-child td { border-bottom: 0; }
.trade-table td strong { color: var(--text); }
.trade-buy { color: var(--ok); }
.trade-sell { color: var(--bad); }
.trade-empty { margin: 0; padding: 18px 10px; color: var(--muted); font-size: 8px; }
.terminal-recovery {
  min-height: 310px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 250px;
  align-items: center;
  gap: 24px;
  padding: 34px 38px;
  border: 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.recovery-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--bad) 58%, var(--line));
  border-radius: 0;
  color: var(--bad);
  font: 650 15px/1 ui-monospace, monospace;
}
.recovery-copy { max-width: 620px; }
.recovery-eyebrow { color: var(--subtle); font-size: 9px; letter-spacing: .04em; }
.recovery-copy h1 { margin: 8px 0 7px; color: var(--text); font-size: 20px; line-height: 1.2; font-weight: 650; letter-spacing: -.015em; }
.recovery-copy p { margin: 0; max-width: 590px; color: var(--muted); font-size: 10px; line-height: 1.65; }
.recovery-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-top: 18px; }
.recovery-actions button {
  min-height: 32px;
  border: 1px solid var(--line-strong);
  border-radius: 0;
  background: transparent;
  color: var(--muted);
  padding: 0 11px;
  cursor: pointer;
  font-size: 9px;
}
.recovery-actions button:hover { color: var(--text); border-color: var(--accent); }
.recovery-actions .recovery-primary { border-color: var(--accent); border-left-width: 2px; background: transparent; color: var(--accent-strong); }
.recovery-context {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7px;
  padding-left: 22px;
  border-left: 1px solid var(--line);
}
.recovery-context span { color: var(--subtle); font-size: 8px; }
.recovery-context strong { color: var(--text); font-size: 11px; font-weight: 620; }
.recovery-context small { color: var(--muted); font-size: 9px; line-height: 1.55; }
@media (max-width: 1120px) {
  .sim-terminal-page { gap: 6px; }
  .sim-toolbar { min-height: 50px; padding-bottom: 8px; }
  .sim-title { gap: 3px; }
  .sim-title > span:last-child { display: none; }
  .symbol-parser { min-height: 34px; grid-template-columns: auto minmax(160px, 1fr) auto; }
  .parser-hint { display: none; }
  .preference-status { min-height: 0; }
  .sim-content { grid-template-columns: 148px minmax(0, 1fr); }
}
@media (max-width: 700px) {
  .sim-toolbar { align-items: flex-start; flex-direction: column; }
  .sim-toolbar-right { width: 100%; justify-content: space-between; }
  .market-switch { overflow-x: auto; }
  .symbol-parser { align-items: stretch; flex-wrap: wrap; }
  .symbol-parser { display: flex; min-height: auto; padding: 8px 0; }
  .parser-input { flex: 1 1 180px; }
  .parser-hint { width: 100%; }
  .sim-content { grid-template-columns: 1fr; }
  .terminal-recovery { grid-template-columns: auto minmax(0, 1fr); padding: 24px 20px; }
  .recovery-context { grid-column: 1 / -1; border-left: 0; border-top: 1px solid var(--line); padding: 16px 0 0; }
}
.market-tab:focus-visible,
.parser-btn:focus-visible,
.parser-input:focus-visible { outline: 2px solid color-mix(in srgb, var(--accent) 65%, transparent); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) { .market-tab, .parser-btn, .parser-input { transition: none !important; } }
</style>
