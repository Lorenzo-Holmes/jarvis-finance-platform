<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'
import MarkdownContent from '../components/common/MarkdownContent.vue'

// ---- 智能询报价参数（FR-07）----
// 行情快照取自 /api/market/prices（与「行情」页同一数据源）；历史收盘价由 Java 服务端从自营 K 线库注入，前端不传。
const market = ref('gold_etf')
const horizonDays = ref(5)
const confidence = ref(95)
const evaluating = ref(false)
const result = ref(null) // { metrics, forecast, content }
const quote = ref(null)  // 最新行情快照（现价/涨跌）
const error = ref('')

const validation = computed(() => {
  if (!market.value.trim()) return '请填写标的代码'
  return ''
})

const HORIZON_OPTIONS = [
  { value: 5, label: '未来 5 个交易日' },
  { value: 10, label: '未来 10 个交易日' },
  { value: 20, label: '未来 20 个交易日' },
]

function fmt(value) {
  if (value == null || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return numeric.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtPct(value) {
  if (value == null || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(2)}%`
}

// 现价（优先用最新行情快照，回退到确定性指标）
const lastClose = computed(() => result.value?.metrics?.price ?? quote.value?.price ?? null)

const changePct = computed(() => result.value?.metrics?.change_pct ?? quote.value?.change_pct ?? null)

const forecast = computed(() => (result.value?.forecast?.available ? result.value.forecast : null))

// 区间条形图：把下界/中心/上界映射到 0-100% 位置，便于直观比较
const band = computed(() => {
  const f = forecast.value
  if (!f) return null
  const lower = Number(f.lower)
  const center = Number(f.center)
  const upper = Number(f.upper)
  const last = Number(f.last_close)
  if (![lower, center, upper, last].every(Number.isFinite)) return null
  const lo = Math.min(lower, last)
  const hi = Math.max(upper, last)
  const span = hi - lo
  if (span <= 0) return null
  const pos = (value) => Math.max(0, Math.min(100, ((value - lo) / span) * 100))
  return {
    lowerPos: pos(lower),
    upperPos: pos(upper),
    centerPos: pos(center),
    lastPos: pos(last),
  }
})

const direction = computed(() => {
  const f = forecast.value
  if (!f) return null
  const slope = Number(f.slope_pct_per_day)
  if (!Number.isFinite(slope)) return null
  if (slope > 0.005) return { key: 'up', label: '上行趋势' }
  if (slope < -0.005) return { key: 'down', label: '下行趋势' }
  return { key: 'flat', label: '横盘震荡' }
})

async function evaluate() {
  if (validation.value || evaluating.value) return
  evaluating.value = true
  error.value = ''
  result.value = null
  quote.value = null
  try {
    // 先取行情快照作为 price_data；历史收盘价由 Java 服务端注入，前端不参与
    try {
      const symbol = market.value.trim()
      const pricesResponse = await api.marketPrices()
      const snapshot = pricesResponse?.data?.[symbol]
      if (snapshot) quote.value = { symbol, ...snapshot }
    } catch (_) {
      // 行情快照不可用时仍可请求 AI；服务端会以自营 K 线为准
    }

    const response = await api.aiQuote(
      quote.value || { symbol: market.value.trim() },
      {
        market: market.value.trim(),
        horizonDays: horizonDays.value,
        confidence: confidence.value / 100,
      },
    )
    if (response.code !== 200 || !response.data) throw new Error(response.message || '智能报价生成失败')
    if (response.data.available === false) {
      error.value = response.data.reason === 'insufficient_closes'
        ? `行情样本不足（仅 ${response.data.bars || 0} 根有效收盘价），无法生成趋势区间`
        : '智能报价暂不可用'
      return
    }
    result.value = response.data
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    evaluating.value = false
  }
}

function clearAll() {
  result.value = null
  quote.value = null
  error.value = ''
}
</script>

<template>
  <section class="qt-workspace">
    <div class="section-bar">
      <div>
        <h1>智能报价</h1>
        <span>现价 · 涨跌 · 未来价格走势趋势区间 · AI 解读</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '输入待完善' : '输入可分析' }}</span>
    </div>

    <div class="qt-layout">
      <aside class="panel qt-input-panel">
        <div class="qt-panel-title">报价参数</div>

        <div class="qt-field">
          <label>标的</label>
          <input v-model="market" class="qt-input" aria-label="标的代码" placeholder="gold_etf / london_gold…" />
          <span>历史收盘价由服务端自营 K 线库注入。</span>
        </div>

        <div class="qt-field">
          <label>预测窗口</label>
          <select v-model.number="horizonDays" class="qt-input" aria-label="预测窗口">
            <option v-for="item in HORIZON_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <span>窗口越长，区间越宽（不确定性累积）。</span>
        </div>

        <div class="qt-field">
          <label>置信度</label>
          <select v-model.number="confidence" class="qt-input" aria-label="置信度">
            <option :value="90">90%</option>
            <option :value="95">95%</option>
            <option :value="99">99%</option>
          </select>
          <span>正态近似下的双侧区间覆盖水平；不代表实际命中率。</span>
        </div>

        <div v-if="validation" class="qt-validation">{{ validation }}</div>
        <button class="btn primary qt-run" type="button" :disabled="evaluating || !!validation" @click="evaluate">
          {{ evaluating ? '正在生成报价解读…' : '生成智能报价' }}
        </button>
        <button v-if="result || error" type="button" class="text-action" @click="clearAll">清空结果</button>
        <div class="qt-note">趋势区间由统计/时序基线模型计算，非秒级实时行情，也不构成投资建议。</div>
      </aside>

      <div class="qt-main">
        <template v-if="result">
          <div class="qt-headline">
            <div class="qt-headline-quote">
              <span class="qt-label">最新价</span>
              <b>{{ fmt(lastClose) }}</b>
              <span :class="Number(changePct || 0) >= 0 ? 'pos' : 'neg'">{{ fmtPct(changePct) }}</span>
            </div>
            <div v-if="direction" class="qt-direction" :class="direction.key">
              <i></i>{{ direction.label }}
            </div>
          </div>

          <div v-if="forecast" class="panel qt-forecast-panel">
            <div class="qt-forecast-head">
              <div>
                <b>趋势区间</b>
                <span>未来 {{ forecast.horizon_days }} 个交易日 · {{ Number(forecast.confidence) * 100 }}% 置信 · 基于 {{ forecast.bars }} 根日 K</span>
              </div>
            </div>

            <div class="qt-band">
              <div class="qt-band-track">
                <div
                  class="qt-band-fill"
                  :style="{ left: band ? band.lowerPos + '%' : '0%', width: band ? (band.upperPos - band.lowerPos) + '%' : '0%' }"
                ></div>
                <div v-if="band" class="qt-band-marker qt-marker-center" :style="{ left: band.centerPos + '%' }" title="预测中心值"></div>
                <div v-if="band" class="qt-band-marker qt-marker-last" :style="{ left: band.lastPos + '%' }" title="最新价"></div>
              </div>
              <div class="qt-band-legend">
                <span class="qt-legend-center"><i></i>预测中心</span>
                <span class="qt-legend-last"><i></i>最新价</span>
              </div>
            </div>

            <div class="qt-band-values">
              <div class="qt-band-value">
                <span>下界</span>
                <b class="down">{{ fmt(forecast.lower) }}</b>
              </div>
              <div class="qt-band-value qt-band-center">
                <span>预测中心值</span>
                <b>{{ fmt(forecast.center) }}</b>
              </div>
              <div class="qt-band-value">
                <span>上界</span>
                <b class="up">{{ fmt(forecast.upper) }}</b>
              </div>
            </div>
          </div>

          <div class="qt-metrics">
            <div class="qt-metric">
              <span>中心值涨跌</span>
              <b :class="Number(forecast?.change_to_center_pct) >= 0 ? 'up' : 'down'">{{ fmtPct(forecast?.change_to_center_pct) }}</b>
              <small>预测中心相对最新价</small>
            </div>
            <div class="qt-metric">
              <span>日均斜率</span>
              <b :class="Number(forecast?.slope_pct_per_day) >= 0 ? 'up' : 'down'">{{ fmtPct(forecast?.slope_pct_per_day) }}</b>
              <small>最小二乘拟合趋势</small>
            </div>
            <div class="qt-metric">
              <span>区间宽度</span>
              <b>±{{ forecast?.band_pct != null ? Number(forecast.band_pct).toFixed(2) : '—' }}%</b>
              <small>半宽占最新价比例</small>
            </div>
            <div class="qt-metric">
              <span>日波动率</span>
              <b>{{ forecast?.vol_daily_pct != null ? Number(forecast.vol_daily_pct).toFixed(2) + '%' : '—' }}</b>
              <small>历史日收益标准差</small>
            </div>
          </div>

          <div class="panel qt-report-panel">
            <div class="qt-report-head">
              <div><b>AI 报价解读</b><span>基于确定性指标与趋势区间生成，数值口径以指标卡为准</span></div>
            </div>
            <MarkdownContent class="qt-output" :content="result.content?.content || result.content || '（暂无解读）'" />
          </div>
        </template>

        <DataState v-else-if="evaluating" state="loading" title="正在生成智能报价" message="计算趋势区间并生成 AI 解读，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="智能报价失败" :message="error" retryable @retry="evaluate" />

        <div v-else class="panel qt-empty">
          <div class="qt-empty-mark">报价</div>
          <b>等待生成智能报价</b>
          <span>选择标的与预测窗口后生成，即可查看现价、未来价格走势趋势区间与 AI 行情解读。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.qt-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.qt-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 10px; align-items: start; }
.qt-input-panel { position: sticky; top: 10px; }
.qt-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.qt-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.qt-field label { color: var(--muted); font-size: 10px; }
.qt-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.qt-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; outline: none; }
.qt-input:focus { border-color: #6a5b40; }
.qt-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.qt-run { width: 100%; min-height: 36px; margin-top: 12px; }
.qt-input-panel .text-action { margin-top: 8px; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.qt-input-panel .text-action:hover { color: var(--text); }
.qt-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.qt-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.qt-headline { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 11px 13px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.qt-headline-quote { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.qt-headline-quote .qt-label { color: var(--subtle); font-size: 10px; }
.qt-headline-quote > b { color: var(--accent-strong); font-size: 24px; line-height: 1; font-weight: 680; letter-spacing: -.025em; font-variant-numeric: tabular-nums; }
.qt-headline-quote > span { font-size: 11px; font-variant-numeric: tabular-nums; }
.qt-headline-quote .pos { color: #e05a5a; }
.qt-headline-quote .neg { color: var(--ok); }
.qt-direction { display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 3px; background: var(--surface); padding: 5px 9px; color: var(--muted); font-size: 10px; font-weight: 650; }
.qt-direction i { width: 6px; height: 6px; border-radius: 50%; background: #5b6066; }
.qt-direction.up { color: #e05a5a; border-color: rgba(224,90,90,.4); }
.qt-direction.up i { background: #e05a5a; }
.qt-direction.down { color: #67c98e; border-color: rgba(103,201,142,.4); }
.qt-direction.down i { background: var(--ok); }
.qt-forecast-panel { display: flex; flex-direction: column; gap: 14px; }
.qt-forecast-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.qt-forecast-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.qt-band { display: flex; flex-direction: column; gap: 9px; }
.qt-band-track { position: relative; height: 34px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; }
.qt-band-fill { position: absolute; top: 0; bottom: 0; background: linear-gradient(180deg, rgba(227,180,102,.22), rgba(227,180,102,.1)); border-left: 1px solid rgba(227,180,102,.5); border-right: 1px solid rgba(227,180,102,.5); }
.qt-band-marker { position: absolute; top: 0; bottom: 0; width: 2px; transform: translateX(-1px); }
.qt-marker-center { background: #e3b466; }
.qt-marker-last { background: #8f989f; }
.qt-band-legend { display: flex; gap: 14px; }
.qt-band-legend span { display: inline-flex; align-items: center; gap: 5px; color: var(--subtle); font-size: 9px; }
.qt-band-legend i { width: 8px; height: 2px; }
.qt-legend-center i { background: #e3b466; }
.qt-legend-last i { background: #8f989f; }
.qt-band-values { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; }
.qt-band-value { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 9px 11px; display: flex; flex-direction: column; gap: 4px; }
.qt-band-value span { color: var(--subtle); font-size: 9px; }
.qt-band-value b { color: var(--text); font-size: 15px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.qt-band-value b.up { color: #e05a5a; }
.qt-band-value b.down { color: #67c98e; }
.qt-band-center { border-color: rgba(227,180,102,.35); }
.qt-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 7px; }
.qt-metric { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.qt-metric span { color: var(--subtle); font-size: 9px; }
.qt-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.qt-metric b.up { color: #e05a5a; }
.qt-metric b.down { color: #67c98e; }
.qt-metric small { color: var(--muted); font-size: 8px; line-height: 1.4; }
.qt-report-panel { display: flex; flex-direction: column; }
.qt-report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.qt-report-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.qt-report-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.qt-output { margin-top: 10px; max-height: 420px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; overflow-wrap: anywhere; }
.qt-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.qt-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.qt-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.qt-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .qt-layout { grid-template-columns: 280px minmax(0, 1fr); } .qt-band-values { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .qt-layout { grid-template-columns: 1fr; } .qt-input-panel { position: static; } }
</style>
