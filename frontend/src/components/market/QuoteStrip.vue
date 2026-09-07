<script setup>
import { formatNumber, formatPercent } from '../../utils/formatters'

const props = defineProps({
  jdPrices: { type: Object, default: null },
  realtimePrices: { type: Object, default: null },
  marketFocus: { type: String, default: 'gold_etf' },
  jdMarket: { type: String, default: 'zheshang' },
})

const emit = defineEmits(['select'])
</script>

<template>
  <div class="quote-strip">
    <template v-if="props.jdPrices">
      <button v-for="(price, key) in props.jdPrices" :key="`jd-${key}`" type="button"
              class="rt-card jd" :class="{ selected: props.marketFocus === 'jd' && props.jdMarket === key }"
              :aria-pressed="props.marketFocus === 'jd' && props.jdMarket === key"
              @click="emit('select', { key: 'jd', jdMarket: key })">
        <span class="rt-topline"><span class="rt-name">{{ price.label }}</span><span class="rt-source">积存金</span></span>
        <span class="rt-price jd-price">{{ formatNumber(price.price) }}</span>
        <span class="rt-sub">
          <span :class="Number(price.change || 0) >= 0 ? 'pos' : 'neg'">{{ price.change }} ({{ formatPercent(price.change_pct) }})</span>
          <span class="rt-muted">{{ price.time }}</span>
        </span>
      </button>
    </template>
    <template v-if="props.realtimePrices">
      <button v-for="(price, key) in props.realtimePrices" :key="`rt-${key}`" type="button"
              class="rt-card" :class="{ selected: props.marketFocus === key }"
              :aria-pressed="props.marketFocus === key"
              @click="emit('select', { key })">
        <span class="rt-topline"><span class="rt-name">{{ price.name }}</span><span class="rt-source">实时</span></span>
        <span class="rt-price">{{ formatNumber(price.price) }}</span>
        <span class="rt-sub">
          <span :class="Number(price.change || 0) >= 0 ? 'pos' : 'neg'">{{ price.change }} ({{ formatPercent(price.change_pct) }})</span>
          <span class="rt-muted">昨收 {{ price.prev_close }}</span>
        </span>
      </button>
    </template>
  </div>
</template>

<style scoped>
.quote-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.rt-card { width: 100%; text-align: left; color: inherit; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 11px 13px 10px; position: relative; overflow: hidden; min-width: 0; cursor: pointer; transition: border-color .15s ease, background .15s ease; }
.rt-card:hover { border-color: #3b3f44; background: #17191b; }
.rt-card.selected { border-color: #695b40; background: rgba(201,166,95,.055); }
.rt-card::before { content: ''; position: absolute; top: 0; left: 0; bottom: 0; width: 2px; background: #6f7479; }
.rt-card.jd::before { background: var(--accent); }
.rt-card.jd .rt-price { color: var(--accent-strong); }
.rt-topline, .rt-sub { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.rt-name { color: var(--muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rt-source { color: var(--subtle); font-size: 9px; border: 1px solid #2c3034; border-radius: 3px; padding: 1px 5px; flex: 0 0 auto; }
.rt-price { display: block; font-size: 23px; line-height: 1.15; font-weight: 650; color: var(--text); font-variant-numeric: tabular-nums; letter-spacing: -.025em; margin-top: 5px; }
.rt-sub { margin-top: 5px; font-size: 11px; }
.rt-muted { color: var(--subtle); font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
@media (max-width: 1100px) { .quote-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .quote-strip { grid-template-columns: 1fr; } }
</style>
