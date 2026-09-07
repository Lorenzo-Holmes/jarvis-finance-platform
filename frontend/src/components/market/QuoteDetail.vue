<script setup>
import { formatNumber } from '../../utils/formatters'

defineProps({
  quote: { type: Object, default: null },
  intervalLabel: { type: String, default: '-' },
})

const fmt = value => formatNumber(value, 2, 4)
</script>

<template>
  <section class="rail-panel">
    <div class="rail-title">报价详情</div>
    <dl v-if="quote" class="quote-list">
      <div><dt>昨收</dt><dd>{{ fmt(quote.prev_close) }}</dd></div>
      <div><dt>今开</dt><dd>{{ fmt(quote.open) }}</dd></div>
      <div><dt>最高</dt><dd class="pos">{{ fmt(quote.high) }}</dd></div>
      <div><dt>最低</dt><dd class="neg">{{ fmt(quote.low) }}</dd></div>
      <div><dt>周期</dt><dd>{{ intervalLabel }}</dd></div>
      <div><dt>更新时间</dt><dd class="time-value">{{ quote.quote_time || '-' }}</dd></div>
    </dl>
    <div v-else class="rail-empty">等待报价数据</div>
  </section>
</template>

<style scoped>
.rail-panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px; }
.rail-title { color: var(--text); font-size: 11px; font-weight: 650; }
.quote-list { margin: 9px 0 0; }
.quote-list > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 29px; border-bottom: 1px solid #222529; }
.quote-list dt { color: var(--subtle); font-size: 9px; }
.quote-list dd { margin: 0; color: var(--text); font-size: 10px; font-weight: 550; font-variant-numeric: tabular-nums; }
.quote-list dd.time-value { max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-empty { color: var(--subtle); font-size: 10px; line-height: 1.6; margin-top: 10px; }
.pos { color: #27c46b !important; }
.neg { color: #ef5350 !important; }
</style>
