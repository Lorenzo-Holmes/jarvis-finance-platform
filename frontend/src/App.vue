<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, defineAsyncComponent } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'

const SimTradeView = defineAsyncComponent(() => import('./components/SimTradeView.vue'))
const AiCenter = defineAsyncComponent(() => import('./components/AiCenter.vue'))
const OpsView = defineAsyncComponent(() => import('./components/OpsView.vue'))
const CrossMarketView = defineAsyncComponent(() => import('./components/CrossMarketView.vue'))
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))

let echartsModulePromise = null
function getEcharts() {
  if (!echartsModulePromise) echartsModulePromise = import('./charts/echarts')
  return echartsModulePromise
}

// ---- 登录态 ----
const user = ref(null)
const isLoggedIn = computed(() => !!user.value)

function handleLoggedIn(u) {
  user.value = u
}
async function logout() {
  try { await api.logout() } catch (e) { /* 即使网络失败也清理本地 UI 状态 */ }
  user.value = null
}

// ---- Tab ----
const baseTabs = ['行情', '多市场', '回测', '模拟盘', '研究助手', '运维']
const tabs = computed(() => user.value?.role === 'ADMIN' ? [...baseTabs, '管理'] : baseTabs)
const activeTab = ref('行情')
function switchTab(name) {
  activeTab.value = name
  if (name === '行情') setTimeout(() => {
    klineEtfChart && klineEtfChart.resize()
    klineLondonChart && klineLondonChart.resize()
  }, 50)
}

// ---- 行情状态 ----
const connected = ref(false)
const realtimePrices = ref(null)   // 黄金ETF + 伦敦金 实时价
const markets = [
  { key: 'gold_etf', label: '黄金ETF华夏', symbol: 'sh518850' },
  { key: 'london_gold', label: '伦敦金(现货黄金)', symbol: 'hf_XAU' },
]
// 每个标的独立的K线配置 (limit: 根数, interval: day/1/5/15/30/60)
const etfCfg = reactive({ limit: 120, interval: 'day' })
const londonCfg = reactive({ limit: 120, interval: 'day' })
const intervals = [
  { v: 'day', label: '日K' },
  { v: '1', label: '1分' },
  { v: '5', label: '5分' },
  { v: '15', label: '15分' },
  { v: '30', label: '30分' },
  { v: '60', label: '60分' },
]

async function loadRealtime() {
  try {
    const d = await api.marketPrices()
    realtimePrices.value = d.data || null
  } catch (e) { /* 忽略, 不阻塞K线 */ }
}

// ---- 京东积存金实时金价 (前端直连京东HTTP, 经 nginx /jd/ 同域反代) ----
// 后端每分钟抓取持久化 -> 前端画K线时请求 /api/jd/kline
const jdPrices = ref(null)
let jdPollTimer = null

async function loadJdLive() {
  try {
    const d = await api.jdPrices()
    if (d.code === 200 && d.data && Object.keys(d.data).length) {
      jdPrices.value = d.data
    }
  } catch (e) { /* 保留上一次有效值 */ }
}

// ---- 京东K线 (请求后端, 由每分钟持久化快照聚合) ----
const jdKlineCfg = reactive({ market: 'zheshang', interval: 5, limit: 200 })
const jdKlineRef = ref(null)
const jdKlineRange = ref(null)
let jdKlineChart = null

async function loadJdKline() {
  try {
    const d = await api.jdKline(jdKlineCfg.market, jdKlineCfg.interval, jdKlineCfg.limit)
    // 兼容两种返回: 后端裸结构 {market,count,range,data} 或 ApiResponse {code,message,data:{...}}
    const body = d?.data && d.data.data ? d.data : d
    jdKlineRange.value = body?.range || null
    await renderJdKline(jdKlineRef, body?.data || [])
  } catch (e) { /* 忽略 */ }
}

async function renderJdKline(elRef, data) {
  if (!elRef.value) return
  const echarts = await getEcharts()
  if (!elRef.value) return
  if (!jdKlineChart) {
    jdKlineChart = echarts.init(elRef.value)
  }
  jdKlineChart.setOption({
    backgroundColor: 'transparent',
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'cross' },
      backgroundColor: '#17191b', borderColor: '#35383d', textStyle: { color: '#f1efe8' },
    },
    xAxis: { type: 'category', data: data.map(b => b.date), axisLine: { lineStyle: { color: '#34383d' } }, axisLabel: { color: '#8f9498' } },
    yAxis: { scale: true, axisLine: { lineStyle: { color: '#34383d' } }, axisLabel: { color: '#8f9498', formatter: v => v.toFixed(2) } },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 14, bottom: 0 }],
    series: [{
      type: 'candlestick',
      data: data.map(b => [b.open, b.close, b.low, b.high]),
      itemStyle: { color: '#27c46b', color0: '#ef5350', borderColor: '#27c46b', borderColor0: '#ef5350' },
    }],
  }, true)
}

// ---- 回测状态 ----
const bt = reactive({ short_ma: 5, long_ma: 20, initial_cash: 100000, running: false })
const btResult = ref(null)
const btError = ref('')

const klineEtfRef = ref(null)
const klineLondonRef = ref(null)
const equityRef = ref(null)
let klineEtfChart = null
let klineLondonChart = null
let equityChart = null
const klineRanges = ref({ gold_etf: null, london_gold: null })

// ---- 行情/回测 ----
async function loadKline() {
  // 同时加载两个标的的K线 (各自独立配置)
  await Promise.all([
    loadOneKline('gold_etf', klineEtfRef, 'klineEtfChart', etfCfg),
    loadOneKline('london_gold', klineLondonRef, 'klineLondonChart', londonCfg),
  ])
}

async function loadOneKline(marketKey, refObj, chartVar, cfg) {
  try {
    const d = await api.marketKline({ market: marketKey, limit: cfg.limit, interval: cfg.interval })
    let data = Array.isArray(d.data) ? d.data : (d.data?.data || [])
    klineRanges.value[marketKey] = d.data?.range
    await renderKline(refObj, chartVar, data)
    connected.value = true
  } catch (e) { connected.value = false }
}

async function runBacktest() {
  bt.running = true; btError.value = ''; btResult.value = null
  try {
    const d = await api.backtest({
      market: 'gold_etf',
      short_ma: bt.short_ma,
      long_ma: bt.long_ma,
      initial_cash: bt.initial_cash,
      limit: etfCfg.limit,
    })
    if (d.code !== 200 || !d.data) {
      throw new Error(d.message || '回测失败')
    }
    btResult.value = d.data
    await nextTick()
    await renderEquity(d.data.equity_curve || [])
  } catch (e) { btError.value = String(e) }
  finally { bt.running = false }
}

async function renderKline(refObj, chartVar, data) {
  if (!refObj.value) return
  const echarts = await getEcharts()
  if (!refObj.value) return
  let chart = chartVar === 'klineEtfChart' ? klineEtfChart : klineLondonChart
  if (!chart) {
    chart = echarts.init(refObj.value)
    if (chartVar === 'klineEtfChart') klineEtfChart = chart
    else klineLondonChart = chart
  }
  const dates = data.map(x => x.date)
  const ohlc = data.map(x => [x.open, x.close, x.low, x.high])
  const vols = data.map((x, i) => [i, x.volume, x.close >= x.open ? 1 : -1])
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['K线', '成交量'], textStyle: { color: '#8f9498' } },
    grid: [
      { left: 55, right: 20, top: 20, height: '62%' },
      { left: 55, right: 20, top: '78%', height: '14%' },
    ],
    xAxis: [
      { type: 'category', data: dates, boundaryGap: true, axisLabel: { color: '#8f9498' } },
      { type: 'category', gridIndex: 1, data: dates, axisLabel: { show: false } },
    ],
    yAxis: [
      { scale: true, axisLabel: { color: '#8f9498' }, splitLine: { lineStyle: { color: '#24272b' } } },
      { gridIndex: 1, axisLabel: { show: false }, splitLine: { show: false } },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 40, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 0, height: 18, borderColor: '#34383d', textStyle: { color: '#8f9498' } },
    ],
    series: [
      { name: 'K线', type: 'candlestick', data: ohlc, itemStyle: { color: '#ef5350', color0: '#27c46b', borderColor: '#ef5350', borderColor0: '#27c46b' } },
      { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, itemStyle: { color: p => p.data[2] > 0 ? '#ef5350' : '#27c46b' } },
    ],
  })
}

async function renderEquity(curve) {
  if (!equityRef.value) return
  const echarts = await getEcharts()
  if (!equityRef.value) return
  if (!equityChart) equityChart = echarts.init(equityRef.value)
  equityChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 55, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: curve.map(p => p.date), axisLabel: { color: '#8f9498' } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8f9498' }, splitLine: { lineStyle: { color: '#24272b' } } },
    series: [{ name: '策略净值', type: 'line', showSymbol: false, data: curve.map(p => p.equity), lineStyle: { color: '#d7b56d', width: 1.8 }, areaStyle: { color: 'rgba(215,181,109,0.08)' } }],
  })
}

function round2(n) { return Math.round(n * 100) / 100 }
function fmt(n) { return n == null ? '-' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtPct(n) { return n == null ? '-' : Number(n).toFixed(2) + '%' }

onMounted(async () => {
  try {
    const me = await api.me()
    if (me.code === 200 && me.data) user.value = me.data
  } catch (e) { user.value = null }
  await Promise.all([loadKline(), loadRealtime(), loadJdKline()])
  loadJdLive()
  jdPollTimer = setInterval(loadJdLive, 30000)
  window.addEventListener('resize', () => {
    klineEtfChart && klineEtfChart.resize()
    klineLondonChart && klineLondonChart.resize()
    equityChart && equityChart.resize()
    jdKlineChart && jdKlineChart.resize()
  })
})

onUnmounted(() => {
  clearInterval(jdPollTimer)
})
</script>

<template>
  <div class="container">
    <header class="navbar">
      <div class="brand">
        <img src="/favicon.png" class="brand-icon" alt="logo" />
        <span class="brand-name">贾维斯 · 金融投研</span>
      </div>
      <div class="nav-right">
        <div v-if="isLoggedIn" class="user-chip">
          <span class="email">{{ user?.email || '已登录' }}</span>
          <button class="btn small" @click="logout">退出</button>
        </div>
        <div v-else class="conn" :class="{ ok: connected }">
          <span class="dot"></span>{{ connected ? '后端已连接' : '行情未连接' }}
        </div>
      </div>
    </header>

    <!-- 未登录: 显示登录页 -->
    <LoginView v-if="!isLoggedIn" @logged-in="handleLoggedIn" />

    <!-- 已登录: 工作台 -->
    <template v-else>
      <nav class="tabs">
        <button v-for="t in tabs" :key="t" class="tab-btn"
                :class="{ active: activeTab === t }" @click="switchTab(t)">{{ t }}</button>
      </nav>

      <!-- 行情: 上排实时价格(左右), 下排K线(各占一整行) -->
      <section v-show="activeTab === '行情'" class="panel-wrap">
        <!-- 顶部: 京东积存金实时价 (两个Card) -->
        <div v-if="jdPrices" class="dual-grid">
          <div v-for="(p, key) in jdPrices" :key="key" class="rt-card jd">
            <div class="rt-name">{{ p.label }}</div>
            <div class="rt-price jd-price">{{ fmt(p.price) }}</div>
            <div class="rt-sub">
              <span :class="(p.change || 0) >= 0 ? 'pos' : 'neg'">
                {{ p.change }} ({{ fmtPct(p.change_pct) }})
              </span>
              <span class="rt-muted">{{ p.time }}</span>
              <span class="rt-muted" style="margin-left:auto">直连</span>
            </div>
          </div>
        </div>

        <!-- 上排: 实时价格 Card (黄金ETF + 伦敦金) -->
        <div v-if="realtimePrices" class="dual-grid">
          <div v-for="(m, key) in realtimePrices" :key="key" class="rt-card">
            <div class="rt-name">{{ m.name }}</div>
            <div class="rt-price">{{ fmt(m.price) }}</div>
            <div class="rt-sub">
              <span :class="(m.change || 0) >= 0 ? 'pos' : 'neg'">
                {{ m.change }} ({{ fmtPct(m.change_pct) }})
              </span>
              <span class="rt-muted">昨收 {{ m.prev_close }}</span>
            </div>
          </div>
        </div>

        <!-- 下排: K线 Card (各占一整行) -->
        <div class="stack-grid">
          <div class="panel kline-card">
            <div class="panel-head">
              <h2>黄金ETF华夏 K线</h2>
              <div class="kline-ctrl">
                <select v-model="etfCfg.interval" @change="loadKline" class="select">
                  <option v-for="iv in intervals" :key="iv.v" :value="iv.v">{{ iv.label }}</option>
                </select>
                <select v-model.number="etfCfg.limit" @change="loadKline" class="select">
                  <option :value="60">60 根</option>
                  <option :value="120">120 根</option>
                  <option :value="250">250 根</option>
                </select>
              </div>
            </div>
            <div ref="klineEtfRef" class="chart tall"></div>
            <div v-if="klineRanges.gold_etf" class="hint">
              区间 {{ klineRanges.gold_etf.min }} ~ {{ klineRanges.gold_etf.max }} ({{ klineRanges.gold_etf.count }} 根)
            </div>
          </div>

          <div class="panel kline-card">
            <div class="panel-head">
              <h2>伦敦金(现货黄金) K线</h2>
              <div class="kline-ctrl">
                <select v-model="londonCfg.interval" @change="loadKline" class="select">
                  <option v-for="iv in intervals" :key="iv.v" :value="iv.v">{{ iv.label }}</option>
                </select>
                <select v-model.number="londonCfg.limit" @change="loadKline" class="select">
                  <option :value="60">60 根</option>
                  <option :value="120">120 根</option>
                  <option :value="250">250 根</option>
                </select>
              </div>
            </div>
            <div ref="klineLondonRef" class="chart tall"></div>
            <div v-if="klineRanges.london_gold" class="hint">
              区间 {{ klineRanges.london_gold.min }} ~ {{ klineRanges.london_gold.max }} ({{ klineRanges.london_gold.count }} 根)
            </div>
          </div>

          <!-- 最下方: 京东积存金K线 (后端实时聚合) -->
          <div class="panel kline-card">
            <div class="panel-head">
              <h2>京东积存金 K线</h2>
              <div class="kline-ctrl">
                <select v-model="jdKlineCfg.market" @change="loadJdKline" class="select">
                  <option value="zheshang">浙商积存金</option>
                  <option value="minsheng">民生积存金</option>
                </select>
                <select v-model.number="jdKlineCfg.interval" @change="loadJdKline" class="select">
                  <option :value="1">1分</option>
                  <option :value="5">5分</option>
                  <option :value="15">15分</option>
                  <option :value="30">30分</option>
                  <option :value="60">60分</option>
                </select>
                <select v-model.number="jdKlineCfg.limit" @change="loadJdKline" class="select">
                  <option :value="100">100 根</option>
                  <option :value="200">200 根</option>
                  <option :value="500">500 根</option>
                </select>
              </div>
            </div>
            <div ref="jdKlineRef" class="chart tall"></div>
            <div v-if="jdKlineRange" class="hint">
              区间 {{ jdKlineRange.min }} ~ {{ jdKlineRange.max }} ({{ jdKlineRange.count }} 根)
            </div>
          </div>
        </div>
      </section>

      <!-- A股/美股/加密货币 -->
      <section v-show="activeTab === '多市场'" class="panel-wrap">
        <CrossMarketView />
      </section>

      <!-- 回测 -->
      <section v-show="activeTab === '回测'" class="panel-wrap">
        <div class="panel">
          <div class="panel-head">
            <h2>双均线策略回测</h2>
            <div class="controls">
              <label>短期 <input type="number" v-model.number="bt.short_ma" min="1" class="num" /></label>
              <label>长期 <input type="number" v-model.number="bt.long_ma" min="2" class="num" /></label>
              <label>本金 <input type="number" v-model.number="bt.initial_cash" min="1000" class="num wide" /></label>
              <button class="btn primary" @click="runBacktest" :disabled="bt.running">{{ bt.running ? '回测中...' : '运行回测' }}</button>
            </div>
          </div>
          <div v-if="btError" class="error">{{ btError }}</div>
          <template v-if="btResult">
            <div class="metrics">
              <div class="metric"><div class="m-label">期末资金</div><div class="m-value">{{ fmt(btResult.final_equity) }}</div></div>
              <div class="metric"><div class="m-label">总收益率</div><div class="m-value" :class="btResult.total_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.total_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">年化收益</div><div class="m-value" :class="btResult.annual_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.annual_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">买入持有</div><div class="m-value" :class="btResult.buy_hold_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.buy_hold_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">最大回撤</div><div class="m-value neg">{{ fmtPct(btResult.max_drawdown_pct) }}</div></div>
              <div class="metric"><div class="m-label">交易次数</div><div class="m-value">{{ btResult.num_trades }}</div></div>
            </div>
            <h3 class="section-sub">策略净值曲线</h3>
            <div ref="equityRef" class="chart"></div>
            <h3 v-if="btResult.trades.length" class="section-sub">近期交易</h3>
            <table v-if="btResult.trades.length" class="table">
              <thead><tr><th>日期</th><th>方向</th><th>价格</th></tr></thead>
              <tbody><tr v-for="(t, i) in btResult.trades.slice(-10)" :key="i">
                <td>{{ t.date }}</td><td :class="t.type === 'BUY' ? 'pos' : 'neg'">{{ t.type }}</td><td>{{ t.price }}</td>
              </tr></tbody>
            </table>
          </template>
        </div>
      </section>

      <!-- 模拟盘 -->
      <section v-show="activeTab === '模拟盘'">
        <SimTradeView />
      </section>

      <!-- 研究助手 -->
      <section v-show="activeTab === '研究助手'" class="panel-wrap">
        <AiCenter />
      </section>

      <!-- 运维监控 -->
      <section v-show="activeTab === '运维'" class="panel-wrap">
        <OpsView />
      </section>

      <!-- 管理员后台 -->
      <section v-if="user?.role === 'ADMIN'" v-show="activeTab === '管理'" class="panel-wrap">
        <AdminView />
      </section>

      <footer class="foot">
        <span>贾维斯金融投研平台 · 仅供研究参考，不构成投资建议</span>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.container { max-width: 1440px; margin: 0 auto; padding: 0 28px 40px; }
.navbar { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 650; letter-spacing: .01em; }
.brand-icon { width: 28px; height: 28px; border-radius: 5px; display: inline-block; object-fit: cover; vertical-align: middle; }
.nav-right { display: flex; align-items: center; gap: 12px; }
.user-chip { display: flex; align-items: center; gap: 10px; }
.email { color: var(--muted); font-size: 12px; }
.conn { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 12px; }
.conn .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.conn.ok .dot { background: var(--ok); }
.tabs { display: flex; gap: 24px; margin: 0 0 18px; border-bottom: 1px solid var(--line); overflow-x: auto; }
.tab-btn { position: relative; flex: 0 0 auto; color: var(--muted); background: transparent; border: 0; padding: 12px 1px 11px; cursor: pointer; font-size: 13px; font-weight: 550; }
.tab-btn.active { color: var(--text); }
.tab-btn.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--accent); }
.panel-wrap { margin-top: 4px; }
.dual-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }
/* 京东积存金 Card 金色高亮 */
.rt-card.jd::before { background: var(--accent); }
.rt-card.jd .rt-price { color: var(--accent-strong); }
.jd-chart { height: 300px; margin-top: 12px; }
.stack-grid { display: flex; flex-direction: column; gap: 12px; }
.rt-card { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 15px 18px; position: relative; overflow: hidden; }
.rt-card::before { content: ''; position: absolute; top: 0; left: 0; bottom: 0; width: 2px; background: #777d84; }
.rt-name { color: var(--muted); font-size: 12px; margin-bottom: 4px; }
.rt-price { font-size: 28px; font-weight: 650; color: var(--text); font-variant-numeric: tabular-nums; letter-spacing: -.025em; }
.rt-sub { display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 13px; }
.rt-muted { color: var(--subtle); font-size: 11px; }
.kline-card { margin-bottom: 0; }
.kline-ctrl { display: flex; gap: 8px; }
@media (max-width: 900px) { .dual-grid { grid-template-columns: 1fr; } }
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.panel-head h2 { margin: 0; font-size: 15px; font-weight: 650; color: var(--text); }
.chart { width: 100%; background: var(--surface); border: 1px solid #222529; border-radius: var(--radius-sm); }
.chart.tall { height: 420px; margin-top: 16px; }
.chart:not(.tall) { height: 300px; margin-top: 12px; }
.controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.controls label { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.num { width: 70px; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 7px 9px; }
.num.wide { width: 110px; }
.select { background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 7px 10px; }
.btn { background: #1c1f22; border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 7px 14px; cursor: pointer; font-size: 13px; font-weight: 550; }
.btn.small { padding: 5px 12px; font-size: 12px; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #17140e; }
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin: 16px 0; }
.metric { background: var(--surface); border: 1px solid #222529; border-radius: var(--radius-sm); padding: 11px 12px; }
.m-label { color: var(--muted); font-size: 11px; }
.m-value { font-size: 19px; font-weight: 650; margin-top: 3px; font-variant-numeric: tabular-nums; }
.pos { color: #27c46b; }
.neg { color: #ef5350; }
.error { color: #ef5350; padding: 10px; background: rgba(239,83,80,.1); border-radius: 6px; margin: 12px 0; }
.hint { color: var(--subtle); font-size: 11px; margin-top: 9px; }
.section-sub { color: var(--muted); font-size: 13px; margin: 18px 0 8px; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th, .table td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #25282c; }
.table th { color: var(--muted); font-weight: 550; background: #131517; }
.foot { color: var(--subtle); font-size: 11px; margin-top: 16px; }
@media (max-width: 700px) { .metrics { grid-template-columns: 1fr 1fr; } }
</style>
