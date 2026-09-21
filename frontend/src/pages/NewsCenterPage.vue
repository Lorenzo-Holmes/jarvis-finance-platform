<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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
const qualityMetrics = ref(null)
const filterStats = ref(null)
const returnedCount = ref(0)
const semanticRanking = ref(null)
const expandedReasons = ref([])
const expandedAnalysis = ref([])
const feedQuery = ref('')
const feedDensity = ref('comfortable')
const feedSearchRef = ref(null)
const savedSources = ref([])
const savedTopics = ref([])
const subscriptionOpen = ref(true)
let preferenceRequestSeq = 0
let digestRequestSeq = 0

const topicOptions = [
  { key: 'markets', label: '市场行情' },
  { key: 'global', label: '宏观 / 全球' },
  { key: 'crypto', label: '加密资产' },
  { key: 'gold', label: '黄金 / 大宗' },
  { key: 'policy', label: '政策监管' },
]

const sourceCountLabel = computed(() => `${sources.value.length} 个可用来源`)
const isAllSources = computed(() => selectedSources.value.length === 0)
const stableList = value => [...value].map(String).sort().join('|')
const subscriptionDirty = computed(() => (
  stableList(selectedSources.value) !== stableList(savedSources.value)
  || stableList(selectedTopics.value) !== stableList(savedTopics.value)
))
const filteredArticles = computed(() => {
  const needle = feedQuery.value.trim().toLowerCase()
  if (!needle) return articles.value
  return articles.value.filter(article => [
    article?.title_zh,
    article?.title,
    article?.summary,
    article?.source,
    article?.source_id,
    ...(Array.isArray(article?.tags) ? article.tags : []),
    ...(Array.isArray(article?.selection_reason) ? article.selection_reason : []),
  ].filter(Boolean).join(' ').toLowerCase().includes(needle))
})

function responseError(response, fallback) {
  if (response?.code !== 200) throw new Error(response?.message || fallback)
}

async function loadPreferences() {
  const requestSeq = ++preferenceRequestSeq
  const [sourceResponse, subscriptionResponse] = await Promise.all([
    api.newsSources(),
    api.newsSubscriptions(),
  ])
  if (requestSeq !== preferenceRequestSeq) return
  responseError(sourceResponse, 'RSS 来源加载失败')
  responseError(subscriptionResponse, '资讯订阅加载失败')
  sources.value = sourceResponse.data?.items || []
  selectedSources.value = [...(subscriptionResponse.data?.sourceKeys || [])]
  selectedTopics.value = [...(subscriptionResponse.data?.topics || [])]
  savedSources.value = [...selectedSources.value]
  savedTopics.value = [...selectedTopics.value]
}

async function loadDigest(force = false) {
  const requestSeq = ++digestRequestSeq
  const response = await api.newsDaily(24, force, rankingMode.value)
  if (requestSeq !== digestRequestSeq) return
  responseError(response, '资讯摘要加载失败')
  const data = response.data || {}
  available.value = data.available !== false
  articles.value = data.items || []
  generatedAt.value = data.generated_at || ''
  qualityMetrics.value = data.quality_metrics || null
  filterStats.value = data.filter_stats || null
  returnedCount.value = Number(data.returned_count || articles.value.length || 0)
  semanticRanking.value = data.semantic_ranking || null
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
    savedSources.value = [...selectedSources.value]
    savedTopics.value = [...selectedTopics.value]
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

function articleIdentity(article) {
  return String(article?.event_cluster_id || article?.url || article?.title || '')
}

function reasonOpen(article) {
  return expandedReasons.value.includes(articleIdentity(article))
}

function toggleReason(article) {
  const key = articleIdentity(article)
  expandedReasons.value = reasonOpen(article)
    ? expandedReasons.value.filter(item => item !== key)
    : [...expandedReasons.value, key]
}

function articleScore(article) {
  const value = article?.hybrid_score ?? article?.rank_score
  return Number.isFinite(Number(value)) ? Math.round(Number(value)) : null
}

function analysisOpen(article) {
  return expandedAnalysis.value.includes(articleIdentity(article))
}

function toggleAnalysis(article) {
  const key = articleIdentity(article)
  expandedAnalysis.value = analysisOpen(article)
    ? expandedAnalysis.value.filter(item => item !== key)
    : [...expandedAnalysis.value, key]
}

function analysisHasDetails(article) {
  const analysis = articleAnalysis(article)
  return Boolean(
    (analysis?.keywords || []).length
    || analysis?.rationale
    || (analysis?.related_markets || []).length
  )
}

function handleNewsShortcut(event) {
  const target = event.target
  const tag = String(target?.tagName || '').toLowerCase()
  const editing = ['input', 'textarea', 'select'].includes(tag) || Boolean(target?.isContentEditable)
  if (event.key === '/' && !editing) {
    event.preventDefault()
    feedSearchRef.value?.focus?.()
    return
  }
  if (event.key === 'Escape' && document.activeElement === feedSearchRef.value) {
    feedQuery.value = ''
    feedSearchRef.value?.blur?.()
  }
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

onMounted(() => {
  load()
  window.addEventListener('keydown', handleNewsShortcut)
})
onBeforeUnmount(() => {
  preferenceRequestSeq += 1
  digestRequestSeq += 1
  window.removeEventListener('keydown', handleNewsShortcut)
})
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
        <div class="ranking-switch" role="group" aria-label="资讯排序方式" :style="{ '--ranking-index': rankingMode === 'smart' ? 0 : 1 }">
          <button type="button" :aria-pressed="rankingMode === 'smart'" :class="{ active: rankingMode === 'smart' }" :disabled="refreshing" @click="changeRanking('smart')">智能精选</button>
          <button type="button" :aria-pressed="rankingMode === 'latest'" :class="{ active: rankingMode === 'latest' }" :disabled="refreshing" @click="changeRanking('latest')">最新发布</button>
        </div>
        <button class="action-button refresh-action" :class="{ active: refreshing }" type="button" :disabled="refreshing" @click="refresh"><i class="refresh-glyph"></i>{{ refreshing ? '刷新中…' : '刷新资讯' }}</button>
      </div>
    </header>

    <Transition name="news-toast"><div v-if="message" class="notice success" role="status">{{ message }}</div></Transition>
    <Transition name="news-toast"><div v-if="error" class="notice error" role="alert">{{ error }}</div></Transition>
    <DataState v-if="loading" state="loading" title="正在加载资讯中心" />

    <template v-else>
      <section class="subscription-panel panel">
        <div class="panel-title">
          <div><b>我的资讯订阅</b><span>服务端持久化，刷新页面和重新登录后仍保留</span></div>
          <div class="panel-actions">
            <button class="text-button" type="button" @click="selectAllSources">全部来源</button>
            <button class="text-button disclosure-button" type="button" :aria-expanded="subscriptionOpen" @click="subscriptionOpen = !subscriptionOpen">{{ subscriptionOpen ? '收起配置' : '展开配置' }}</button>
          </div>
        </div>
        <Transition name="subscription-fold">
        <div v-show="subscriptionOpen" class="subscription-body">
        <div class="choice-grid source-grid">
          <label class="choice" :class="{ active: isAllSources }">
            <input :checked="isAllSources" type="checkbox" @change="selectAllSources" />
            <span><b>全部来源</b><small>不限制来源</small></span>
          </label>
          <label v-for="source in sources" :key="source.sourceKey" class="choice" :class="{ active: selectedSources.includes(source.sourceKey) }">
            <input v-model="selectedSources" type="checkbox" :value="source.sourceKey" />
            <span><b>{{ source.name }}</b><small>{{ source.category }} · 可信度 {{ source.credibility }}</small><i class="credibility-meter"><em :style="{ width: `${Math.max(0, Math.min(100, Number(source.credibility) || 0))}%` }"></em></i></span>
          </label>
        </div>
        <div class="topic-row">
          <span>主题</span>
          <label v-for="topic in topicOptions" :key="topic.key" class="topic-choice" :class="{ active: selectedTopics.includes(topic.key) }">
            <input v-model="selectedTopics" type="checkbox" :value="topic.key" />
            <i></i>{{ topic.label }}
          </label>
          <span class="topic-hint">不选主题表示不限制主题</span>
        </div>
        <div class="subscription-actions">
          <span>已选 {{ selectedSources.length || '全部' }} 个来源 · {{ selectedTopics.length || '全部' }} 个主题</span>
          <div class="save-state"><i :class="{ dirty: subscriptionDirty }"></i>{{ subscriptionDirty ? '有未保存更改' : '已同步' }}</div>
          <button class="action-button primary" type="button" :disabled="saving || !subscriptionDirty" @click="saveSubscriptions">{{ saving ? '保存中…' : '保存订阅' }}</button>
        </div>
        </div>
        </Transition>
      </section>

      <section class="feed-panel panel" :class="{ refreshing }">
        <div class="panel-title">
          <div><b>每日要闻</b><span>{{ generatedAt ? `更新于 ${formatTime(generatedAt)}` : '等待抓取' }}</span></div>
          <div class="feed-actions">
            <label class="feed-search">
              <span>检索</span>
              <input ref="feedSearchRef" v-model="feedQuery" type="search" aria-label="筛选当前资讯" aria-keyshortcuts="/" placeholder="标题 / 来源 / 标签" />
              <button v-if="feedQuery" type="button" aria-label="清空资讯筛选" @click="feedQuery = ''">×</button>
            </label>
            <div class="density-switch" role="group" aria-label="资讯阅读密度" :style="{ '--density-index': feedDensity === 'comfortable' ? 0 : 1 }">
              <button type="button" :aria-pressed="feedDensity === 'comfortable'" :class="{ active: feedDensity === 'comfortable' }" @click="feedDensity = 'comfortable'">舒适</button>
              <button type="button" :aria-pressed="feedDensity === 'compact'" :class="{ active: feedDensity === 'compact' }" @click="feedDensity = 'compact'">紧凑</button>
            </div>
            <button class="text-button" type="button" :disabled="analyzing || !articles.length" @click="analyzeArticles">{{ analyzing ? 'AI分析中…' : 'AI分析当前资讯' }}</button>
            <span class="feed-status" :class="{ muted: !available }">{{ available ? 'RSS READY' : 'RSS UNAVAILABLE' }}</span>
          </div>
        </div>
        <div v-if="refreshing && articles.length" class="refresh-indicator" role="status">
          <i></i><span>{{ rankingMode === 'smart' ? '正在重新整理精选…' : '正在更新最新资讯…' }}</span>
        </div>
        <div v-if="qualityMetrics" class="quality-strip" aria-label="资讯质量摘要">
          <div><span>当前结果</span><b>{{ returnedCount }}</b></div>
          <div><span>跨源确认</span><b>{{ qualityMetrics.confirmed_event_count ?? 0 }}</b></div>
          <div><span>重复折叠</span><b>{{ qualityMetrics.duplicate_merge_count ?? 0 }}</b></div>
          <div><span>来源覆盖</span><b>{{ qualityMetrics.source_diversity ?? 0 }}</b></div>
          <small v-if="filterStats">筛选 {{ filterStats.filter_before_count ?? 0 }} → {{ filterStats.filter_after_count ?? 0 }}</small>
          <small v-if="semanticRanking?.stage">{{ semanticRanking.stage }}</small>
        </div>
        <DataState v-if="!articles.length" state="empty" title="暂无匹配资讯" message="可调整订阅范围或手动刷新。" compact />
        <DataState v-else-if="!filteredArticles.length" state="empty" title="当前结果中没有匹配项" message="可清空检索词继续浏览。" compact />
        <div v-else class="article-list" :class="{ compact: feedDensity === 'compact' }">
          <div v-if="feedQuery" class="search-count">显示 {{ filteredArticles.length }} / {{ articles.length }} 条</div>
          <TransitionGroup name="article-flow" tag="div" class="article-flow">
          <article v-for="(article, index) in filteredArticles" :key="`${article.url}-${article.published}`" class="article-row" :style="{ '--article-index': Math.min(index, 8) }">
            <div class="article-meta"><span>{{ article.source || article.source_id }}</span><time>{{ formatTime(article.published) }}</time></div>
            <a :href="article.url" target="_blank" rel="noreferrer">{{ article.title_zh || article.title }}</a>
            <div class="article-detail">
              <small v-if="article.title_zh && article.title_zh !== article.title">{{ article.title }}</small>
              <p v-if="article.summary">{{ article.summary }}</p>
              <span v-for="tag in article.tags || []" :key="tag" class="tag">{{ tag }}</span>
              <em v-if="article.analysis?.direction" :class="`impact ${article.analysis.direction}`">{{ article.analysis.direction === 'positive' ? '偏正面' : article.analysis.direction === 'negative' ? '偏负面' : '中性' }}</em>
              <button
                v-if="(article.selection_reason || []).length || articleScore(article) !== null"
                type="button"
                class="reason-toggle"
                :aria-expanded="reasonOpen(article)"
                @click="toggleReason(article)"
              >{{ reasonOpen(article) ? '收起依据' : '为什么入选' }}</button>
              <div v-if="reasonOpen(article)" class="reason-panel">
                <div class="reason-scores">
                  <span v-if="articleScore(article) !== null">综合 {{ articleScore(article) }}</span>
                  <span v-if="article.semantic_score != null">语义 {{ Math.round(Number(article.semantic_score)) }}</span>
                  <span v-if="article.confirmation_score != null">交叉确认 {{ Math.round(Number(article.confirmation_score)) }}</span>
                  <span v-if="article.source_count > 1">{{ article.source_count }} 个来源</span>
                </div>
                <ul v-if="(article.selection_reason || []).length">
                  <li v-for="reason in article.selection_reason" :key="reason">{{ reason }}</li>
                </ul>
              </div>
              <template v-if="articleAnalysis(article)">
                <p class="ai-summary">{{ articleAnalysis(article).summary }}</p>
                <em class="ai-badge">AI · {{ articleAnalysis(article).sentiment || 'neutral' }} · 风险 {{ articleAnalysis(article).risk_level || 'low' }}</em>
                <button v-if="analysisHasDetails(article)" type="button" class="analysis-toggle" :aria-expanded="analysisOpen(article)" @click="toggleAnalysis(article)">{{ analysisOpen(article) ? '收起分析' : '分析详情' }}</button>
                <div v-if="analysisOpen(article)" class="analysis-details">
                  <div><span v-for="keyword in articleAnalysis(article).keywords || []" :key="`ai-${keyword}`" class="tag ai-tag">{{ keyword }}</span></div>
                  <small v-if="articleAnalysis(article).rationale" class="ai-rationale">依据：{{ articleAnalysis(article).rationale }}</small>
                  <div><button v-for="market in articleAnalysis(article).related_markets || []" :key="`market-${market}`" type="button" class="tag market-tag" @click="openMarket(market)">{{ market }} · 行情</button></div>
                </div>
              </template>
            </div>
          </article>
          </TransitionGroup>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.news-center { --news-motion-press: var(--ds-motion-press, 100ms); --news-motion-state: var(--ds-motion-state, 160ms); --news-motion-surface: var(--ds-motion-surface, 220ms); --news-motion-layout: var(--ds-motion-layout, 340ms); --news-ease: var(--ds-ease, cubic-bezier(.22,1,.36,1)); display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.news-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; padding: 4px 0 10px; border-bottom: 1px solid var(--line); }
.eyebrow { color: var(--accent-strong); font: 10px/1 ui-monospace, monospace; letter-spacing: .14em; }
.news-head h2 { margin: 8px 0 4px; color: var(--text); font-size: 22px; letter-spacing: .05em; }
.news-head p { margin: 0; color: var(--muted); font-size: 10px; }
.head-actions, .subscription-actions { display: flex; align-items: center; gap: 10px; }
.ranking-switch { position: relative; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 2px; border: 1px solid var(--line); border-radius: 8px; background: color-mix(in srgb, var(--surface) 88%, transparent); overflow: hidden; }
.ranking-switch::before { content: ''; position: absolute; z-index: 0; inset: 2px auto 2px 2px; width: calc((100% - 4px) / 2); border: 1px solid color-mix(in srgb, var(--accent) 16%, var(--line-strong)); border-radius: 6px; background: linear-gradient(180deg, color-mix(in srgb, var(--workspace-accent-wash) 80%, transparent), color-mix(in srgb, var(--workspace-accent-wash) 46%, transparent)); transform: translateX(calc(var(--ranking-index) * 100%)); transition: transform var(--news-motion-layout) var(--news-ease); pointer-events: none; }
.ranking-switch button { position: relative; z-index: 1; min-height: 27px; padding: 0 9px; border: 0; border-radius: 6px; background: transparent; color: var(--subtle); cursor: pointer; font: 8px ui-monospace, monospace; transition: color var(--news-motion-state) ease; }
.ranking-switch button.active { color: var(--text); background: transparent; }
.ranking-switch button:disabled { cursor: wait; opacity: .6; }
.source-count, .feed-status, .topic-hint { color: var(--subtle); font: 9px ui-monospace, monospace; }
.panel { padding: 13px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); }
.panel-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 9px; border-bottom: 1px solid var(--line); }
.panel-title > div { display: flex; flex-direction: column; gap: 4px; }
.panel-title .panel-actions { flex-direction: row; align-items: center; gap: 5px; }
.subscription-body { display: grid; max-height: 1200px; overflow: clip; transform-origin: top center; }
.subscription-fold-enter-active, .subscription-fold-leave-active { transition: opacity var(--news-motion-surface) ease, transform var(--news-motion-surface) var(--news-ease), max-height var(--news-motion-layout) var(--news-ease); }
.subscription-fold-enter-from, .subscription-fold-leave-to { opacity: 0; transform: translateY(-4px) scaleY(.985); max-height: 0; }
.disclosure-button { color: var(--muted); }
.feed-actions { display: flex; align-items: center; gap: 9px; }
.feed-search { min-width: 190px; height: 30px; display: flex; align-items: center; gap: 6px; padding: 0 7px; border: 1px solid var(--line); border-radius: 7px; background: color-mix(in srgb, var(--surface) 76%, transparent); transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }.feed-search:focus-within { border-color: color-mix(in srgb, var(--accent) 30%, var(--line-strong)); background: var(--surface); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 7%, transparent); }.feed-search > span { color: var(--subtle); font: 7px ui-monospace, monospace; }.feed-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--text); font-size: 8px; }.feed-search button { border: 0; background: transparent; color: var(--subtle); cursor: pointer; padding: 0 2px; }
.density-switch { position: relative; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 2px; border: 1px solid var(--line); border-radius: 7px; background: color-mix(in srgb, var(--surface) 72%, transparent); overflow: hidden; }.density-switch::before { content: ''; position: absolute; z-index: 0; inset: 2px auto 2px 2px; width: calc((100% - 4px) / 2); border-radius: 5px; background: color-mix(in srgb, var(--workspace-accent-wash) 64%, transparent); transform: translateX(calc(var(--density-index) * 100%)); transition: transform var(--news-motion-layout) var(--news-ease); pointer-events: none; }.density-switch button { position: relative; z-index: 1; min-height: 25px; padding: 0 7px; border: 0; border-radius: 5px; background: transparent; color: var(--subtle); cursor: pointer; font-size: 8px; transition: color var(--news-motion-state) ease; }.density-switch button.active { color: var(--text); background: transparent; }
.panel-title b { color: var(--text); font-size: 12px; }
.panel-title span { color: var(--subtle); font-size: 9px; }
.action-button, .text-button { border: 1px solid var(--line-strong); background: var(--surface); color: var(--text); border-radius: var(--radius-sm); padding: 7px 11px; cursor: pointer; font-size: 9px; }
.refresh-action { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }.refresh-glyph { width: 9px; height: 9px; border: 1px solid currentColor; border-left-color: transparent; border-radius: 50%; }.refresh-action.active .refresh-glyph { animation: news-refresh-spin .8s linear infinite; }
@keyframes news-refresh-spin { to { transform: rotate(360deg); } }
.news-center button:focus-visible { outline: 2px solid color-mix(in srgb, var(--accent) 62%, transparent); outline-offset: 2px; }
.action-button.primary { border-color: var(--accent); background: var(--accent); color: #17140e; font-weight: 700; }
.action-button:disabled { opacity: .5; cursor: not-allowed; }
.text-button { padding: 4px 7px; color: var(--accent-strong); }
.notice { position: fixed; z-index: 90; top: 74px; right: 24px; max-width: min(420px, calc(100vw - 32px)); padding: 10px 13px; border-radius: 9px; background: color-mix(in srgb, var(--surface) 92%, transparent); box-shadow: 0 16px 42px rgba(0,0,0,.16); backdrop-filter: blur(16px); font-size: 9px; }
.notice.success { border: 1px solid rgba(39,196,107,.24); color: #67d69a; }
.notice.error { border: 1px solid rgba(239,83,80,.24); color: #e47d79; }
.news-toast-enter-active, .news-toast-leave-active { transition: opacity var(--news-motion-surface) ease, transform var(--news-motion-surface) var(--news-ease); }
.news-toast-enter-from { opacity: 0; transform: translateY(-7px) scale(.985); }
.news-toast-leave-to { opacity: 0; transform: translateY(-5px) scale(.985); }
.choice-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; margin-top: 11px; }
.choice { display: flex; align-items: flex-start; gap: 7px; min-width: 0; padding: 8px; border: 1px solid var(--line); border-radius: var(--radius-sm); cursor: pointer; }
.choice.active { border-color: #695b40; background: rgba(201,166,95,.07); }
.choice input { margin-top: 2px; accent-color: var(--accent); }
.choice span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.choice b { overflow: hidden; color: var(--text); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.choice small { color: var(--subtle); font-size: 8px; }
.credibility-meter { display: block; width: 100%; height: 3px; margin-top: 2px; border-radius: 999px; overflow: hidden; background: color-mix(in srgb, var(--line) 76%, transparent); }.credibility-meter em { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, color-mix(in srgb, var(--accent) 45%, var(--muted)), var(--accent)); opacity: .65; transition: width .22s cubic-bezier(.22,1,.36,1), opacity .16s ease; }.choice:hover .credibility-meter em, .choice.active .credibility-meter em { opacity: .95; }
.topic-row { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin-top: 12px; color: var(--muted); font-size: 9px; }
.topic-choice { position: relative; display: inline-flex; align-items: center; gap: 6px; min-height: 28px; padding: 0 9px; border: 1px solid var(--line); border-radius: 999px; background: color-mix(in srgb, var(--surface) 76%, transparent); color: var(--muted); cursor: pointer; transition: border-color .16s ease, background .16s ease, color .16s ease, transform .12s ease; }.topic-choice input { position: absolute; opacity: 0; pointer-events: none; }.topic-choice i { width: 6px; height: 6px; border: 1px solid var(--line-strong); border-radius: 50%; background: transparent; transition: background .16s ease, border-color .16s ease, box-shadow .16s ease; }.topic-choice:hover, .topic-choice:focus-within { border-color: var(--line-strong); color: var(--text); }.topic-choice:active { transform: scale(.98); }.topic-choice.active { border-color: color-mix(in srgb, var(--accent) 30%, var(--line-strong)); background: color-mix(in srgb, var(--workspace-accent-wash) 46%, transparent); color: var(--text); }.topic-choice.active i { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 8%, transparent); }
.subscription-actions { justify-content: space-between; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--subtle); font-size: 9px; }
.save-state { margin-left: auto; display: inline-flex; align-items: center; gap: 5px; color: var(--subtle); font: 8px ui-monospace, monospace; }.save-state i { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }.save-state i.dirty { background: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 8%, transparent); }
.feed-status { color: var(--ok); }
.feed-panel { position: relative; }
.feed-panel > .panel-title { position: sticky; z-index: 12; top: 0; margin: -13px -13px 0; padding: 13px 13px 9px; background: color-mix(in srgb, var(--panel) 90%, transparent); backdrop-filter: blur(14px); }
.feed-status.muted { color: var(--bad); }
.refresh-indicator { position: absolute; z-index: 4; top: 53px; right: 13px; display: inline-flex; align-items: center; gap: 6px; padding: 5px 8px; border: 1px solid color-mix(in srgb, var(--accent) 18%, var(--line)); border-radius: 999px; background: color-mix(in srgb, var(--surface) 90%, transparent); color: var(--muted); font: 7px ui-monospace, monospace; backdrop-filter: blur(10px); pointer-events: none; }.refresh-indicator i { width: 6px; height: 6px; border: 1px solid var(--accent); border-top-color: transparent; border-radius: 50%; animation: news-spin .8s linear infinite; }.feed-panel .article-list { transition: opacity .16s ease, filter .16s ease; }.feed-panel.refreshing .article-list { opacity: .72; filter: saturate(.86); }
@keyframes news-spin { to { transform: rotate(360deg); } }
.quality-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)) auto auto; gap: 7px; align-items: center; margin: 10px 0 4px; }
.quality-strip > div { min-width: 0; display: grid; gap: 3px; padding: 8px 9px; border: 1px solid color-mix(in srgb, var(--line) 82%, transparent); border-radius: 7px; background: color-mix(in srgb, var(--surface) 72%, transparent); }
.quality-strip span, .quality-strip small { color: var(--subtle); font: 7px ui-monospace, monospace; }
.quality-strip b { color: var(--text); font: 650 11px ui-monospace, monospace; }
.quality-strip small { white-space: nowrap; }
.article-list { display: grid; margin-top: 3px; }
.article-flow { position: relative; display: grid; }
.article-flow-enter-active, .article-flow-leave-active { transition: opacity var(--news-motion-surface) ease, transform var(--news-motion-surface) var(--news-ease); }
.article-flow-enter-from { opacity: 0; transform: translateY(5px) scale(.995); }
.article-flow-leave-to { opacity: 0; transform: translateY(-3px) scale(.995); }
.article-flow-leave-active { position: absolute; width: 100%; }
.article-flow-move { transition: transform var(--news-motion-layout) var(--news-ease); }
.article-list.compact .article-row { padding-top: 8px; padding-bottom: 8px; gap: 4px 12px; }.article-list.compact .article-detail > p:not(.ai-summary) { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.article-list.compact .article-meta { gap: 2px; }
.search-count { padding: 7px 8px 3px; color: var(--subtle); font: 7px ui-monospace, monospace; }
.article-row { position: relative; display: grid; grid-template-columns: 180px minmax(0, 1fr); gap: 7px 14px; align-items: baseline; padding: 12px 8px; border-bottom: 1px solid var(--line); border-radius: 7px; transition: transform var(--news-motion-surface) var(--news-ease), background var(--news-motion-state) ease, border-color var(--news-motion-state) ease, box-shadow var(--news-motion-surface) ease; }
.article-row { animation: news-article-in var(--news-motion-surface) var(--news-ease) both; animation-delay: calc(var(--article-index, 0) * 18ms); }
@keyframes news-article-in { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
.article-row::before { content: ''; position: absolute; left: 0; top: 10px; bottom: 10px; width: 2px; border-radius: 999px; background: var(--accent); opacity: 0; transform: scaleY(.4); transition: opacity .16s ease, transform .18s cubic-bezier(.22,1,.36,1); }
.article-row:hover, .article-row:focus-within { transform: translateY(-1px); background: color-mix(in srgb, var(--workspace-accent-wash) 18%, transparent); border-color: color-mix(in srgb, var(--line-strong) 78%, var(--accent)); box-shadow: 0 8px 24px rgba(0,0,0,.035); }
.article-row:hover::before, .article-row:focus-within::before { opacity: .75; transform: scaleY(1); }
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
.analysis-toggle { border: 0; background: transparent; color: var(--muted); padding: 2px 0; cursor: pointer; font-size: 8px; }.analysis-toggle:hover { color: var(--text); }.analysis-details { flex-basis: 100%; display: grid; gap: 7px; padding: 8px 0 1px; animation: news-detail-in .18s cubic-bezier(.22,1,.36,1) both; }.analysis-details > div { display: flex; flex-wrap: wrap; gap: 5px; }
@keyframes news-detail-in { from { opacity: 0; transform: translateY(-3px); } to { opacity: 1; transform: translateY(0); } }
.market-tag { color: var(--ok); border-color: rgba(39,196,107,.24); cursor: pointer; }
.reason-toggle { border: 0; background: transparent; color: var(--accent-strong); padding: 2px 0; cursor: pointer; font-size: 8px; }
.reason-panel { flex-basis: 100%; display: grid; gap: 7px; padding: 9px 10px; border: 1px solid color-mix(in srgb, var(--accent) 16%, var(--line)); border-radius: 7px; background: color-mix(in srgb, var(--workspace-accent-wash) 26%, transparent); }
.reason-scores { display: flex; flex-wrap: wrap; gap: 5px; }.reason-scores span { padding: 2px 5px; border-radius: 999px; background: color-mix(in srgb, var(--surface) 72%, transparent); color: var(--muted); font: 7px ui-monospace, monospace; }
.reason-panel ul { display: grid; gap: 4px; margin: 0; padding-left: 15px; color: var(--muted); font-size: 8px; line-height: 1.45; }
.tag, .impact { border: 1px solid var(--line-strong); border-radius: 3px; padding: 2px 5px; color: var(--subtle); font-size: 8px; font-style: normal; }
.impact.positive { color: var(--ok); border-color: rgba(39,196,107,.25); }.impact.negative { color: var(--bad); border-color: rgba(239,83,80,.25); }
@media (max-width: 850px) { .choice-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 850px) { .quality-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }.quality-strip small { white-space: normal; } }
@media (max-width: 600px) { .notice { top: 64px; left: 12px; right: 12px; max-width: none; }.news-head, .subscription-actions { align-items: flex-start; flex-direction: column; }.head-actions, .feed-actions { width: 100%; align-items: stretch; flex-wrap: wrap; }.feed-actions { flex-direction: row; }.feed-search { width: 100%; min-width: 0; box-sizing: border-box; }.ranking-switch, .density-switch { max-width: 100%; overflow-x: auto; }.action-button, .text-button, .ranking-switch button, .density-switch button { min-height: 38px; }.article-row { grid-template-columns: 1fr; gap: 5px; } .article-detail { grid-column: auto; } }
@media (prefers-reduced-motion: reduce) { .news-center, .news-center * { scroll-behavior: auto !important; }.news-center *, .news-center *::before, .news-center *::after { animation-duration: .001ms !important; animation-iteration-count: 1 !important; transition-duration: .001ms !important; } }
</style>
