<script setup>
import { formatNumber, formatPercent } from '../../utils/formatters'

defineProps({
  realtimePrices: { type: Object, default: null },
  jdPrices: { type: Object, default: null },
})

const fmt = value => formatNumber(value)
const fmtPct = value => formatPercent(value)
</script>

<template>
  <div class="market-tape">
    <div v-for="(item, key) in realtimePrices || {}" :key="`rt-${key}`" class="tape-item">
      <span>{{ item.name }}</span><b>{{ fmt(item.price) }}</b><em :class="(item.change || 0) >= 0 ? 'pos' : 'neg'">{{ fmtPct(item.change_pct) }}</em>
    </div>
    <div v-for="(item, key) in jdPrices || {}" :key="`jd-${key}`" class="tape-item gold">
      <span>{{ item.label }}</span><b>{{ fmt(item.price) }}</b><em :class="(item.change || 0) >= 0 ? 'pos' : 'neg'">{{ fmtPct(item.change_pct) }}</em>
    </div>
  </div>
</template>

<style scoped>
.market-tape { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; border: 1px solid var(--line); background: var(--line); border-radius: var(--radius-sm); overflow: hidden; }
.tape-item { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 9px; min-height: 34px; padding: 0 10px; background: var(--surface); min-width: 0; }
.tape-item span { color: var(--muted); font-size: 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tape-item b { color: var(--text); font-size: 11px; font-weight: 650; font-variant-numeric: tabular-nums; }
.tape-item.gold b { color: var(--accent-strong); }
.tape-item em { font-size: 9px; font-style: normal; font-variant-numeric: tabular-nums; }
.pos { color: #27c46b; }
.neg { color: #ef5350; }
@media (max-width: 900px) { .market-tape { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 520px) { .market-tape { grid-template-columns: 1fr; } }
</style>
