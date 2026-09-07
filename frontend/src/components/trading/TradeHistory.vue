<script setup>
import { formatNumber } from '../../utils/formatters'

defineProps({ trades: { type: Array, default: () => [] } })

const fmt = value => formatNumber(value)
function tradeLabel(type) {
  if (type === 'BUY') return '买入'
  if (type === 'FORCE_SELL') return '强平'
  return '卖出'
}
</script>

<template>
  <section class="panel trades-panel">
    <div class="panel-head"><div><h2>成交记录</h2><span>最近 {{ trades.length }} 笔</span></div></div>
    <div v-if="trades.length" class="table-scroll">
      <table class="table trades-table">
        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>杠杆</th><th>价格</th><th>数量</th><th>成交金额</th></tr></thead>
        <tbody>
          <tr v-for="trade in trades" :key="trade.id">
            <td>{{ trade.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
            <td class="symbol-cell">{{ trade.symbol }}</td>
            <td :class="trade.type === 'BUY' ? 'pos' : 'neg'">{{ tradeLabel(trade.type) }}</td>
            <td>{{ trade.leverage > 1 ? trade.leverage + 'x' : '-' }}</td>
            <td>{{ fmt(trade.price) }}</td>
            <td>{{ trade.quantity }}</td>
            <td>{{ fmt(trade.amount) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="trade-empty">暂无成交记录</div>
  </section>
</template>

<style scoped>
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; min-width: 0; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.panel-head h2 { margin: 0; color: var(--text); font-size: 13px; font-weight: 680; }
.panel-head > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.table-scroll { width: 100%; overflow-x: auto; margin-top: 10px; }
.table { width: 100%; border-collapse: collapse; font-size: 10px; font-variant-numeric: tabular-nums; }
.table th, .table td { text-align: left; padding: 8px 9px; border-bottom: 1px solid #25282c; white-space: nowrap; }
.table th { color: var(--subtle); font-weight: 550; background: #131517; font-size: 9px; }
.table td { color: var(--muted); }
.trades-table { min-width: 720px; }
.symbol-cell { color: var(--text) !important; font-weight: 600; }
.trade-empty { padding: 20px 0 8px; text-align: center; color: var(--subtle); font-size: 9px; }
.pos { color: #27c46b !important; }
.neg { color: #ef5350 !important; }
</style>
