<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import { formatNewsTime, pickNewsItems } from '../../utils/marketBoard'

const props = defineProps({ active: { type: Boolean, default: true } })

const NEWS_FETCH_LIMIT = 40
const PAGE_SIZE = 8

const items = ref([])
const state = ref('loading')
const reason = ref('')
const generatedAt = ref('')
const page = ref(1)
const showOriginal = ref(false)
const rankingMode = ref('smart')
const pageDirection = ref('forward')
const pageCount = computed(() => Math.max(1, Math.ceil(items.value.length / PAGE_SIZE)))
const translating = ref(false)
const visibleItems = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return items.value.slice(start, start + PAGE_SIZE)
})
let timer = 0
let loadSequence = 0
let translationSequence = 0

function containsChinese(value) {
  return /[\u3400-\u9fff]/.test(String(value || ''))
}

async function localizeItems(snapshot) {
  const candidates = snapshot
    .filter(item => item.titleOriginal && !containsChinese(item.titleOriginal) && item.titleZh === item.titleOriginal)
    .map(item => item.titleOriginal)
  const titles = [...new Set(candidates)]
  if (!titles.length) return

  const sequence = ++translationSequence
  translating.value = true
  try {
    const response = await api.newsTranslate(titles)
    if (sequence !== translationSequence) return
    const translated = Array.isArray(response?.data?.translations) ? response.data.translations : []
    if (translated.length !== titles.length) return
    const byOriginal = new Map(titles.map((title, index) => [title, String(translated[index] || title).trim() || title]))
    items.value = items.value.map(item => ({
      ...item,
      titleZh: byOriginal.get(item.titleOriginal) || item.titleZh,
    }))
  } catch {
    // 中文化是增强层；失败时保留原文，不影响新闻阅读。
  } finally {
    if (sequence === translationSequence) translating.value = false
  }
}

async function load(force = false) {
    const requestSequence = ++loadSequence
  if (!items.value.length) state.value = 'loading'
  reason.value = ''
  try {
    const response = await api.newsDaily(NEWS_FETCH_LIMIT, force, rankingMode.value)
    if (requestSequence !== loadSequence) return
    const payload = response?.data
    if (!payload || payload.available === false) {
      translationSequence += 1
      translating.value = false
      items.value = []
      state.value = 'unavailable'
      reason.value = payload?.reason === 'ai_service_unavailable'
        ? '资讯服务暂时不可用'
        : '市场要闻暂时不可用'
      return
    }
    items.value = pickNewsItems(payload, NEWS_FETCH_LIMIT)
    if (force) page.value = 1
    else page.value = Math.min(page.value, pageCount.value)
    generatedAt.value = payload.generated_at || ''
    state.value = items.value.length ? 'ready' : 'empty'
    reason.value = items.value.length ? '' : '暂未抓取到今日市场要闻'
    if (items.value.length) void localizeItems(items.value)
  } catch (e) {
    if (requestSequence !== loadSequence) return
    state.value = 'unavailable'
    reason.value = e?.message || '市场要闻暂时不可用'
  }
}

async function changeRanking(mode) {
  if (!['smart', 'latest'].includes(mode) || rankingMode.value === mode) return
  rankingMode.value = mode
  page.value = 1
  await load(false)
}

function displayTitle(item) {
  return showOriginal.value ? (item.titleOriginal || item.title) : (item.titleZh || item.title)
}

function displayScore(item) {
  const value = item?.hybridScore ?? item?.rankScore
  return Number.isFinite(Number(value)) ? Math.round(Number(value)) : null
}

function previousPage() {
  pageDirection.value = 'backward'
  page.value = Math.max(1, page.value - 1)
}

function nextPage() {
  pageDirection.value = 'forward'
  page.value = Math.min(pageCount.value, page.value + 1)
}

function startTimer() {
  stopTimer()
  timer = window.setInterval(() => {
    if (props.active) load(false)
  }, 300000)
}

function stopTimer() {
  if (timer) window.clearInterval(timer)
  timer = 0
}

onMounted(() => {
  load()
  startTimer()
})
watch(() => props.active, active => {
  if (active) {
    load()
    startTimer()
  } else {
    loadSequence += 1
    translationSequence += 1
    translating.value = false
    stopTimer()
  }
})
onBeforeUnmount(() => {
  loadSequence += 1
  translationSequence += 1
  stopTimer()
})
</script>

<template>
  <section class="market-news-board" :class="{ translating }">
    <header class="news-head">
      <div>
        <span>MARKET BRIEFING</span>
        <h2>市场要闻</h2>
        <p>把价格异动放回宏观、行业与事件背景中阅读。</p>
      </div>
      <div class="news-actions">
        <small v-if="translating">正在中文化标题…</small>
        <small v-else-if="generatedAt">更新 {{ formatNewsTime(generatedAt) }}</small>
        <div class="ranking-switch" role="group" aria-label="市场要闻排序方式" :style="{ '--ranking-index': rankingMode === 'smart' ? 0 : 1 }">
          <button type="button" :aria-pressed="rankingMode === 'smart'" :class="{ active: rankingMode === 'smart' }" @click="changeRanking('smart')">精选</button>
          <button type="button" :aria-pressed="rankingMode === 'latest'" :class="{ active: rankingMode === 'latest' }" @click="changeRanking('latest')">最新</button>
        </div>
        <button class="refresh-action" :class="{ active: state === 'loading' }" type="button" :disabled="state === 'loading'" @click="load(true)"><i class="refresh-glyph"></i>刷新要闻</button>
        <button type="button" @click="showOriginal = !showOriginal">{{ showOriginal ? '显示中文' : '显示原文' }}</button>
      </div>
    </header>

    <p v-if="state === 'loading' && !items.length" class="news-state">正在整理市场要闻…</p>
    <p v-else-if="state !== 'ready'" class="news-state">{{ reason }}</p>

    <div v-else class="news-list-wrap">
      <Transition :name="`page-${pageDirection}`" mode="out-in">
      <div :key="`${rankingMode}-${page}`" class="news-grid">
      <article v-for="(item, index) in visibleItems" :key="item.id || item.url || index" class="news-item">
        <div class="news-index">{{ String((page - 1) * PAGE_SIZE + index + 1).padStart(2, '0') }}</div>
        <div class="news-copy">
          <Transition name="title-swap" mode="out-in">
            <a v-if="item.linkable" :key="displayTitle(item)" :href="item.url" target="_blank" rel="noopener noreferrer">{{ displayTitle(item) }}</a>
            <strong v-else :key="displayTitle(item)">{{ displayTitle(item) }}</strong>
          </Transition>
          <div v-if="displayScore(item) !== null || item.sourceCount > 1" class="intelligence-badges">
            <span v-if="displayScore(item) !== null">精选 {{ displayScore(item) }}</span>
            <span v-if="item.sourceCount > 1">多源 ×{{ item.sourceCount }}</span>
          </div>
          <footer>
            <span>{{ item.source || item.host || '来源未知' }}</span>
            <time v-if="item.published">{{ formatNewsTime(item.published) }}</time>
          </footer>
        </div>
      </article>
      </div>
      </Transition>
      <footer class="news-pager" aria-label="市场要闻分页">
        <span>共 {{ items.length }} 条</span>
        <div>
          <button type="button" :disabled="page <= 1" @click="previousPage">上一页</button>
          <b>第 {{ page }} / {{ pageCount }} 页</b>
          <button type="button" :disabled="page >= pageCount" @click="nextPage">下一页</button>
        </div>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.market-news-board { --news-motion-state: var(--ds-motion-state, 160ms); --news-motion-surface: var(--ds-motion-surface, 220ms); --news-ease: var(--ds-ease, cubic-bezier(.22,1,.36,1)); margin: 0 0 16px; padding: 14px; border: 1px solid color-mix(in srgb, var(--line) 88%, transparent); border-radius: 11px; background: color-mix(in srgb, var(--workspace-panel-wash, var(--panel)) 88%, transparent); }
.news-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.news-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.news-head h2 { margin: 4px 0 0; color: var(--text); font-size: 15px; font-weight: 680; }
.news-head p { margin: 4px 0 0; color: var(--muted); font-size: 9px; }
.news-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 8px; }
.ranking-switch { position: relative; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 2px; border: 1px solid var(--line); border-radius: 7px; background: color-mix(in srgb, var(--surface) 84%, transparent); overflow: hidden; }
.ranking-switch::before { content: ''; position: absolute; z-index: 0; inset: 2px auto 2px 2px; width: calc((100% - 4px) / 2); border: 1px solid color-mix(in srgb, var(--accent) 16%, var(--line-strong)); border-radius: 5px; background: color-mix(in srgb, var(--workspace-accent-wash) 72%, transparent); transform: translateX(calc(var(--ranking-index) * 100%)); transition: transform var(--news-motion-surface) var(--news-ease); pointer-events: none; }
.ranking-switch button { position: relative; z-index: 1; height: 25px; padding: 0 8px; border: 0; border-radius: 5px; background: transparent; color: var(--subtle); transition: color var(--news-motion-state) ease; }
.ranking-switch button.active { color: var(--text); background: transparent; }
.news-actions small { color: var(--subtle); font-size: 8px; }
.news-actions button { height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 9px; }
.news-actions button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.refresh-action { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }.refresh-glyph { width: 8px; height: 8px; border: 1px solid currentColor; border-left-color: transparent; border-radius: 50%; }.refresh-action.active .refresh-glyph { will-change: transform; animation: news-refresh-spin .8s linear infinite; }
@keyframes news-refresh-spin { to { transform: rotate(360deg); } }
.news-actions button:focus-visible, .news-copy a:focus-visible, .news-pager button:focus-visible { outline: 2px solid color-mix(in srgb, var(--accent) 58%, transparent); outline-offset: 2px; }
.news-state { margin: 12px 0 0; color: var(--muted); font-size: 10px; }
.news-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.page-forward-enter-active, .page-forward-leave-active, .page-backward-enter-active, .page-backward-leave-active { transition: opacity var(--news-motion-surface) ease, transform var(--news-motion-surface) var(--news-ease); }
.page-forward-enter-from, .page-backward-leave-to { opacity: 0; transform: translateX(10px); }
.page-forward-leave-to, .page-backward-enter-from { opacity: 0; transform: translateX(-10px); }
.news-item { position: relative; contain: paint; min-width: 0; display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; padding: 11px 8px; border-bottom: 1px solid color-mix(in srgb, var(--line) 66%, transparent); border-radius: 7px; transition: transform var(--news-motion-surface) var(--news-ease), background var(--news-motion-state) ease, border-color var(--news-motion-state) ease, box-shadow var(--news-motion-surface) ease; }
.news-item::before { content: ''; position: absolute; left: 0; top: 9px; bottom: 9px; width: 2px; border-radius: 999px; background: var(--accent); opacity: 0; transform: scaleY(.4); transition: opacity .16s ease, transform .18s cubic-bezier(.22,1,.36,1); }
.news-item:hover, .news-item:focus-within { transform: translateY(-1px); background: color-mix(in srgb, var(--workspace-accent-wash) 18%, transparent); border-color: color-mix(in srgb, var(--line-strong) 76%, var(--accent)); box-shadow: 0 8px 24px rgba(0,0,0,.03); }
.news-item:hover::before, .news-item:focus-within::before { opacity: .7; transform: scaleY(1); }
.news-index { padding-top: 2px; color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; }
.news-copy { position: relative; min-width: 0; overflow: hidden; }
.market-news-board.translating .news-copy::before { content: ''; position: absolute; z-index: 3; inset: 0; background: linear-gradient(100deg, transparent 0%, color-mix(in srgb, var(--accent) 7%, transparent) 42%, color-mix(in srgb, var(--accent) 13%, transparent) 50%, transparent 60%); transform: translateX(-120%); will-change: transform; animation: title-translate-shimmer 1.25s ease-in-out infinite; pointer-events: none; }
@keyframes title-translate-shimmer { to { transform: translateX(120%); } }
.title-swap-enter-active, .title-swap-leave-active { transition: opacity var(--news-motion-state) ease, transform var(--news-motion-state) var(--news-ease); }
.title-swap-enter-from { opacity: 0; transform: translateY(2px); }.title-swap-leave-to { opacity: 0; transform: translateY(-2px); }
.news-copy a, .news-copy > strong { display: block; color: var(--text); font-size: 10.5px; font-weight: 600; line-height: 1.5; text-decoration: none; }
.news-copy a:hover { color: var(--accent-strong); }
.intelligence-badges { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 6px; }
.intelligence-badges span { padding: 2px 5px; border: 1px solid color-mix(in srgb, var(--accent) 16%, var(--line)); border-radius: 999px; background: color-mix(in srgb, var(--workspace-accent-wash) 34%, transparent); color: var(--muted); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .02em; }
.news-copy footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 7px; color: var(--subtle); font: 600 7.5px/1 ui-monospace, monospace; }
.news-copy footer span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.news-copy time { flex: 0 0 auto; }
.news-pager { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; padding-top: 9px; border-top: 1px solid var(--line); color: var(--subtle); font-size: 8px; }
.news-pager > div { display: flex; align-items: center; gap: 8px; }
.news-pager b { min-width: 70px; color: var(--muted); font: 600 8px/1 ui-monospace, monospace; text-align: center; }
.news-pager button { min-height: 27px; padding: 0 9px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 8px; }
.news-pager button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.news-pager button:disabled { opacity: .36; cursor: default; }
@media (max-width: 760px) { .news-head { align-items: flex-start; flex-direction: column; } .news-actions { width: 100%; flex-wrap: wrap; }.news-actions button { min-height: 38px; }.ranking-switch { max-width: 100%; overflow-x: auto; } .news-grid { grid-template-columns: 1fr; } .news-pager { align-items: flex-start; flex-direction: column; }.news-pager button { min-height: 38px; } }
@media (prefers-reduced-motion: reduce) { .market-news-board, .market-news-board * { scroll-behavior: auto !important; }.market-news-board *, .market-news-board *::before, .market-news-board *::after { animation-duration: .001ms !important; animation-iteration-count: 1 !important; transition-duration: .001ms !important; } }
</style>
