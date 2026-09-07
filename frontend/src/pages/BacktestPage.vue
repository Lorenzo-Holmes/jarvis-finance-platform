<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { api } from '../api/client'
import { useEcharts } from '../composables/useEcharts'
import { formatNumber, formatPercent } from '../utils/formatters'

const props = defineProps({ active: { type: Boolean, default: false } })

const bt = reactive({ short_ma: 5, long_ma: 20, initial_cash: 100000, running: false })
const historyLimit = ref(120)
const result = ref(null)
const error = ref('')
const equityChart = useEcharts()
const equityChartRef = equityChart.elementRef

const invalid = computed(() => bt.short_ma < 1 || bt.long_ma < 2 || bt.short_ma >= bt.long_ma || bt.initial_cash < 1000)
const validation = computed(() => {
  if (bt.short_ma < 1) return '短期均线必须大于 0'
  if (bt.long_ma < 2) return '长期均线必须至少为 2'
  if (bt.short_ma >= bt.long_ma) return '短期均线必须小于长期均线'
  if (bt.initial_cash < 1000) return '初始本金不能低于 1,000'
  return ''
})
const excessReturn = computed(() => result.value
  ? Number(result.value.total_return_pct || 0) - Number(result.value.buy_hold_return_pct || 0)
  : null)

async function runBacktest() {
  if (invalid.value) {
    error.value = validation.value
    return
  }
  bt.running = true
  error.value = ''
  result.value = null
  equityChart.clear()
  try {
    const response = await api.backtest({
      market: 'gold_etf',
      short_ma: bt.short_ma,
      long_ma: bt.long_ma,
      initial_cash: bt.initial_cash,
      limit: historyLimit.value,
    })
    if (response.code !== 200 || !response.data) throw new Error(response.message || '回测失败')
    result.value = response.data
    await nextTick()
    await renderEquity(response.data.equity_curve || [])
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    bt.running = false
  }
}

async function renderEquity(curve) {
  await equityChart.setOption({
    animation: false,
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 55, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: curve.map(point => point.date), axisLabel: { color: '#8f9498', hideOverlap: true } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8f9498' }, splitLine: { lineStyle: { color: '#24272b' } } },
    series: [{
      name: '策略净值', type: 'line', showSymbol: false, data: curve.map(point => point.equity),
      lineStyle: { color: '#d7b56d', width: 1.8 }, areaStyle: { color: 'rgba(215,181,109,0.08)' },
    }],
  })
}

watch(() => props.active, async active => {
  if (!active || !result.value) return
  await nextTick()
  requestAnimationFrame(() => equityChart.resize())
})
</script>

<template>
  <section class="backtest-workspace">
    <div class="section-bar">
      <div>
        <h1>策略回测工作台</h1>
        <span>黄金ETF · 双均线策略 · 参数验证与收益对比</span>
      </div>
      <span class="section-status"><i :class="{ ok: !invalid }"></i>{{ invalid ? '参数待修正' : '参数可运行' }}</span>
    </div>

    <div class="backtest-layout">
      <aside class="panel bt-parameter-panel">
        <div class="bt-panel-title">策略参数</div>
        <div class="bt-field">
          <label>短期均线</label>
          <input type="number" v-model.number="bt.short_ma" min="1" class="bt-input" />
          <span>快速均线，用于捕捉短周期趋势变化。</span>
        </div>
        <div class="bt-field">
          <label>长期均线</label>
          <input type="number" v-model.number="bt.long_ma" min="2" class="bt-input" />
          <span>慢速均线，应大于短期均线参数。</span>
        </div>
        <div class="bt-field">
          <label>初始本金</label>
          <input type="number" v-model.number="bt.initial_cash" min="1000" class="bt-input" />
          <span>用于策略收益曲线的初始资金基准。</span>
        </div>
        <div class="bt-field">
          <label>历史样本</label>
          <select v-model.number="historyLimit" class="bt-input">
            <option :value="60">60 根</option>
            <option :value="120">120 根</option>
            <option :value="250">250 根</option>
          </select>
          <span>样本越长，策略结果越不易受短期行情影响。</span>
        </div>
        <div v-if="validation" class="bt-validation">{{ validation }}</div>
        <button class="btn primary bt-run" type="button" @click="runBacktest" :disabled="bt.running || invalid">
          {{ bt.running ? '正在计算…' : '运行策略回测' }}
        </button>
        <div class="bt-note">回测结果仅基于历史样本，不代表未来收益。</div>
      </aside>

      <div class="bt-main">
        <div v-if="error" class="error">{{ error }}</div>
        <template v-if="result">
          <div class="bt-metrics">
            <div class="bt-metric"><span>策略总收益</span><b :class="result.total_return_pct >= 0 ? 'pos' : 'neg'">{{ formatPercent(result.total_return_pct) }}</b></div>
            <div class="bt-metric"><span>年化收益</span><b :class="result.annual_return_pct >= 0 ? 'pos' : 'neg'">{{ formatPercent(result.annual_return_pct) }}</b></div>
            <div class="bt-metric"><span>超额收益</span><b :class="excessReturn >= 0 ? 'pos' : 'neg'">{{ formatPercent(excessReturn) }}</b></div>
            <div class="bt-metric"><span>最大回撤</span><b class="neg">{{ formatPercent(result.max_drawdown_pct) }}</b></div>
            <div class="bt-metric"><span>期末资金</span><b>{{ formatNumber(result.final_equity) }}</b></div>
            <div class="bt-metric"><span>交易次数</span><b>{{ result.num_trades }}</b></div>
          </div>

          <div class="panel bt-chart-panel">
            <div class="bt-chart-head">
              <div><b>策略净值曲线</b><span>买入持有 {{ formatPercent(result.buy_hold_return_pct) }}</span></div>
              <span class="bt-benchmark">策略 vs. Buy & Hold</span>
            </div>
            <div ref="equityChartRef" class="chart bt-equity-chart"></div>
          </div>
        </template>

        <div v-else-if="!bt.running" class="panel bt-empty">
          <div class="bt-empty-mark">MA</div>
          <b>等待回测结果</b>
          <span>配置左侧参数后运行策略，即可查看收益、回撤和交易记录。</span>
        </div>
        <div v-else class="panel bt-empty">
          <div class="bt-empty-mark">…</div>
          <b>正在计算策略表现</b>
          <span>正在基于当前黄金ETF历史样本执行回测。</span>
        </div>
      </div>
    </div>

    <div v-if="result?.trades?.length" class="panel bt-trades-panel">
      <div class="bt-chart-head">
        <div><b>近期交易</b><span>最近 {{ Math.min(10, result.trades.length) }} 笔</span></div>
        <span class="bt-benchmark">按时间排序</span>
      </div>
      <div class="table-scroll">
        <table class="table bt-table">
          <thead><tr><th>日期</th><th>方向</th><th>成交价格</th></tr></thead>
          <tbody>
            <tr v-for="(trade, index) in result.trades.slice(-10).reverse()" :key="index">
              <td>{{ trade.date }}</td>
              <td :class="trade.type === 'BUY' ? 'pos' : 'neg'">{{ trade.type }}</td>
              <td>{{ formatNumber(trade.price) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.backtest-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.backtest-layout { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 10px; align-items: start; }
.bt-parameter-panel { position: sticky; top: 10px; }
.bt-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.bt-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.bt-field label { color: var(--muted); font-size: 10px; }
.bt-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.bt-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; font-variant-numeric: tabular-nums; outline: none; }
.bt-input:focus { border-color: #6a5b40; }
.bt-validation { margin-top: 12px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.bt-run { width: 100%; min-height: 36px; margin-top: 12px; }
.bt-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.bt-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.bt-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 7px; }
.bt-metric { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 11px; min-width: 0; }
.bt-metric span { display: block; color: var(--subtle); font-size: 9px; white-space: nowrap; }
.bt-metric b { display: block; margin-top: 5px; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: -.015em; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bt-chart-panel { min-width: 0; }
.bt-chart-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.bt-chart-head > div { display: flex; align-items: baseline; gap: 8px; }
.bt-chart-head b { color: var(--text); font-size: 12px; font-weight: 650; }
.bt-chart-head span { color: var(--subtle); font-size: 9px; }
.bt-benchmark { color: var(--muted) !important; }
.bt-equity-chart { height: 390px !important; margin-top: 10px !important; }
.bt-empty { min-height: 450px; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.bt-empty-mark { width: 44px; height: 44px; display: grid; place-items: center; border: 1px solid #4d4434; color: var(--accent-strong); background: rgba(201,166,95,.05); border-radius: 50%; font-size: 11px; font-weight: 700; letter-spacing: .04em; }
.bt-empty b { margin-top: 12px; color: var(--text); font-size: 12px; }
.bt-empty span { max-width: 330px; margin-top: 6px; color: var(--subtle); font-size: 10px; line-height: 1.6; }
.bt-trades-panel { min-width: 0; }
.table-scroll { overflow-x: auto; margin-top: 9px; }
.bt-table { min-width: 520px; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
@media (max-width: 1180px) { .bt-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 820px) { .backtest-layout { grid-template-columns: 1fr; } .bt-parameter-panel { position: static; } .bt-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .section-bar { align-items: flex-start; flex-direction: column; } }
@media (max-width: 520px) { .bt-metrics { grid-template-columns: 1fr 1fr; } .bt-equity-chart { height: 320px !important; } }
</style>
