<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

const emit = defineEmits(['navigate-module'])

const sources = ref([])
const articles = ref([])
const selectedSources = ref([])
const selectedTopics = ref([])
const loading = ref(true)
const refreshing = ref(false)
const analyzing = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const generatedAt = ref('')
const available = ref(true)
const rankingMode = ref('smart')

const topicOptions = [
  { key: 'markets', label: '市场行情' },
  { key: 'global', label: '宏观 / 全球' },
  { key: 'crypto', label: '加密资产' },
  { key: 'gold', label: '黄金 / 大宗' },
  { key: 'policy', label: '政策监管' },
]

const sourceCountLabel = computed(() => `${sources.value.length} 个可用来源`)
const isAllSources = computed(() => selectedSources.value.length === 0)

function responseError(response, fallback) {
  if (response?.code !== 200) throw new Error(response?.message || fallback)
}

async function loadPreferences() {
  const [sourceResponse, subscriptionResponse] = await Promise.all([
    api.newsSources(),
    api.newsSubscriptions(),
  ])
  responseError(sourceResponse, 'RSS 来源加载失败')
  responseError(subscriptionResponse, '资讯订阅加载失败')
  sources.value = sourceResponse.data?.items || []
  selectedSources.value = [...(subscriptionResponse.data?.sourceKeys || [])]
  selectedTopics.value = [...(subscriptionResponse.data?.topics || [])]
}

async function loadDigest(force = false) {
  const response = await api.newsDaily(24, force, rankingMode.value)
  responseError(response, '资讯摘要加载失败')
  const data = response.data || {}
  available.value = data.available !== false
  articles.value = data.items || []
  generatedAt.value = data.generated_at || ''
}

async function changeRanking(mode) {
  if (!['smart', 'latest'].includes(mode) || rankingMode.value === mode || refreshing.value) return
  rankingMode.value = mode
  refreshing.value = true
  error.value = ''
  try {
    await loadDigest(false)
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    refreshing.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadPreferences()
    await loadDigest(false)
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

async function refresh() {
  refreshing.value = true
  error.value = ''
  try {
    await loadDigest(true)
    message.value = '资讯已刷新'
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    refreshing.value = false
  }
}

function selectAllSources() {
  selectedSources.value = []
}

async function saveSubscriptions() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const response = await api.saveNewsSubscriptions({
      sourceKeys: selectedSources.value,
      topics: selectedTopics.value,
    })
    responseError(response, '资讯订阅保存失败')
    message.value = '资讯订阅已保存'
    await loadDigest(false)
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    saving.value = false
  }
}

function formatTime(value) {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function articleKey(article) {
  return `${article?.source_id || ''}|${article?.url || ''}`
}

function articleAnalysis(article) {
  return article?.aiAnalysis || article?.ai_analysis || null
}

function openMarket(market) {
  // 资讯与行情之间保留可观察的工作流入口；当前上下文仍由多市场页负责解析。
  emit('navigate-module', '多市场')
}

async function analyzeArticles() {
  if (!articles.value.length || analyzing.value) return
  analyzing.value = true
  error.value = ''
  message.value = ''
  try {
    const response = await api.newsAnalyze(articles.value)
    responseError(response, 'RSS AI 分析失败')
    const analyses = response.data?.analyses || []
    const byKey = new Map(analyses.map(item => [item.key, item]))
    articles.value = articles.value.map(article => ({
      ...article,
      aiAnalysis: byKey.get(articleKey(article)) || article.aiAnalysis || article.ai_analysis,
    }))
    message.value = analyses.length ? `已完成 ${analyses.length} 条资讯的 AI 分析` : '模型未返回可用分析'
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    analyzing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="news-center">
    <header class="news-head">
      <div>
        <span class="eyebrow">INFORMATION CENTER / RSS</span>
        <h2>资讯中心</h2>
        <p>聚合财经信息源，按来源与主题订阅；订阅为空时展示全部可用来源。</p>
      </div>
      <div class="head-actions">
        <span class="source-count">{{ sourceCountLabel }}</span>
        <div class="ranking-switch" role="group" aria-label="资讯排序方式">
          <button type="button" :aria-pressed="rankingMode === 'smart'" :class="{ active: rankingMode === 'smart' }" :disabled="refreshing" @click="changeRanking('smart')">智能精选</button>
          <button type="button" :aria-pressed="rankingMode === 'latest'" :class="{ active: rankingMode === 'latest' }" :disabled="refreshing" @click="changeRanking('latest')">最新发布</button>
        </div>
        <button class="action-button" type="button" :disabled="refreshing" @click="refresh">{{ refreshing ? '刷新中…' : '刷新资讯' }}</button>
      </div>
    </header>

    <div v-if="message" class="notice success" role="status">{{ message }}</div>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <DataState v-if="loading" state="loading" title="正在加载资讯中心" />

    <template v-else>
      <section class="subscription-panel panel">
        <div class="panel-title">
          <div><b>我的资讯订阅</b><span>服务端持久化，刷新页面和重新登录后仍保留</span></div>
          <button class="text-button" type="button" @click="selectAllSources">全部来源</button>
        </div>
        <div class="choice-grid source-grid">
          <label class="choice" :class="{ active: isAllSources }">
            <input :checked="isAllSources" type="checkbox" @change="selectAllSources" />
            <span><b>全部来源</b><small>不限制来源</small></span>
          </label>
          <label v-for="source in sources" :key="source.sourceKey" class="choice" :class="{ active: selectedSources.includes(source.sourceKey) }">
            <input v-model="selectedSources" type="checkbox" :value="source.sourceKey" />
            <span><b>{{ source.name }}</b><small>{{ source.category }} · 可信度 {{ source.credibility }}</small></span>
          </label>
        </div>
        <div class="topic-row">
          <span>主题</span>
          <label v-for="topic in topicOptions" :key="topic.key" class="topic-choice">
            <input v-model="selectedTopics" type="checkbox" :value="topic.key" />
            {{ topic.label }}
          </label>
          <span class="topic-hint">不选主题表示不限制主题</span>
        </div>
        <div class="subscription-actions">
          <span>已选 {{ selectedSources.length || '全部' }} 个来源 · {{ selectedTopics.length || '全部' }} 个主题</span>
          <button class="action-button primary" type="button" :disabled="saving" @click="saveSubscriptions">{{ saving ? '保存中…' : '保存订阅' }}</button>
        </div>
      </section>

      <section class="feed-panel panel">
        <div class="panel-title">
          <div><b>每日要闻</b><span>{{ generatedAt ? `更新于 ${formatTime(generatedAt)}` : '等待抓取' }}</span></div>
          <div class="feed-actions">
            <button class="text-button" type="button" :disabled="analyzing || !articles.length" @click="analyzeArticles">{{ analyzing ? 'AI分析中…' : 'AI分析当前资讯' }}</button>
            <span class="feed-status" :class="{ muted: !available }">{{ available ? 'RSS READY' : 'RSS UNAVAILABLE' }}</span>
          </div>
        </div>
        <DataState v-if="!articles.length" state="empty" title="暂无匹配资讯" message="可调整订阅范围或手动刷新。" compact />
        <div v-else class="article-list">
          <article v-for="article in articles" :key="`${article.url}-${article.published}`" class="article-row">
            <div class="article-meta"><span>{{ article.source || article.source_id }}</span><time>{{ formatTime(article.published) }}</time></div>
            <a :href="article.url" target="_blank" rel="noreferrer">{{ article.title_zh || article.title }}</a>
            <div class="article-detail">
              <small v-if="article.title_zh && article.title_zh !== article.title">{{ article.title }}</small>
              <p v-if="article.summary">{{ article.summary }}</p>
              <span v-for="tag in article.tags || []" :key="tag" class="tag">{{ tag }}</span>
              <em v-if="article.analysis?.direction" :class="`impact ${article.analysis.direction}`">{{ article.analysis.direction === 'positive' ? '偏正面' : article.analysis.direction === 'negative' ? '偏负面' : '中性' }}</em>
              <template v-if="articleAnalysis(article)">
                <p class="ai-summary">{{ articleAnalysis(article).summary }}</p>
                <span v-for="keyword in articleAnalysis(article).keywords || []" :key="`ai-${keyword}`" class="tag ai-tag">{{ keyword }}</span>
                <em class="ai-badge">AI · {{ articleAnalysis(article).sentiment || 'neutral' }} · 风险 {{ articleAnalysis(article).risk_level || 'low' }}</em>
                <small v-if="articleAnalysis(article).rationale" class="ai-rationale">依据：{{ articleAnalysis(article).rationale }}</small>
                <button v-for="market in articleAnalysis(article).related_markets || []" :key="`market-${market}`" type="button" class="tag market-tag" @click="openMarket(market)">{{ market }} · 行情</button>
              </template>
            </div>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.news-center { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.news-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; padding: 4px 0 10px; border-bottom: 1px solid var(--line); }
.eyebrow { color: var(--accent-strong); font: 10px/1 ui-monospace, monospace; letter-spacing: .14em; }
.news-head h2 { margin: 8px 0 4px; color: var(--text); font-size: 22px; letter-spacing: .05em; }
.news-head p { margin: 0; color: var(--muted); font-size: 10px; }
.head-actions, .subscription-actions { display: flex; align-items: center; gap: 10px; }
.ranking-switch { display: inline-flex; padding: 2px; border: 1px solid var(--line); border-radius: 8px; background: color-mix(in srgb, var(--surface) 88%, transparent); }
.ranking-switch button { min-height: 27px; padding: 0 9px; border: 0; border-radius: 6px; background: transparent; color: var(--subtle); cursor: pointer; font: 8px ui-monospace, monospace; transition: background .16s ease, color .16s ease, box-shadow .16s ease; }
.ranking-switch button.active { color: var(--text); background: var(--workspace-accent-wash); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 18%, transparent); }
.ranking-switch button:disabled { cursor: wait; opacity: .6; }
.source-count, .feed-status, .topic-hint { color: var(--subtle); font: 9px ui-monospace, monospace; }
.panel { padding: 13px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); }
.panel-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 9px; border-bottom: 1px solid var(--line); }
.panel-title > div { display: flex; flex-direction: column; gap: 4px; }
.feed-actions { display: flex; align-items: center; gap: 9px; }
.panel-title b { color: var(--text); font-size: 12px; }
.panel-title span { color: var(--subtle); font-size: 9px; }
.action-button, .text-button { border: 1px solid var(--line-strong); background: var(--surface); color: var(--text); border-radius: var(--radius-sm); padding: 7px 11px; cursor: pointer; font-size: 9px; }
.action-button.primary { border-color: var(--accent); background: var(--accent); color: #17140e; font-weight: 700; }
.action-button:disabled { opacity: .5; cursor: not-allowed; }
.text-button { padding: 4px 7px; color: var(--accent-strong); }
.notice { padding: 8px 10px; border-radius: var(--radius-sm); font-size: 9px; }
.notice.success { border: 1px solid rgba(39,196,107,.2); color: #67d69a; background: rgba(39,196,107,.07); }
.notice.error { border: 1px solid rgba(239,83,80,.2); color: #e47d79; background: rgba(239,83,80,.07); }
.choice-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; margin-top: 11px; }
.choice { display: flex; align-items: flex-start; gap: 7px; min-width: 0; padding: 8px; border: 1px solid var(--line); border-radius: var(--radius-sm); cursor: pointer; }
.choice.active { border-color: #695b40; background: rgba(201,166,95,.07); }
.choice input, .topic-choice input { margin-top: 2px; accent-color: var(--accent); }
.choice span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.choice b { overflow: hidden; color: var(--text); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.choice small { color: var(--subtle); font-size: 8px; }
.topic-row { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin-top: 12px; color: var(--muted); font-size: 9px; }
.topic-choice { display: inline-flex; align-items: center; gap: 4px; color: var(--muted); cursor: pointer; }
.subscription-actions { justify-content: space-between; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--subtle); font-size: 9px; }
.feed-status { color: var(--ok); }
.feed-status.muted { color: var(--bad); }
.article-list { display: grid; margin-top: 3px; }
.article-row { display: grid; grid-template-columns: 180px minmax(0, 1fr); gap: 7px 14px; align-items: baseline; padding: 12px 3px; border-bottom: 1px solid var(--line); }
.article-meta { display: flex; flex-direction: column; gap: 4px; }
.article-meta span { color: var(--accent-strong); font-size: 9px; }
.article-meta time { color: var(--subtle); font: 8px ui-monospace, monospace; }
.article-row a { color: var(--text); font-size: 11px; line-height: 1.45; text-decoration: none; }
.article-row a:hover { color: var(--accent-strong); }
.article-detail { grid-column: 2; display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.article-detail small { flex-basis: 100%; color: var(--subtle); font-size: 8px; }
.article-detail p { flex-basis: 100%; margin: 0; color: var(--muted); font-size: 9px; line-height: 1.55; }
.article-detail .ai-summary { color: var(--text); border-left: 2px solid var(--accent-strong); padding-left: 7px; }
.ai-tag { color: var(--accent-strong); }
.ai-badge { border-color: rgba(201,166,95,.35); color: var(--accent-strong); }
.ai-rationale { flex-basis: 100%; color: var(--subtle); font-size: 8px; line-height: 1.45; }
.market-tag { color: var(--ok); border-color: rgba(39,196,107,.24); cursor: pointer; }
.tag, .impact { border: 1px solid var(--line-strong); border-radius: 3px; padding: 2px 5px; color: var(--subtle); font-size: 8px; font-style: normal; }
.impact.positive { color: var(--ok); border-color: rgba(39,196,107,.25); }.impact.negative { color: var(--bad); border-color: rgba(239,83,80,.25); }
@media (max-width: 850px) { .choice-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .news-head, .subscription-actions { align-items: flex-start; flex-direction: column; } .feed-actions { align-items: flex-start; flex-direction: column; } .article-row { grid-template-columns: 1fr; gap: 5px; } .article-detail { grid-column: auto; } }
</style>
