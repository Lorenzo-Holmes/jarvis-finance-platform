<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import { formatNumber, formatPercent } from '../../utils/formatters'
import { quoteClass } from '../../utils/marketBoard'

const props = defineProps({ active: { type: Boolean, default: true } })
const emit = defineEmits(['select'])

const cards = ref([])
const loading = ref(true)
const error = ref('')
const trackRef = ref(null)
const paused = ref(false)
let refreshTimer = 0
let carouselTimer = 0

const availableCount = computed(() => cards.value.filter(item => item.available !== false && Number.isFinite(Number(item.price))).length)

function changeClass(item) {
  return quoteClass(item?.change_pct)
}

function priceText(item) {
  if (item?.available === false) return '—'
  return formatNumber(item?.price, 2, 4)
}

function changeText(item) {
  if (item?.available === false) return '暂不可用'
  return formatPercent(item?.change_pct)
}

async function loadOverview() {
  if (!props.active) return
  if (!cards.value.length) loading.value = true
  error.value = ''
  try {
    const response = await api.marketOverview()
    if (response?.code !== 200 || !Array.isArray(response?.data)) {
      throw new Error(response?.message || '市场概览加载失败')
    }
    cards.value = response.data
  } catch (e) {
    error.value = e?.message || '市场概览暂不可用'
  } finally {
    loading.value = false
  }
}

function scrollByCard(direction = 1) {
  const track = trackRef.value
  if (!track) return
  const card = track.querySelector('.pulse-card')
  const distance = (card?.getBoundingClientRect().width || 190) + 8
  const nearEnd = track.scrollLeft + track.clientWidth >= track.scrollWidth - distance * 0.75
  const nearStart = track.scrollLeft <= distance * 0.25
  if (direction > 0 && nearEnd) {
    track.scrollTo({ left: 0, behavior: 'smooth' })
    return
  }
  if (direction < 0 && nearStart) {
    track.scrollTo({ left: track.scrollWidth, behavior: 'smooth' })
    return
  }
  track.scrollBy({ left: direction * distance * 2, behavior: 'smooth' })
}

function selectCard(item) {
  if (!item || item.available === false) return
  emit('select', {
    market: item.market,
    symbol: item.symbol,
    name: item.name,
    sourceModule: 'market-overview',
  })
}

function startTimers() {
  stopTimers()
  refreshTimer = window.setInterval(loadOverview, 30000)
  carouselTimer = window.setInterval(() => {
    if (!paused.value && props.active) scrollByCard(1)
  }, 5200)
}

function stopTimers() {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (carouselTimer) window.clearInterval(carouselTimer)
  refreshTimer = 0
  carouselTimer = 0
}

onMounted(async () => {
  await loadOverview()
  await nextTick()
  startTimers()
})
watch(() => props.active, active => {
  if (active) {
    loadOverview()
    startTimers()
  } else {
    stopTimers()
  }
})
onBeforeUnmount(stopTimers)
</script>

<template>
  <section class="market-pulse" aria-label="全球市场脉搏">
    <header class="pulse-head">
      <div>
        <span>MARKET PULSE</span>
        <h2>全球市场脉搏</h2>
        <p>沪深北 · 美股 · 港股 · 上海金</p>
      </div>
      <div class="pulse-actions">
        <small>{{ availableCount }}/{{ cards.length || 14 }} 在线</small>
        <button type="button" aria-label="向左滚动" @click="scrollByCard(-1)">←</button>
        <button type="button" aria-label="向右滚动" @click="scrollByCard(1)">→</button>
        <button type="button" :disabled="loading" @click="loadOverview">{{ loading ? '刷新中' : '刷新' }}</button>
      </div>
    </header>

    <p v-if="error && !cards.length" class="pulse-notice">{{ error }}</p>
    <div
      v-else
      ref="trackRef"
      class="pulse-track"
      @mouseenter="paused = true"
      @mouseleave="paused = false"
      @focusin="paused = true"
      @focusout="paused = false"
    >
      <button
        v-for="item in cards"
        :key="item.key || item.symbol"
        type="button"
        class="pulse-card"
        :class="{ unavailable: item.available === false }"
        :disabled="item.available === false"
        @click="selectCard(item)"
      >
        <div class="pulse-card-title">
          <strong>{{ item.name }}</strong>
          <span>{{ item.region || '—' }}</span>
        </div>
        <div class="pulse-price">{{ priceText(item) }}</div>
        <div class="pulse-meta">
          <b :class="changeClass(item)">{{ changeText(item) }}</b>
          <small>{{ item.currency || '' }}</small>
        </div>
        <footer>
          <span>{{ item.symbol }}</span>
          <span>{{ item.source || (item.available === false ? '数据源暂不可用' : '行情源') }}</span>
        </footer>
      </button>
      <article v-if="loading && !cards.length" v-for="n in 7" :key="n" class="pulse-card skeleton" aria-hidden="true"></article>
    </div>
  </section>
</template>

<style scoped>
.market-pulse { min-width: 0; margin-bottom: 14px; }
.pulse-head { min-height: 52px; display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; padding: 0 2px 10px; }
.pulse-head > div:first-child { min-width: 0; }
.pulse-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.pulse-head h2 { margin: 4px 0 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: -.02em; }
.pulse-head p { margin: 4px 0 0; color: var(--muted); font-size: 9px; }
.pulse-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 5px; }
.pulse-actions small { margin-right: 4px; color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; }
.pulse-actions button { height: 28px; min-width: 28px; padding: 0 8px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 9px; }
.pulse-actions button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.pulse-actions button:disabled { opacity: .45; }
.pulse-track { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(168px, 1fr); gap: 8px; overflow-x: auto; overscroll-behavior-inline: contain; scroll-snap-type: x proximity; scrollbar-width: none; padding: 1px 1px 7px; }
.pulse-track::-webkit-scrollbar { display: none; }
.pulse-card { scroll-snap-align: start; min-width: 0; min-height: 128px; display: flex; flex-direction: column; padding: 12px; border: 1px solid color-mix(in srgb, var(--line) 88%, transparent); border-radius: 10px; background: color-mix(in srgb, var(--workspace-panel-wash, var(--panel)) 92%, transparent); color: var(--text); text-align: left; cursor: pointer; transition: transform .16s ease, border-color .16s ease, background .16s ease; }
.pulse-card:hover:not(:disabled) { transform: translateY(-2px); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.pulse-card.unavailable { cursor: default; opacity: .54; }
.pulse-card-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.pulse-card-title strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 670; }
.pulse-card-title span { flex: 0 0 auto; color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; }
.pulse-price { margin-top: 16px; font: 680 20px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; letter-spacing: -.035em; }
.pulse-meta { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-top: 8px; }
.pulse-meta b { font: 650 9px/1 ui-monospace, monospace; }
.pulse-meta small { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.pulse-card footer { margin-top: auto; display: grid; gap: 4px; padding-top: 12px; color: var(--subtle); font-size: 7px; }
.pulse-card footer span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
.flat { color: var(--muted); }
.pulse-notice { margin: 0; padding: 16px; border: 1px solid var(--line); color: var(--muted); font-size: 10px; }
.skeleton { cursor: default; background: linear-gradient(100deg, var(--panel) 30%, var(--surface) 50%, var(--panel) 70%); background-size: 200% 100%; animation: pulse-skeleton 1.4s linear infinite; }
@keyframes pulse-skeleton { to { background-position: -200% 0; } }
@media (max-width: 720px) { .pulse-head { align-items: flex-start; flex-direction: column; } .pulse-actions { width: 100%; } .pulse-actions small { margin-right: auto; } .pulse-track { grid-auto-columns: minmax(154px, 72vw); } }
@media (prefers-reduced-motion: reduce) { .pulse-card, .skeleton { transition: none; animation: none; } }
</style>
