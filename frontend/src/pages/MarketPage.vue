<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api/client'
import QuoteStrip from '../components/market/QuoteStrip.vue'
import MultiMarketBoard from '../components/market/MultiMarketBoard.vue'
import DataState from '../components/common/DataState.vue'
import { useMarketChart } from '../composables/useMarketChart'
import { usePolling } from '../composables/usePolling'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useFreshness } from '../composables/useFreshness'
import { formatNumber, formatPercent } from '../utils/formatters'

const props = defineProps({ active: { type: Boolean, default: true } })
const emit = defineEmits(['connection-change', 'context-change', 'ready'])

const connected = ref(false)
const initializing = ref(true)
const streamServerTime = ref('')
let closePriceStream = null
const realtimePrices = ref(null)
const jdPrices = ref(null)
const marketFocus = ref('gold_etf')
const inspectorOpen = ref(false)
const periodPopoverRef = ref(null)
const samplePopoverRef = ref(null)
const marketKlines = reactive({ gold_etf: [], london_gold: [] })
const klineRanges = ref({ gold_etf: null, london_gold: null })
const jdKlineData = ref([])
const jdKlineRange = ref(null)
const jdKlineError = ref('')
const klineErrors = reactive({ gold_etf: '', london_gold: '' })

const etfCfg = reactive({ limit: 120, interval: 'day' })
const londonCfg = reactive({ limit: 120, interval: 'day' })
const jdKlineCfg = reactive({ market: 'zheshang', interval: 5, limit: 200 })

const intervals = [
  { v: 'day', label: '日K' },
  { v: '1', label: '1分' },
  { v: '5', label: '5分' },
  { v: '15', label: '15分' },
  { v: '30', label: '30分' },
  { v: '60', label: '1小时' },
]
const marketChart = useMarketChart()
const marketChartRef = marketChart.elementRef
const latestMarketKline = useLatestRequest()
const latestJdKline = useLatestRequest()
const freshness = useFreshness(90000)
// SSE 是主实时链路；30s HTTP 轮询保留为兜底，避免代理层暂时不支持 SSE 时页面完全失去报价。
const poll = usePolling(async () => {
  await Promise.all([loadJdLive(), loadRealtime()])
}, 30000)

function startPriceStream() {
  if (closePriceStream) return
  closePriceStream = api.marketPriceStream(payload => {
    if (payload?.market && Object.keys(payload.market).length) realtimePrices.value = payload.market
    if (payload?.jd && Object.keys(payload.jd).length) jdPrices.value = payload.jd
    streamServerTime.value = payload?.server_time || ''
    freshness.touch()
    setConnected(true)
  }, () => {
    // EventSource 会自动重连；期间继续由 30s fallback poll 保留最后有效报价。
    setConnected(false)
  })
}

function stopPriceStream() {
  closePriceStream?.()
  closePriceStream = null
}

function setConnected(value) {
  connected.value = value
  emit('connection-change', value)
}

async function loadRealtime() {
  try {
    const response = await api.marketPrices()
    realtimePrices.value = response.data || null
    if (realtimePrices.value) freshness.touch()
  } catch (_) { /* 保留最后一次有效报价 */ }
}

async function loadJdLive() {
  try {
    const response = await api.jdPrices()
    if (response.code === 200 && response.data && Object.keys(response.data).length) {
      jdPrices.value = response.data
      freshness.touch()
    }
  } catch (_) { /* 保留最后一次有效报价 */ }
}

async function loadJdKline() {
  const requestVersion = latestJdKline.begin()
  jdKlineError.value = ''
  const requestMarket = jdKlineCfg.market
  const requestInterval = jdKlineCfg.interval
  const requestLimit = jdKlineCfg.limit
  try {
    const response = await api.jdKline(requestMarket, requestInterval, requestLimit)
    if (!latestJdKline.isLatest(requestVersion)) return
    const body = response?.data && response.data.data ? response.data : response
    jdKlineRange.value = body?.range || null
    jdKlineData.value = body?.data || []
    if (marketFocus.value === 'jd') await renderPrimaryChart()
  } catch (error) {
    if (latestJdKline.isLatest(requestVersion)) jdKlineError.value = error?.message || String(error)
  }
}

async function loadOneKline(marketKey, cfg) {
  const requestVersion = latestMarketKline.begin(marketKey)
  klineErrors[marketKey] = ''
  const requestLimit = cfg.limit
  const requestInterval = cfg.interval
  try {
    const response = await api.marketKline({ market: marketKey, limit: requestLimit, interval: requestInterval })
    if (!latestMarketKline.isLatest(requestVersion, marketKey)) return
    if (response.code !== 200) throw new Error(response.message || 'K线加载失败')
    const data = Array.isArray(response.data) ? response.data : (response.data?.data || [])
    marketKlines[marketKey] = data
    klineRanges.value[marketKey] = response.data?.range || null
    setConnected(true)
    if (marketFocus.value === marketKey) await renderPrimaryChart()
  } catch (error) {
    if (latestMarketKline.isLatest(requestVersion, marketKey)) {
      klineErrors[marketKey] = error?.message || String(error)
      setConnected(false)
    }
  }
}

async function loadKlines() {
  await Promise.all([
    loadOneKline('gold_etf', etfCfg),
    loadOneKline('london_gold', londonCfg),
  ])
}

const focusedTitle = computed(() => {
  if (marketFocus.value === 'gold_etf') return '黄金ETF华夏'
  if (marketFocus.value === 'london_gold') return '伦敦金（现货黄金）'
  return jdKlineCfg.market === 'zheshang' ? '浙商积存金' : '民生积存金'
})
const focusedInterval = computed(() => marketFocus.value === 'gold_etf'
  ? etfCfg.interval
  : marketFocus.value === 'london_gold' ? londonCfg.interval : String(jdKlineCfg.interval))
const focusedLimit = computed(() => marketFocus.value === 'gold_etf'
  ? etfCfg.limit
  : marketFocus.value === 'london_gold' ? londonCfg.limit : jdKlineCfg.limit)
const focusedRange = computed(() => marketFocus.value === 'gold_etf'
  ? klineRanges.value.gold_etf
  : marketFocus.value === 'london_gold' ? klineRanges.value.london_gold : jdKlineRange.value)
const focusedQuote = computed(() => {
  if (marketFocus.value === 'gold_etf') return realtimePrices.value?.gold_etf || null
  if (marketFocus.value === 'london_gold') return realtimePrices.value?.london_gold || null
  return jdPrices.value?.[jdKlineCfg.market] || null
})
const focusedDayRange = computed(() => {
  const quote = focusedQuote.value
  const low = Number(quote?.low)
  const high = Number(quote?.high)
  const price = Number(quote?.price)
  if (![low, high, price].every(Number.isFinite) || high <= low) return null
  return {
    low,
    high,
    price,
    pct: Math.max(0, Math.min(100, ((price - low) / (high - low)) * 100)),
  }
})
const focusedResearchContext = computed(() => {
  if (marketFocus.value === 'gold_etf') {
    return { market: 'gold_etf', symbol: '518850', name: focusedTitle.value, sourceModule: 'market' }
  }
  if (marketFocus.value === 'london_gold') {
    return { market: 'london_gold', symbol: 'XAUUSD', name: focusedTitle.value, sourceModule: 'market' }
  }
  return {
    market: 'jd_gold',
    symbol: jdKlineCfg.market === 'zheshang' ? 'JD-ZS-GOLD' : 'JD-MS-GOLD',
    name: focusedTitle.value,
    sourceModule: 'market',
  }
})
const focusedQuoteStale = computed(() => Boolean(focusedQuote.value?.stale))
const focusedIntervals = computed(() => marketFocus.value === 'jd'
  ? [{ v: '1', label: '1分' }, { v: '5', label: '5分' }, { v: '15', label: '15分' }, { v: '30', label: '30分' }, { v: '60', label: '1小时' }]
  : intervals)
const focusedData = computed(() => marketFocus.value === 'jd' ? jdKlineData.value : marketKlines[marketFocus.value])
const focusedError = computed(() => marketFocus.value === 'jd' ? jdKlineError.value : klineErrors[marketFocus.value])
const chartState = computed(() => {
  if (initializing.value && !focusedData.value?.length) return 'loading'
  if (focusedError.value && !focusedData.value?.length) return 'error'
  if (!focusedData.value?.length) return 'empty'
  return ''
})

async function setMarketFocus(key, jdMarket = null) {
  if (jdMarket) jdKlineCfg.market = jdMarket
  marketFocus.value = key
  if (key === 'jd' && !jdKlineData.value.length) await loadJdKline()
  await nextTick()
  await renderPrimaryChart()
  emit('context-change', focusedResearchContext.value)
}

async function handleQuoteSelect(selection) {
  await setMarketFocus(selection.key, selection.jdMarket || null)
}

async function setFocusedInterval(value) {
  if (marketFocus.value === 'gold_etf') {
    etfCfg.interval = value
    await loadOneKline('gold_etf', etfCfg)
  } else if (marketFocus.value === 'london_gold') {
    londonCfg.interval = value
    await loadOneKline('london_gold', londonCfg)
  } else {
    jdKlineCfg.interval = Number(value)
    await loadJdKline()
  }
  if (periodPopoverRef.value) periodPopoverRef.value.open = false
}

async function setFocusedLimit(value) {
  const limit = Number(value)
  if (marketFocus.value === 'gold_etf') {
    etfCfg.limit = limit
    await loadOneKline('gold_etf', etfCfg)
  } else if (marketFocus.value === 'london_gold') {
    londonCfg.limit = limit
    await loadOneKline('london_gold', londonCfg)
  } else {
    jdKlineCfg.limit = limit
    await loadJdKline()
  }
  if (samplePopoverRef.value) samplePopoverRef.value.open = false
}

function toggleInspector() {
  inspectorOpen.value = !inspectorOpen.value
}

function closeMarketPopovers(event) {
  const period = periodPopoverRef.value
  const sample = samplePopoverRef.value
  if (period?.open && !period.contains(event.target)) period.open = false
  if (sample?.open && !sample.contains(event.target)) sample.open = false
}

function onMarketKeydown(event) {
  if (event.key !== 'Escape') return
  if (document.querySelector('.command-palette, [role="dialog"][aria-modal="true"]')) return
  if (periodPopoverRef.value?.open) {
    periodPopoverRef.value.open = false
    event.preventDefault()
    return
  }
  if (samplePopoverRef.value?.open) {
    samplePopoverRef.value.open = false
    event.preventDefault()
    return
  }
  if (inspectorOpen.value) {
    inspectorOpen.value = false
    event.preventDefault()
  }
}

async function renderPrimaryChart() {
  const isJd = marketFocus.value === 'jd'
  const data = isJd ? jdKlineData.value : marketKlines[marketFocus.value]
  await marketChart.renderCandles(data, {
    withVolume: !isJd,
    visibleCount: 80,
    showLegend: false,
  })
}

async function retryFocusedKline() {
  if (marketFocus.value === 'jd') return loadJdKline()
  const cfg = marketFocus.value === 'gold_etf' ? etfCfg : londonCfg
  return loadOneKline(marketFocus.value, cfg)
}

async function initialize() {
  initializing.value = true
  try {
    await Promise.all([loadKlines(), loadRealtime(), loadJdKline(), loadJdLive()])
    await renderPrimaryChart()
  } finally {
    initializing.value = false
  }
}

async function initializeMountedWorkspace() {
  // Initialize the ECharts canvas before the archive-to-workspace crossfade.
  // The workspace is pre-mounted at opacity 0, so module parsing, canvas setup
  // and the first chart allocation happen off-screen instead of on the first
  // visible MARKET frame.
  await marketChart.prepare()
  await initialize()
  await nextTick()
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      marketChart.resize()
      emit('ready')
    })
  })
}

watch(() => props.active, async active => {
  if (active) {
    poll.start()
    startPriceStream()
    await nextTick()
    requestAnimationFrame(() => marketChart.resize())
  } else {
    poll.stop()
    stopPriceStream()
  }
}, { immediate: true })

onMounted(() => {
  initializeMountedWorkspace()
  document.addEventListener('pointerdown', closeMarketPopovers)
  window.addEventListener('keydown', onMarketKeydown)
})
onBeforeUnmount(() => {
  stopPriceStream()
  document.removeEventListener('pointerdown', closeMarketPopovers)
  window.removeEventListener('keydown', onMarketKeydown)
})

watch([marketFocus, () => jdKlineCfg.market], () => {
  emit('context-change', focusedResearchContext.value)
}, { immediate: true })
</script>

<template>
  <section class="market-workspace">
    <div class="section-bar">
      <div class="market-identity">
        <div>
          <h1>{{ focusedTitle }}</h1>
          <span>{{ focusedResearchContext.symbol }} · {{ focusedInterval === 'day' ? '日K' : focusedInterval + (marketFocus === 'jd' ? '分' : '分钟') }}</span>
        </div>
        <div class="market-hero-price">
          <strong>{{ formatNumber(focusedQuote?.price) }}</strong>
          <span :class="Number(focusedQuote?.change || 0) >= 0 ? 'pos' : 'neg'">
            {{ formatNumber(focusedQuote?.change) }} · {{ formatPercent(focusedQuote?.change_pct) }}
          </span>
        </div>
      </div>
      <span class="section-status" :class="{ stale: freshness.stale || focusedQuoteStale }">
        <i :class="{ ok: connected && !freshness.stale && !focusedQuoteStale, warn: freshness.stale || focusedQuoteStale }"></i>
        {{ !connected ? '正在连接行情' : focusedQuoteStale || freshness.stale ? '行情更新延迟' : `实时 · ${freshness.label}` }}
      </span>
    </div>

    <QuoteStrip :jd-prices="jdPrices" :realtime-prices="realtimePrices" :market-focus="marketFocus"
                :jd-market="jdKlineCfg.market" @select="handleQuoteSelect" />

    <div class="market-primary-layout" :class="{ 'inspector-open': inspectorOpen }">
      <aside class="market-scale-rail" aria-hidden="true">
        <div class="scale-ticks"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div>
      </aside>

      <div class="market-chart-panel">
        <div class="chart-shell">
          <div class="floating-chart-toolbar" aria-label="图表工具">
            <select v-if="marketFocus === 'jd'" v-model="jdKlineCfg.market" aria-label="积存金来源" @change="loadJdKline" class="toolbar-source-select">
              <option value="zheshang">浙商</option>
              <option value="minsheng">民生</option>
            </select>

            <details ref="periodPopoverRef" class="toolbar-popover period-popover">
              <summary><span>{{ focusedIntervals.find(item => String(item.v) === String(focusedInterval))?.label || focusedInterval }}</span><i aria-hidden="true"></i></summary>
              <div class="toolbar-popover-menu" role="menu" aria-label="K线周期">
                <button v-for="item in focusedIntervals" :key="item.v" type="button"
                        :class="{ active: String(focusedInterval) === String(item.v) }" @click="setFocusedInterval(item.v)">
                  <span>{{ item.label }}</span><b>{{ String(focusedInterval) === String(item.v) ? '✓' : '' }}</b>
                </button>
              </div>
            </details>

            <details ref="samplePopoverRef" class="toolbar-popover sample-popover">
              <summary><span>{{ focusedLimit }} 根</span><i aria-hidden="true"></i></summary>
              <div class="toolbar-popover-menu" role="menu" aria-label="K线样本数量">
                <template v-if="marketFocus === 'jd'">
                  <button v-for="value in [100, 200, 500]" :key="value" type="button" :class="{ active: focusedLimit === value }" @click="setFocusedLimit(value)">
                    <span>{{ value }} 根</span><b>{{ focusedLimit === value ? '✓' : '' }}</b>
                  </button>
                </template>
                <template v-else>
                  <button v-for="value in [60, 120, 250]" :key="value" type="button" :class="{ active: focusedLimit === value }" @click="setFocusedLimit(value)">
                    <span>{{ value }} 根</span><b>{{ focusedLimit === value ? '✓' : '' }}</b>
                  </button>
                </template>
              </div>
            </details>
            <button type="button" class="toolbar-more" aria-label="更多图表工具">•••</button>
          </div>

          <div ref="marketChartRef" class="chart market-main-chart"></div>
          <DataState v-if="chartState" :state="chartState" overlay
                     :message="chartState === 'error' ? focusedError : chartState === 'empty' ? '当前条件下没有可绘制的 K 线数据。' : '正在获取行情与历史数据。'"
                     :retryable="chartState === 'error'" @retry="retryFocusedKline" />
          <div v-if="focusedRange" class="chart-footnote">
            <span>数据区间</span>
            <b>{{ focusedRange.min ?? focusedRange.start ?? '-' }} — {{ focusedRange.max ?? focusedRange.end ?? '-' }}</b>
            <span>{{ focusedRange.count ?? focusedLimit }} 根</span>
          </div>
        </div>
      </div>

      <aside class="market-rail market-inspector" :class="{ 'inspector-collapsed': !inspectorOpen }" aria-label="行情检查器">
        <header class="rail-head">
          <div class="inspector-detail"><b>行情数据</b><small>{{ connected && !freshness.stale && !focusedQuoteStale ? '实时' : '延迟' }}</small></div>
          <button type="button" class="inspector-toggle" :aria-expanded="inspectorOpen" :aria-label="inspectorOpen ? '收起行情详情' : '展开行情详情'" @click="toggleInspector">{{ inspectorOpen ? '›' : '‹' }}</button>
        </header>
        <section class="quote-detail inspector-detail">
          <div class="rail-symbol">{{ focusedTitle }}</div>
          <div class="rail-price">{{ formatNumber(focusedQuote?.price) }}</div>
          <div class="rail-change" :class="Number(focusedQuote?.change || 0) >= 0 ? 'pos' : 'neg'">
            {{ formatNumber(focusedQuote?.change) }} · {{ formatPercent(focusedQuote?.change_pct) }}
          </div>
        </section>
        <section v-if="focusedDayRange" class="range-instrument inspector-detail" aria-label="日内价格位置">
          <div class="rail-section-head"><b>日内位置</b></div>
          <div class="range-scale">
            <span>{{ formatNumber(focusedDayRange.low) }}</span>
            <div class="range-track"><i class="range-marker" :style="{ left: `${focusedDayRange.pct}%` }"></i></div>
            <span>{{ formatNumber(focusedDayRange.high) }}</span>
          </div>
          <div class="range-readout"><span>LOW</span><b>{{ focusedDayRange.pct.toFixed(0) }}%</b><span>HIGH</span></div>
        </section>
        <dl class="data-list rail-data-list inspector-detail">
          <div v-if="focusedQuote?.prev_close != null"><dt>昨收</dt><dd>{{ formatNumber(focusedQuote.prev_close) }}</dd></div>
          <div v-if="focusedQuote?.open != null"><dt>今开</dt><dd>{{ formatNumber(focusedQuote.open) }}</dd></div>
          <div v-if="focusedQuote?.high != null"><dt>最高</dt><dd>{{ formatNumber(focusedQuote.high) }}</dd></div>
          <div v-if="focusedQuote?.low != null"><dt>最低</dt><dd>{{ formatNumber(focusedQuote.low) }}</dd></div>
          <div><dt>周期</dt><dd>{{ focusedInterval === 'day' ? '日K' : focusedInterval + ' 分钟' }}</dd></div>
          <div><dt>样本</dt><dd>{{ focusedLimit }} 根</dd></div>
        </dl>
        <section class="data-health inspector-detail">
          <div class="rail-section-head"><b>数据状态</b></div>
          <div class="health-row"><span><i :class="{ ok: connected && !freshness.stale && !focusedQuoteStale, warn: freshness.stale || focusedQuoteStale }"></i>行情</span><b>{{ !connected ? '连接中' : focusedQuoteStale || freshness.stale ? '更新延迟' : '正常' }}</b></div>
          <div class="health-row"><span>数据源</span><b>{{ focusedQuote?.source || (marketFocus === 'jd' ? '京东积存金' : '行情接口') }}</b></div>
          <div class="health-row"><span>行情时间</span><b>{{ focusedQuote?.quote_time || focusedQuote?.time || '实时刷新' }}</b></div>
          <div class="health-row"><span>更新频率</span><b>1 秒</b></div>
          <div class="health-row"><span>最近同步</span><b>{{ freshness.label }}</b></div>
        </section>
      </aside>
    </div>

    <!-- 黄金之外的维度：A 股 / 美股 / 加密 看板 +每日要闻（各自独立降级） -->
    <MultiMarketBoard :active="active" />
  </section>
</template>

<style scoped>
.market-workspace { display: flex; flex-direction: column; gap: 0; margin: 0; }
.section-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 68px; padding: 0 2px 10px; }
.market-identity { display: flex; align-items: flex-end; gap: 22px; min-width: 0; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 20px; line-height: 1; font-weight: 640; letter-spacing: -.02em; }
.section-bar > div > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.market-hero-price { display: grid; gap: 5px; padding-bottom: 1px; }
.market-hero-price strong { color: var(--text); font: 620 32px/1 ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; letter-spacing: -.04em; }
.market-hero-price span { font: 600 9px/1 ui-monospace, SFMono-Regular, Menlo, monospace; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.section-status i, .health-row i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok, .health-row i.ok { background: var(--ok); }
.section-status i.warn, .health-row i.warn { background: var(--warn); }
.section-status.stale { color: var(--warn); }

.market-primary-layout {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) 44px;
  align-items: stretch;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 52%, transparent);
  transition: grid-template-columns var(--motion-layout, 300ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
.market-primary-layout.inspector-open { grid-template-columns: 26px minmax(0, 1fr) 252px; }
.market-scale-rail { min-width: 0; display: flex; flex-direction: column; align-items: center; padding: 12px 0 10px; border-right: 1px solid color-mix(in srgb, var(--line) 58%, transparent); color: var(--subtle); background: transparent; }
.scale-code { margin-bottom: 11px; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; writing-mode: vertical-rl; transform: rotate(180deg); }
.market-scale-rail b { margin: 8px 0 4px; color: var(--accent-strong); font: 650 7px/1 ui-monospace, monospace; }
.market-scale-rail small { color: var(--subtle); font: 600 6px/1 ui-monospace, monospace; letter-spacing: .08em; writing-mode: vertical-rl; transform: rotate(180deg); }
.scale-ticks { flex: 1; min-height: 92px; width: 100%; display: grid; align-content: space-between; justify-items: end; padding-right: 5px; }
.scale-ticks.lower { flex: .55; min-height: 54px; margin-top: 8px; }
.scale-ticks i { width: 7px; height: 1px; background: var(--line-strong); opacity: .52; }
.scale-ticks i:nth-child(3n) { width: 12px; background: var(--accent); opacity: .44; }

.market-chart-panel { min-width: 0; position: relative; background: transparent; }
.chart-shell { position: relative; background: var(--workspace-chart-bg, transparent); }
.chart-overlay-legend { position: absolute; z-index: 3; left: 18px; top: 14px; display: flex; align-items: center; gap: 12px; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .06em; pointer-events: none; }
.chart-overlay-legend span { display: inline-flex; align-items: center; gap: 5px; }
.chart-overlay-legend b { margin-left: 6px; color: var(--muted); font-weight: 600; }
.legend-swatch { width: 12px; height: 2px; display: inline-block; background: var(--line-strong); }
.legend-swatch.price { background: var(--accent); }
.legend-swatch.volume { background: #7487a1; }
.market-main-chart { height: clamp(540px, calc(100dvh - 290px), 760px); }
.chart-footnote { position: absolute; z-index: 5; left: 14px; bottom: 10px; display: flex; align-items: center; gap: 7px; padding: 5px 8px; border: 1px solid var(--material-border, rgba(255,255,255,.06)); border-radius: 7px; background: color-mix(in srgb, var(--material-glass, transparent) 72%, transparent); color: var(--subtle); font-size: 8px; font-variant-numeric: tabular-nums; backdrop-filter: blur(10px); }
.chart-footnote b { color: var(--muted); font-weight: 550; }

.floating-chart-toolbar {
  position: absolute;
  z-index: 8;
  top: 12px;
  right: 16px;
  min-height: 38px;
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 4px;
  border: 1px solid var(--material-border, rgba(255,255,255,.07));
  border-radius: var(--material-toolbar-radius, 10px);
  background: var(--material-glass, rgba(24,27,30,.72));
  box-shadow: var(--material-shadow, 0 16px 42px rgba(0,0,0,.16));
  backdrop-filter: blur(var(--material-blur, 18px));
  -webkit-backdrop-filter: blur(var(--material-blur, 18px));
}
.toolbar-popover { position: relative; }
.toolbar-popover > summary,
.toolbar-source-select,
.toolbar-more { height: 30px; min-width: 70px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 0 10px; border: 0; border-radius: 7px; outline: 0; background: transparent; color: var(--muted); cursor: pointer; list-style: none; font: 600 9px/1 Inter, "MiSans", "PingFang SC", sans-serif; transition: color var(--motion-fast,120ms) ease, background var(--motion-fast,120ms) ease, transform var(--motion-fast,120ms) ease; }
.toolbar-popover > summary::-webkit-details-marker { display: none; }
.toolbar-popover > summary:hover,
.toolbar-popover[open] > summary,
.toolbar-source-select:hover,
.toolbar-more:hover { color: var(--text); background: rgba(255,255,255,.045); }
.toolbar-popover > summary:active,
.toolbar-more:active { transform: scale(.97); }
.toolbar-popover > summary i { width: 5px; height: 5px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(45deg) translateY(-1px); transition: transform var(--motion-standard,200ms) var(--motion-ease,cubic-bezier(.22,1,.36,1)); }
.toolbar-popover[open] > summary i { transform: rotate(225deg) translate(-1px,-1px); }
.toolbar-source-select { appearance: none; min-width: 62px; text-align: center; }
.toolbar-more { min-width: 34px; padding: 0 8px; letter-spacing: .12em; }
.toolbar-popover-menu { position: absolute; z-index: 30; top: calc(100% + 8px); right: 0; width: 154px; padding: 6px; border: 1px solid var(--material-border, rgba(255,255,255,.07)); border-radius: var(--material-popover-radius,12px); background: var(--material-elevated, rgba(34,37,41,.80)); box-shadow: var(--material-shadow-elevated, 0 20px 60px rgba(0,0,0,.24)); backdrop-filter: blur(var(--material-blur-elevated,28px)); -webkit-backdrop-filter: blur(var(--material-blur-elevated,28px)); transform-origin: top right; animation: market-popover-in var(--motion-standard,200ms) var(--motion-ease,cubic-bezier(.22,1,.36,1)); }
.toolbar-popover-menu button { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 9px; border: 0; border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 10px; }
.toolbar-popover-menu button:hover,
.toolbar-popover-menu button.active { color: var(--text); background: rgba(255,255,255,.05); }
.toolbar-popover-menu button b { color: var(--accent-strong); font-size: 10px; font-weight: 650; }
@keyframes market-popover-in { from { opacity: 0; transform: translateY(-3px) scale(.96); } to { opacity: 1; transform: translateY(0) scale(1); } }

.market-rail { min-width: 0; display: flex; flex-direction: column; overflow: hidden; border-left: 1px solid color-mix(in srgb, var(--line) 54%, transparent); background: color-mix(in srgb, var(--material-glass, transparent) 34%, transparent); backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); }
.rail-head { min-height: 56px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 0 10px; border-bottom: 1px solid color-mix(in srgb, var(--line) 44%, transparent); }
.rail-head > div, .rail-section-head { display: grid; gap: 4px; }
.rail-head b, .rail-section-head b { color: var(--text); font-size: 10px; font-weight: 650; }
.rail-head small { color: var(--subtle); font-size: 8px; }
.inspector-toggle { width: 30px; height: 30px; flex: 0 0 auto; border: 0; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font-size: 20px; transition: color var(--motion-fast,120ms) ease, background var(--motion-fast,120ms) ease, transform var(--motion-fast,120ms) ease; }
.inspector-toggle:hover { color: var(--text); background: rgba(255,255,255,.045); }
.inspector-toggle:active { transform: scale(.94); }
.market-inspector .inspector-detail { opacity: 1; transform: translateX(0); transition: opacity var(--motion-standard,200ms) ease, transform var(--motion-layout,300ms) var(--motion-ease,cubic-bezier(.22,1,.36,1)); }
.market-inspector.inspector-collapsed .rail-head { min-height: 100%; padding: 10px 0 0; justify-content: flex-start; align-items: center; flex-direction: column; border-bottom: 0; }
.market-inspector.inspector-collapsed .rail-head > div,
.market-inspector.inspector-collapsed .inspector-detail { display: none; }
.quote-detail { padding: 14px 13px 12px; border-bottom: 1px solid color-mix(in srgb, var(--line) 54%, transparent); }
.rail-symbol { color: var(--muted); font-size: 9px; }
.rail-price { margin-top: 7px; color: var(--text); font: 650 25px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.rail-change { margin-top: 6px; font: 600 8px/1 ui-monospace, monospace; }
.range-instrument { padding: 12px 13px 13px; border-bottom: 1px solid color-mix(in srgb, var(--line) 54%, transparent); }
.range-scale { display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 8px; margin-top: 12px; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.range-track { position: relative; height: 18px; border-top: 1px solid var(--line-strong); border-bottom: 1px solid var(--line); background: repeating-linear-gradient(90deg, transparent 0 18%, rgba(83,97,102,.42) 18% calc(18% + 1px), transparent calc(18% + 1px) 20%); }
.range-marker { position: absolute; top: -5px; bottom: -5px; width: 1px; background: var(--accent); transform: translateX(-50%); }
.range-readout { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; margin-top: 7px; color: var(--subtle); font: 600 6px/1 ui-monospace, monospace; letter-spacing: .07em; }
.range-readout b { color: var(--accent-strong); text-align: center; }
.range-readout span:last-child { text-align: right; }
.data-list { margin: 0; }
.rail-data-list { padding: 0 13px; border-bottom: 1px solid color-mix(in srgb, var(--line) 54%, transparent); }
.data-list > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 31px; border-bottom: 1px solid color-mix(in srgb, var(--line) 46%, transparent); }
.data-list > div:last-child { border-bottom: 0; }
.data-list dt { color: var(--muted); font-size: 10px; }
.data-list dd { margin: 0; color: var(--text); font: 600 8px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.data-health { flex: 1; padding: 13px; }
.rail-section-head { padding-bottom: 8px; }
.health-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 32px; border-bottom: 1px solid color-mix(in srgb, var(--line) 44%, transparent); color: var(--muted); font-size: 10px; }
.health-row span { display: inline-flex; align-items: center; gap: 6px; }
.health-row b { max-width: 145px; color: var(--text); font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }

/* V4 — content-first market canvas. */
.section-bar {
  min-height: 88px;
  padding: 4px 2px 18px;
  align-items: flex-end;
}
.market-identity { gap: 28px; }
.section-bar h1 {
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -.035em;
}
.section-bar > div > span { margin-top: 8px; font-size: 11px; }
.market-hero-price { gap: 7px; }
.market-hero-price strong {
  font-size: 42px;
  font-weight: 590;
  letter-spacing: -.055em;
}
.market-hero-price span { font-size: 11px; }
.section-status { margin-bottom: 3px; font-size: 10px; }
.section-status i { width: 5px; height: 5px; }

.market-primary-layout {
  grid-template-columns: 14px minmax(0, 1fr) 32px;
  border-bottom: 0;
}
.market-primary-layout.inspector-open { grid-template-columns: 14px minmax(0, 1fr) 264px; }
.market-scale-rail {
  padding: 22px 0 18px;
  border-right: 0;
  opacity: .52;
}
.scale-ticks { min-height: 100%; width: 100%; padding-right: 2px; }
.scale-ticks i { width: 4px; opacity: .42; }
.scale-ticks i:nth-child(3n) { width: 8px; opacity: .48; }
.scale-code,
.market-scale-rail b,
.market-scale-rail small,
.scale-ticks.lower { display: none; }

.market-chart-panel {
  border-radius: 14px;
  overflow: hidden;
}
.chart-shell {
  min-height: 600px;
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface) 46%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.025);
}
.market-main-chart { height: clamp(600px, calc(100dvh - 250px), 820px); }
.chart-overlay-legend { display: none; }
.chart-footnote {
  left: 16px;
  bottom: 12px;
  padding: 4px 7px;
  border: 0;
  border-radius: 8px;
  background: rgba(8,11,14,.42);
  color: var(--subtle);
  font-size: 8px;
  box-shadow: none;
  backdrop-filter: blur(12px);
}
.floating-chart-toolbar {
  top: 14px;
  right: 16px;
  min-height: 36px;
  border-radius: 12px;
  background: color-mix(in srgb, var(--material-glass) 84%, transparent);
  box-shadow: 0 10px 34px rgba(0,0,0,.12), inset 0 1px 0 rgba(255,255,255,.03);
}

.market-rail {
  border-left: 1px solid color-mix(in srgb, var(--line) 54%, transparent);
  background: color-mix(in srgb, var(--material-glass) 68%, transparent);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}
.market-inspector.inspector-collapsed {
  border-left-color: transparent;
  background: transparent;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}
.market-inspector.inspector-collapsed .rail-head { padding-top: 14px; }
.inspector-toggle {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  font-size: 18px;
}
.rail-head {
  min-height: 52px;
  padding: 0 12px;
  border-bottom-color: color-mix(in srgb, var(--line) 34%, transparent);
}
.quote-detail,
.range-instrument,
.rail-data-list { border-bottom-color: color-mix(in srgb, var(--line) 34%, transparent); }
.rail-price { font-size: 30px; font-weight: 600; letter-spacing: -.04em; }
.data-list > div,
.health-row { border-bottom-color: color-mix(in srgb, var(--line) 30%, transparent); }

@media (max-width: 1100px) {
  .market-primary-layout { grid-template-columns: 10px minmax(0, 1fr) 30px; }
  .market-primary-layout.inspector-open { grid-template-columns: 10px minmax(0, 1fr) 232px; }
  .market-hero-price strong { font-size: 36px; }
}
@media (max-width: 900px) {
  .market-primary-layout,
  .market-primary-layout.inspector-open { grid-template-columns: 1fr; }
  .market-scale-rail { display: none; }
  .market-rail { display: grid; grid-template-columns: 180px 1fr 1fr; border-left: 0; border-top: 1px solid var(--line); overflow: visible; }
  .market-rail .rail-head { display: none; }
  .market-inspector.inspector-collapsed .inspector-detail { display: block; opacity: 1; transform: none; }
  .quote-detail, .rail-data-list { border-right: 1px solid var(--line); border-bottom: 0; }
  .range-instrument { display: none; }
  .market-main-chart { height: 450px; }
  .floating-chart-toolbar { top: 10px; right: 10px; }
}
@media (max-width: 700px) { .floating-chart-toolbar { position: relative; top: auto; right: auto; width: fit-content; margin: 10px 0 0 10px; } }
@media (max-width: 620px) { .section-bar { align-items: flex-start; flex-direction: column; } .market-rail { grid-template-columns: 1fr; } .quote-detail, .rail-data-list { border-right: 0; border-bottom: 1px solid var(--line); } }
@media (prefers-reduced-motion: reduce) { .market-primary-layout, .market-rail, .toolbar-popover > summary, .toolbar-popover > summary i, .inspector-toggle, .market-inspector .inspector-detail { transition: none !important; } .toolbar-popover-menu { animation: none !important; } }

/* V6 — calmer market surface and more deliberate floating controls. */
.section-bar {
  min-height: 82px;
  padding-bottom: 14px;
}
.market-hero-price strong { font-size: 40px; }
.market-chart-panel {
  border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 78%, transparent);
  background: color-mix(in srgb, var(--surface) 34%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.022), 0 18px 46px rgba(0,0,0,.055);
}
.chart-shell {
  min-height: 580px;
  background: transparent;
  box-shadow: none;
}
.market-main-chart { height: clamp(580px, calc(100dvh - 268px), 790px); }
.floating-chart-toolbar {
  top: 12px;
  right: 12px;
  min-height: 34px;
  padding: 3px;
  border-color: color-mix(in srgb, var(--material-border, var(--line)) 82%, transparent);
  background: color-mix(in srgb, var(--material-glass) 76%, transparent);
  box-shadow: 0 8px 26px rgba(0,0,0,.09), inset 0 1px 0 rgba(255,255,255,.025);
  opacity: .92;
  transition: opacity .16s ease, background .16s ease, border-color .16s ease, box-shadow .16s ease;
}
.floating-chart-toolbar:hover,
.floating-chart-toolbar:focus-within {
  opacity: 1;
  background: color-mix(in srgb, var(--material-glass) 90%, transparent);
  border-color: color-mix(in srgb, var(--text) 12%, var(--material-border, var(--line)));
  box-shadow: 0 12px 34px rgba(0,0,0,.12), inset 0 1px 0 rgba(255,255,255,.035);
}
.toolbar-popover > summary,
.toolbar-source-select,
.toolbar-more { height: 28px; }
.toolbar-popover > summary { min-width: 66px; }
.chart-footnote { opacity: .72; }
.market-rail:not(.inspector-collapsed) {
  box-shadow: -14px 0 34px rgba(0,0,0,.05);
}
.market-inspector .inspector-detail { transition-duration: 180ms, 260ms; }
@media (max-width: 1100px) {
  .chart-shell { min-height: 480px; }
  .market-main-chart { height: 480px; }
}
@media (max-width: 900px) {
  .chart-shell { min-height: 430px; }
  .market-main-chart { height: 430px; }
  .market-chart-panel { border-radius: 12px; }
}
@media (prefers-reduced-motion: reduce) {
  .floating-chart-toolbar { transition: none !important; }
}

/* V7 — Attention Depth: the active market canvas comes forward without glow. */
.section-bar,
.quote-strip,
.market-inspector,
.market-chart-panel {
  transition: opacity .20s ease, border-color .20s ease, box-shadow .20s ease, transform .20s cubic-bezier(.22,1,.36,1);
}
.market-workspace:has(.chart-shell:hover) .section-bar,
.market-workspace:has(.chart-shell:hover) .quote-strip {
  opacity: .76;
}
.market-workspace:has(.chart-shell:hover) .market-inspector.inspector-collapsed {
  opacity: .62;
}
.market-workspace:has(.chart-shell:hover) .market-chart-panel {
  border-color: color-mix(in srgb, var(--text) 11%, var(--material-border, var(--line)));
  box-shadow: inset 0 1px 0 rgba(255,255,255,.028), 0 22px 54px rgba(0,0,0,.065);
}
.market-workspace:has(.floating-chart-toolbar:hover) .chart-footnote,
.market-workspace:has(.floating-chart-toolbar:focus-within) .chart-footnote { opacity: .42; }
@media (prefers-reduced-motion: reduce) {
  .section-bar,
  .quote-strip,
  .market-inspector,
  .market-chart-panel { transition: none !important; }
}
</style>
