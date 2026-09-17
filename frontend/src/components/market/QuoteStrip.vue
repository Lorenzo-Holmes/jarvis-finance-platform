<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { formatNumber, formatPercent } from '../../utils/formatters'

const props = defineProps({
  jdPrices: { type: Object, default: null },
  realtimePrices: { type: Object, default: null },
  marketFocus: { type: String, default: 'gold_etf' },
  jdMarket: { type: String, default: 'zheshang' },
})

const emit = defineEmits(['select'])
const stripRef = ref(null)
const selectionLensStyle = ref({ opacity: '0' })
let lensRaf = 0

function syncSelectionLens() {
  if (lensRaf) window.cancelAnimationFrame(lensRaf)
  lensRaf = window.requestAnimationFrame(() => {
    lensRaf = 0
    const strip = stripRef.value
    const target = strip?.querySelector('.rt-card.selected')
    if (!strip || !target) {
      selectionLensStyle.value = { opacity: '0' }
      return
    }
    selectionLensStyle.value = {
      opacity: '1',
      width: `${target.offsetWidth}px`,
      transform: `translate3d(${target.offsetLeft}px, 0, 0)`,
    }
  })
}

watch(
  () => [props.marketFocus, props.jdMarket, Object.keys(props.jdPrices || {}).join('|'), Object.keys(props.realtimePrices || {}).join('|')],
  () => nextTick(syncSelectionLens),
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('resize', syncSelectionLens)
  syncSelectionLens()
})

onBeforeUnmount(() => {
  if (lensRaf) window.cancelAnimationFrame(lensRaf)
  window.removeEventListener('resize', syncSelectionLens)
})
</script>

<template>
  <div ref="stripRef" class="quote-strip market-ticker" aria-label="行情带">
    <i class="quote-selection-lens" aria-hidden="true" :style="selectionLensStyle"></i>
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
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 5px;
  min-height: 46px;
  padding: 3px 0 7px;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 0;
  scrollbar-width: none;
  scroll-snap-type: x proximity;
}
.quote-strip::-webkit-scrollbar { display: none; }
.rt-card {
  position: relative;
  z-index: 1;
  flex: 0 1 240px;
  min-width: 198px;
  display: grid;
  grid-template-columns: minmax(90px, 1fr) auto auto;
  grid-template-areas: "copy value delta";
  align-items: center;
  column-gap: 10px;
  min-height: 40px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  scroll-snap-align: start;
  transition: color var(--motion-standard, 190ms) ease, background var(--motion-standard, 190ms) ease, border-color var(--motion-standard, 190ms) ease, box-shadow var(--motion-standard, 190ms) ease, transform var(--motion-fast, 110ms) ease;
}
.rt-card:hover {
  border-color: color-mix(in srgb, var(--material-border, var(--line)) 76%, transparent);
  background: color-mix(in srgb, var(--workspace-hover-bg, rgba(255,255,255,.028)) 76%, transparent);
}
.rt-card:active { transform: scale(.98); }
.rt-card.selected {
  color: var(--text);
  border-color: transparent;
  background: transparent;
  box-shadow: none;
}
.rt-card:focus-visible { outline: 0; background: var(--workspace-hover-bg, rgba(255,255,255,.028)); box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus, var(--accent)) 11%, transparent); }
.rt-copy { grid-area: copy; display: grid; gap: 3px; min-width: 0; }
.rt-copy b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 11px; font-weight: 590; }
.rt-copy small { display: none; }
.rt-value { grid-area: value; color: var(--text); font: 620 14px/1 ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; text-align: right; }
.rt-delta { grid-area: delta; min-width: 76px; font: 580 9.5px/1 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: nowrap; text-align: right; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
.quote-selection-lens {
  position: absolute;
  z-index: 0;
  left: 0;
  top: 3px;
  height: 40px;
  border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 88%, transparent);
  border-radius: 10px;
  background:
    linear-gradient(180deg, rgba(255,255,255,.028), transparent),
    color-mix(in srgb, var(--material-glass, rgba(255,255,255,.04)) 54%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.026), 0 8px 20px rgba(0,0,0,.035);
  pointer-events: none;
  transition:
    transform .34s cubic-bezier(.22,1,.36,1),
    width .34s cubic-bezier(.22,1,.36,1),
    opacity .16s ease;
}
@media (max-width: 1100px) { .rt-card { flex-basis: 216px; min-width: 196px; } }
@media (max-width: 620px) { .quote-strip { padding-left: 0; padding-right: 0; } .rt-card { flex-basis: 192px; min-width: 192px; } }
@media (prefers-reduced-motion: reduce) { .quote-selection-lens { transition: none !important; } }
</style>
