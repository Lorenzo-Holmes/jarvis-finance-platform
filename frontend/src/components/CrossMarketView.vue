<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/client'

const market = ref('a_share')
const selectedSymbol = ref('')
const instruments = ref([])
const quote = ref(null)
const kline = ref([])
const range = ref(null)
const technicalAnalysis = ref(null)
const interval = ref('1d')
const loading = ref(false)
const error = ref('')
const analysis = ref('')
const analysisLoading = ref(false)
const chartRef = ref(null)
let chart = null
let pollTimer = null
let echartsPromise = null

const marketOptions = [
  { value: 'a_share', label: 'A股' },
  { value: 'us_stock', label: '美股' },
  { value: 'crypto', label: '加密货币' },
]

const marketIntervals = computed(() => market.value === 'a_share'
  ? [{ value: '1d', label: '日K' }]
  : [{ value: '1d', label: '日K' }, { value: '1h', label: '1小时' }, { value: '15m', label: '15分钟' }])

const currentInstruments = computed(() => instruments.value.filter(i => i.market === market.value))
const currentInstrument = computed(() => currentInstruments.value.find(i => i.symbol === selectedSymbol.value))

function fmt(value) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}

function fmtPct(value) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return `${Number(value).toFixed(2)}%`
}

async function getEcharts() {
  if (!echartsPromise) echartsPromise = import('../charts/echarts')
  return echartsPromise
}

async function loadInstruments() {
  const response = await api.marketInstruments()
  if (response.code !== 200 || !Array.isArray(response.data)) {
    throw new Error(response.message || '标的列表加载失败')
  }
  instruments.value = response.data
  chooseDefaultSymbol()
}

function chooseDefaultSymbol() {
  const available = currentInstruments.value
  if (!available.some(i => i.symbol === selectedSymbol.value)) {
    selectedSymbol.value = available[0]?.symbol || ''
  }
  if (!marketIntervals.value.some(i => i.value === interval.value)) {
    interval.value = marketIntervals.value[0].value
  }
}

async function loadData() {
  if (!selectedSymbol.value) return
  loading.value = true
  error.value = ''
  try {
    const [quoteResponse, klineResponse] = await Promise.all([
      api.marketAssetQuote(market.value, selectedSymbol.value),
      api.marketAssetKline(market.value, selectedSymbol.value, interval.value, 120),
    ])
    if (quoteResponse.code !== 200) throw new Error(quoteResponse.message || '报价加载失败')
    if (klineResponse.code !== 200) throw new Error(klineResponse.message || 'K线加载失败')
    quote.value = quoteResponse.data
    kline.value = klineResponse.data?.data || []
    range.value = klineResponse.data?.range || null
    technicalAnalysis.value = klineResponse.data?.analysis || null
    await nextTick()
    await renderChart()
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

async function renderChart() {
  if (!chartRef.value) return
  const echarts = await getEcharts()
  chart ||= echarts.init(chartRef.value)
  chart.setOption({
    backgroundColor: 'transparent',
    grid: { left: 55, right: 20, top: 24, bottom: 42 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    xAxis: {
      type: 'category',
      data: kline.value.map(item => item.date),
      axisLabel: { color: '#8ba0c8', hideOverlap: true },
      axisLine: { lineStyle: { color: '#243453' } },
    },
    yAxis: {
      scale: true,
      axisLabel: { color: '#8ba0c8' },
      splitLine: { lineStyle: { color: '#1a2540' } },
    },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 4 }],
    series: [{
      name: 'K线',
      type: 'candlestick',
      data: kline.value.map(item => [item.open, item.close, item.low, item.high]),
      itemStyle: { color: '#ef5350', color0: '#27c46b', borderColor: '#ef5350', borderColor0: '#27c46b' },
    }, {
      name: 'SMA20',
      type: 'line',
      data: kline.value.map(item => item.sma20 ?? '-'),
      showSymbol: false,
      lineStyle: { color: '#f5c542', width: 1.5 },
    }, {
      name: 'EMA12',
      type: 'line',
      data: kline.value.map(item => item.ema12 ?? '-'),
      showSymbol: false,
      lineStyle: { color: '#4da8ff', width: 1.2 },
    }],
  }, true)
}

async function runAnalysis() {
  if (!quote.value || analysisLoading.value) return
  analysisLoading.value = true
  analysis.value = ''
  try {
    const response = await api.aiQuote({
      ...quote.value,
      analysis_market: market.value,
      technical_analysis: technicalAnalysis.value,
    })
    if (response.code !== 200) throw new Error(response.message || '分析失败')
    analysis.value = response.data?.content || '（AI暂无回复）'
  } catch (e) {
    analysis.value = `⚠️ ${e?.message || e}`
  } finally {
    analysisLoading.value = false
  }
}

async function refresh() {
  try {
    if (!instruments.value.length) await loadInstruments()
    await loadData()
  } catch (e) {
    error.value = e?.message || String(e)
  }
}

watch(market, () => {
  chooseDefaultSymbol()
  analysis.value = ''
  technicalAnalysis.value = null
  loadData()
})
watch([selectedSymbol, interval], () => {
  analysis.value = ''
  loadData()
})

onMounted(async () => {
  await refresh()
  pollTimer = setInterval(() => {
    if (!loading.value) loadData()
  }, 30000)
  window.addEventListener('resize', () => chart?.resize())
})

onUnmounted(() => {
  clearInterval(pollTimer)
  chart?.dispose()
})
</script>

<template>
  <div class="cross-market">
    <div class="panel">
      <div class="panel-head">
        <div>
          <h2>多市场行情与分析</h2>
          <div class="hint">A股、美股、加密货币 · 公开行情源 · 每 30 秒刷新</div>
        </div>
        <div class="controls">
          <select v-model="market" class="select">
            <option v-for="item in marketOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <select v-model="selectedSymbol" class="select">
            <option v-for="item in currentInstruments" :key="item.symbol" :value="item.symbol">
              {{ item.name }} ({{ item.symbol }})
            </option>
          </select>
          <select v-model="interval" class="select">
            <option v-for="item in marketIntervals" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <button class="btn" @click="refresh" :disabled="loading">{{ loading ? '加载中…' : '刷新' }}</button>
        </div>
      </div>

      <div v-if="error" class="error">{{ error }}</div>

      <div v-if="quote" class="quote-grid">
        <div class="quote-main">
          <div class="hint">{{ currentInstrument?.name || quote.name }} · {{ quote.currency }}</div>
          <div class="quote-price">{{ fmt(quote.price) }}</div>
          <div :class="Number(quote.change || 0) >= 0 ? 'pos' : 'neg'">
            {{ fmt(quote.change) }} ({{ fmtPct(quote.change_pct) }})
          </div>
        </div>
        <div class="quote-meta">
          <div>昨收 <b>{{ fmt(quote.prev_close) }}</b></div>
          <div>开盘 <b>{{ fmt(quote.open) }}</b></div>
          <div>最高/最低 <b>{{ fmt(quote.high) }} / {{ fmt(quote.low) }}</b></div>
          <div class="hint">数据源：{{ quote.source }} · {{ quote.quote_time }}</div>
        </div>
      </div>

      <div ref="chartRef" class="chart tall"></div>
      <div v-if="range" class="hint">区间 {{ range.start }} ~ {{ range.end }}（{{ range.count }} 根）</div>

      <div v-if="technicalAnalysis?.status === 'ok'" class="technical-panel">
        <div class="technical-head">
          <h3>技术指标摘要</h3>
          <span class="hint">基于当前周期历史 K 线计算</span>
        </div>
        <div class="technical-grid">
          <div><span>趋势</span><b :class="technicalAnalysis.trend">{{ technicalAnalysis.trend_label }}</b></div>
          <div><span>动能</span><b>{{ technicalAnalysis.momentum_label }}</b></div>
          <div><span>RSI(14)</span><b>{{ fmt(technicalAnalysis.indicators?.rsi14) }}</b></div>
          <div><span>MACD</span><b>{{ fmt(technicalAnalysis.indicators?.macd) }}</b></div>
          <div><span>20期支撑</span><b>{{ fmt(technicalAnalysis.support_20) }}</b></div>
          <div><span>20期阻力</span><b>{{ fmt(technicalAnalysis.resistance_20) }}</b></div>
        </div>
        <div class="hint disclaimer">{{ technicalAnalysis.disclaimer }}</div>
      </div>
    </div>

    <div class="panel analysis-panel">
      <div class="panel-head">
        <h2>AI 行情分析</h2>
        <button class="btn primary" @click="runAnalysis" :disabled="!quote || analysisLoading">
          {{ analysisLoading ? '分析中…' : '分析当前标的' }}
        </button>
      </div>
      <div v-if="analysis" class="out">{{ analysis }}</div>
      <div v-else class="hint">AI 仅对当前行情进行研究性解读，不构成投资建议。</div>
    </div>
  </div>
</template>

<style scoped>
.cross-market { display: flex; flex-direction: column; gap: 16px; }
.panel { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 20px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.panel-head h2 { margin: 0; font-size: 18px; }
.controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.select { background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 8px 10px; }
.btn { background: #1a2540; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 8px 16px; cursor: pointer; }
.btn.primary { background: linear-gradient(135deg, #4da8ff, #a842ff); border: none; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.hint { color: #8ba0c8; font-size: 12px; line-height: 1.6; }
.quote-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 18px; }
.quote-main, .quote-meta { background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; padding: 16px; }
.quote-price { font-size: 34px; font-weight: 700; margin: 8px 0; color: #f5c542; }
.quote-meta { display: grid; gap: 8px; align-content: center; color: #8ba0c8; font-size: 13px; }
.quote-meta b { color: #e9effb; margin-left: 6px; }
.chart { width: 100%; background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; }
.chart.tall { height: 420px; margin-top: 16px; }
.pos { color: #27c46b; } .neg { color: #ef5350; }
.error { color: #ef5350; padding: 10px; background: rgba(239,83,80,.1); border-radius: 6px; margin-top: 14px; }
.analysis-panel { min-height: 100px; }
.technical-panel { margin-top: 16px; padding: 14px; background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; }
.technical-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.technical-head h3 { margin: 0; color: #e9effb; font-size: 15px; }
.technical-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.technical-grid > div { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.technical-grid span { color: #8ba0c8; font-size: 12px; }
.technical-grid b { color: #e9effb; font-size: 13px; line-height: 1.4; }
.technical-grid b.bullish { color: #27c46b; }
.technical-grid b.bearish { color: #ef5350; }
.disclaimer { margin-top: 10px; }
.out { margin-top: 14px; background: rgba(8,16,34,.7); border: 1px dashed #38517f; border-radius: 8px; padding: 12px; white-space: pre-wrap; color: #d9e6ff; line-height: 1.6; }
@media (max-width: 850px) { .quote-grid { grid-template-columns: 1fr; } .technical-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 500px) { .technical-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
