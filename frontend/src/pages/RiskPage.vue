<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

// ---- 风险分析参数 ----
const market = ref('gold_etf')
const confidence = ref(95)
const days = ref(60)
const portfolioValue = ref(null) // 可选：账户资金，用于金额换算
const analyzing = ref(false)
const result = ref(null) // { metrics, alerts, content }
const error = ref('')

const validation = computed(() => {
  if (!market.value.trim()) return '请填写标的代码'
  return ''
})

const levelRank = { high: 3, medium: 2, low: 1 }
const riskLevel = computed(() => {
  if (!result.value?.alerts?.length) return null
  return result.value.alerts
    .slice()
    .sort((a, b) => (levelRank[b.level] || 0) - (levelRank[a.level] || 0))[0].level
})
const riskLabel = { high: '高风险', medium: '中风险', low: '低风险' }

function fmtPct(value) {
  if (value == null || value === '') return '—'
  return `${value}%`
}

async function analyze() {
  if (validation.value || analyzing.value) return
  analyzing.value = true
  error.value = ''
  result.value = null
  try {
    const response = await api.aiRisk(
      market.value.trim(),
      confidence.value / 100,
      portfolioValue.value && Number(portfolioValue.value) > 0 ? Number(portfolioValue.value) : null,
      days.value,
    )
    if (response.code !== 200 || !response.data) throw new Error(response.message || '风险分析失败')
    if (response.data.available === false) {
      error.value = response.data.reason === 'insufficient_closes'
        ? `行情样本不足（仅 ${response.data.bars || 0} 根有效收盘价），无法计算风险指标`
        : '风险分析暂不可用'
      return
    }
    result.value = response.data
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    analyzing.value = false
  }
}

function clearAll() {
  result.value = null
  error.value = ''
}
</script>

<template>
  <section class="rk-workspace">
    <div class="section-bar">
      <div>
        <h1>风险预警</h1>
        <span>VaR · ES · 波动率 · 最大回撤 · 超阈值预警</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '输入待完善' : '输入可分析' }}</span>
    </div>

    <div class="rk-layout">
      <aside class="panel rk-input-panel">
        <div class="rk-panel-title">分析参数</div>

        <div class="rk-field">
          <label>标的</label>
          <input v-model="market" class="rk-input" aria-label="标的代码" placeholder="gold_etf / london_gold…" />
          <span>基于该标的的历史日 K 收盘价计算风险指标。</span>
        </div>

        <div class="rk-field">
          <label>置信度</label>
          <select v-model.number="confidence" class="rk-input" aria-label="置信度">
            <option :value="90">90%</option>
            <option :value="95">95%</option>
            <option :value="99">99%</option>
          </select>
          <span>VaR 置信水平，越高越保守。</span>
        </div>

        <div class="rk-field">
          <label>样本窗口</label>
          <select v-model.number="days" class="rk-input" aria-label="样本窗口">
            <option :value="60">最近 60 根</option>
            <option :value="120">最近 120 根</option>
            <option :value="250">最近 250 根</option>
          </select>
          <span>窗口越长，指标越稳健。</span>
        </div>

        <div class="rk-field">
          <label>账户资金（可选）</label>
          <input v-model.number="portfolioValue" class="rk-input" type="number" min="0" aria-label="账户资金" placeholder="如 100000，用于换算单日潜在亏损金额" />
          <span>填写后额外给出账户级金额预警。</span>
        </div>

        <div v-if="validation" class="rk-validation">{{ validation }}</div>
        <button class="btn primary rk-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          {{ analyzing ? '正在计算风险…' : '运行风险分析' }}
        </button>
        <button v-if="result || error" type="button" class="text-action" @click="clearAll">清空结果</button>
        <div class="rk-note">数值由确定性计算层基于历史样本生成，模型仅负责解读；历史模拟法对尾部风险估计偏乐观，仅供参考。</div>
      </aside>

      <div class="rk-main">
        <template v-if="result">
          <div class="rk-banner" :class="riskLevel">
            <span class="rk-banner-dot"></span>
            <div>
              <b>总体风险评级：{{ riskLabel[riskLevel] || '—' }}</b>
              <span v-if="result.metrics">{{ market.trim() }} · 最近 {{ result.metrics.bars }} 根日 K · {{ result.metrics.confidence * 100 }}% 置信度</span>
            </div>
          </div>

          <div class="rk-metrics">
            <div class="rk-metric">
              <span>单日 VaR</span>
              <b :class="Number(result.metrics.var_pct) < 0 ? 'loss' : ''">{{ fmtPct(result.metrics.var_pct) }}</b>
              <small>95% 置信下日最大预期亏损</small>
            </div>
            <div class="rk-metric">
              <span>尾部风险 ES</span>
              <b :class="Number(result.metrics.es_pct) < 0 ? 'loss' : ''">{{ fmtPct(result.metrics.es_pct) }}</b>
              <small>跌破 VaR 时的平均亏损</small>
            </div>
            <div class="rk-metric">
              <span>年化波动率</span>
              <b>{{ fmtPct(result.metrics.vol_annual_pct) }}</b>
              <small>基于历史日收益年化</small>
            </div>
            <div class="rk-metric">
              <span>历史最大回撤</span>
              <b :class="Number(result.metrics.max_drawdown_pct) < 0 ? 'loss' : ''">{{ fmtPct(result.metrics.max_drawdown_pct) }}</b>
              <small>样本区间峰谷最大跌幅</small>
            </div>
            <div v-if="result.metrics.var_amount != null" class="rk-metric rk-amount">
              <span>账户单日潜在亏损</span>
              <b class="loss">¥{{ Number(result.metrics.var_amount).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</b>
              <small>按资金规模换算</small>
            </div>
          </div>

          <div v-if="result.alerts?.length" class="rk-alerts">
            <div v-for="(alert, index) in result.alerts" :key="index" class="rk-alert" :class="alert.level">
              <b>{{ alert.level === 'high' ? '高' : alert.level === 'medium' ? '中' : '低' }}级预警 · {{ alert.metric.toUpperCase() }}</b>
              <span>{{ alert.rule }}</span>
              <p>{{ alert.message }}</p>
            </div>
          </div>

          <div class="panel rk-report-panel">
            <div class="rk-report-head">
              <div><b>风险报告</b><span>AI 基于确定性指标生成，数值口径以指标卡为准</span></div>
            </div>
            <div class="rk-output">{{ result.content?.content || result.content || '（暂无报告）' }}</div>
          </div>
        </template>

        <DataState v-else-if="analyzing" state="loading" title="正在计算风险指标" message="基于历史样本计算 VaR/ES 并生成风险报告，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="风险分析失败" :message="error" retryable @retry="analyze" />

        <div v-else class="panel rk-empty">
          <div class="rk-empty-mark">风控</div>
          <b>等待风险分析</b>
          <span>配置左侧参数后运行分析，即可查看 VaR/ES/波动率/最大回撤指标与超阈值预警卡片。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.rk-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.rk-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 10px; align-items: start; }
.rk-input-panel { position: sticky; top: 10px; }
.rk-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.rk-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.rk-field label { color: var(--muted); font-size: 10px; }
.rk-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.rk-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; outline: none; }
.rk-input:focus { border-color: #6a5b40; }
.rk-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.rk-run { width: 100%; min-height: 36px; margin-top: 12px; }
.rk-input-panel .text-action { margin-top: 8px; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.rk-input-panel .text-action:hover { color: var(--text); }
.rk-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.rk-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.rk-banner { display: flex; align-items: center; gap: 10px; padding: 10px 13px; border-radius: var(--radius-sm); border: 1px solid var(--line); background: var(--panel); }
.rk-banner .rk-banner-dot { width: 8px; height: 8px; border-radius: 50%; background: #5b6066; flex: 0 0 auto; }
.rk-banner.low .rk-banner-dot { background: var(--ok); }
.rk-banner.medium { border-color: rgba(227,180,102,.4); background: rgba(227,180,102,.06); }
.rk-banner.medium .rk-banner-dot { background: #e3b466; }
.rk-banner.high { border-color: rgba(239,83,80,.45); background: rgba(239,83,80,.07); }
.rk-banner.high .rk-banner-dot { background: #ef5350; }
.rk-banner b { color: var(--text); font-size: 12px; font-weight: 680; }
.rk-banner span { display: block; margin-top: 2px; color: var(--subtle); font-size: 9px; }
.rk-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 7px; }
.rk-metric { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.rk-metric span { color: var(--subtle); font-size: 9px; }
.rk-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.rk-metric b.loss { color: #ef5350; }
.rk-metric small { color: var(--muted); font-size: 8px; line-height: 1.4; }
.rk-metric.rk-amount b { font-size: 15px; }
.rk-alerts { display: flex; flex-direction: column; gap: 7px; }
.rk-alert { border-left: 3px solid #5b6066; background: var(--panel); border-radius: var(--radius-sm); padding: 9px 12px; display: flex; flex-direction: column; gap: 3px; }
.rk-alert.medium { border-left-color: #e3b466; }
.rk-alert.high { border-left-color: #ef5350; background: rgba(239,83,80,.05); }
.rk-alert b { color: var(--text); font-size: 10px; font-weight: 680; }
.rk-alert span { color: var(--subtle); font-size: 9px; font-family: ui-monospace, monospace; }
.rk-alert p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.55; }
.rk-report-panel { display: flex; flex-direction: column; }
.rk-report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.rk-report-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.rk-report-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.rk-output { margin-top: 10px; max-height: 420px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.rk-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.rk-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.rk-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.rk-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .rk-layout { grid-template-columns: 280px minmax(0, 1fr); } }
@media (max-width: 760px) { .rk-layout { grid-template-columns: 1fr; } .rk-input-panel { position: static; } }
</style>
