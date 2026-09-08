<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import AccountStrip from './trading/AccountStrip.vue'
import MarketTape from './trading/MarketTape.vue'
import OrderTicket from './trading/OrderTicket.vue'
import PositionsTable from './trading/PositionsTable.vue'
import TradeHistory from './trading/TradeHistory.vue'
import { usePolling } from '../composables/usePolling'

const account = ref(null)
const trades = ref([])
const initializing = ref(true)
const msg = ref('')
const msgType = ref('info')
const realtimePrices = ref(null)
const jdPrices = ref(null)
const submitting = ref(false)
let pendingOrderAttempt = null
let closePriceStream = null

const order = reactive({
  symbol: 'sh518850',
  type: 'BUY',
  quantity: 100,
  leverage: 1,
})

const selectedQuote = computed(() => {
  if (order.symbol === 'sh518850') return realtimePrices.value?.gold_etf || null
  if (order.symbol === 'hf_XAU') return realtimePrices.value?.london_gold || null
  if (order.symbol === 'jd_zheshang') return jdPrices.value?.zheshang || null
  if (order.symbol === 'jd_minsheng') return jdPrices.value?.minsheng || null
  return null
})
const selectedPrice = computed(() => Number(selectedQuote.value?.price || 0))
const estimatedNotional = computed(() => selectedPrice.value * Math.max(0, Number(order.quantity || 0)))
const estimatedMargin = computed(() => order.type === 'BUY'
  ? estimatedNotional.value / Math.max(1, Number(order.leverage || 1))
  : estimatedNotional.value)
const orderReady = computed(() => selectedPrice.value > 0
  && Number(order.quantity) > 0
  && !selectedQuote.value?.stale)

async function loadJdLive() {
  try {
    const response = await api.jdPrices()
    if (response.code === 200 && response.data && Object.keys(response.data).length) {
      jdPrices.value = response.data
    }
  } catch (_) { /* 保留上一次有效值 */ }
}

function startPriceStream() {
  if (closePriceStream) return
  closePriceStream = api.marketPriceStream(payload => {
    if (payload?.market && Object.keys(payload.market).length) realtimePrices.value = payload.market
    if (payload?.jd && Object.keys(payload.jd).length) jdPrices.value = payload.jd
  })
}

async function loadRealtime() {
  try {
    const response = await api.marketPrices()
    realtimePrices.value = response.data || null
  } catch (_) { /* 保留上一次有效值 */ }
}

async function load() {
  try {
    const [accountResponse, tradesResponse] = await Promise.all([
      api.simAccount(),
      api.simTrades(20),
      loadRealtime(),
    ])
    if (accountResponse.code !== 200) throw new Error(accountResponse.message || '模拟账户加载失败')
    if (tradesResponse.code !== 200) throw new Error(tradesResponse.message || '成交记录加载失败')
    account.value = accountResponse.data
    trades.value = tradesResponse.data?.trades || []
  } catch (error) {
    msg.value = '加载失败: ' + error
    msgType.value = 'error'
  }
}

async function submitOrder() {
  if (submitting.value) return
  if (selectedQuote.value?.stale) {
    msg.value = '源行情时间已陈旧，已暂停下单，请等待新行情。'
    msgType.value = 'error'
    return
  }
  msg.value = ''
  submitting.value = true

  const current = {
    type: order.type,
    symbol: order.symbol,
    quantity: Number(order.quantity),
    // 后端规定卖出订单杠杆必须为 1；界面隐藏杠杆控件时也要同步修正请求参数。
    leverage: order.type === 'SELL' ? 1 : Number(order.leverage),
  }
  const samePending = pendingOrderAttempt
    && pendingOrderAttempt.type === current.type
    && pendingOrderAttempt.symbol === current.symbol
    && pendingOrderAttempt.quantity === current.quantity
    && pendingOrderAttempt.leverage === current.leverage
  if (!samePending) pendingOrderAttempt = { ...current, id: crypto.randomUUID() }

  try {
    const response = await api.simOrder(
      current.type, current.symbol, current.quantity, current.leverage, pendingOrderAttempt.id,
    )
    pendingOrderAttempt = null
    if (response.code === 200) {
      const data = response.data
      const extra = data.leverage > 1 ? ` | 保证金 ${data.margin}, 借款 ${data.loan}` : ''
      msg.value = response.message + ' | ' + data.message + extra
      msgType.value = 'ok'
      await load()
    } else {
      msg.value = response.message
      msgType.value = 'error'
    }
  } catch (error) {
    // 网络异常时保留 clientOrderId，重试相同订单继续使用同一个幂等号。
    msg.value = '下单失败: ' + error
    msgType.value = 'error'
  } finally {
    submitting.value = false
  }
}

async function quickSell(symbol, quantity) {
  if (!confirm(`确认卖出全部 ${quantity} 股(${symbol})?`)) return
  try {
    const response = await api.simOrder('SELL', symbol, Number(quantity), 1)
    msg.value = response.data?.message || response.message
    msgType.value = response.code === 200 ? 'ok' : 'error'
    await load()
  } catch (error) {
    msg.value = '卖出失败: ' + error
    msgType.value = 'error'
  }
}

const polling = usePolling(async () => {
  await Promise.all([load(), loadJdLive()])
}, 30000)

async function initialize() {
  initializing.value = true
  await Promise.all([load(), loadJdLive()])
  initializing.value = false
}

onMounted(async () => {
  await initialize()
  startPriceStream()
  polling.start()
})

onBeforeUnmount(() => closePriceStream?.())
</script>

<template>
  <div class="sim">
    <div class="sim-head">
      <div><h2>模拟交易工作台</h2><span>账户资产、订单票据、持仓与成交记录</span></div>
      <span class="sim-mode">PAPER TRADING</span>
    </div>

    <DataState v-if="initializing && !account" state="loading" title="正在加载模拟账户"
               message="正在同步账户资产、行情与最近成交。" />
    <DataState v-else-if="!account && msgType === 'error'" state="error" title="模拟账户加载失败"
               :message="msg" retryable @retry="initialize" />

    <template v-if="account">
      <AccountStrip :account="account" />
      <MarketTape :realtime-prices="realtimePrices" :jd-prices="jdPrices" />

      <div class="trade-layout">
      <OrderTicket
        v-model:symbol="order.symbol"
        v-model:type="order.type"
        v-model:quantity="order.quantity"
        v-model:leverage="order.leverage"
        :selected-price="selectedPrice"
        :estimated-notional="estimatedNotional"
        :estimated-margin="estimatedMargin"
        :ready="orderReady"
        :submitting="submitting"
        :message="msg"
        :message-type="msgType"
        @submit="submitOrder"
      />
        <PositionsTable :account="account" @quick-sell="quickSell" />
      </div>

      <TradeHistory :trades="trades" />
    </template>
  </div>
</template>

<style scoped>
.sim { display: flex; flex-direction: column; gap: 10px; }
.sim-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; min-height: 38px; }
.sim-head h2 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; }
.sim-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.sim-mode { margin: 0 !important; color: var(--accent-strong) !important; border: 1px solid #51462f; background: rgba(201,166,95,.05); border-radius: 3px; padding: 3px 6px; font-size: 8px !important; font-weight: 700; letter-spacing: .08em; }
.trade-layout { display: grid; grid-template-columns: 310px minmax(0, 1fr); gap: 10px; align-items: stretch; }
@media (max-width: 860px) { .trade-layout { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .sim-head { align-items: flex-start; flex-direction: column; } }
</style>
