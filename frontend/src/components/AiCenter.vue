<script setup>
import { computed, ref, onBeforeUnmount, onMounted, nextTick, watch } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import MarkdownContent from './common/MarkdownContent.vue'
import ResearchTasksPanel from './ResearchTasksPanel.vue'
import { extractFinancialDocument } from '../utils/financialDocumentImport'

const props = defineProps({
  researchContext: { type: Object, default: null },
})
const emit = defineEmits(['navigate-module'])

// 研究台有两个视图：对话（无状态、即时）与研究任务（有状态、落库可回看）。
// 任务归档是冻结的 13 个模块之一（本组件就是「研究助手」模块），
// 所以新的研究任务视图挂在这里，而不是新增第 14 个模块。
const deskView = ref('chat')

// ---- 对话 ----
const messages = ref([])
const input = ref('')
const sending = ref(false)
const aiStatus = ref(null)
const statusLoading = ref(true)
const statusError = ref('')
const chatBox = ref(null)
const activeEvidence = ref('market')
const inspectorCollapsed = ref(true)
const sourcePeek = ref(null)
const researchFileInput = ref(null)
const researchAttachments = ref([])
const documentImporting = ref(false)
const documentImportStatus = ref('')
const documentImportError = ref('')
let attachmentSequence = 0
let documentImportController = null
let currentChatAbort = null

// ---- 智能报价 ----
const quoteData = ref(null)
const quoteLoading = ref(false)
const quoteResult = ref('')
const quoteError = ref('')

// ---- 财报解析 ----
const reportText = ref('')
const reportLoading = ref(false)
const reportResult = ref('')
const reportError = ref('')

// ---- 产业链 ----
const chainNode = ref('黄金')
const chainLoading = ref(false)
const chainResult = ref('')
const chainError = ref('')

const contextTarget = computed(() => props.researchContext?.name || props.researchContext?.symbol || '')
const contextSymbol = computed(() => props.researchContext?.symbol || '')
const evidenceCount = computed(() => [
  Boolean(quoteData.value || quoteResult.value),
  Boolean(reportText.value.trim() || reportResult.value),
  Boolean(chainNode.value.trim() || chainResult.value),
].filter(Boolean).length)
const researchDate = computed(() => new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: 'short', day: 'numeric',
}).format(new Date()))
const researchObjective = computed(() => contextTarget.value
  ? `围绕 ${contextTarget.value} 建立可验证的研究判断，并持续检查支持证据、反方证据与风险条件。`
  : '选择研究对象后，在同一工作区内组织假设、证据、反证与风险条件。')
const sourceCatalog = computed(() => ([
  {
    key: 'market', label: '行情', routeKey: '行情', ready: Boolean(quoteData.value || quoteResult.value),
    meta: quoteData.value ? `${contextSymbol.value || '当前标的'} · ${quoteData.value.price}` : '实时市场快照',
    content: quoteResult.value || (quoteData.value
      ? `当前价格 ${quoteData.value.price}，变动 ${quoteData.value.change}（${quoteData.value.change_pct}%），昨收 ${quoteData.value.prev_close}。`
      : '尚未生成行情摘要。可从左侧“行情”证据刷新当前市场快照。'),
  },
  {
    key: 'filing', label: '财报', routeKey: '财报解析', ready: Boolean(reportText.value.trim() || reportResult.value),
    meta: reportText.value.trim() ? `${reportText.value.length} 字符材料` : '原始披露材料',
    content: reportResult.value || reportText.value.trim() || '尚未加入财报材料。可粘贴财报原文或从财务视图继续。',
  },
  {
    key: 'chain', label: '产业链', routeKey: '产业链图谱', ready: Boolean(chainNode.value.trim() || chainResult.value),
    meta: chainNode.value || '产业链研究',
    content: chainResult.value || (chainNode.value ? `当前产业链研究主题：${chainNode.value}。尚未生成分析结果。` : '尚未加入产业链证据。'),
  },
]))
const sugg = computed(() => {
  const target = contextTarget.value
  if (!target) return [
    '当前黄金ETF适合定投吗？',
    '分析一下黄金的产业链逻辑',
    '金价处于什么位置，风险如何？',
  ]
  return [
    `围绕 ${target} 给出研究假设、证据和主要风险。`,
    `分析 ${target} 的产业链位置与关键上下游。`,
    `列出 ${target} 当前最需要验证的三个反方证据。`,
  ]
})

watch(contextTarget, value => {
  if (!value) return
  if (!chainNode.value.trim() || chainNode.value === '黄金') chainNode.value = value
}, { immediate: true })

async function loadStatus() {
  statusLoading.value = true
  statusError.value = ''
  try {
    const d = await api.aiStatus()
    aiStatus.value = d.data
    if (!d.data?.available) statusError.value = d.data?.message || d.message || '研究引擎当前不可用'
  } catch (e) {
    aiStatus.value = { available: false, message: '研究引擎未连接' }
    statusError.value = e?.message || '研究引擎未连接'
  } finally {
    statusLoading.value = false
  }
}

function push(role, content, apiContent = '') {
  messages.value.push({ role, content, apiContent })
}

function chooseResearchFile() {
  documentImportError.value = ''
  researchFileInput.value?.click()
}

async function handleResearchFileChange(event) {
  const file = event.target?.files?.[0]
  if (event.target) event.target.value = ''
  if (!file || documentImporting.value) return

  documentImportController?.abort()
  const controller = new AbortController()
  documentImportController = controller
  documentImporting.value = true
  documentImportStatus.value = `正在读取 ${file.name}`
  documentImportError.value = ''
  try {
    const extracted = await extractFinancialDocument(file, {
      signal: controller.signal,
      onProgress(update) {
        documentImportStatus.value = update.status || `正在读取 ${file.name}`
      },
    })
    if (controller.signal.aborted) return
    const text = String(extracted?.text || '').trim()
    if (!text) throw new Error('没有从文件中提取到可用文字')
    const attachment = {
      id: `research-file-${++attachmentSequence}`,
      name: file.name,
      text,
      warnings: extracted.warnings || [],
      sent: false,
    }
    researchAttachments.value = [...researchAttachments.value, attachment].slice(-4)
    documentImportStatus.value = `已加入 ${file.name} · ${text.length.toLocaleString()} 字符`
    if (!reportText.value.trim()) {
      reportText.value = text
      reportResult.value = ''
      reportError.value = ''
      activeEvidence.value = 'filing'
    }
  } catch (e) {
    if (e?.name !== 'AbortError') documentImportError.value = e?.message || '文件读取失败'
  } finally {
    if (documentImportController === controller) documentImportController = null
    if (!controller.signal.aborted) documentImporting.value = false
  }
}

function removeResearchAttachment(id) {
  researchAttachments.value = researchAttachments.value.filter(item => item.id !== id)
}

function buildAttachmentContext(question) {
  const pending = researchAttachments.value.filter(item => !item.sent)
  if (!pending.length) return { content: '', attachedIds: [] }
  const available = Math.max(0, 9_200 - question.length)
  if (available < 300) return { content: '', attachedIds: [] }

  let remaining = available
  const blocks = []
  const attachedIds = []
  for (const item of pending) {
    const heading = `【上传文件：${item.name}】\n`
    if (remaining <= heading.length + 80) break
    const bodyLimit = Math.min(item.text.length, remaining - heading.length)
    const body = item.text.slice(0, bodyLimit)
    blocks.push(`${heading}${body}${bodyLimit < item.text.length ? '\n[文件内容已按对话长度限制截断]' : ''}`)
    attachedIds.push(item.id)
    remaining -= heading.length + body.length + 20
  }
  return { content: blocks.join('\n\n'), attachedIds }
}

async function scrollChatToBottom() {
  await nextTick()
  if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
}

function stopChat() {
  currentChatAbort?.abort()
}

async function sendChat() {
  const text = input.value.trim()
  if (!text || sending.value) return
  const attachmentContext = buildAttachmentContext(text)
  const apiContent = attachmentContext.content
    ? `${text}\n\n以下是用户在本轮明确上传并要求纳入研究的材料：\n${attachmentContext.content}`
    : text
  push('user', text, apiContent)
  if (attachmentContext.attachedIds.length) {
    const sent = new Set(attachmentContext.attachedIds)
    researchAttachments.value = researchAttachments.value.map(item => sent.has(item.id) ? { ...item, sent: true } : item)
  }
  input.value = ''
  // 不把前端欢迎语发给模型；保留最近 20 条真实 user/assistant 上下文。
  const history = messages.value.slice(1).slice(-20).map(({ role, content, apiContent: hiddenContent }) => ({
    role,
    content: hiddenContent || content,
  }))
  push('assistant', '')
  const assistantIndex = messages.value.length - 1
  sending.value = true
  let streamFinished = false
  currentChatAbort = new AbortController()
  await scrollChatToBottom()
  try {
    await api.aiChatStream(history, ({ event, data }) => {
      if (event === 'delta' && data?.content) {
        messages.value[assistantIndex].content += data.content
        scrollChatToBottom()
      } else if (event === 'done') {
        // 部分代理在 SSE 已发送 done 后关闭 chunked 连接时，浏览器仍会
        // 抛出一次 network error。done 已确认模型输出完整，不能再把它
        // 呈现为失败。
        streamFinished = true
      } else if (event === 'error') {
        throw new Error(data?.message || 'AI 流式响应中断')
      }
    }, currentChatAbort.signal)
    if (!messages.value[assistantIndex].content) {
      messages.value[assistantIndex].content = '（无回复）'
    }
  } catch (e) {
    if (e?.name === 'AbortError') {
      if (!messages.value[assistantIndex].content) messages.value[assistantIndex].content = '（已停止）'
    } else if (streamFinished) {
      // 兼容 Cloudflare/Nginx 在 SSE 正常结束后的连接收尾异常。
      if (!messages.value[assistantIndex].content) messages.value[assistantIndex].content = '（无回复）'
    } else {
      const prefix = messages.value[assistantIndex].content ? '\n\n' : ''
      messages.value[assistantIndex].content += `${prefix}⚠️ ${e?.message || e}`
    }
  } finally {
    currentChatAbort = null
    sending.value = false
    scrollChatToBottom()
  }
}

function useSuggestion(s) { input.value = s }

function appendReference(label) {
  const token = `@${label}`
  input.value = input.value.trim() ? `${input.value.trim()} ${token} ` : `${token} `
}

function onComposerKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey) return
  event.preventDefault()
  sendChat()
}

function openWorkspace(routeKey) {
  emit('navigate-module', routeKey)
}

function openSourcePeek(key) {
  sourcePeek.value = sourceCatalog.value.find(source => source.key === key) || null
}

function closeSourcePeek() {
  sourcePeek.value = null
}

function onResearchKeydown(event) {
  if (event.key !== 'Escape' || !sourcePeek.value) return
  event.preventDefault()
  closeSourcePeek()
}

function openPeekWorkspace() {
  if (!sourcePeek.value?.routeKey) return
  const routeKey = sourcePeek.value.routeKey
  closeSourcePeek()
  openWorkspace(routeKey)
}

function useResearchContext() {
  if (!contextTarget.value) return
  input.value = `围绕 ${contextTarget.value}，按 THESIS / EVIDENCE / COUNTER EVIDENCE / RISK / CONCLUSION 结构进行研究。`
}

// 智能报价解读
async function runQuote() {
  quoteLoading.value = true
  quoteResult.value = ''
  quoteError.value = ''
  try {
    const q = await api.marketPrices()
    const rt = q.data?.gold_etf
    if (!rt) throw new Error('未获取到黄金ETF行情')
    quoteData.value = rt
    const d = await api.aiQuote(rt)
    quoteResult.value = d.data?.content || '（暂无研究结论）'
  } catch (e) {
    quoteError.value = e?.message || String(e)
  } finally {
    quoteLoading.value = false
  }
}

// 财报解析
async function runReport() {
  if (!reportText.value.trim()) return
  reportLoading.value = true
  reportResult.value = ''
  reportError.value = ''
  try {
    const d = await api.aiFinancialReport(reportText.value.trim())
    reportResult.value = d.data?.content || '（暂无研究结论）'
  } catch (e) {
    reportError.value = e?.message || String(e)
  } finally {
    reportLoading.value = false
  }
}

// 产业链
async function runChain() {
  if (!chainNode.value.trim()) return
  chainLoading.value = true
  chainResult.value = ''
  chainError.value = ''
  try {
    const d = await api.aiChain(chainNode.value.trim())
    chainResult.value = d.data?.content || '（暂无研究结论）'
  } catch (e) {
    chainError.value = e?.message || String(e)
  } finally {
    chainLoading.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', onResearchKeydown)
  loadStatus()
  messages.value.push({
    role: 'assistant',
    content: contextTarget.value
      ? `已建立 ${contextTarget.value} 的研究工作区。可以从左侧加入证据，或直接提出需要验证的研究问题。`
      : '研究工作区已就绪。可以从左侧加入行情、财报或产业链证据，再开始建立研究论证。',
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onResearchKeydown)
  documentImportController?.abort()
  currentChatAbort?.abort()
})
</script>

<template>
  <div class="ai research-workspace">
    <header class="research-head">
      <div>
        <h2>{{ contextTarget || '研究助手' }}</h2>
        <span>{{ contextSymbol ? `${contextSymbol} · ` : '' }}{{ evidenceCount }} 个证据源 · 连续研究记录</span>
      </div>
      <div class="research-head-actions">
        <button v-if="props.researchContext" type="button" class="context-action" @click="useResearchContext">引用当前对象</button>
        <span class="engine-status" :title="statusError || '研究引擎状态'">
          <i :class="aiStatus?.available ? 'ok' : 'bad'"></i>
          {{ statusLoading ? '检查研究服务' : (aiStatus?.available ? '研究服务可用' : '研究服务暂不可用') }}
        </span>
      </div>
    </header>

    <div class="desk-switch" role="tablist" aria-label="研究台视图">
      <button
        type="button"
        role="tab"
        :aria-selected="deskView === 'chat'"
        :class="{ on: deskView === 'chat' }"
        data-testid="research-desk-switch-chat"
        @click="deskView = 'chat'"
      >研究对话</button>
      <button
        type="button"
        role="tab"
        :aria-selected="deskView === 'tasks'"
        :class="{ on: deskView === 'tasks' }"
        data-testid="research-desk-switch-tasks"
        @click="deskView = 'tasks'"
      >研究任务</button>
    </div>

    <div v-if="deskView === 'tasks'" class="desk-tasks">
      <ResearchTasksPanel />
    </div>

    <div v-else class="research-layout" :class="{ 'inspector-collapsed': inspectorCollapsed }">
      <aside class="evidence-dock" aria-label="研究证据">
        <div class="dock-head">
          <div><b>证据</b></div>
          <small>{{ evidenceCount }}/3</small>
        </div>

        <button type="button" class="evidence-row" :class="{ active: activeEvidence === 'market' }" @click="activeEvidence = 'market'">
          <i :class="{ ready: quoteData || quoteResult }"></i>
          <span><b>行情</b><small>{{ quoteData ? `${quoteData.price} · ${quoteData.change_pct}%` : '获取实时行情与摘要' }}</small></span>
          <em>→</em>
        </button>
        <button type="button" class="evidence-row" :class="{ active: activeEvidence === 'filing' }" @click="activeEvidence = 'filing'">
          <i :class="{ ready: reportText.trim() || reportResult }"></i>
          <span><b>财报</b><small>{{ reportText.trim() ? `${reportText.length} 字符已加入` : '粘贴财报或关键数据' }}</small></span>
          <em>→</em>
        </button>
        <button type="button" class="evidence-row" :class="{ active: activeEvidence === 'chain' }" @click="activeEvidence = 'chain'">
          <i :class="{ ready: chainNode.trim() || chainResult }"></i>
          <span><b>产业链</b><small>{{ chainNode || '添加产业链研究主题' }}</small></span>
          <em>→</em>
        </button>

        <section class="evidence-detail">
          <template v-if="activeEvidence === 'market'">
            <div class="evidence-detail-head"><b>行情证据</b><button type="button" class="text-action" @click="runQuote" :disabled="quoteLoading">{{ quoteLoading ? '分析中' : '刷新摘要' }}</button></div>
            <div v-if="quoteData" class="quote-snapshot">
              <b>{{ quoteData.price }}</b>
              <span :class="quoteData.change >= 0 ? 'pos' : 'neg'">{{ quoteData.change }} · {{ quoteData.change_pct }}%</span>
              <small>昨收 {{ quoteData.prev_close }}</small>
            </div>
            <DataState v-if="quoteLoading" state="loading" title="正在生成行情摘要" compact />
            <DataState v-else-if="quoteError" state="error" title="行情研究失败" :message="quoteError" compact retryable @retry="runQuote" />
            <MarkdownContent v-else-if="quoteResult" class="tool-output" :content="quoteResult" />
            <p v-else class="tool-empty">将当前市场快照加入研究记录，适合验证价格结构和短期风险。</p>
          </template>

          <template v-else-if="activeEvidence === 'filing'">
            <div class="evidence-detail-head"><b>财报证据</b><button type="button" class="text-action" @click="runReport" :disabled="reportLoading || !reportText.trim()">{{ reportLoading ? '解析中' : '解析' }}</button></div>
            <textarea v-model="reportText" aria-label="财报文本" placeholder="粘贴财报内容、业绩摘要或关键数据…" rows="7"></textarea>
            <DataState v-if="reportLoading" state="loading" title="正在解析财报" compact />
            <DataState v-else-if="reportError" state="error" title="财报解析失败" :message="reportError" compact retryable @retry="runReport" />
            <MarkdownContent v-else-if="reportResult" class="tool-output" :content="reportResult" />
          </template>

          <template v-else>
            <div class="evidence-detail-head"><b>产业链证据</b><button type="button" class="text-action" @click="runChain" :disabled="chainLoading || !chainNode.trim()">{{ chainLoading ? '分析中' : '分析' }}</button></div>
            <input v-model="chainNode" class="input" aria-label="产业链主题" placeholder="黄金 / 白酒 / 芯片…" />
            <DataState v-if="chainLoading" state="loading" title="正在分析产业链" compact />
            <DataState v-else-if="chainError" state="error" title="产业链分析失败" :message="chainError" compact retryable @retry="runChain" />
            <MarkdownContent v-else-if="chainResult" class="tool-output" :content="chainResult" />
            <p v-else class="tool-empty">补充产业位置、关键上下游与供需逻辑，用于验证基本面假设。</p>
          </template>
        </section>
      </aside>

      <main class="research-canvas">
        <article class="research-document">
          <header class="document-masthead">
            <div>
              <h3>{{ contextTarget || '新建研究' }}</h3>
              <small>{{ contextSymbol || '未选择证券' }} · {{ researchDate }}</small>
            </div>
            <em>{{ messages.length }} 条研究记录</em>
          </header>

          <section class="research-objective">
            <span>研究目标</span>
            <p>{{ researchObjective }}</p>
          </section>

          <section class="research-checklist">
            <span>验证清单</span>
            <ol>
              <li><b>01</b><p>验证价格与市场行为是否支持当前研究假设。</p></li>
              <li><b>02</b><p>核对披露材料与财务证据，区分事实、解释与待验证项。</p></li>
              <li><b>03</b><p>主动寻找产业链、风险与反方证据，记录可能的失效条件。</p></li>
            </ol>
          </section>

          <section class="document-sources">
            <span>研究证据</span>
            <div>
              <button
                v-for="source in sourceCatalog"
                :key="source.key"
                type="button"
                :class="{ ready: source.ready }"
                @click="openSourcePeek(source.key)"
              >
                <i></i>
                <span><b>{{ source.label }}</b><small>{{ source.meta }}</small></span>
                <em>查看</em>
              </button>
            </div>
          </section>
        </article>

        <div class="prompt-templates">
          <span>建议起点</span>
          <button v-for="s in sugg" :key="s" type="button" @click="useSuggestion(s)">{{ s }}</button>
        </div>

        <div class="conversation-label"><span>JARVIS 注释</span><small>基于当前研究上下文持续追加</small></div>
        <div class="chat-window" ref="chatBox" role="log" aria-live="polite" aria-relevant="additions text" aria-label="研究会话记录">
          <div v-for="(m, i) in messages" :key="i" class="message-row" :class="m.role">
            <div class="message-meta"><span>{{ m.role === 'user' ? '问题' : 'JARVIS' }}</span><i></i></div>
            <MarkdownContent v-if="m.role === 'assistant'" class="message-content" :content="m.content" />
            <div v-else class="message-content">{{ m.content }}</div>
          </div>
        </div>

        <input
          ref="researchFileInput"
          class="research-file-input"
          type="file"
          accept=".pdf,.docx,.md,.markdown,.txt,.png,.jpg,.jpeg,.webp,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/markdown,text/plain,image/png,image/jpeg,image/webp"
          aria-label="上传研究文件"
          @change="handleResearchFileChange"
        />
        <div v-if="researchAttachments.length || documentImporting || documentImportStatus || documentImportError" class="research-attachments" aria-live="polite">
          <div v-for="item in researchAttachments" :key="item.id" class="research-attachment-chip">
            <span><b>{{ item.name }}</b><small>{{ item.sent ? '已引用到对话' : '待引用' }} · {{ item.text.length.toLocaleString() }} 字符</small></span>
            <button type="button" :aria-label="`移除 ${item.name}`" @click="removeResearchAttachment(item.id)">×</button>
          </div>
          <span v-if="documentImporting" class="attachment-status">{{ documentImportStatus || '正在读取文件…' }}</span>
          <span v-else-if="documentImportError" class="attachment-status error">{{ documentImportError }}</span>
          <span v-else-if="documentImportStatus" class="attachment-status">{{ documentImportStatus }}</span>
        </div>

        <div class="composer-shell">
          <textarea
            v-model="input"
            rows="2"
            aria-label="研究问题"
            placeholder="向 JARVIS 提问，或使用 @行情 / @财报 / @产业链 引用证据…"
            :disabled="sending"
            @keydown="onComposerKeydown"
          ></textarea>
          <div class="composer-tools">
            <div>
              <button type="button" :disabled="documentImporting" @click="chooseResearchFile">{{ documentImporting ? '读取中…' : '上传文件' }}</button>
              <button type="button" @click="appendReference('行情')">@ 行情</button>
              <button type="button" @click="appendReference('财报')">@ 财报</button>
              <button type="button" @click="appendReference('产业链')">@ 产业链</button>
            </div>
            <button type="button" class="send-btn" @click="sending ? stopChat() : sendChat()" :class="{ stop: sending }" :aria-label="sending ? '停止生成' : '提交研究问题'">
              {{ sending ? '停止' : '发送' }}
            </button>
          </div>
        </div>
        <div class="research-disclaimer">Enter 发送，Shift + Enter 换行。生成内容用于研究辅助，请结合原始数据独立判断。</div>
      </main>

      <aside class="context-inspector" aria-label="研究上下文">
        <header>
          <div><b>上下文</b></div>
          <button type="button" aria-label="折叠上下文" @click="inspectorCollapsed = true">×</button>
        </header>
        <template v-if="props.researchContext">
          <section class="inspector-target">
            <span>当前研究对象</span>
            <strong>{{ props.researchContext.name || props.researchContext.symbol }}</strong>
            <small>{{ props.researchContext.symbol }} · {{ props.researchContext.market || '研究上下文' }}</small>
          </section>
          <section class="inspector-stats">
            <div><span>证据源</span><b>{{ evidenceCount }}</b></div>
            <div><span>研究记录</span><b>{{ messages.length }}</b></div>
            <div><span>来源模块</span><b>{{ props.researchContext.sourceModule || '—' }}</b></div>
          </section>
          <section class="inspector-links">
            <span>相关工作区</span>
            <button type="button" @click="openWorkspace('行情')">行情 <i>→</i></button>
            <button type="button" @click="openWorkspace('财报解析')">财报解析 <i>→</i></button>
            <button type="button" @click="openWorkspace('产业链图谱')">产业链 <i>→</i></button>
            <button type="button" @click="openWorkspace('风险预警')">风险 <i>→</i></button>
          </section>
        </template>
        <div v-else class="inspector-empty">
          <b>尚未选择研究对象</b>
          <span>从行情页选择标的后，会在不同研究页面间自动复用。</span>
          <button type="button" @click="openWorkspace('行情')">前往行情</button>
        </div>
      </aside>

      <button v-if="inspectorCollapsed" type="button" class="inspector-restore" @click="inspectorCollapsed = false">上下文</button>

      <aside v-if="sourcePeek" class="source-peek" aria-label="证据快速查看">
        <header>
          <div><span>证据</span><b>{{ sourcePeek.label }}</b></div>
          <button type="button" aria-label="关闭证据预览" @click="closeSourcePeek">×</button>
        </header>
        <div class="source-peek-meta">
          <span>{{ sourcePeek.meta }}</span>
          <i :class="{ ready: sourcePeek.ready }"></i>
        </div>
        <div class="source-peek-body">
          <MarkdownContent v-if="sourcePeek.ready && sourcePeek.content" :content="sourcePeek.content" />
          <p v-else>{{ sourcePeek.content }}</p>
        </div>
        <footer>
          <button type="button" @click="openPeekWorkspace">打开完整{{ sourcePeek.label }}视图 <span>→</span></button>
        </footer>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.research-workspace { display: flex; flex-direction: column; gap: 12px; height: calc(100dvh - 140px); min-height: 560px; overflow: hidden; }
.research-head {
  min-height: 52px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 0 2px 10px;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 48%, transparent);
}
.research-head h2 { margin: 0; color: var(--text); font-size: 21px; line-height: 1; font-weight: 650; letter-spacing: -.022em; }
.research-head > div:first-child > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 11px; }
.research-head-actions { display: flex; align-items: center; gap: 10px; }
.context-action { min-height: 31px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: rgba(255,255,255,.018); color: var(--muted); padding: 0 10px; cursor: pointer; font-size: 10px; transition: color .16s ease, background .16s ease, transform .1s ease; }
.context-action:hover { color: var(--text); background: var(--workspace-hover-bg); }
.context-action:active { transform: scale(.97); }
.engine-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.desk-switch {
  align-self: flex-start;
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--material-border, var(--line));
  border-radius: 9px;
  background: color-mix(in srgb, var(--material-glass, transparent) 62%, transparent);
}
.desk-switch button {
  min-height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font: 600 9px/1 Inter, "MiSans", "PingFang SC", sans-serif;
  transition: color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease;
}
.desk-switch button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.desk-switch button:active { transform: scale(.97); }
.desk-switch button.on { color: var(--text); background: color-mix(in srgb, var(--workspace-accent-wash) 68%, transparent); }
.desk-tasks { min-height: 0; flex: 1; overflow: auto; }
.engine-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.engine-status i.ok { background: var(--ok); }

.research-layout {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 208px;
  gap: 12px;
  border: 0;
  border-radius: 0;
  overflow: hidden;
  background: transparent;
  box-shadow: none;
}
.research-layout.inspector-collapsed { grid-template-columns: 200px minmax(0, 1fr); }
.research-layout.inspector-collapsed .context-inspector { display: none; }

.evidence-dock {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--material-border, var(--line));
  border-radius: 10px;
  background: color-mix(in srgb, var(--material-glass, transparent) 68%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.022);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}
.dock-head,
.context-inspector > header {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 13px;
  border-bottom: 1px solid var(--line);
}
.dock-head > div,
.context-inspector > header > div { display: flex; align-items: baseline; gap: 6px; }
.dock-head b,
.context-inspector header b { color: var(--text); font-size: 12px; font-weight: 650; }
.dock-head span,
.context-inspector header span,
.dock-head small { color: var(--subtle); font-size: 8px; }
.context-inspector header button { border: 0; background: transparent; color: var(--subtle); cursor: pointer; font-size: 15px; }

.evidence-row {
  width: calc(100% - 12px);
  min-height: 61px;
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  margin: 2px 6px;
  padding: 8px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  text-align: left;
  cursor: pointer;
  transition: color var(--motion-standard, 190ms) ease, background var(--motion-standard, 190ms) ease, transform var(--motion-fast, 110ms) ease;
}
.evidence-row:hover,
.evidence-row.active { color: var(--text); background: rgba(255,255,255,.035); }
.evidence-row:active { transform: scale(.99); }
.evidence-row > i { width: 6px; height: 6px; border: 1px solid var(--line-strong); border-radius: 50%; }
.evidence-row > i.ready { border-color: var(--ok); background: var(--ok); }
.evidence-row > span { min-width: 0; display: grid; gap: 5px; }
.evidence-row b { color: inherit; font-size: 11px; font-weight: 620; }
.evidence-row small { color: var(--subtle); font-size: 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evidence-row em { color: var(--subtle); font-size: 11px; font-style: normal; }
.evidence-detail { flex: 1; min-height: 0; padding: 13px; overflow: auto; border-top: 1px solid color-mix(in srgb, var(--line) 58%, transparent); }
.evidence-detail-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.evidence-detail-head b { color: var(--text); font-size: 10px; font-weight: 650; }
.text-action { border: 0; background: transparent; color: var(--accent-strong); padding: 2px 0; font-size: 9px; cursor: pointer; }
.text-action:disabled { opacity: .42; cursor: not-allowed; }
.quote-snapshot { display: grid; grid-template-columns: 1fr auto; gap: 4px 8px; align-items: baseline; margin-top: 12px; padding: 11px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.quote-snapshot > b { color: var(--text); font-size: 20px; line-height: 1; font-weight: 680; font-variant-numeric: tabular-nums; }
.quote-snapshot > span { font-size: 9px; font-variant-numeric: tabular-nums; }
.quote-snapshot small { grid-column: 1 / -1; color: var(--subtle); font-size: 8px; }
.input,
.evidence-detail textarea { width: 100%; background: var(--workspace-control-bg); border: 1px solid var(--line); color: var(--text); outline: none; font-size: 10px; }
.input { height: 34px; padding: 0 8px; margin-top: 10px; }
.evidence-detail textarea { margin-top: 10px; padding: 9px; resize: vertical; line-height: 1.55; }
.input:focus,
.evidence-detail textarea:focus { border-color: var(--workspace-focus); }
.evidence-detail :deep(.data-state) { margin-top: 10px; }
.tool-output { margin-top: 10px; max-height: 260px; overflow: auto; background: var(--surface); padding: 9px 10px; color: var(--text); font-size: 9px; line-height: 1.65; }
.tool-empty { margin: 12px 0 0; color: var(--subtle); font-size: 9px; line-height: 1.6; }

.research-canvas {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 6px 18px 12px;
  background: transparent;
  overflow: hidden;
}
.research-document { flex: 0 0 auto; padding: 3px 4px 4px; }
.document-masthead { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 4px 4px 18px; }
.document-masthead > div { min-width: 0; }
.document-masthead span { color: var(--subtle); font-size: 9px; letter-spacing: .04em; }
.document-masthead h3 { margin: 7px 0 6px; color: var(--text); font-size: 25px; line-height: 1.05; font-weight: 640; letter-spacing: -.028em; }
.document-masthead small { color: var(--muted); font: 500 9px/1 ui-monospace, monospace; }
.document-masthead em { flex: 0 0 auto; color: var(--subtle); font-size: 9px; font-style: normal; }
.research-objective { padding: 8px 4px 16px; }
.research-objective > span,
.research-checklist > span,
.document-sources > span { color: var(--subtle); font-size: 9px; letter-spacing: .03em; }
.research-objective p { margin: 7px 0 0; max-width: 820px; color: var(--text); font-size: 12px; line-height: 1.72; }
.research-checklist { padding: 8px 4px 16px; }
.research-checklist ol { list-style: none; display: grid; gap: 0; margin: 7px 0 0; padding: 0; }
.research-checklist li { display: grid; grid-template-columns: 34px minmax(0, 1fr); gap: 8px; padding: 6px 0; }
.research-checklist b { color: var(--accent-strong); font: 600 8px/1.7 ui-monospace, monospace; }
.research-checklist p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.65; }
.document-sources { padding: 13px 4px 5px; }
.document-sources > div { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; margin-top: 8px; }
.document-sources button {
  min-width: 0;
  min-height: 55px;
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: rgba(255,255,255,.022);
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}
.document-sources button:hover { color: var(--text); border-color: var(--material-border, var(--line)); background: rgba(255,255,255,.04); }
.document-sources button > i { width: 6px; height: 6px; border: 1px solid var(--line-strong); border-radius: 50%; }
.document-sources button.ready > i { border-color: var(--ok); background: var(--ok); }
.document-sources button > span { min-width: 0; display: grid; gap: 4px; }
.document-sources b { color: inherit; font-size: 9px; }
.document-sources small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--subtle); font-size: 7px; }
.document-sources em { color: var(--subtle); font-size: 7px; font-style: normal; white-space: nowrap; }
.prompt-templates { display: flex; align-items: center; gap: 6px; padding: 10px 0; overflow-x: auto; scrollbar-width: none; }
.prompt-templates::-webkit-scrollbar { display: none; }
.prompt-templates > span { color: var(--subtle); font-size: 8px; white-space: nowrap; margin-right: 2px; }
.prompt-templates button { flex: 0 0 auto; border: 1px solid transparent; border-radius: 8px; background: rgba(255,255,255,.025); color: var(--muted); padding: 6px 8px; font-size: 9px; cursor: pointer; }
.prompt-templates button:hover { color: var(--text); background: rgba(255,255,255,.045); }
.conversation-label { display: flex; align-items: baseline; gap: 8px; padding: 13px 2px 8px; }
.conversation-label span { color: var(--text); font-size: 10px; font-weight: 630; }
.conversation-label small { color: var(--subtle); font-size: 8px; }
.chat-window { flex: 1; min-height: 130px; max-height: none; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.message-row { display: grid; grid-template-columns: 82px minmax(0, 1fr); gap: 15px; margin: 2px 0; padding: 14px 10px; border-radius: 8px; }
.message-row:last-child { border-bottom: 0; }
.message-row.user { background: color-mix(in srgb, var(--workspace-accent-wash) 42%, transparent); }
.message-meta { display: flex; align-items: flex-start; gap: 6px; color: var(--subtle); font-size: 8px; }
.message-meta i { width: 4px; height: 4px; margin-top: 4px; border-radius: 50%; background: var(--workspace-neutral-dot); }
.message-row.assistant .message-meta i { background: var(--accent); }
.message-content { color: var(--text); font-size: 12px; line-height: 1.72; overflow-wrap: anywhere; }

.research-file-input { display: none; }
.research-attachments { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; margin-top: 8px; padding: 0 2px; overflow-x: auto; scrollbar-width: thin; }
.research-attachment-chip { flex: 0 0 auto; min-width: 0; max-width: 300px; display: flex; align-items: center; gap: 7px; padding: 6px 7px 6px 9px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: color-mix(in srgb, var(--workspace-accent-wash) 45%, transparent); }
.research-attachment-chip > span { min-width: 0; display: grid; gap: 2px; }
.research-attachment-chip b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); font-size: 8.5px; font-weight: 620; }
.research-attachment-chip small { color: var(--subtle); font-size: 7.5px; }
.research-attachment-chip button { border: 0; background: transparent; color: var(--subtle); cursor: pointer; font-size: 13px; }
.attachment-status { flex: 0 0 auto; color: var(--subtle); font-size: 8px; }
.attachment-status.error { color: var(--bad); }

.composer-shell {
  flex: 0 0 auto;
  margin-top: 12px;
  border: 1px solid var(--material-border, var(--line-strong));
  border-radius: 10px;
  overflow: hidden;
  background: var(--material-glass, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.025), var(--material-shadow, none);
  backdrop-filter: blur(var(--material-blur, 18px));
  -webkit-backdrop-filter: blur(var(--material-blur, 18px));
  transition: border-color var(--motion-standard, 190ms) ease, box-shadow var(--motion-standard, 190ms) ease;
}
.composer-shell:focus-within { border-color: color-mix(in srgb, var(--accent) 56%, var(--line-strong)); }
.composer-shell > textarea { width: 100%; min-height: 62px; resize: none; border: 0; outline: 0; background: transparent; color: var(--text); padding: 11px 12px 6px; font-size: 11px; line-height: 1.55; }
.composer-tools { min-height: 35px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 4px 6px 6px 9px; }
.composer-tools > div { display: flex; align-items: center; gap: 3px; }
.composer-tools button:not(.send-btn) { border: 0; background: transparent; color: var(--subtle); padding: 5px 6px; cursor: pointer; font-size: 8px; }
.composer-tools button:not(.send-btn) { border-radius: 7px; transition: color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.composer-tools button:not(.send-btn):hover:not(:disabled) { color: var(--text); background: rgba(255,255,255,.045); }
.composer-tools button:not(.send-btn):disabled { opacity: .42; cursor: default; }
.composer-tools button:not(.send-btn):active { transform: scale(.96); }
.send-btn { min-width: 74px; min-height: 29px; border: 1px solid var(--workspace-action-border); border-radius: 8px; background: transparent; color: var(--workspace-action-text); padding: 0 10px; cursor: pointer; font-size: 9px; font-weight: 650; transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease; }
.send-btn:hover:not(:disabled) { background: rgba(255,255,255,.045); }
.send-btn:active:not(:disabled) { transform: scale(.97); }
.send-btn.stop { border-color: #684043; background: rgba(239,83,80,.08); color: #e47d79; }
.research-disclaimer { margin-top: 6px; color: var(--subtle); font-size: 8px; line-height: 1.5; }

.context-inspector {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: auto;
  border: 1px solid var(--material-border, var(--line));
  border-radius: 10px;
  background: color-mix(in srgb, var(--material-glass, transparent) 68%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.022);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}
.inspector-target { display: grid; gap: 6px; padding: 14px; border-bottom: 1px solid var(--line); }
.inspector-target span,
.inspector-links > span { color: var(--subtle); font-size: 8px; }
.inspector-target strong { color: var(--text); font-size: 12px; font-weight: 650; }
.inspector-target small { color: var(--muted); font: 500 8px/1.4 ui-monospace, monospace; }
.inspector-stats { padding: 6px 14px; border-bottom: 1px solid var(--line); }
.inspector-stats > div { min-height: 31px; display: flex; align-items: center; justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--line); }
.inspector-stats > div:last-child { border-bottom: 0; }
.inspector-stats span { color: var(--muted); font-size: 9px; }
.inspector-stats b { color: var(--text); font-size: 9px; font-weight: 580; }
.inspector-links { display: grid; gap: 0; padding: 13px 14px; }
.inspector-links > span { margin-bottom: 6px; }
.inspector-links button { min-height: 33px; display: flex; align-items: center; justify-content: space-between; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: var(--muted); padding: 0; cursor: pointer; font-size: 9px; text-align: left; }
.inspector-links button:hover { color: var(--text); }
.inspector-links i { color: var(--subtle); font-style: normal; }
.inspector-empty { display: grid; gap: 8px; padding: 16px 14px; }
.inspector-empty b { color: var(--text); font-size: 10px; }
.inspector-empty span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.inspector-empty button { justify-self: start; border: 0; background: transparent; color: var(--accent-strong); padding: 0; cursor: pointer; font-size: 9px; }
.inspector-restore { position: absolute; top: 8px; right: 8px; min-height: 28px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: var(--material-glass, var(--panel-raised)); color: var(--muted); padding: 0 9px; cursor: pointer; font-size: 8px; backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); }
.inspector-restore:hover { color: var(--text); background: rgba(255,255,255,.045); }
.source-peek {
  position: absolute;
  z-index: 12;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(340px, 42%);
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  border: 1px solid var(--material-border, var(--line-strong));
  border-right: 0;
  border-radius: 12px 0 0 12px;
  background: var(--material-elevated, #151d20);
  box-shadow: -18px 0 54px rgba(0,0,0,.18);
  backdrop-filter: blur(var(--material-blur-elevated, 28px));
  -webkit-backdrop-filter: blur(var(--material-blur-elevated, 28px));
  animation: source-peek-in var(--motion-layout, 300ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
@keyframes source-peek-in {
  from { opacity: 0; transform: translateX(8px); }
  to { opacity: 1; transform: translateX(0); }
}
@media (prefers-reduced-motion: reduce) {
  .source-peek { animation: none !important; }
  .composer-shell,
  .composer-tools button,
  .send-btn,
  .evidence-row { transition: none !important; }
}
.source-peek > header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 0 14px; border-bottom: 1px solid var(--line); }
.source-peek > header > div { display: flex; align-items: baseline; gap: 8px; }
.source-peek > header span { color: var(--subtle); font-size: 7px; text-transform: uppercase; letter-spacing: .08em; }
.source-peek > header b { color: var(--text); font-size: 11px; }
.source-peek > header button { border: 0; background: transparent; color: var(--subtle); cursor: pointer; font-size: 16px; }
.source-peek-meta { min-height: 42px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 0 14px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 8px; }
.source-peek-meta i { width: 6px; height: 6px; border: 1px solid var(--line-strong); border-radius: 50%; }
.source-peek-meta i.ready { border-color: var(--ok); background: var(--ok); }
.source-peek-body { min-height: 0; overflow: auto; padding: 15px 14px; color: var(--text); font-size: 9px; line-height: 1.7; }
.source-peek-body p { margin: 0; color: var(--muted); }
.source-peek > footer { padding: 10px 14px; border-top: 1px solid var(--line); }
.source-peek > footer button { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; border: 0; border-top: 1px solid var(--line-strong); border-bottom: 1px solid var(--line-strong); border-radius: 0; background: transparent; color: var(--muted); padding: 0 10px; cursor: pointer; font-size: 9px; }
.source-peek > footer button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.pos { color: #27c46b; }
.neg { color: #ef5350; }

@media (max-width: 1180px) {
  .research-layout { grid-template-columns: 188px minmax(0, 1fr) 188px; }
  .message-row { grid-template-columns: 66px minmax(0, 1fr); gap: 10px; }
}
@media (max-width: 940px) {
  .research-layout,
  .research-layout.inspector-collapsed { grid-template-columns: 186px minmax(0, 1fr); }
  .context-inspector { display: none; }
  .inspector-restore { display: none; }
  .research-head { align-items: flex-start; }
  .source-peek { width: min(360px, 52%); }
}
@media (max-width: 720px) {
  .research-workspace { height: auto; min-height: 0; overflow: visible; }
  .research-head { flex-direction: column; align-items: stretch; }
  .research-head-actions { justify-content: space-between; }
  .research-layout,
  .research-layout.inspector-collapsed { grid-template-columns: 1fr; }
  .evidence-dock { border: 1px solid var(--material-border, var(--line)); }
  .evidence-detail { max-height: 290px; }
  .chat-window { min-height: 300px; max-height: 520px; }
  .message-row { grid-template-columns: 1fr; gap: 6px; }
  .document-sources > div { grid-template-columns: 1fr; }
  .source-peek { width: calc(100% - 12px); }
}

/* V5 — increase research readability without changing information density or behavior. */
.engine-status { font-size: 10.5px; }
.desk-switch button { font-size: 10.5px; }
.dock-head span,
.context-inspector header span,
.dock-head small { font-size: 9.5px; }
.evidence-row b { font-size: 11.5px; }
.evidence-row small { font-size: 10px; }
.evidence-detail-head b { font-size: 11px; }
.text-action { font-size: 10px; }
.quote-snapshot > span { font-size: 10px; }
.quote-snapshot small { font-size: 9.5px; }
.input,
.evidence-detail textarea { font-size: 11px; }
.tool-output { font-size: 10.5px; line-height: 1.72; }
.tool-empty { font-size: 10px; }
.document-masthead span,
.document-masthead em { font-size: 10px; }
.document-masthead small { font-size: 9.5px; }
.research-objective > span,
.research-checklist > span,
.document-sources > span { font-size: 10px; }
.research-checklist b { font-size: 9.5px; }
.research-checklist p { font-size: 11px; }
.document-sources b { font-size: 10px; }
.document-sources small,
.document-sources em { font-size: 9px; }
.prompt-templates > span,
.prompt-templates button { font-size: 10px; }
.conversation-label span { font-size: 11px; }
.conversation-label small { font-size: 9.5px; }
.message-meta { font-size: 9.5px; }
.message-content { font-size: 12.5px; }
.composer-shell > textarea { font-size: 12px; }
.composer-tools button:not(.send-btn) { font-size: 9.5px; }
.send-btn { min-height: 31px; font-size: 10px; }
.research-disclaimer { font-size: 9.5px; }
.inspector-target span,
.inspector-links > span { font-size: 9.5px; }
.inspector-target small { font-size: 9px; }
.inspector-stats span,
.inspector-stats b,
.inspector-links button,
.inspector-empty span,
.inspector-empty button { font-size: 10px; }
.inspector-empty b { font-size: 11px; }
.inspector-restore { font-size: 9.5px; }
.source-peek > header span { font-size: 9px; }
.source-peek > header b { font-size: 11.5px; }
.source-peek-meta { font-size: 9.5px; }
.source-peek-body { font-size: 10.5px; }
.source-peek > footer button { font-size: 10px; }
.document-sources button,
.prompt-templates button,
.composer-tools button:not(.send-btn),
.inspector-links button,
.source-peek > footer button {
  transition: color .16s ease, background .16s ease, border-color .16s ease, transform .10s ease;
}
.document-sources button:active,
.prompt-templates button:active,
.inspector-links button:active,
.source-peek > footer button:active { transform: scale(.985); }

/* V6 — stronger document hierarchy, quieter utility surfaces. */
.research-workspace {
  height: calc(100dvh - 156px);
  min-height: 540px;
}
.research-canvas {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  border-radius: 12px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--surface) 24%, transparent), transparent 24%);
}
.research-document {
  flex: 0 0 auto;
  min-height: auto;
  max-height: none;
  overflow: visible;
  padding-right: 4px;
}
.document-masthead {
  padding-top: 8px;
  padding-bottom: 20px;
}
.document-masthead h3 { font-size: 27px; }
.evidence-dock,
.context-inspector {
  border-color: color-mix(in srgb, var(--material-border, var(--line)) 84%, transparent);
  background: color-mix(in srgb, var(--material-glass, transparent) 58%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.018);
}
.evidence-row:hover,
.evidence-row.active { background: color-mix(in srgb, var(--workspace-hover-bg) 76%, transparent); }
.composer-shell {
  border-radius: 12px;
  background: color-mix(in srgb, var(--material-glass, transparent) 82%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.03), 0 12px 34px rgba(0,0,0,.07);
}
.composer-shell:focus-within {
  border-color: color-mix(in srgb, var(--accent) 42%, var(--line-strong));
  box-shadow: inset 0 1px 0 rgba(255,255,255,.035), 0 0 0 3px color-mix(in srgb, var(--accent) 7%, transparent), 0 14px 38px rgba(0,0,0,.08);
}
.message-row {
  transition: background .16s ease, transform .10s ease;
}
.message-row.user {
  background: color-mix(in srgb, var(--workspace-accent-wash) 54%, transparent);
}
.message-row:hover { background: color-mix(in srgb, var(--workspace-hover-bg) 46%, transparent); }
.message-row.user:hover { background: color-mix(in srgb, var(--workspace-accent-wash) 68%, transparent); }
.prompt-templates { padding-top: 6px; padding-bottom: 6px; }
.conversation-label { padding-top: 8px; padding-bottom: 5px; }
.chat-window {
  flex: 0 0 104px;
  min-height: 88px;
  max-height: 104px;
}
.composer-shell { flex: 0 0 auto; min-height: 104px; margin-top: 6px; }
.research-disclaimer { margin-top: 3px; }
@media (max-width: 1180px) {
  .research-workspace {
    height: calc(100dvh - 146px);
    gap: 8px;
  }
  .research-head { min-height: 46px; padding-bottom: 8px; }
  .desk-switch { padding: 2px; }
  .desk-switch button { min-height: 28px; padding-left: 10px; padding-right: 10px; }
  .research-document { min-height: 0; }
  .prompt-templates { padding-top: 4px; padding-bottom: 4px; }
  .prompt-templates button { padding-top: 5px; padding-bottom: 5px; }
  .conversation-label { padding-top: 6px; padding-bottom: 4px; }
  .chat-window { flex-basis: 88px; min-height: 76px; max-height: 88px; }
  .composer-shell { min-height: 96px; margin-top: 4px; }
  .composer-shell > textarea { min-height: 54px; padding-top: 9px; }
  .research-disclaimer { margin-top: 1px; }
}
@media (max-width: 720px) {
  .research-workspace { height: auto; min-height: 0; }
  .research-canvas { display: flex; flex-direction: column; overflow: visible; }
  .research-document { flex: 0 0 auto; overflow: visible; padding-right: 0; }
  .chat-window { flex: 0 0 auto; min-height: 300px; max-height: 520px; }
}
@media (prefers-reduced-motion: reduce) {
  .message-row,
  .composer-shell { transition: none !important; }
}

/* V7 — Research Desk: AI behaves like an annotation layer, not a chat product. */
.research-layout,
.research-document,
.evidence-dock,
.context-inspector,
.prompt-templates,
.conversation-label {
  transition: opacity .20s ease, transform .20s cubic-bezier(.22,1,.36,1), border-color .20s ease;
}
.chat-window {
  border-top: 1px solid color-mix(in srgb, var(--line) 44%, transparent);
  padding-top: 4px;
}
.message-row {
  padding: 10px 6px;
  border-radius: 0;
  background: transparent;
}
.message-row.user,
.message-row.user:hover { background: transparent; }
.message-row.user .message-content {
  justify-self: start;
  max-width: min(760px, 92%);
  padding: 8px 11px;
  border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 76%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--workspace-accent-wash) 46%, transparent);
}
.message-row.assistant .message-content {
  padding-left: 13px;
  border-left: 1px solid color-mix(in srgb, var(--accent) 54%, var(--line));
}
.message-row.assistant .message-meta span { color: var(--accent-strong); }
.research-layout:has(.composer-shell:focus-within) .evidence-dock,
.research-layout:has(.composer-shell:focus-within) .context-inspector {
  opacity: .62;
  transform: scale(.995);
}
.research-layout:has(.composer-shell:focus-within) .research-document,
.research-layout:has(.composer-shell:focus-within) .prompt-templates,
.research-layout:has(.composer-shell:focus-within) .conversation-label {
  opacity: .78;
}
.research-layout:has(.composer-shell:focus-within) .composer-shell {
  border-color: color-mix(in srgb, var(--accent) 48%, var(--line-strong));
  box-shadow: inset 0 1px 0 rgba(255,255,255,.04), 0 0 0 3px color-mix(in srgb, var(--accent) 6%, transparent), 0 18px 44px rgba(0,0,0,.09);
}
.research-layout:has(.research-document:hover) .evidence-dock,
.research-layout:has(.research-document:hover) .context-inspector { opacity: .78; }
@media (max-width: 720px) {
  .research-layout:has(.composer-shell:focus-within) .evidence-dock,
  .research-layout:has(.composer-shell:focus-within) .context-inspector,
  .research-layout:has(.composer-shell:focus-within) .research-document,
  .research-layout:has(.composer-shell:focus-within) .prompt-templates,
  .research-layout:has(.composer-shell:focus-within) .conversation-label { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .research-layout,
  .research-document,
  .evidence-dock,
  .context-inspector,
  .prompt-templates,
  .conversation-label { transition: none !important; }
}
</style>
