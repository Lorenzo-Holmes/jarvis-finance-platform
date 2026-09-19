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
    const response = await api.newsDaily(NEWS_FETCH_LIMIT, force)
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

function displayTitle(item) {
  return showOriginal.value ? (item.titleOriginal || item.title) : (item.titleZh || item.title)
}

function previousPage() {
  page.value = Math.max(1, page.value - 1)
}

function nextPage() {
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
  <section class="market-news-board">
    <header class="news-head">
      <div>
        <span>MARKET BRIEFING</span>
        <h2>市场要闻</h2>
        <p>把价格异动放回宏观、行业与事件背景中阅读。</p>
      </div>
      <div class="news-actions">
        <small v-if="translating">正在中文化标题…</small>
        <small v-else-if="generatedAt">更新 {{ formatNewsTime(generatedAt) }}</small>
        <button type="button" :disabled="state === 'loading'" @click="load(true)">刷新要闻</button>
        <button type="button" @click="showOriginal = !showOriginal">{{ showOriginal ? '显示中文' : '显示原文' }}</button>
      </div>
    </header>

    <p v-if="state === 'loading' && !items.length" class="news-state">正在整理市场要闻…</p>
    <p v-else-if="state !== 'ready'" class="news-state">{{ reason }}</p>

    <div v-else class="news-list-wrap">
      <div class="news-grid">
      <article v-for="(item, index) in visibleItems" :key="item.id || item.url || index" class="news-item">
        <div class="news-index">{{ String((page - 1) * PAGE_SIZE + index + 1).padStart(2, '0') }}</div>
        <div class="news-copy">
          <a v-if="item.linkable" :href="item.url" target="_blank" rel="noopener noreferrer">{{ displayTitle(item) }}</a>
          <strong v-else>{{ displayTitle(item) }}</strong>
          <footer>
            <span>{{ item.source || item.host || '来源未知' }}</span>
            <time v-if="item.published">{{ formatNewsTime(item.published) }}</time>
          </footer>
        </div>
      </article>
      </div>
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
.market-news-board { margin: 0 0 16px; padding: 14px; border: 1px solid color-mix(in srgb, var(--line) 88%, transparent); border-radius: 11px; background: color-mix(in srgb, var(--workspace-panel-wash, var(--panel)) 88%, transparent); }
.news-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.news-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.news-head h2 { margin: 4px 0 0; color: var(--text); font-size: 15px; font-weight: 680; }
.news-head p { margin: 4px 0 0; color: var(--muted); font-size: 9px; }
.news-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 8px; }
.news-actions small { color: var(--subtle); font-size: 8px; }
.news-actions button { height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 9px; }
.news-actions button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.news-state { margin: 12px 0 0; color: var(--muted); font-size: 10px; }
.news-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.news-item { min-width: 0; display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; padding: 11px 2px; border-bottom: 1px solid color-mix(in srgb, var(--line) 66%, transparent); }
.news-index { padding-top: 2px; color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; }
.news-copy { min-width: 0; }
.news-copy a, .news-copy > strong { display: block; color: var(--text); font-size: 10.5px; font-weight: 600; line-height: 1.5; text-decoration: none; }
.news-copy a:hover { color: var(--accent-strong); }
.news-copy footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 7px; color: var(--subtle); font: 600 7.5px/1 ui-monospace, monospace; }
.news-copy footer span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.news-copy time { flex: 0 0 auto; }
.news-pager { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; padding-top: 9px; border-top: 1px solid var(--line); color: var(--subtle); font-size: 8px; }
.news-pager > div { display: flex; align-items: center; gap: 8px; }
.news-pager b { min-width: 70px; color: var(--muted); font: 600 8px/1 ui-monospace, monospace; text-align: center; }
.news-pager button { min-height: 27px; padding: 0 9px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 8px; }
.news-pager button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.news-pager button:disabled { opacity: .36; cursor: default; }
@media (max-width: 760px) { .news-head { align-items: flex-start; flex-direction: column; } .news-actions { width: 100%; flex-wrap: wrap; } .news-grid { grid-template-columns: 1fr; } .news-pager { align-items: flex-start; flex-direction: column; } }
</style>
