<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

// ---- 市场趋势预测参数（FR-12）----
// 单资产日 K 统计基线：历史收盘价由 Java 服务端从自营 K 线库注入，前端不传。
const market = ref('gold_etf')
const horizonDays = ref(10)
const confidence = ref(95)
const evaluating = ref(false)
const result = ref(null) // { forecast, indicators, direction, content }
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

const forecast = computed(() => (result.value?.forecast ? result.value.forecast : null))
const indicators = computed(() => result.value?.indicators || {})
const direction = computed(() => result.value?.direction || null)

function fmt(value) {
  if (value == null || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return numeric.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtPct(value, digits = 2) {
  if (value == null || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(digits)}%`
}

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

// RSI 状态：>70 超买 / <30 超卖
const rsiState = computed(() => {
  const value = Number(indicators.value.rsi14)
  if (!Number.isFinite(value)) return null
  if (value >= 70) return { key: 'over', label: '超买区' }
  if (value <= 30) return { key: 'under', label: '超卖区' }
  return { key: 'neutral', label: '中性区' }
})

async function evaluate() {
  if (validation.value || evaluating.value) return
  evaluating.value = true
  error.value = ''
  result.value = null
  try {
    const response = await api.aiTrend({
      market: market.value.trim(),
      horizonDays: horizonDays.value,
      confidence: confidence.value / 100,
    })
    if (response.code !== 200 || !response.data) throw new Error(response.message || '市场趋势预测失败')
    if (response.data.available === false) {
      error.value = response.data.reason === 'insufficient_closes'
        ? `行情样本不足（仅 ${response.data.bars || 0} 根有效收盘价），无法生成趋势预测`
        : '市场趋势预测暂不可用'
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
  error.value = ''
}
</script>

<template>
  <section class="tr-workspace">
    <div class="section-bar">
      <div>
        <h1>市场趋势预测</h1>
        <span>单资产日 K 统计基线 · 趋势区间 · 技术依据 · AI 解读</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '输入待完善' : '输入可分析' }}</span>
    </div>

    <div class="tr-layout">
      <aside class="panel tr-input-panel">
        <div class="tr-panel-title">预测参数</div>

        <div class="tr-field">
          <label>标的</label>
          <input v-model="market" class="tr-input" aria-label="标的代码" placeholder="gold_etf / london_gold…" />
          <span>历史收盘价由服务端自营 K 线库注入。</span>
        </div>

        <div class="tr-field">
          <label>预测窗口</label>
          <select v-model.number="horizonDays" class="tr-input" aria-label="预测窗口">
            <option v-for="item in HORIZON_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <span>窗口越长，区间越宽（不确定性累积）。</span>
        </div>

        <div class="tr-field">
          <label>置信度</label>
          <select v-model.number="confidence" class="tr-input" aria-label="置信度">
            <option :value="90">90%</option>
            <option :value="95">95%</option>
            <option :value="99">99%</option>
          </select>
          <span>正态近似下的双侧区间覆盖水平；不代表实际命中率。</span>
        </div>

        <div v-if="validation" class="tr-validation">{{ validation }}</div>
        <button class="btn primary tr-run" type="button" :disabled="evaluating || !!validation" @click="evaluate">
          {{ evaluating ? '正在生成趋势预测…' : '生成趋势预测' }}
        </button>
        <button v-if="result || error" type="button" class="text-action" @click="clearAll">清空结果</button>
        <div class="tr-note">趋势区间由统计/时序基线模型计算，非秒级实时行情，也不构成投资建议。</div>
      </aside>

      <div class="tr-main">
        <template v-if="result">
          <div class="tr-headline">
            <div class="tr-headline-quote">
              <span class="tr-label">最新收盘价</span>
              <b>{{ fmt(forecast?.last_close) }}</b>
              <span class="tr-sub">基于 {{ forecast?.bars || 0 }} 根日 K</span>
            </div>
            <div v-if="direction" class="tr-direction" :class="direction.key">
              <i></i>{{ direction.label }}
            </div>
          </div>

          <div v-if="forecast" class="panel tr-forecast-panel">
            <div class="tr-forecast-head">
              <div>
                <b>趋势区间（预测结果）</b>
                <span>未来 {{ forecast.horizon_days }} 个交易日 · {{ Number(forecast.confidence) * 100 }}% 置信</span>
              </div>
            </div>

            <div class="tr-band">
              <div class="tr-band-track">
                <div
                  class="tr-band-fill"
                  :style="{ left: band ? band.lowerPos + '%' : '0%', width: band ? (band.upperPos - band.lowerPos) + '%' : '0%' }"
                ></div>
                <div v-if="band" class="tr-band-marker tr-marker-center" :style="{ left: band.centerPos + '%' }" title="预测中心值"></div>
                <div v-if="band" class="tr-band-marker tr-marker-last" :style="{ left: band.lastPos + '%' }" title="最新价"></div>
              </div>
              <div class="tr-band-legend">
                <span class="tr-legend-center"><i></i>预测中心</span>
                <span class="tr-legend-last"><i></i>最新价</span>
              </div>
            </div>

            <div class="tr-band-values">
              <div class="tr-band-value">
                <span>下界</span>
                <b class="down">{{ fmt(forecast.lower) }}</b>
              </div>
              <div class="tr-band-value tr-band-center">
                <span>预测中心值</span>
                <b>{{ fmt(forecast.center) }}</b>
              </div>
              <div class="tr-band-value">
                <span>上界</span>
                <b class="up">{{ fmt(forecast.upper) }}</b>
              </div>
            </div>
          </div>

          <div class="tr-metrics">
            <div class="tr-metric">
              <span>中心值涨跌</span>
              <b :class="Number(forecast?.change_to_center_pct) >= 0 ? 'up' : 'down'">{{ fmtPct(forecast?.change_to_center_pct) }}</b>
              <small>预测中心相对最新价</small>
            </div>
            <div class="tr-metric">
              <span>日均斜率</span>
              <b :class="Number(forecast?.slope_pct_per_day) >= 0 ? 'up' : 'down'">{{ fmtPct(forecast?.slope_pct_per_day) }}</b>
              <small>最小二乘拟合趋势</small>
            </div>
            <div class="tr-metric">
              <span>区间宽度</span>
              <b>±{{ forecast?.band_pct != null ? Number(forecast.band_pct).toFixed(2) : '—' }}%</b>
              <small>半宽占最新价比例</small>
            </div>
            <div class="tr-metric">
              <span>日波动率</span>
              <b>{{ forecast?.vol_daily_pct != null ? Number(forecast.vol_daily_pct).toFixed(2) + '%' : '—' }}</b>
              <small>历史日收益标准差</small>
            </div>
          </div>

          <div class="panel tr-basis-panel">
            <div class="tr-basis-head">
              <div><b>预测依据</b><span>均线 · 动量 · 支撑阻力（确定性计算，LLM 不改写）</span></div>
              <div v-if="rsiState" class="tr-rsi-state" :class="rsiState.key">{{ rsiState.label }}</div>
            </div>
            <div class="tr-basis-grid">
              <div class="tr-basis-item">
                <span>SMA5</span>
                <b>{{ fmt(indicators.sma5) }}</b>
              </div>
              <div class="tr-basis-item">
                <span>SMA20</span>
                <b>{{ fmt(indicators.sma20) }}</b>
              </div>
              <div class="tr-basis-item">
                <span>EMA12</span>
                <b>{{ fmt(indicators.ema12) }}</b>
              </div>
              <div class="tr-basis-item">
                <span>RSI14</span>
                <b>{{ indicators.rsi14 != null ? Number(indicators.rsi14).toFixed(2) : '—' }}</b>
              </div>
              <div class="tr-basis-item">
                <span>距 SMA20</span>
                <b :class="Number(indicators.distance_to_sma20_pct) >= 0 ? 'up' : 'down'">{{ fmtPct(indicators.distance_to_sma20_pct) }}</b>
              </div>
              <div class="tr-basis-item">
                <span>均线排列</span>
                <b>{{ indicators.ma_trend || '—' }}</b>
              </div>
              <div class="tr-basis-item">
                <span>近 20 日支撑</span>
                <b>{{ fmt(indicators.support20) }}</b>
              </div>
              <div class="tr-basis-item">
                <span>近 20 日阻力</span>
                <b>{{ fmt(indicators.resistance20) }}</b>
              </div>
            </div>
          </div>

          <div class="panel tr-report-panel">
            <div class="tr-report-head">
              <div><b>AI 趋势解读</b><span>基于确定性指标与趋势区间生成，数值口径以指标卡为准</span></div>
            </div>
            <div class="tr-output">{{ result.content?.content || result.content || '（暂无解读）' }}</div>
          </div>
        </template>

        <DataState v-else-if="evaluating" state="loading" title="正在生成市场趋势预测" message="计算趋势区间与技术依据并生成 AI 解读，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="市场趋势预测失败" :message="error" retryable @retry="evaluate" />

        <div v-else class="panel tr-empty">
          <div class="tr-empty-mark">趋势</div>
          <b>等待生成市场趋势预测</b>
          <span>选择标的与预测窗口后生成，即可查看未来趋势区间、技术依据与 AI 解读。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.tr-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.tr-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 10px; align-items: start; }
.tr-input-panel { position: sticky; top: 10px; }
.tr-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.tr-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.tr-field label { color: var(--muted); font-size: 10px; }
.tr-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.tr-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; outline: none; }
.tr-input:focus { border-color: #6a5b40; }
.tr-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.tr-run { width: 100%; min-height: 36px; margin-top: 12px; }
.tr-input-panel .text-action { margin-top: 8px; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.tr-input-panel .text-action:hover { color: var(--text); }
.tr-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.tr-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.tr-headline { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 11px 13px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.tr-headline-quote { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.tr-headline-quote .tr-label { color: var(--subtle); font-size: 10px; }
.tr-headline-quote > b { color: var(--accent-strong); font-size: 24px; line-height: 1; font-weight: 680; letter-spacing: -.025em; font-variant-numeric: tabular-nums; }
.tr-headline-quote .tr-sub { color: var(--subtle); font-size: 10px; }
.tr-direction { display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 3px; background: var(--surface); padding: 5px 9px; color: var(--muted); font-size: 10px; font-weight: 650; }
.tr-direction i { width: 6px; height: 6px; border-radius: 50%; background: #5b6066; }
.tr-direction.up { color: #e05a5a; border-color: rgba(224,90,90,.4); }
.tr-direction.up i { background: #e05a5a; }
.tr-direction.down { color: #67c98e; border-color: rgba(103,201,142,.4); }
.tr-direction.down i { background: var(--ok); }
.tr-forecast-panel { display: flex; flex-direction: column; gap: 14px; }
.tr-forecast-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.tr-forecast-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.tr-band { display: flex; flex-direction: column; gap: 9px; }
.tr-band-track { position: relative; height: 34px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; }
.tr-band-fill { position: absolute; top: 0; bottom: 0; background: linear-gradient(180deg, rgba(227,180,102,.22), rgba(227,180,102,.1)); border-left: 1px solid rgba(227,180,102,.5); border-right: 1px solid rgba(227,180,102,.5); }
.tr-band-marker { position: absolute; top: 0; bottom: 0; width: 2px; transform: translateX(-1px); }
.tr-marker-center { background: #e3b466; }
.tr-marker-last { background: #8f989f; }
.tr-band-legend { display: flex; gap: 14px; }
.tr-band-legend span { display: inline-flex; align-items: center; gap: 5px; color: var(--subtle); font-size: 9px; }
.tr-band-legend i { width: 8px; height: 2px; }
.tr-legend-center i { background: #e3b466; }
.tr-legend-last i { background: #8f989f; }
.tr-band-values { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; }
.tr-band-value { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 9px 11px; display: flex; flex-direction: column; gap: 4px; }
.tr-band-value span { color: var(--subtle); font-size: 9px; }
.tr-band-value b { color: var(--text); font-size: 15px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.tr-band-value b.up { color: #e05a5a; }
.tr-band-value b.down { color: #67c98e; }
.tr-band-center { border-color: rgba(227,180,102,.35); }
.tr-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 7px; }
.tr-metric { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.tr-metric span { color: var(--subtle); font-size: 9px; }
.tr-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.tr-metric b.up { color: #e05a5a; }
.tr-metric b.down { color: #67c98e; }
.tr-metric small { color: var(--muted); font-size: 8px; line-height: 1.4; }
.tr-basis-panel { display: flex; flex-direction: column; gap: 12px; }
.tr-basis-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.tr-basis-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.tr-basis-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.tr-rsi-state { border: 1px solid var(--line); border-radius: 3px; padding: 4px 8px; font-size: 9px; font-weight: 650; color: var(--muted); }
.tr-rsi-state.over { color: #e05a5a; border-color: rgba(224,90,90,.4); }
.tr-rsi-state.under { color: #67c98e; border-color: rgba(103,201,142,.4); }
.tr-basis-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 7px; }
.tr-basis-item { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 9px 11px; display: flex; flex-direction: column; gap: 4px; }
.tr-basis-item span { color: var(--subtle); font-size: 9px; }
.tr-basis-item b { color: var(--text); font-size: 14px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.tr-basis-item b.up { color: #e05a5a; }
.tr-basis-item b.down { color: #67c98e; }
.tr-report-panel { display: flex; flex-direction: column; }
.tr-report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.tr-report-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.tr-report-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.tr-output { margin-top: 10px; max-height: 420px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.tr-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.tr-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.tr-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.tr-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .tr-layout { grid-template-columns: 280px minmax(0, 1fr); } .tr-band-values { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .tr-layout { grid-template-columns: 1fr; } .tr-input-panel { position: static; } }
</style>
