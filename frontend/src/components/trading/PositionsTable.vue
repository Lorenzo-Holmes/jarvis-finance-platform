<script setup>
import { computed } from 'vue'
import { formatNumber, formatPercent } from '../../utils/formatters'

const props = defineProps({
  account: { type: Object, default: null },
})
const emit = defineEmits(['quick-sell'])

const positions = computed(() => props.account?.positions || {})
const count = computed(() => Object.keys(positions.value).length)
const fmt = value => formatNumber(value)
const fmtPct = value => formatPercent(value)
const posClass = value => Number(value || 0) >= 0 ? 'pos' : 'neg'
</script>

<template>
  <section class="panel positions-panel">
    <div class="panel-head">
      <div><h2>当前持仓</h2><span>{{ count }} 个标的</span></div>
      <span v-if="account?.riskStatus && account.riskStatus !== 'NONE'"
            class="risk-badge" :class="'rk-' + String(account.riskStatus).toLowerCase()">{{ account.riskStatus }}</span>
    </div>

    <div v-if="count" class="table-scroll">
      <table class="table positions-table">
        <thead><tr><th>标的</th><th>数量</th><th>成本</th><th>现价</th><th>市值</th><th>杠杆</th><th>借款</th><th>盈亏</th><th>盈亏%</th><th></th></tr></thead>
        <tbody>
          <tr v-for="(position, symbol) in positions" :key="symbol">
            <td class="symbol-cell">{{ symbol }}</td>
            <td>{{ position.quantity }}</td>
            <td>{{ fmt(position.avgCost) }}</td>
            <td>{{ fmt(position.currentPrice) }}<span v-if="position.stale" class="stale-tag" :title="`最后行情：${position.quoteTime || '-'}`">过期</span></td>
            <td>{{ fmt(position.marketValue) }}</td>
            <td><span v-if="position.leverage > 1" class="lev-chip">{{ position.leverage }}x</span><span v-else>1x</span></td>
            <td :class="position.loan > 0 ? 'warn' : ''">{{ position.loan > 0 ? fmt(position.loan) : '-' }}</td>
            <td :class="posClass(position.profit)">{{ fmt(position.profit) }}</td>
            <td :class="posClass(position.profitPct)">{{ fmtPct(position.profitPct) }}</td>
            <td><button type="button" class="flat-action" :aria-label="`全平 ${symbol}`" @click="emit('quick-sell', symbol, position.quantity)">全平</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="position-empty"><b>暂无持仓</b><span>从左侧订单票据提交第一笔模拟交易。</span></div>
  </section>
</template>

<style scoped>
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; min-width: 0; }
.positions-panel { min-height: 470px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.panel-head h2 { margin: 0; color: var(--text); font-size: 13px; font-weight: 680; }
.panel-head > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.risk-badge { border-radius: 3px; padding: 2px 6px; font-size: 8px; font-weight: 700; letter-spacing: .05em; }
.rk-safe { background: rgba(39,196,107,.12); color: #67d69a; }
.rk-warn { background: rgba(241,196,15,.12); color: #e2c15d; }
.rk-danger { background: rgba(239,83,80,.14); color: #ff817e; }
.table-scroll { width: 100%; overflow-x: auto; margin-top: 10px; }
.table { width: 100%; border-collapse: collapse; font-size: 10px; font-variant-numeric: tabular-nums; }
.table th, .table td { text-align: left; padding: 8px 9px; border-bottom: 1px solid #25282c; white-space: nowrap; }
.table th { color: var(--subtle); font-weight: 550; background: #131517; font-size: 9px; }
.table td { color: var(--muted); }
.positions-table { min-width: 870px; }
.symbol-cell { color: var(--text) !important; font-weight: 600; }
.lev-chip { background: var(--accent-soft); color: var(--accent-strong); border-radius: 3px; padding: 2px 5px; font-size: 8px; font-weight: 650; }
.stale-tag { display: inline-block; margin-left: 5px; padding: 1px 4px; border-radius: 3px; background: rgba(241,196,15,.1); color: #e2c15d; font-size: 8px; vertical-align: 1px; }
.flat-action { border: 1px solid #513538; background: rgba(239,83,80,.05); color: #d97774; border-radius: 3px; padding: 4px 7px; font-size: 9px; cursor: pointer; }
.position-empty { min-height: 390px; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.position-empty b { color: var(--text); font-size: 11px; }
.position-empty span { margin-top: 5px; color: var(--subtle); font-size: 9px; }
.warn { color: #e2b85e !important; }
.pos { color: #27c46b !important; }
.neg { color: #ef5350 !important; }
@media (max-width: 860px) { .positions-panel { min-height: 360px; } .position-empty { min-height: 280px; } }
</style>
