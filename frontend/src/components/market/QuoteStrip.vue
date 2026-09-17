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
  <div class="quote-strip market-ticker" aria-label="行情带">
    <template v-if="props.jdPrices">
      <button v-for="(price, key) in props.jdPrices" :key="`jd-${key}`" type="button"
              class="rt-card jd" :class="{ selected: props.marketFocus === 'jd' && props.jdMarket === key }"
              :aria-pressed="props.marketFocus === 'jd' && props.jdMarket === key"
              @click="emit('select', { key: 'jd', jdMarket: key })">
        <span class="rt-copy"><b>{{ price.label }}</b><small>积存金</small></span>
        <span class="rt-value">{{ formatNumber(price.price) }}</span>
        <span class="rt-delta" :class="Number(price.change || 0) >= 0 ? 'pos' : 'neg'">
          {{ price.change }} · {{ formatPercent(price.change_pct) }}
        </span>
      </button>
    </template>
    <template v-if="props.realtimePrices">
      <button v-for="(price, key) in props.realtimePrices" :key="`rt-${key}`" type="button"
              class="rt-card" :class="{ selected: props.marketFocus === key }"
              :aria-pressed="props.marketFocus === key"
              @click="emit('select', { key })">
        <span class="rt-copy"><b>{{ price.name }}</b><small>实时</small></span>
        <span class="rt-value">{{ formatNumber(price.price) }}</span>
        <span class="rt-delta" :class="Number(price.change || 0) >= 0 ? 'pos' : 'neg'">
          {{ price.change }} · {{ formatPercent(price.change_pct) }}
        </span>
      </button>
    </template>
  </div>
</template>

<style scoped>
.quote-strip {
  display: flex;
  align-items: stretch;
  gap: 6px;
  min-height: 42px;
  padding: 2px 0 6px;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 0;
  scrollbar-width: none;
  scroll-snap-type: x proximity;
}
.quote-strip::-webkit-scrollbar { display: none; }
.rt-card {
  position: relative;
  flex: 0 1 260px;
  min-width: 220px;
  display: grid;
  grid-template-columns: minmax(90px, 1fr) auto auto;
  grid-template-areas: "copy value delta";
  align-items: center;
  column-gap: 12px;
  min-height: 38px;
  padding: 6px 9px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  scroll-snap-align: start;
  transition: color var(--motion-standard, 190ms) ease, background var(--motion-standard, 190ms) ease, transform var(--motion-fast, 110ms) ease;
}
.rt-card::after { content: ''; position: absolute; left: 10px; right: 10px; bottom: 1px; height: 1px; border-radius: 999px; background: var(--accent); transform: scaleX(0); transform-origin: left center; transition: transform var(--motion-standard, 190ms) var(--motion-ease, cubic-bezier(.22,1,.36,1)); }
.rt-card:hover { background: var(--workspace-hover-bg, rgba(255,255,255,.028)); }
.rt-card:active { transform: scale(.98); }
.rt-card.selected { color: var(--text); background: transparent; }
.rt-card.selected::after { transform: scaleX(1); }
.rt-card:focus-visible { outline: 0; background: var(--workspace-hover-bg, rgba(255,255,255,.028)); box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus, var(--accent)) 11%, transparent); }
.rt-copy { grid-area: copy; display: grid; gap: 3px; min-width: 0; }
.rt-copy b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 10.5px; font-weight: 580; }
.rt-copy small { display: none; }
.rt-value { grid-area: value; color: var(--text); font: 620 14px/1 ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; text-align: right; }
.rt-delta { grid-area: delta; min-width: 76px; font: 580 9px/1 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: nowrap; text-align: right; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
@media (max-width: 1100px) { .rt-card { flex-basis: 220px; min-width: 210px; } }
@media (max-width: 620px) { .quote-strip { padding-left: 0; padding-right: 0; } .rt-card { flex-basis: 205px; min-width: 205px; } }
</style>
