<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../../api/client'
import { getMultiMarketChartOptions, useMarketChart } from '../../composables/useMarketChart'

const props = defineProps({
  account: { type: Object, default: null },
  realtimePrices: { type: Object, default: null },
  jdPrices: { type: Object, default: null },
  openOrders: { type: Array, default: () => [] },
  instruments: { type: Array, default: () => [] },
  selectedSymbol: { type: String, default: '' },
  sessionOpen: { type: Boolean, default: true },
  submitting: { type: Boolean, default: false },
  message: { type: String, default: '' },
  messageType: { type: String, default: 'info' },
})

const emit = defineEmits(['submit', 'update-order', 'cancel-order', 'select-symbol'])

const DEFAULT_INSTRUMENTS = [
  { symbol: 'sh518850', ticker: '518850', name: '黄金ETF华夏', market: 'gold_etf', quoteKey: 'gold_etf', kind: 'market', currency: '¥' },
  { symbol: 'hf_XAU', ticker: 'XAU', name: '伦敦金', market: 'london_gold', quoteKey: 'london_gold', kind: 'market', currency: '$' },
  { symbol: 'jd_zheshang', ticker: 'JD-ZS', name: '浙商积存金', market: 'zheshang', quoteKey: 'zheshang', kind: 'jd', currency: '¥' },
  { symbol: 'jd_minsheng', ticker: 'JD-MS', name: '民生积存金', market: 'minsheng', quoteKey: 'minsheng', kind: 'jd', currency: '¥' },
]

const rangeOptions = [
  { key: '1D', limit: 40 },
  { key: '1W', limit: 80 },
  { key: '1M', limit: 120 },
  { key: '3M', limit: 160 },
  { key: 'YTD', limit: 200 },
  { key: '1Y', limit: 220 },
  { key: '5Y', limit: 250 },
  { key: 'All', limit: 250 },
]

const intervalOptions = [
  { value: '1', label: '1m' },
  { value: '5', label: '5m' },
  { value: '15', label: '15m' },
  { value: '30', label: '30m' },
  { value: '60', label: '1h' },
  { value: 'day', label: '1D' },
]
const extendedIntervalOptions = [
  { value: '1d', label: '1D' },
  { value: '5m', label: '5m' },
  { value: '10m', label: '10m' },
  { value: '15m', label: '15m' },
  { value: '30m', label: '30m' },
  { value: '1h', label: '1h' },
]

const extendedQuote = ref(null)
const activeSymbol = ref(props.selectedSymbol || 'sh518850')
const selectedRange = ref('1D')
const selectedInterval = ref('15')
const klineData = ref([])
const chartError = ref('')
const chartLoading = ref(false)
const modalOpen = ref(false)
const editingOrderId = ref(null)
const positionMarkerY = ref(null)
const stopMarkerY = ref(null)
const orderState = reactive({
  side: 'SELL',
  orderType: 'STOP_MARKET',
  quantity: 1,
  leverage: 1,
  stopPrice: 0,
  timeInForce: 'DAY',
})

const marketChart = useMarketChart()
const chartRef = marketChart.elementRef

const terminalInstruments = computed(() => {
  const source = props.instruments.length ? props.instruments : DEFAULT_INSTRUMENTS
  return source.map(item => {
    const legacyMarket = item.legacyMarket || (!['a_share', 'us_stock', 'crypto'].includes(item.market) ? item.market : null)
    const kind = item.kind || (legacyMarket ? (legacyMarket.startsWith('jd_') ? 'jd' : 'market') : 'extended')
    const currency = item.currency === 'CNY' ? '¥' : item.currency === 'USD' ? '$' : item.currency || '·'
    return {
      ...item,
      ticker: item.ticker || item.symbol,
      market: legacyMarket || item.market,
      quoteKey: item.quoteKey || legacyMarket || item.symbol,
      kind,
      currency,
    }
  })
})
const activeInstrument = computed(() => terminalInstruments.value.find(item => item.symbol === activeSymbol.value)
  || terminalInstruments.value[0] || DEFAULT_INSTRUMENTS[0])
const currentQuote = computed(() => activeInstrument.value.kind === 'extended'
  ? extendedQuote.value
  : activeInstrument.value.kind === 'jd'
  ? props.jdPrices?.[activeInstrument.value.quoteKey] || null
  : props.realtimePrices?.[activeInstrument.value.quoteKey] || null)
const currentPrice = computed(() => Number(currentQuote.value?.price || 0))
const quoteStale = computed(() => Boolean(currentQuote.value?.stale))
const activePosition = computed(() => props.account?.positions?.[activeSymbol.value] || null)
const availableQuantity = computed(() => Number(activePosition.value?.quantity || 0))
const activeOpenOrder = computed(() => props.openOrders.find(order =>
  order?.symbol === activeSymbol.value && ['OPEN', 'TRIGGERING'].includes(String(order?.status || '').toUpperCase())) || null)
const activeRange = computed(() => rangeOptions.find(item => item.key === selectedRange.value) || rangeOptions[0])
const visibleIntervals = computed(() => activeInstrument.value.kind === 'extended'
  ? extendedIntervalOptions
  : activeInstrument.value.kind === 'jd'
    ? intervalOptions.filter(item => item.value !== 'day')
    : intervalOptions)
const latestBar = computed(() => klineData.value.at(-1) || {})
const livePnl = computed(() => {
  const position = activePosition.value
  if (!position || currentPrice.value <= 0) return Number(position?.profit || 0)
  return (currentPrice.value - Number(position.avgCost || 0)) * Number(position.quantity || 0)
})
const previewStop = computed(() => modalOpen.value && orderState.orderType === 'STOP_MARKET'
  ? Number(orderState.stopPrice || 0)
  : Number(activeOpenOrder.value?.stopPrice || 0))
const positionPrice = computed(() => Number(activePosition.value?.avgCost || activePosition.value?.currentPrice || 0))
const estimatedAmount = computed(() => {
  const px = orderState.orderType === 'STOP_MARKET' ? Number(orderState.stopPrice || 0) : currentPrice.value
  return px * Math.max(0, Number(orderState.quantity || 0))
})
const stopRelationError = computed(() => {
  if (orderState.orderType !== 'STOP_MARKET' || currentPrice.value <= 0 || Number(orderState.stopPrice || 0) <= 0) return ''
  if (orderState.side === 'SELL' && Number(orderState.stopPrice) >= currentPrice.value) return '卖出止损价需低于当前价'
  if (orderState.side === 'BUY' && Number(orderState.stopPrice) <= currentPrice.value) return '买入止损价需高于当前价'
  return ''
})
const canSubmit = computed(() => {
  const qty = Number(orderState.quantity || 0)
  if (props.submitting || !props.sessionOpen || qty <= 0 || currentPrice.value <= 0 || quoteStale.value) return false
  if (orderState.side === 'SELL' && qty > availableQuantity.value) return false
  if (orderState.orderType === 'STOP_MARKET' && (Number(orderState.stopPrice || 0) <= 0 || stopRelationError.value)) return false
  return true
})

function priceDigits() {
  return activeInstrument.value.kind === 'jd' ? 2 : 3
}

function formatPrice(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '—'
  return number.toFixed(priceDigits())
}

function formatMoney(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return `${activeInstrument.value.currency}0.00`
  return `${activeInstrument.value.currency}${number.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatAccountMoney(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return '¥0.00'
  return `¥${number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function signed(value, digits = 2) {
  const number = Number(value || 0)
  const prefix = number > 0 ? '+' : ''
  return `${prefix}${number.toFixed(digits)}`
}

function signedMoney(value) {
  const number = Number(value || 0)
  const prefix = number > 0 ? '+' : number < 0 ? '−' : ''
  return `${prefix}${activeInstrument.value.currency}${Math.abs(number).toFixed(3)}`
}

function cycleSymbol() {
  const list = terminalInstruments.value
  const index = list.findIndex(item => item.symbol === activeSymbol.value)
  const next = list[(index + 1) % list.length]
  if (next) {
    activeSymbol.value = next.symbol
    emit('select-symbol', next.symbol)
  }
}

function normalizeExtendedInterval() {
  if (activeInstrument.value.kind !== 'extended') return selectedInterval.value
  if (extendedIntervalOptions.some(item => item.value === selectedInterval.value)) return selectedInterval.value
  selectedInterval.value = '15m'
  return selectedInterval.value
}

function setRange(key) {
  selectedRange.value = key
}

async function loadKline() {
  chartLoading.value = true
  chartError.value = ''
  try {
    let rows = []
    const instrument = activeInstrument.value
    const limit = activeRange.value.limit
    if (instrument.kind === 'extended') {
      const interval = normalizeExtendedInterval()
      const [quoteResponse, klineResponse] = await Promise.all([
        api.marketAssetQuote(instrument.market, instrument.symbol),
        api.marketAssetKline(instrument.market, instrument.symbol, interval, Math.min(limit, 500)),
      ])
      if (quoteResponse?.code !== 200) throw new Error(quoteResponse?.message || '报价加载失败')
      if (klineResponse?.code !== 200) throw new Error(klineResponse?.message || 'K线加载失败')
      extendedQuote.value = quoteResponse.data
      rows = klineResponse.data?.data || []
    } else if (instrument.kind === 'jd') {
      const response = await api.jdKline(instrument.market, Number(selectedInterval.value), Math.min(limit, 500))
      const body = response?.data?.data ? response.data : response
      rows = body?.data || []
    } else {
      const response = await api.marketKline({ market: instrument.market, interval: selectedInterval.value, limit })
      const body = response?.data
      rows = Array.isArray(body) ? body : (body?.data || [])
    }
    klineData.value = Array.isArray(rows) ? rows : []
    await nextTick()
    await renderChart()
  } catch (error) {
    chartError.value = error?.message || String(error)
    klineData.value = []
    positionMarkerY.value = null
    stopMarkerY.value = null
    marketChart.clear()
  } finally {
    chartLoading.value = false
  }
}

async function renderChart() {
  if (!klineData.value.length) return
  await marketChart.renderCandles(klineData.value, getMultiMarketChartOptions({
    visibleCount: activeRange.value.limit,
  }))
  attachChartEvents()
  renderAnnotations()
}

function attachChartEvents() {
  const instance = marketChart.getChart()
  if (!instance) return
  instance.off('click', handleChartClick)
  instance.off('datazoom', syncAnnotationPixels)
  instance.off('finished', syncAnnotationPixels)
  instance.on('click', handleChartClick)
  instance.on('datazoom', syncAnnotationPixels)
  instance.on('finished', syncAnnotationPixels)
}

function handleChartClick(params) {
  if (params?.dataType === 'markLine' && params?.name === 'stop-order') editActiveStop()
}

function toYAxisPixel(price) {
  const instance = marketChart.getChart()
  if (!instance || !Number.isFinite(Number(price)) || Number(price) <= 0) return null
  const pixel = instance.convertToPixel({ yAxisIndex: 0 }, Number(price))
  const value = Array.isArray(pixel) ? pixel.at(-1) : pixel
  return Number.isFinite(Number(value)) ? Number(value) : null
}

function syncAnnotationPixels() {
  positionMarkerY.value = activePosition.value ? toYAxisPixel(positionPrice.value) : null
  stopMarkerY.value = previewStop.value > 0 ? toYAxisPixel(previewStop.value) : null
}

function renderAnnotations() {
  const instance = marketChart.getChart()
  if (!instance) return
  const lines = []

  if (activePosition.value && Number(activePosition.value.quantity || 0) > 0 && positionPrice.value > 0) {
    lines.push({
      name: 'position',
      yAxis: positionPrice.value,
      lineStyle: { color: '#5d6d35', width: 1, type: 'dashed' },
      label: { show: false },
    })
  }

  if (previewStop.value > 0) {
    lines.push({
      name: 'stop-order',
      yAxis: previewStop.value,
      lineStyle: { color: '#6a383b', width: 1, type: 'dashed' },
      label: { show: false },
    })
  }

  const bandHalf = previewStop.value > 0
    ? Math.max(previewStop.value * 0.00035, activeInstrument.value.kind === 'jd' ? 0.04 : 0.004)
    : 0

  instance.setOption({
    series: [{
      name: 'K线',
      markLine: {
        silent: false,
        animation: false,
        symbol: ['none', 'none'],
        data: lines,
      },
      markArea: {
        silent: true,
        animation: false,
        itemStyle: { color: 'rgba(255,255,255,.035)' },
        data: previewStop.value > 0 ? [[
          { yAxis: previewStop.value - bandHalf },
          { yAxis: previewStop.value + bandHalf },
        ]] : [],
      },
    }],
  }, false)
  requestAnimationFrame(syncAnnotationPixels)
}

function resetOrder(side) {
  const positionQty = availableQuantity.value
  orderState.side = side
  orderState.orderType = side === 'SELL' && positionQty > 0 ? 'STOP_MARKET' : 'MARKET'
  orderState.quantity = side === 'SELL' && positionQty > 0 ? Math.max(1, Math.min(positionQty, 8)) : 1
  orderState.leverage = 1
  const px = currentPrice.value
  orderState.stopPrice = px > 0
    ? Number((side === 'SELL' ? px * 0.993 : px * 1.007).toFixed(priceDigits()))
    : 0
  orderState.timeInForce = 'DAY'
  editingOrderId.value = null
}

function openOrder(side) {
  resetOrder(side)
  modalOpen.value = true
  nextTick(renderAnnotations)
}

function closeModal() {
  modalOpen.value = false
  editingOrderId.value = null
  nextTick(renderAnnotations)
}

function editActiveStop() {
  const order = activeOpenOrder.value
  if (!order || String(order.status).toUpperCase() !== 'OPEN') return
  orderState.side = order.side
  orderState.orderType = order.orderType
  orderState.quantity = Number(order.quantity)
  orderState.leverage = Number(order.leverage || 1)
  orderState.stopPrice = Number(order.stopPrice)
  orderState.timeInForce = order.timeInForce || 'DAY'
  editingOrderId.value = order.id
  modalOpen.value = true
  nextTick(renderAnnotations)
}

function stepQuantity(delta) {
  const next = Math.max(1, Number(orderState.quantity || 0) + delta)
  orderState.quantity = orderState.side === 'SELL' && availableQuantity.value > 0
    ? Math.min(next, availableQuantity.value)
    : next
}

function stepStop(delta) {
  const step = activeInstrument.value.kind === 'jd' ? 0.01 : 0.001
  orderState.stopPrice = Math.max(step, Number((Number(orderState.stopPrice || 0) + delta * step).toFixed(priceDigits())))
}

function submitOrder() {
  if (!canSubmit.value) return
  if (editingOrderId.value) {
    emit('update-order', { id: editingOrderId.value, stopPrice: Number(orderState.stopPrice) })
    return
  }
  emit('submit', {
    side: orderState.side,
    symbol: activeSymbol.value,
    quantity: Number(orderState.quantity),
    leverage: Number(orderState.leverage || 1),
    orderType: orderState.orderType,
    stopPrice: orderState.orderType === 'STOP_MARKET' ? Number(orderState.stopPrice) : undefined,
    timeInForce: orderState.timeInForce,
  })
}

function selectSymbol(symbol) {
  if (!terminalInstruments.value.some(item => item.symbol === symbol)) return
  activeSymbol.value = symbol
  emit('select-symbol', symbol)
}

function cancelEditingOrder() {
  if (!editingOrderId.value) return
  emit('cancel-order', editingOrderId.value)
}

watch([activeSymbol, selectedInterval, selectedRange], async ([, interval]) => {
  if (activeInstrument.value.kind === 'jd' && interval === 'day') {
    selectedInterval.value = '15'
    return
  }
  await loadKline()
})

watch(() => props.selectedSymbol, value => {
  if (value && value !== activeSymbol.value && terminalInstruments.value.some(item => item.symbol === value)) {
    activeSymbol.value = value
  }
})

watch(() => props.instruments, list => {
  if (!list.length) return
  if (!terminalInstruments.value.some(item => item.symbol === activeSymbol.value)) {
    const next = terminalInstruments.value[0]
    if (next) {
      activeSymbol.value = next.symbol
      emit('select-symbol', next.symbol)
    }
  }
}, { deep: true })

watch(() => [props.account, props.openOrders, currentQuote.value?.price, modalOpen.value, orderState.stopPrice], () => {
  renderAnnotations()
}, { deep: false })

watch(() => props.submitting, (busy, wasBusy) => {
  if (wasBusy && !busy && props.messageType === 'ok') closeModal()
})

onMounted(loadKline)
onBeforeUnmount(() => {
  const instance = marketChart.getChart()
  instance?.off('click', handleChartClick)
  instance?.off('datazoom', syncAnnotationPixels)
  instance?.off('finished', syncAnnotationPixels)
})
</script>

<template>
  <section class="trade-terminal">
    <header class="terminal-topbar">
      <button type="button" class="symbol-block" title="切换标的" @click="cycleSymbol">
        <span class="symbol-index">标的</span>
        <span class="symbol-copy">
          <b>{{ activeInstrument.ticker }}</b>
          <small>{{ activeInstrument.name }}</small>
        </span>
        <span class="symbol-price">{{ activeInstrument.currency }}{{ formatPrice(currentPrice) }}</span>
        <span class="symbol-change" :class="Number(currentQuote?.change || 0) >= 0 ? 'up' : 'down'">
          {{ signed(currentQuote?.change, 2) }} · {{ signed(currentQuote?.change_pct, 2) }}%
        </span>
      </button>
      <div class="terminal-readout">
        <span><i :class="{ open: props.sessionOpen }"></i>{{ props.sessionOpen ? '市场开放' : '市场休市' }}</span>
        <span>{{ quoteStale ? '行情延迟' : '实时行情' }}</span>
      </div>
    </header>

    <div class="terminal-workspace">
      <section class="chart-deck">
        <div class="quote-row">
          <div class="ohlcv">
            <span>开 <b>{{ formatPrice(latestBar.open) }}</b></span>
            <span>高 <b>{{ formatPrice(latestBar.high) }}</b></span>
            <span>低 <b>{{ formatPrice(latestBar.low) }}</b></span>
            <span>收 <b>{{ formatPrice(latestBar.close) }}</b></span>
            <span>量 <b>{{ Number(latestBar.volume || 0).toLocaleString() }}</b></span>
          </div>
          <span class="quote-source">{{ activeInstrument.kind === 'extended' ? activeInstrument.market : activeInstrument.quoteKey }}</span>
        </div>

        <div class="chart-stage">
          <div ref="chartRef" class="terminal-chart"></div>

          <template v-if="positionMarkerY !== null && activePosition">
            <div class="chart-marker position-marker" :style="{ top: `${positionMarkerY}px` }">
              <span>{{ Number(activePosition.quantity || 0).toFixed(0) }}</span>
              <strong :class="livePnl >= 0 ? 'up' : 'down'">{{ signedMoney(livePnl) }}</strong>
              <span class="marker-close" aria-hidden="true">×</span>
            </div>
            <div class="axis-price-tag position-price-tag" :style="{ top: `${positionMarkerY}px` }">
              {{ formatPrice(positionPrice) }}
            </div>
          </template>

          <template v-if="stopMarkerY !== null && previewStop > 0">
            <button type="button" class="chart-marker stop-marker" :style="{ top: `${stopMarkerY}px` }" @click="editActiveStop">
              STOP
            </button>
            <div class="axis-price-tag stop-price-tag" :style="{ top: `${stopMarkerY}px` }">
              {{ formatPrice(previewStop) }}
            </div>
          </template>

          <div v-if="chartLoading" class="chart-state">正在加载行情…</div>
          <div v-else-if="chartError" class="chart-state error">
            <span>{{ chartError }}</span>
            <button type="button" @click="loadKline">重试</button>
          </div>
          <div v-else-if="!klineData.length" class="chart-state">暂无可绘制的 K 线数据</div>
        </div>

        <footer class="terminal-rangebar">
          <nav class="range-list" aria-label="图表范围">
            <button v-for="item in rangeOptions" :key="item.key" type="button"
                    :class="{ active: selectedRange === item.key }" @click="setRange(item.key)">
              {{ item.key }}
            </button>
          </nav>
          <label class="interval-picker">
            <span>INTERVAL</span>
            <select v-model="selectedInterval" aria-label="K线周期">
              <option v-for="item in visibleIntervals" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <button v-if="activeOpenOrder" type="button" class="open-order-chip" @click="editActiveStop">
            {{ activeOpenOrder.status === 'TRIGGERING' ? 'TRIGGERING' : 'STOP' }} {{ formatPrice(activeOpenOrder.stopPrice) }}
          </button>
        </footer>
      </section>

      <aside class="execution-rail" aria-label="交易执行面板">
        <header class="execution-head">
          <div><span>EXECUTION</span><b>执行与持仓</b></div>
          <small>{{ props.sessionOpen ? '就绪' : '暂停' }}</small>
        </header>

        <section class="execution-account">
          <div class="rail-section-head"><span>01</span><b>账户概览</b></div>
          <dl>
            <div class="account-primary"><dt>净资产</dt><dd>{{ formatAccountMoney(props.account?.netEquity ?? props.account?.totalAssets) }}</dd></div>
            <div><dt>可用资金</dt><dd>{{ formatAccountMoney(props.account?.cash) }}</dd></div>
            <div><dt>持仓市值</dt><dd>{{ formatAccountMoney(props.account?.marketValue) }}</dd></div>
            <div><dt>总收益率</dt><dd :class="Number(props.account?.totalReturnPct || 0) >= 0 ? 'up' : 'down'">{{ signed(props.account?.totalReturnPct, 2) }}%</dd></div>
          </dl>
        </section>

        <section class="execution-position">
          <div class="rail-section-head"><span>02</span><b>当前持仓</b></div>
          <template v-if="activePosition">
            <div class="position-hero">
              <strong>{{ Number(activePosition.quantity || 0).toLocaleString() }}</strong>
              <span>持仓数量</span>
            </div>
            <dl>
              <div><dt>平均成本</dt><dd>{{ formatPrice(positionPrice) }}</dd></div>
              <div><dt>浮动盈亏</dt><dd :class="livePnl >= 0 ? 'up' : 'down'">{{ signedMoney(livePnl) }}</dd></div>
            </dl>
          </template>
          <p v-else class="rail-empty">当前标的暂无持仓。</p>
        </section>

        <section class="execution-order">
          <div class="rail-section-head"><span>03</span><b>活动挂单</b></div>
          <button v-if="activeOpenOrder" type="button" class="working-order" @click="editActiveStop">
            <span>{{ activeOpenOrder.status === 'TRIGGERING' ? '触发中' : '等待中' }}</span>
            <b>{{ activeOpenOrder.orderType === 'STOP_MARKET' ? '止损单' : activeOpenOrder.orderType }}</b>
            <em>{{ formatPrice(activeOpenOrder.stopPrice) }}</em>
          </button>
          <p v-else class="rail-empty">无活动挂单。</p>
        </section>

        <div class="execution-actions execution-segmented" aria-label="买卖执行">
          <button type="button" class="execute buy" :disabled="!props.sessionOpen || quoteStale" @click="openOrder('BUY')">
            <span>BUY</span><b>买入</b>
          </button>
          <button type="button" class="execute sell" :disabled="!props.sessionOpen || quoteStale" @click="openOrder('SELL')">
            <span>SELL</span><b>卖出</b>
          </button>
        </div>
        <p class="execution-note">提交前将在右侧确认数量、类型与止损条件。</p>
      </aside>
    </div>

    <div v-if="modalOpen" class="modal-layer" @mousedown.self="closeModal">
      <section class="order-modal" role="dialog" aria-modal="true" aria-labelledby="order-modal-title">
        <header class="modal-header">
          <div class="modal-kicker"><span>SIMULATION</span><b>模拟交易 · 订单确认</b></div>
          <button type="button" class="modal-close" aria-label="关闭" @click="closeModal">×</button>
        </header>
        <div class="modal-title" id="order-modal-title">
          <div class="modal-instrument">
            <small>{{ activeInstrument.name || '交易标的' }}</small>
            <h2>{{ activeInstrument.ticker }}</h2>
          </div>
          <div class="modal-last-price">
            <small>最新行情</small>
            <strong>{{ activeInstrument.currency }}{{ formatPrice(currentPrice) }}</strong>
            <span v-if="quoteStale" class="modal-stale">行情陈旧</span>
          </div>
        </div>

        <div class="side-switch">
          <button type="button" :class="{ active: orderState.side === 'BUY', buy: orderState.side === 'BUY' }"
                  :disabled="Boolean(editingOrderId)" @click="orderState.side = 'BUY'">买入</button>
          <button type="button" :class="{ active: orderState.side === 'SELL', sell: orderState.side === 'SELL' }"
                  :disabled="Boolean(editingOrderId)" @click="orderState.side = 'SELL'">卖出</button>
        </div>

        <div class="order-grid">
          <label>委托类型</label>
          <select v-model="orderState.orderType" :disabled="Boolean(editingOrderId)">
            <option value="MARKET">市价单</option>
            <option value="STOP_MARKET">止损市价单</option>
          </select>

          <div class="field-label">
            <label>交易数量</label>
            <small v-if="orderState.side === 'SELL'">可卖 {{ availableQuantity }}</small>
          </div>
          <div class="step-input">
            <input v-model.number="orderState.quantity" type="number" min="1" step="1" :disabled="Boolean(editingOrderId)" />
            <div class="step-buttons">
              <button type="button" :disabled="Boolean(editingOrderId)" @click="stepQuantity(1)">+</button>
              <button type="button" :disabled="Boolean(editingOrderId)" @click="stepQuantity(-1)">−</button>
            </div>
          </div>

          <div v-if="orderState.orderType === 'STOP_MARKET'" class="field-label">
            <label>止损触发价</label>
            <small>买价 {{ formatPrice(currentQuote?.bid || currentPrice) }} · 卖价 {{ formatPrice(currentQuote?.ask || currentPrice) }}</small>
          </div>
          <div v-if="orderState.orderType === 'STOP_MARKET'" class="step-input" :class="{ invalid: stopRelationError }">
            <input v-model.number="orderState.stopPrice" type="number" min="0" :step="activeInstrument.kind === 'jd' ? '0.01' : '0.001'" />
            <div class="step-buttons">
              <button type="button" @click="stepStop(1)">+</button>
              <button type="button" @click="stepStop(-1)">−</button>
            </div>
          </div>

          <label>订单有效期</label>
          <select v-model="orderState.timeInForce" :disabled="Boolean(editingOrderId)">
            <option value="DAY">当日有效</option>
            <option value="GTC">撤销前有效</option>
          </select>
        </div>

        <div class="order-summary">
          <div class="summary-line">
            <b>{{ orderState.side === 'SELL' ? '预计卖出金额' : '预计买入金额' }}</b>
            <strong>{{ formatMoney(estimatedAmount) }}</strong>
          </div>
          <p v-if="orderState.orderType === 'STOP_MARKET'">
            当 {{ activeInstrument.ticker }} 到达 {{ formatPrice(orderState.stopPrice) }}，订单将转为市价单。
            挂单保存在服务端，关闭页面后仍会继续监控。当前行情 {{ formatPrice(currentPrice) }}。
          </p>
          <p v-else>市价单将使用当前可用实时行情成交。行情陈旧时系统会拒绝成交。</p>
          <p v-if="stopRelationError" class="validation-hint">{{ stopRelationError }}</p>
          <button v-if="editingOrderId" type="button" class="cancel-order-link" @click="cancelEditingOrder">撤销该挂单</button>
        </div>

        <div v-if="message" class="order-message" :class="messageType">{{ message }}</div>

        <footer class="modal-actions">
          <button type="button" class="cancel" @click="closeModal">取消</button>
          <button type="button" class="confirm" :class="orderState.side === 'SELL' ? 'sell' : 'buy'"
                  :disabled="!canSubmit" @click="submitOrder">
            {{ props.submitting ? '提交中…' : editingOrderId ? '更新挂单' : `${orderState.side === 'SELL' ? '确认卖出' : '确认买入'} ${activeInstrument.ticker}` }}
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.trade-terminal {
  --trade-bg: var(--surface);
  --trade-panel: var(--panel);
  --trade-surface: var(--surface);
  --trade-elevated: var(--surface-2, var(--panel-raised, var(--surface)));
  --trade-border: var(--line);
  --trade-border-strong: var(--line-strong);
  --trade-text: var(--text);
  --trade-muted: var(--muted);
  --trade-subtle: var(--subtle);
  --trade-accent: var(--accent-strong);
  --trade-warn: var(--warn);
  --trade-buy: var(--ok);
  --trade-sell: var(--bad);
  position: relative;
  min-height: 640px;
  overflow: hidden;
  border: 1px solid var(--trade-border);
  border-radius: 0;
  background: var(--trade-bg);
  color: var(--trade-text);
  box-shadow: none;
}
.terminal-topbar { height: 48px; display: flex; align-items: center; justify-content: flex-start; border-bottom: 1px solid var(--trade-border); background: var(--trade-panel); }
.symbol-block { height: 100%; display: flex; align-items: center; gap: 8px; padding: 0 16px; color: var(--trade-text); background: transparent; border: 0; border-right: 1px solid var(--trade-border); cursor: pointer; font-variant-numeric: tabular-nums; }
.symbol-square { width: 16px; height: 16px; border-radius: 3px; background: #ff8a00; box-shadow: 0 0 0 1px rgba(255,255,255,.08) inset; }
.symbol-block b { font-size: 15px; letter-spacing: .01em; }
.symbol-price { color: var(--trade-muted); font-size: 14px; }
.symbol-change { font-size: 13px; font-weight: 650; }
.symbol-change.up { color: #ef5350; }
.symbol-change.down { color: #27c46b; }
.position-marker strong.up { color: var(--trade-buy); }
.position-marker strong.down { color: var(--trade-sell); }
.quote-row { min-height: 54px; display: flex; align-items: center; gap: 16px; padding: 0 14px; border-bottom: 1px solid var(--trade-border); background: var(--trade-surface); }
.trade-actions { display: flex; gap: 6px; }
.quick { min-width: 86px; height: 32px; border: 1px solid var(--trade-border); border-radius: 4px; background: var(--trade-elevated); font-size: 12px; font-weight: 750; cursor: pointer; transition: background .15s ease, border-color .15s ease; }
.quick.buy { color: var(--trade-buy); }
.quick.sell { color: var(--trade-sell); }
.quick:hover { background: color-mix(in srgb, var(--trade-elevated), var(--trade-text) 6%); border-color: var(--trade-border-strong); }
.ohlcv { display: flex; align-items: center; gap: 10px; color: var(--trade-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.ohlcv b { color: var(--trade-accent); }
.stale-badge { margin-left: auto; color: var(--trade-warn); font-size: 10px; }
.chart-stage { position: relative; height: clamp(480px, calc(100dvh - 286px), 760px); min-height: 480px; background: var(--trade-bg); }
.terminal-chart { width: 100%; height: 100%; border: 0; background: transparent; }
.chart-state { position: absolute; inset: 0; z-index: 12; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--trade-muted); background: rgba(11,15,19,.78); font-size: 12px; }
.chart-state.error { color: var(--trade-sell); }
.chart-state button { border: 1px solid var(--trade-border); border-radius: 4px; background: var(--trade-elevated); color: var(--trade-text); padding: 5px 9px; cursor: pointer; }
.chart-marker, .axis-price-tag { position: absolute; z-index: 9; transform: translateY(-50%); font-variant-numeric: tabular-nums; }
.position-marker { right: 78px; display: flex; align-items: center; gap: 6px; min-height: 29px; padding: 0 8px; border: 1px solid var(--trade-border-strong); border-radius: 5px; background: var(--trade-elevated); color: var(--trade-text); font-size: 11px; font-weight: 700; pointer-events: none; box-shadow: 0 6px 18px rgba(0,0,0,.18); }
.marker-close { color: var(--trade-subtle); font-size: 15px; font-weight: 500; margin-left: 1px; }
.stop-marker { right: 150px; min-width: 68px; height: 29px; border: 1px solid var(--trade-border-strong); border-radius: 5px; background: var(--trade-elevated); color: var(--trade-sell); font-size: 11px; font-weight: 760; cursor: pointer; box-shadow: 0 6px 18px rgba(0,0,0,.18); }
.axis-price-tag { right: 4px; min-width: 56px; padding: 4px 6px; border-radius: 4px; background: var(--trade-elevated); color: var(--trade-text); text-align: center; font-size: 11px; font-weight: 750; pointer-events: none; }
.stop-price-tag { background: color-mix(in srgb, var(--trade-elevated), var(--trade-sell) 17%); }
.terminal-rangebar { min-height: 49px; display: flex; align-items: center; gap: 18px; padding: 0 18px; border-top: 1px solid var(--trade-border); background: var(--trade-surface); }
.range-list { display: flex; align-items: center; gap: 4px; }
.range-list button { border: 0; background: transparent; color: var(--trade-muted); padding: 7px 8px; font-size: 11px; font-weight: 650; cursor: pointer; }
.range-list button.active { color: var(--trade-accent); }
.interval-picker { display: flex; align-items: center; gap: 5px; border-left: 1px solid var(--trade-border-strong); padding-left: 17px; color: var(--trade-muted); font-size: 11px; }
.interval-picker select { appearance: none; border: 0; outline: none; background: transparent; color: var(--trade-text); font-weight: 750; padding: 4px 18px 4px 2px; cursor: pointer; }
.interval-picker select option { color: var(--trade-text); background: var(--trade-panel); }
.open-order-chip { margin-left: auto; border: 1px solid rgba(239,83,80,.32); border-radius: 5px; background: rgba(239,83,80,.08); color: #ef5350; padding: 5px 9px; font-size: 10px; font-weight: 700; cursor: pointer; }
.modal-layer { position: absolute; inset: 0; z-index: 20; display: grid; place-items: center; padding: 18px; background: rgba(4,7,10,.62); backdrop-filter: blur(5px); }
.order-modal { position: relative; width: min(480px, 100%); max-height: min(88vh, 760px); overflow: auto; border: 1px solid var(--trade-border-strong); border-radius: 11px; background: var(--trade-panel); color: var(--trade-text); box-shadow: 0 30px 90px rgba(0,0,0,.46); }
.modal-header { min-height: 48px; display: flex; align-items: center; justify-content: space-between; padding: 11px 20px 0 24px; }
.modal-kicker { display: flex; flex-direction: column; gap: 5px; }
.modal-kicker span { color: var(--trade-subtle); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .12em; }
.modal-kicker b { color: var(--trade-muted); font-size: 11px; font-weight: 600; }
.modal-close { width: 32px; height: 32px; border: 1px solid var(--trade-border); border-radius: 50%; background: transparent; color: var(--trade-muted); font-size: 21px; line-height: 1; cursor: pointer; }
.modal-close:hover { border-color: var(--trade-border-strong); background: var(--trade-elevated); color: var(--trade-text); }
.modal-title { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 24px 20px; font-variant-numeric: tabular-nums; }
.modal-instrument small, .modal-last-price small { display: block; color: var(--trade-subtle); font-size: 10px; }
.modal-instrument h2 { margin: 5px 0 0; color: var(--trade-text); font-size: 22px; line-height: 1.15; letter-spacing: .01em; }
.modal-last-price { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.modal-last-price strong { color: var(--trade-accent); font-size: 20px; font-weight: 700; }
.modal-stale { color: var(--trade-warn); font-size: 9px; }
.side-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin: 0 24px 18px; padding: 4px; border: 1px solid var(--trade-border); border-radius: 6px; background: var(--trade-bg); }
.side-switch button { height: 39px; border: 0; border-radius: 4px; background: transparent; color: var(--trade-muted); font-size: 12px; font-weight: 650; cursor: pointer; }
.side-switch button.active.sell { background: var(--trade-sell); color: #17140e; font-weight: 800; }
.side-switch button.active.buy { background: var(--trade-buy); color: #17140e; font-weight: 800; }
.side-switch button:disabled { cursor: default; }
.order-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(145px, 185px); gap: 12px 18px; align-items: center; padding: 0 24px 20px; }
.order-grid > label, .field-label > label { color: var(--trade-muted); font-size: 11px; }
.field-label { display: flex; flex-direction: column; gap: 2px; }
.field-label small { color: var(--trade-subtle); font-size: 9px; }
.order-grid select, .step-input { min-height: 42px; border: 1px solid var(--trade-border-strong); border-radius: 5px; background: var(--trade-elevated); color: var(--trade-text); }
.order-grid select { padding: 0 12px; outline: none; }
.order-grid select:focus, .step-input:focus-within { border-color: var(--trade-accent); }
.step-input { display: grid; grid-template-columns: 1fr 34px; overflow: hidden; }
.step-input.invalid { border-color: #784046; }
.step-input input { width: 100%; border: 0; outline: none; background: transparent; color: var(--trade-text); padding: 0 11px; font-variant-numeric: tabular-nums; }
.step-buttons { display: grid; grid-template-rows: 1fr 1fr; border-left: 1px solid var(--trade-border); }
.step-buttons button { border: 0; background: var(--trade-surface); color: var(--trade-muted); line-height: 1; cursor: pointer; }
.step-buttons button + button { border-top: 1px solid var(--trade-border); }
.step-buttons button:disabled { opacity: .45; cursor: not-allowed; }
.order-summary { border-top: 1px solid var(--trade-border); border-bottom: 1px solid var(--trade-border); padding: 16px 24px; background: color-mix(in srgb, var(--trade-surface), transparent 30%); }
.summary-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.summary-line b, .summary-line strong { color: var(--trade-text); font-size: 12px; }
.summary-line strong { color: var(--trade-accent); font-variant-numeric: tabular-nums; }
.order-summary p { margin: 11px 0 0; color: var(--trade-subtle); font-size: 10px; line-height: 1.6; }
.order-summary .validation-hint { margin-top: 8px; color: var(--trade-sell); }
.cancel-order-link { margin-top: 10px; padding: 0; border: 0; background: transparent; color: var(--trade-sell); text-decoration: underline; font-size: 10px; cursor: pointer; }
.order-message { margin: 12px 24px 0; border-radius: 4px; padding: 8px 10px; background: var(--trade-surface); color: var(--trade-muted); font-size: 10px; }
.order-message.ok { color: var(--trade-buy); }
.order-message.error { color: var(--trade-sell); }
.modal-actions { position: sticky; bottom: 0; display: flex; justify-content: flex-end; gap: 10px; padding: 14px 24px max(14px, env(safe-area-inset-bottom)); border-top: 1px solid var(--trade-border); background: var(--trade-panel); }
.modal-actions button { min-width: 108px; min-height: 40px; border: 0; border-radius: 5px; font-size: 11px; font-weight: 750; cursor: pointer; }
.modal-actions .cancel { background: var(--trade-surface); color: var(--trade-text); }
.modal-actions .confirm.sell { background: var(--trade-sell); color: #17140e; }
.modal-actions .confirm.buy { background: var(--trade-buy); color: #17140e; }
.modal-actions .confirm:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 760px) {
  .trade-terminal { min-height: 650px; border-radius: 0; }
  .symbol-block { padding-left: 10px; }
  .symbol-change { display: none; }
  .quote-row { gap: 10px; }
  .quick { min-width: 70px; }
  .ohlcv { overflow: hidden; gap: 6px; font-size: 10px; }
  .chart-stage { height: 500px; min-height: 500px; }
  .terminal-rangebar { overflow-x: auto; gap: 10px; padding: 0 10px; }
  .range-list { min-width: max-content; }
  .open-order-chip { display: none; }
  .order-grid { grid-template-columns: 1fr 160px; gap: 12px; }
  .position-marker { right: 70px; }
  .stop-marker { right: 132px; }
}
@media (max-width: 500px) {
  .symbol-block { border-right: 0; }
  .symbol-price { font-size: 12px; }
  .quote-row { height: auto; align-items: flex-start; flex-direction: column; padding: 8px; }
  .ohlcv { width: 100%; }
  .chart-stage { height: 450px; min-height: 450px; }
  .order-grid { grid-template-columns: 1fr; }
  .order-grid { gap: 8px; }
  .order-grid > label, .field-label { margin: 3px 0 -3px; }
  .chart-marker, .axis-price-tag { display: none; }
  .modal-layer { position: fixed; inset: 0; align-items: end; padding: 0; }
  .order-modal { width: 100%; max-height: 90dvh; border-radius: 16px 16px 0 0; border-bottom: 0; }
  .modal-header { padding: 16px 18px 0 20px; }
  .modal-title { padding: 14px 20px 16px; }
  .side-switch { margin-right: 20px; margin-left: 20px; }
  .order-grid { padding-right: 20px; padding-left: 20px; }
  .order-summary { padding-right: 20px; padding-left: 20px; }
  .order-message { margin-right: 20px; margin-left: 20px; }
  .modal-actions { padding-right: 20px; padding-left: 20px; }
}

/* Trading workspace V4: modern financial app with restrained Rhine accents. */
.trade-terminal {
  --trade-ui-font: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  --trade-mono-font: ui-monospace, "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  min-height: 0;
  border: 0;
  border-right: 1px solid var(--trade-border);
  border-radius: 0;
  background: transparent;
  font-family: var(--trade-ui-font);
}
.terminal-topbar {
  min-height: 56px;
  height: auto;
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  border-bottom: 1px solid color-mix(in srgb, var(--trade-border) 72%, transparent);
  background: transparent;
}
.symbol-block {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(108px, auto) auto auto;
  align-items: center;
  gap: 11px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid color-mix(in srgb, var(--trade-border) 72%, transparent);
  border-radius: 0;
  background: transparent;
  transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease;
}
.symbol-block:hover { background: color-mix(in srgb, var(--trade-text) 3%, transparent); }
.symbol-block:active { transform: translateY(1px); }
.symbol-index { color: var(--trade-subtle); font-size: 8px; font-weight: 600; }
.symbol-copy { min-width: 0; display: grid; gap: 3px; text-align: left; }
.symbol-copy b { color: var(--trade-text); font: 650 12px/1 var(--trade-mono-font); }
.symbol-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--trade-subtle); font-size: 8px; }
.symbol-price { color: var(--trade-text); font: 650 15px/1 var(--trade-mono-font); }
.symbol-change { font: 600 9px/1 var(--trade-mono-font); }
.terminal-readout { display: flex; align-items: center; gap: 16px; padding: 0 14px; color: var(--trade-subtle); font-size: 8px; font-weight: 600; }
.terminal-readout span { display: inline-flex; align-items: center; gap: 6px; }
.terminal-readout i { width: 5px; height: 5px; border-radius: 50%; background: var(--trade-sell); }
.terminal-readout i.open { background: var(--trade-buy); }
.terminal-workspace { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr) clamp(196px, 14vw, 216px); align-items: stretch; }
.chart-deck { min-width: 0; border-right: 1px solid color-mix(in srgb, var(--trade-border) 72%, transparent); }
.quote-row {
  min-height: 38px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid color-mix(in srgb, var(--trade-border) 64%, transparent);
  background: transparent;
}
.ohlcv { gap: 15px; color: var(--trade-subtle); font-size: 8px; font-weight: 560; }
.ohlcv b { margin-left: 4px; color: var(--trade-muted); font: 620 8px/1 var(--trade-mono-font); }
.quote-source { color: var(--trade-subtle); font: 600 7px/1 var(--trade-mono-font); letter-spacing: .06em; text-transform: uppercase; }
.chart-stage { height: clamp(360px, calc(100dvh - 420px), 620px); min-height: 360px; background: transparent; }
.chart-state { background: color-mix(in srgb, var(--trade-bg) 88%, transparent); }
.chart-state button { border-radius: 7px; background: color-mix(in srgb, var(--trade-text) 4%, transparent); }
.position-marker,
.stop-marker,
.axis-price-tag {
  border-radius: 6px;
  background: color-mix(in srgb, var(--material-elevated, var(--trade-panel)) 92%, transparent);
  box-shadow: 0 8px 22px rgba(0,0,0,.1);
  backdrop-filter: blur(12px);
}
.position-marker { border-left: 2px solid var(--trade-accent); }
.stop-marker { border-left: 2px solid var(--trade-sell); }
.stop-price-tag { background: color-mix(in srgb, var(--material-elevated, var(--trade-panel)) 92%, transparent); color: var(--trade-sell); }
.terminal-rangebar { min-height: 42px; padding: 0 12px; gap: 14px; background: transparent; border-top-color: color-mix(in srgb, var(--trade-border) 64%, transparent); }
.range-list { gap: 0; }
.range-list button { position: relative; padding: 7px; border-radius: 6px; font: 600 8px/1 var(--trade-mono-font); transition: color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.range-list button:hover { background: color-mix(in srgb, var(--trade-text) 3.5%, transparent); color: var(--trade-text); }
.range-list button:active { transform: scale(.96); }
.range-list button.active { color: var(--trade-accent); }
.range-list button.active::before { content: ''; position: absolute; left: 6px; right: 6px; bottom: -7px; height: 2px; border-radius: 2px; background: var(--trade-accent); }
.interval-picker { font: 600 7px/1 var(--trade-mono-font); letter-spacing: .05em; }
.interval-picker select { font: 650 8px/1 var(--trade-mono-font); }
.open-order-chip { border-radius: 7px; background: color-mix(in srgb, var(--trade-sell) 5%, transparent); border: 1px solid color-mix(in srgb, var(--trade-sell) 24%, transparent); color: var(--trade-sell); font: 650 8px/1 var(--trade-mono-font); }

.execution-rail { min-width: 0; display: flex; flex-direction: column; background: color-mix(in srgb, var(--trade-panel) 22%, transparent); }
.execution-head { min-height: 56px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 0 14px; border-bottom: 1px solid color-mix(in srgb, var(--trade-border) 64%, transparent); }
.execution-head > div, .rail-section-head { display: grid; gap: 4px; }
.execution-head span, .rail-section-head span { color: var(--trade-subtle); font: 600 7px/1 var(--trade-mono-font); letter-spacing: .08em; }
.execution-head b, .rail-section-head b { color: var(--trade-text); font-size: 10px; font-weight: 650; }
.execution-head small { color: var(--trade-accent); font-size: 8px; font-weight: 650; }
.execution-account,
.execution-position,
.execution-order { padding: 15px 14px 2px; border-bottom: 0; }
.execution-account dl,
.execution-position dl { margin: 11px 0 0; }
.execution-account dl { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 12px; }
.execution-account dl > div,
.execution-position dl > div { min-height: 0; display: grid; gap: 4px; border: 0; }
.execution-account dl > .account-primary { grid-column: 1 / -1; gap: 5px; padding: 3px 0 4px; }
.execution-rail dt { color: var(--trade-subtle); font-size: 8px; }
.execution-rail dd { margin: 0; color: var(--trade-text); font: 600 9px/1 var(--trade-mono-font); text-align: left; }
.execution-account .account-primary dd { font-size: 17px; letter-spacing: -.025em; }
.execution-rail .up { color: var(--trade-buy); }
.execution-rail .down { color: var(--trade-sell); }
.position-hero { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-top: 10px; padding: 5px 0 3px; border: 0; }
.position-hero strong { color: var(--trade-text); font: 650 19px/1 var(--trade-mono-font); }
.position-hero span { color: var(--trade-subtle); font-size: 8px; }
.rail-empty { margin: 10px 0 0; color: var(--trade-subtle); font-size: 8px; line-height: 1.5; }
.working-order { width: 100%; min-height: 40px; margin-top: 9px; display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 8px; padding: 0 9px; border: 1px solid color-mix(in srgb, var(--trade-border) 68%, transparent); border-radius: 8px; background: color-mix(in srgb, var(--trade-text) 2.5%, transparent); color: var(--trade-muted); text-align: left; cursor: pointer; transition: background var(--motion-fast, 110ms) ease, border-color var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.working-order:hover { color: var(--trade-text); background: color-mix(in srgb, var(--trade-text) 4%, transparent); border-color: color-mix(in srgb, var(--trade-accent) 30%, var(--trade-border)); }
.working-order:active { transform: scale(.985); }
.working-order span { color: var(--trade-subtle); font-size: 7px; font-weight: 600; }
.working-order em { color: var(--trade-subtle); font: 600 8px/1 var(--trade-mono-font); font-style: normal; }
.working-order b { color: inherit; font-size: 9px; }
.execution-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin: auto 12px 10px; padding: 4px; border: 1px solid color-mix(in srgb, var(--trade-border) 72%, transparent); border-radius: 10px; background: color-mix(in srgb, var(--material-glass, var(--trade-panel)) 64%, transparent); }
.execute { min-height: 42px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 7px; background: transparent; cursor: pointer; transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease, box-shadow var(--motion-fast, 110ms) ease; }
.execute + .execute { border-left: 0; }
.execute::before { display: none; }
.execute span { font: 650 7px/1 var(--trade-mono-font); letter-spacing: .06em; opacity: .72; }
.execute b { font-size: 11px; font-weight: 650; }
.execute.buy { color: var(--trade-buy); }
.execute.sell { color: var(--trade-sell); }
.execute.buy:hover:not(:disabled) { background: color-mix(in srgb, var(--trade-buy) 9%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--trade-buy) 18%, transparent); }
.execute.sell:hover:not(:disabled) { background: color-mix(in srgb, var(--trade-sell) 9%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--trade-sell) 18%, transparent); }
.execute:active:not(:disabled) { transform: scale(.98); }
.execute:disabled { opacity: .34; cursor: not-allowed; }
.execution-note { margin: 0; padding: 0 14px 13px; border-top: 0; color: var(--trade-subtle); font-size: 7px; line-height: 1.45; }

.modal-layer { place-items: stretch end; padding: 0; background: linear-gradient(90deg, rgba(4,7,10,0) 45%, rgba(4,7,10,.14) 100%); backdrop-filter: blur(2px); }
.order-modal {
  width: min(420px, 100%);
  height: 100%;
  max-height: none;
  border: 1px solid var(--material-border, var(--trade-border-strong));
  border-right: 0;
  border-radius: 16px 0 0 16px;
  background: color-mix(in srgb, var(--material-elevated, var(--trade-panel)) 90%, transparent);
  box-shadow: -24px 0 70px rgba(0,0,0,.2), inset 1px 0 0 rgba(255,255,255,.035);
  backdrop-filter: blur(var(--material-blur-elevated, 28px)) saturate(1.08);
  -webkit-backdrop-filter: blur(var(--material-blur-elevated, 28px)) saturate(1.08);
  animation: order-sheet-in var(--motion-layout, 300ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
@keyframes order-sheet-in {
  from { opacity: 0; transform: translateX(14px); }
  to { opacity: 1; transform: translateX(0); }
}
.modal-header { min-height: 58px; padding: 0 14px 0 18px; border-bottom: 1px solid color-mix(in srgb, var(--trade-border) 66%, transparent); }
.modal-close { width: 30px; height: 30px; border: 0; border-radius: 8px; transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.modal-close:hover { background: color-mix(in srgb, var(--trade-text) 5%, transparent); color: var(--trade-accent); }
.modal-close:active { transform: scale(.94); }
.modal-title { padding: 16px 18px; border-bottom: 1px solid color-mix(in srgb, var(--trade-border) 66%, transparent); }
.modal-instrument h2 { font: 650 20px/1 var(--trade-mono-font); }
.modal-last-price strong { color: var(--trade-text); font: 650 17px/1 var(--trade-mono-font); }
.side-switch { gap: 4px; margin: 12px 18px 16px; padding: 4px; border: 1px solid var(--material-border, var(--trade-border)); border-radius: 10px; background: color-mix(in srgb, var(--material-glass, var(--trade-panel)) 68%, transparent); }
.side-switch button { position: relative; height: 36px; border-radius: 7px; transition: color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.side-switch button + button { border-left: 0; }
.side-switch button.active.sell,
.side-switch button.active.buy { font-weight: 700; }
.side-switch button.active.buy { color: var(--trade-buy); background: color-mix(in srgb, var(--trade-buy) 9%, transparent); }
.side-switch button.active.sell { color: var(--trade-sell); background: color-mix(in srgb, var(--trade-sell) 9%, transparent); }
.side-switch button:hover:not(:disabled) { background: color-mix(in srgb, var(--trade-text) 4.5%, transparent); }
.side-switch button:active:not(:disabled) { transform: scale(.97); }
.side-switch button.active::before { display: none; }
.order-grid { padding: 16px 18px 18px; grid-template-columns: minmax(0, 1fr) 160px; }
.order-grid select,
.step-input { min-height: 38px; border: 1px solid var(--material-border, var(--trade-border-strong)); border-radius: 8px; background: color-mix(in srgb, var(--material-glass, var(--trade-panel)) 64%, transparent); }
.step-buttons { border-left: 1px solid var(--trade-border); }
.step-buttons button { background: transparent; }
.order-summary { padding: 14px 18px; background: transparent; }
.order-message { margin: 10px 18px 0; border-radius: 7px; background: color-mix(in srgb, var(--trade-text) 3%, transparent); border: 1px solid color-mix(in srgb, var(--trade-border) 66%, transparent); }
.modal-actions { padding: 12px 18px max(12px, env(safe-area-inset-bottom)); background: color-mix(in srgb, var(--material-elevated, var(--trade-panel)) 92%, transparent); }
.modal-actions button { min-height: 38px; border-radius: 8px; border: 1px solid var(--trade-border-strong); background: transparent; transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.modal-actions button:hover:not(:disabled) { background: color-mix(in srgb, var(--trade-text) 5%, transparent); }
.modal-actions button:active:not(:disabled) { transform: scale(.97); }
.modal-actions .cancel { background: transparent; color: var(--trade-muted); }
.modal-actions .confirm.sell,
.modal-actions .confirm.buy { background: transparent; }
.modal-actions .confirm.sell { border-color: var(--trade-sell); color: var(--trade-sell); }
.modal-actions .confirm.buy { border-color: var(--trade-buy); color: var(--trade-buy); }

@media (max-width: 1120px) {
  .terminal-workspace { grid-template-columns: 1fr; }
  .chart-deck { border-right: 0; }
  .chart-stage { height: clamp(250px, calc(100dvh - 518px), 400px); min-height: 250px; }
  .symbol-block { grid-template-columns: auto minmax(90px, auto) auto; }
  .symbol-change { display: none; }
  .execution-rail { display: grid; grid-template-columns: 1.15fr .9fr .9fr 188px; align-items: stretch; border-top: 1px solid color-mix(in srgb, var(--trade-border) 70%, transparent); }
  .execution-head { display: none; }
  .rail-section-head { gap: 2px; }
  .execution-account, .execution-position, .execution-order { min-width: 0; padding: 8px 10px; border-right: 1px solid color-mix(in srgb, var(--trade-border) 64%, transparent); }
  .execution-account dl { margin-top: 5px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 8px; }
  .execution-account dl > .account-primary { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; padding: 0; }
  .execution-account .account-primary dd { font-size: 12px; }
  .execution-position .position-hero { margin-top: 4px; padding: 0; }
  .execution-position .position-hero strong { font-size: 15px; }
  .execution-position dl { margin-top: 5px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
  .working-order { min-height: 32px; margin-top: 5px; }
  .execution-segmented { grid-column: 4; grid-row: 1; align-self: center; margin: 10px; }
  .execution-note { display: none; }
}
@media (max-width: 760px) {
  .chart-stage { height: 300px; min-height: 300px; }
  .execution-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .execution-order { border-right: 0; }
  .execution-segmented { grid-column: 2; grid-row: 2; }
}
@media (max-width: 560px) {
  .terminal-topbar { align-items: stretch; flex-direction: column; }
  .symbol-block { min-height: 58px; border-right: 0; border-bottom: 1px solid var(--trade-border); }
  .terminal-readout { min-height: 34px; }
  .execution-rail { grid-template-columns: 1fr; }
  .execution-account, .execution-position, .execution-order { border-right: 0; border-bottom: 1px solid var(--trade-border); }
  .execution-segmented { grid-column: 1; grid-row: auto; margin: 10px 12px; }
  .order-modal { width: 100%; height: min(92dvh, 760px); margin-top: auto; border-left: 0; border-top: 1px solid var(--trade-border-strong); border-radius: 14px 14px 0 0; }
}
.symbol-block:focus-visible,
.range-list button:focus-visible,
.interval-picker select:focus-visible,
.open-order-chip:focus-visible,
.working-order:focus-visible,
.execute:focus-visible,
.modal-close:focus-visible,
.side-switch button:focus-visible,
.order-grid select:focus-visible,
.step-input:focus-within,
.modal-actions button:focus-visible { outline: 2px solid color-mix(in srgb, var(--trade-accent) 65%, transparent); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) {
  .order-modal { animation: none !important; }
  .execute,
  .symbol-block,
  .working-order,
  .range-list button,
  .modal-close,
  .side-switch button,
  .modal-actions button { transition: none !important; }
}
</style>
