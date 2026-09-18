<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import { formatNewsTime, pickNewsItems } from '../../utils/marketBoard'

const props = defineProps({ active: { type: Boolean, default: true } })

const items = ref([])
const state = ref('loading')
const reason = ref('')
const generatedAt = ref('')
let timer = 0

async function load(force = false) {
  if (!items.value.length) state.value = 'loading'
  reason.value = ''
  try {
    const response = await api.newsDaily(12, force)
    const payload = response?.data
    if (!payload || payload.available === false) {
      items.value = []
      state.value = 'unavailable'
      reason.value = payload?.reason === 'ai_service_unavailable'
        ? '资讯服务暂时不可用'
        : '市场要闻暂时不可用'
      return
    }
    items.value = pickNewsItems(payload, 12)
    generatedAt.value = payload.generated_at || ''
    state.value = items.value.length ? 'ready' : 'empty'
    reason.value = items.value.length ? '' : '暂未抓取到今日市场要闻'
  } catch (e) {
    state.value = 'unavailable'
    reason.value = e?.message || '市场要闻暂时不可用'
  }
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
    stopTimer()
  }
})
onBeforeUnmount(stopTimer)
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
        <small v-if="generatedAt">更新 {{ formatNewsTime(generatedAt) }}</small>
        <button type="button" :disabled="state === 'loading'" @click="load(true)">刷新要闻</button>
      </div>
    </header>

    <p v-if="state === 'loading' && !items.length" class="news-state">正在整理市场要闻…</p>
    <p v-else-if="state !== 'ready'" class="news-state">{{ reason }}</p>

    <div v-else class="news-grid">
      <article v-for="(item, index) in items" :key="item.id || item.url || index" class="news-item">
        <div class="news-index">{{ String(index + 1).padStart(2, '0') }}</div>
        <div class="news-copy">
          <a v-if="item.linkable" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
          <strong v-else>{{ item.title }}</strong>
          <footer>
            <span>{{ item.source || item.host || '来源未知' }}</span>
            <time v-if="item.published">{{ formatNewsTime(item.published) }}</time>
          </footer>
        </div>
      </article>
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
@media (max-width: 760px) { .news-head { align-items: flex-start; flex-direction: column; } .news-actions { width: 100%; justify-content: space-between; } .news-grid { grid-template-columns: 1fr; } }
</style>
