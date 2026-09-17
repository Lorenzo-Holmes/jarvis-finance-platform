<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../../api/client'
import { formatNumber } from '../../utils/formatters'
import { formatNewsTime, groupInstrumentsByMarket, pickNewsItems, quoteClass } from '../../utils/marketBoard'

/**
 * 主行情页的「多市场看板 + 每日要闻」。
 *
 * 数据来源全部是既有接口：目录 /api/market/instruments、逐标的 /api/market/extended/quote、
 * 要闻 /api/news/daily（Java 代理 Python 的 RSS digest）。
 * 行情与要闻各自独立降级——要闻拿不到不该让行情区块变成错误态，反之亦然。
 */
const props = defineProps({ active: { type: Boolean, default: true } })

const instruments = ref([])
const quoteMap = ref({})
const boardState = ref('loading')
const boardError = ref('')
const news = ref([])
const newsState = ref('loading')
const newsReason = ref('')
const newsStamp = ref('')

// 行情 60s 刷一次；要闻的抓取间隔在 Python 侧是 300s，所以每 5 个 tick 才问一次，
// 既保持"当日要闻"的新鲜度，又不至于每次轮询都去打外部 feed。
const BOARD_TICK_MS = 60000
const NEWS_EVERY_TICKS = 5
let pollTimer = 0
let tickCount = 0

const groups = computed(() => groupInstrumentsByMarket(instruments.value))

function quoteFor(market, symbol) {
  return quoteMap.value[`${market}:${symbol}`] || null
}

function pctText(value) {
  const num = Number(value)
  return Number.isFinite(num) ? `${num.toFixed(2)}%` : '—'
}

async function loadBoard() {
  try {
    const response = await api.marketInstruments()
    const list = Array.isArray(response?.data) ? response.data : []
    instruments.value = list
    const results = await Promise.allSettled(
      list.map(entry => api.marketAssetQuote(entry.market, entry.symbol)),
    )
    const next = {}
    results.forEach((result, index) => {
      if (result.status !== 'fulfilled') return
      const quote = result.value?.data
      if (quote && typeof quote === 'object' && !Array.isArray(quote)) {
        next[`${list[index].market}:${list[index].symbol}`] = quote
      }
    })
    quoteMap.value = next
    boardState.value = list.length ? 'ready' : 'error'
    boardError.value = list.length ? '' : '行情目录为空'
  } catch (error) {
    boardState.value = 'error'
    boardError.value = error?.message || '多市场行情不可用'
  }
}

async function loadNews(force = false) {
  if (!news.value.length) newsState.value = 'loading'
  try {
    const response = await api.newsDaily(12, force)
    const payload = response?.data
    if (!payload || payload.available === false) {
      news.value = []
      newsState.value = 'unavailable'
      newsReason.value = payload?.reason === 'ai_service_unavailable'
        ? '资讯服务暂时不可用（AI 服务未连线）'
        : '要闻暂时不可用'
      return
    }
    news.value = pickNewsItems(payload, 12)
    newsStamp.value = payload.generated_at || ''
    if (news.value.length) {
      newsState.value = 'ready'
      newsReason.value = ''
    } else {
      newsState.value = 'empty'
      newsReason.value = '这一轮没有抓到资讯，可以点「抓取」再试'
    }
  } catch (error) {
    news.value = []
    newsState.value = 'unavailable'
    newsReason.value = error?.message || '要闻暂时不可用'
  }
}

function tick() {
  if (!props.active) return
  tickCount += 1
  loadBoard()
  if (tickCount % NEWS_EVERY_TICKS === 0) loadNews()
}

onMounted(() => {
  loadBoard()
  loadNews()
  pollTimer = window.setInterval(tick, BOARD_TICK_MS)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = 0
})

defineExpose({ loadBoard, loadNews })
</script>

<template>
  <div class="multi-market">
    <section class="board-panel">
      <header class="panel-head">
        <div class="panel-title">
          <span class="panel-kicker">MULTI-MARKET BOARD</span>
          <h2>多市场看板</h2>
          <span class="panel-sub">黄金之外，同时盯 A 股 / 美股 / 加密</span>
        </div>
        <button type="button" :disabled="boardState === 'loading'" @click="loadBoard">刷新</button>
      </header>

      <p v-if="boardState === 'error'" class="notice">{{ boardError }}</p>
      <p v-else-if="boardState === 'loading'" class="notice">正在载入多市场行情…</p>

      <div v-else class="board-groups">
        <div v-for="group in groups" :key="group.key" class="board-group">
          <h3>
            <b>{{ group.label.en }}</b>
            <em>{{ group.label.zh }}</em>
            <span>{{ group.instruments.length }}</span>
          </h3>
          <ul>
            <li v-for="item in group.instruments" :key="item.symbol">
              <div class="inst">
                <strong>{{ item.name || item.symbol }}</strong>
                <small>{{ item.symbol }} · {{ item.currency }}</small>
              </div>
              <template v-if="quoteFor(item.market, item.symbol)">
                <b class="price">{{ formatNumber(quoteFor(item.market, item.symbol).price) }}</b>
                <span :class="['chg', quoteClass(quoteFor(item.market, item.symbol).change_pct)]">
                  {{ pctText(quoteFor(item.market, item.symbol).change_pct) }}
                </span>
              </template>
              <template v-else>
                <b class="price">—</b>
                <span class="chg flat">无报价</span>
              </template>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <section class="news-panel">
      <header class="panel-head">
        <div class="panel-title">
          <span class="panel-kicker">DAILY BRIEFING</span>
          <h2>每日要闻</h2>
          <span class="panel-sub">点击标题跳转原文，来源与时间随行</span>
        </div>
        <button type="button" :disabled="newsState === 'loading'" @click="loadNews(true)">抓取</button>
      </header>

      <p v-if="newsState === 'loading'" class="notice">正在载入要闻…</p>
      <p v-else-if="newsState !== 'ready'" class="notice">{{ newsReason }}</p>

      <ul v-else class="news-list">
        <li v-for="item in news" :key="item.id">
          <!-- 只有 http(s) 才渲染成可点链接，其余按纯文本展示（RSS 里出现过 javascript:） -->
          <a v-if="item.linkable" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
          <span v-else class="news-plain">{{ item.title }}</span>
          <footer>
            <span>{{ item.source || item.host || '来源未知' }}</span>
            <time v-if="item.published">{{ formatNewsTime(item.published) }}</time>
          </footer>
        </li>
      </ul>

      <p v-if="newsStamp" class="stamp">抓取于 {{ formatNewsTime(newsStamp) }}</p>
    </section>
  </div>
</template>

<style scoped>
.multi-market { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 14px; margin-top: 14px; }
.board-panel, .news-panel { border: 1px solid var(--line); background: var(--panel); padding: 14px 16px 16px; min-width: 0; }
.panel-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid color-mix(in srgb, var(--line) 72%, transparent); }
.panel-title { display: grid; gap: 5px; }
.panel-kicker { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.panel-head h2 { margin: 0; color: var(--text); font-size: 15px; line-height: 1; font-weight: 650; letter-spacing: -.015em; }
.panel-sub { color: var(--muted); font-size: 9px; }
.panel-head button { flex: 0 0 auto; border: 1px solid var(--line-strong); background: transparent; color: var(--muted); border-radius: var(--radius-sm); padding: 5px 11px; font-size: 12px; cursor: pointer; transition: color var(--motion-fast, 110ms) ease, border-color var(--motion-fast, 110ms) ease; }
.panel-head button:hover:not(:disabled) { color: var(--text); border-color: var(--text); }
.panel-head button:disabled { opacity: .45; cursor: default; }
.notice { margin: 12px 0 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.board-groups { display: grid; grid-template-columns: repeat(auto-fit, minmax(215px, 1fr)); gap: 16px; margin-top: 12px; }
.board-group h3 { display: flex; align-items: baseline; gap: 7px; margin: 0 0 8px; padding-bottom: 6px; border-bottom: 1px solid var(--line); }
.board-group h3 b { font: 650 12px/1 ui-monospace, monospace; color: var(--accent-strong); letter-spacing: .09em; }
.board-group h3 em { font-style: normal; font-size: 12px; color: var(--muted); }
.board-group h3 span { margin-left: auto; font: 600 10px/1 ui-monospace, monospace; color: var(--subtle); border: 1px solid var(--line-strong); border-radius: 999px; padding: 2px 6px; }
.board-group ul { list-style: none; margin: 0; padding: 0; }
.board-group li { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: baseline; gap: 9px; padding: 7px 0; border-bottom: 1px solid var(--line); }
.board-group li:last-child { border-bottom: 0; }
.inst { min-width: 0; }
.inst strong { display: block; color: var(--text); font-size: 13px; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inst small { display: block; margin-top: 3px; color: var(--subtle); font: 600 10px/1 ui-monospace, monospace; }
.price { color: var(--text); font: 650 13px/1 ui-monospace, monospace; }
.chg { font: 600 12px/1 ui-monospace, monospace; color: var(--muted); }
.chg.pos { color: var(--ok); }
.chg.neg { color: var(--bad); }
.news-list { list-style: none; margin: 12px 0 0; padding: 0; }
.news-list li { padding: 9px 0; border-bottom: 1px solid var(--line); }
.news-list li:last-child { border-bottom: 0; }
.news-list a, .news-plain { display: block; color: var(--text); font-size: 13px; line-height: 1.5; text-decoration: none; }
.news-list a:hover { color: var(--accent-strong); text-decoration: underline; }
.news-plain { color: var(--muted); }
.news-list footer { display: flex; align-items: baseline; gap: 10px; margin-top: 6px; color: var(--subtle); font: 600 10px/1 ui-monospace, monospace; }
.news-list footer time { margin-left: auto; }
.stamp { margin: 10px 0 0; color: var(--subtle); font: 600 10px/1 ui-monospace, monospace; }
@media (max-width: 1100px) { .multi-market { grid-template-columns: 1fr; } }
</style>