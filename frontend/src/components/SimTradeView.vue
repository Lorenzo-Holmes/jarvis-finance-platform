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
const emit = defineEmits(['context-change'])

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
  const [accountResponse, ordersResponse, marketResponse, jdResponse] = await Promise.all([
    api.simAccount(), api.simOpenOrders(), api.marketPrices().catch(() => null), api.jdPrices().catch(() => null),
  ])
  if (accountResponse?.code !== 200) throw new Error(accountResponse?.message || '模拟账户加载失败')
  if (ordersResponse?.code !== 200) throw new Error(ordersResponse?.message || '挂单加载失败')
  account.value = accountResponse.data
  openOrders.value = Array.isArray(ordersResponse.data) ? ordersResponse.data : []
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

async function initialize() {
  initializing.value = true
  try {
    await loadPreferences()
    await loadInstruments()
    await Promise.all([loadWorkspace(), loadSession()])
  } catch (initializeError) {
    msg.value = initializeError?.message || String(initializeError)
    msgType.value = 'error'
  } finally { initializing.value = false }
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
    <DataState v-else-if="!account" state="error" title="模拟交易终端加载失败"
               :message="msg || '无法读取模拟账户。'" retryable @retry="initialize" />

    <template v-if="account">
      <header class="sim-toolbar">
        <div class="sim-title"><h1>EXECUTION / SIM TRADING</h1><span>专业图表 · 自选标的 · 模拟下单 · 持仓与止损</span></div>
        <div class="market-switch" role="tablist" aria-label="模拟盘市场切换">
          <button v-for="item in marketOptions" :key="item.value" type="button" class="market-tab"
                  role="tab" :aria-selected="market === item.value" :class="{ active: market === item.value }"
                  @click="market = item.value">{{ item.label }}</button>
        </div>
      </header>

      <div class="symbol-parser">
        <span class="parser-label">INSTRUMENT / 导入自选</span>
        <input v-model="customQuery" class="parser-input"
               :placeholder="market === 'a_share' ? '输入 600519 / SH600519' : market === 'us_stock' ? '输入 AAPL / BRK.B' : '输入 BTC / BTCUSDT'"
               @keyup.enter="resolveCustomInstrument" />
        <button type="button" class="parser-btn" :disabled="resolveLoading || !customQuery.trim()" @click="resolveCustomInstrument">
          {{ resolveLoading ? 'RESOLVING…' : 'RESOLVE + WATCH' }}
        </button>
        <span class="parser-hint">解析成功后自动持久化，可直接用于模拟交易</span>
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
    </template>
  </div>
</template>

<style scoped>
.sim-terminal-page { display: flex; flex-direction: column; gap: 10px; margin: 0; min-width: 0; }
.sim-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 50px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.sim-title h1 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .11em; }
.sim-title span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.market-switch { display: flex; align-items: stretch; gap: 22px; align-self: stretch; }
.market-tab { position: relative; border: 0; background: transparent; color: var(--muted); padding: 0 1px 9px; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; white-space: nowrap; }
.market-tab.active { color: var(--text); font-weight: 650; }
.market-tab.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--accent); }
.symbol-parser { display: flex; align-items: center; gap: 8px; padding: 8px 0; background: transparent; border: 0; border-bottom: 1px solid var(--line); border-radius: 0; }
.parser-label { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .07em; white-space: nowrap; }
.parser-input { flex: 0 1 260px; min-width: 140px; height: 30px; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); border-radius: 0; color: var(--text); padding: 0 6px; font-size: 10px; outline: none; }
.parser-input:focus { border-color: #6a5b40; }
.parser-btn { min-height: 30px; border: 1px solid #373a32; border-radius: 0; background: #373a32; color: #f2eee6; padding: 5px 11px; cursor: pointer; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .07em; white-space: nowrap; }
.parser-btn:disabled { opacity: .45; cursor: not-allowed; }
.parser-hint, .preference-status { color: var(--subtle); font-size: 9px; }
.preference-status { min-height: 12px; display: flex; gap: 10px; }
.parser-error { color: var(--warn); }
.sim-content { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 0; align-items: stretch; min-width: 0; border: 1px solid var(--line); }
@media (max-width: 900px) { .sim-content { grid-template-columns: 190px minmax(0, 1fr); } }
@media (max-width: 700px) {
  .sim-toolbar { align-items: flex-start; flex-direction: column; }
  .market-switch { width: 100%; overflow-x: auto; }
  .symbol-parser { align-items: stretch; flex-wrap: wrap; }
  .parser-input { flex: 1 1 180px; }
  .parser-hint { width: 100%; }
  .sim-content { grid-template-columns: 1fr; }
}
</style>
