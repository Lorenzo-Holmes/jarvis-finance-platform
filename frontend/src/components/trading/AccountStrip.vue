<script setup>
import { formatNumber, formatPercent } from '../../utils/formatters'

defineProps({
  account: { type: Object, default: null },
})

const fmt = value => formatNumber(value)
const fmtPct = value => formatPercent(value)
const posClass = value => Number(value || 0) >= 0 ? 'pos' : 'neg'
const riskValueClass = value => Number(value) < 15 ? 'neg' : (Number(value) < 25 ? 'warn' : '')
</script>

<template>
  <div v-if="account" class="account-strip">
    <div><span>总资产</span><b>{{ fmt(account.totalAssets) }}</b></div>
    <div><span>可用资金</span><b class="accent">{{ fmt(account.cash) }}</b></div>
    <div><span>持仓市值</span><b>{{ fmt(account.marketValue) }}</b></div>
    <div><span>净资产</span><b>{{ fmt(account.netEquity) }}</b></div>
    <div><span>借款</span><b :class="account.loanBalance > 0 ? 'warn' : ''">{{ fmt(account.loanBalance || 0) }}</b></div>
    <div><span>总收益率</span><b :class="posClass(account.totalReturnPct)">{{ fmtPct(account.totalReturnPct) }}</b></div>
    <div v-if="account.riskStatus && account.riskStatus !== 'NONE'">
      <span>维持保证金率</span><b :class="riskValueClass(account.maintMarginPct)">{{ fmtPct(account.maintMarginPct) }}</b>
    </div>
  </div>
</template>

<style scoped>
.account-strip { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); border: 1px solid var(--line); border-radius: var(--radius); background: var(--panel); overflow: hidden; }
.account-strip > div { min-width: 0; padding: 10px 12px; border-right: 1px solid var(--line); }
.account-strip > div:last-child { border-right: 0; }
.account-strip span { display: block; color: var(--subtle); font-size: 9px; }
.account-strip b { display: block; margin-top: 4px; color: var(--text); font-size: 15px; font-weight: 680; letter-spacing: -.015em; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-strip b.accent { color: var(--accent-strong); }
.pos { color: #27c46b !important; }
.neg { color: #ef5350 !important; }
.warn { color: #e2c15d !important; }
@media (max-width: 1180px) { .account-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); } .account-strip > div { border-bottom: 1px solid var(--line); } }
@media (max-width: 620px) { .account-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
