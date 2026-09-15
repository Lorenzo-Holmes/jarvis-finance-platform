<script setup>
import { computed, ref, onMounted, nextTick, watch } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import MarkdownContent from './common/MarkdownContent.vue'

const props = defineProps({
  researchContext: { type: Object, default: null },
})

// ---- 对话 ----
const messages = ref([])
const input = ref('')
const sending = ref(false)
const aiStatus = ref(null)
const statusLoading = ref(true)
const statusError = ref('')
const chatBox = ref(null)
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

function push(role, content) {
  messages.value.push({ role, content })
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
  push('user', text)
  input.value = ''
  // 不把前端欢迎语发给模型；保留最近 20 条真实 user/assistant 上下文。
  const history = messages.value.slice(1).slice(-20).map(({ role, content }) => ({ role, content }))
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
  loadStatus()
  messages.value.push({ role: 'assistant', content: '欢迎使用贾维斯投研助手。你可以直接询问行情结构、策略风险、财报数据或产业链逻辑。' })
})
</script>

<template>
  <div class="ai">
    <div class="research-head">
      <div>
        <h2>RESEARCH INTELLIGENCE / ANALYSIS DESK</h2>
        <span>问题 → 证据 → 反证 → 风险 → 结论；行情、财报与产业链能力保持联动</span>
      </div>
      <div class="engine-status" :title="statusError || '研究引擎状态'">
        <i :class="aiStatus?.available ? 'ok' : 'bad'"></i>
        <span>{{ aiStatus?.provider || 'Research Engine' }} · {{ statusLoading ? '检查中' : (aiStatus?.display_name || aiStatus?.model || '未连接') }}</span>
        <b>{{ statusLoading ? 'CHECKING' : (aiStatus?.available ? 'ONLINE' : 'OFFLINE') }}</b>
      </div>
    </div>

    <div v-if="props.researchContext" class="context-target">
      <span>GLOBAL RESEARCH CONTEXT</span>
      <strong>{{ props.researchContext.symbol || props.researchContext.name }}</strong>
      <small>{{ props.researchContext.name || props.researchContext.market || '—' }}</small>
      <button type="button" @click="useResearchContext">USE IN RESEARCH TASK</button>
    </div>

    <div class="research-layout">
      <aside class="toolbox">
        <section class="tool-section">
          <div class="tool-head"><div><b>01 / MARKET EVIDENCE</b><span>黄金ETF行情快照</span></div><button type="button" class="text-action" @click="runQuote" :disabled="quoteLoading">{{ quoteLoading ? '分析中' : '生成' }}</button></div>
          <div v-if="quoteData" class="quote-snapshot">
            <b>{{ quoteData.price }}</b>
            <span :class="quoteData.change >= 0 ? 'pos' : 'neg'">{{ quoteData.change }} · {{ quoteData.change_pct }}%</span>
            <small>昨收 {{ quoteData.prev_close }}</small>
          </div>
          <DataState v-if="quoteLoading" state="loading" title="正在生成行情摘要" compact />
          <DataState v-else-if="quoteError" state="error" title="行情研究失败" :message="quoteError" compact retryable @retry="runQuote" />
          <MarkdownContent v-else-if="quoteResult" class="tool-output" :content="quoteResult" />
          <div v-else class="tool-empty">获取当前黄金ETF行情并生成研究摘要。</div>
        </section>

        <section class="tool-section">
          <div class="tool-head"><div><b>02 / FILING EVIDENCE</b><span>财报文本研究</span></div><button type="button" class="text-action" @click="runReport" :disabled="reportLoading || !reportText.trim()">{{ reportLoading ? '解析中' : '解析' }}</button></div>
          <textarea v-model="reportText" aria-label="财报文本" placeholder="粘贴财报内容或关键数据…" rows="5"></textarea>
          <DataState v-if="reportLoading" state="loading" title="正在解析财报" compact />
          <DataState v-else-if="reportError" state="error" title="财报解析失败" :message="reportError" compact retryable @retry="runReport" />
          <MarkdownContent v-else-if="reportResult" class="tool-output" :content="reportResult" />
        </section>

        <section class="tool-section">
          <div class="tool-head"><div><b>03 / CHAIN EVIDENCE</b><span>主题与产业链分析</span></div><button type="button" class="text-action" @click="runChain" :disabled="chainLoading || !chainNode.trim()">{{ chainLoading ? '分析中' : '分析' }}</button></div>
          <input v-model="chainNode" class="input" aria-label="产业链主题" placeholder="黄金 / 铜 / 芯片…" />
          <DataState v-if="chainLoading" state="loading" title="正在分析产业链" compact />
          <DataState v-else-if="chainError" state="error" title="产业链分析失败" :message="chainError" compact retryable @retry="runChain" />
          <MarkdownContent v-else-if="chainResult" class="tool-output" :content="chainResult" />
        </section>
      </aside>

      <main class="panel conversation-panel">
        <div class="conversation-head">
          <div><b>RESEARCH NOTES</b><span>结合市场、策略或材料连续建立研究论证</span></div>
          <span class="context-note">20 MESSAGE CONTEXT</span>
        </div>

        <div class="prompt-templates">
          <span>QUESTION TEMPLATES</span>
          <button v-for="s in sugg" :key="s" type="button" @click="useSuggestion(s)">{{ s }}</button>
        </div>

        <div class="chat-window" ref="chatBox" role="log" aria-live="polite" aria-relevant="additions text" aria-label="研究会话记录">
          <div v-for="(m, i) in messages" :key="i" class="message-row" :class="m.role">
            <div class="message-meta"><span>{{ m.role === 'user' ? 'RESEARCH QUESTION' : 'JARVIS NOTE' }}</span><i></i></div>
            <MarkdownContent v-if="m.role === 'assistant'" class="message-content" :content="m.content" />
            <div v-else class="message-content">{{ m.content }}</div>
          </div>
        </div>

        <div class="composer">
          <input v-model="input" @keyup.enter="sendChat" aria-label="研究问题" placeholder="输入研究问题，例如：比较黄金ETF与伦敦金近期走势" :disabled="sending" />
          <button type="button" class="send-btn" @click="sending ? stopChat() : sendChat()" :class="{ stop: sending }" :aria-label="sending ? '停止生成' : '提交研究问题'">{{ sending ? 'STOP' : 'RUN RESEARCH' }}</button>
        </div>
        <div class="research-disclaimer">生成内容用于研究辅助，请结合原始数据和风险约束独立判断。</div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.ai { display: flex; flex-direction: column; gap: 12px; }
.context-target { min-height: 38px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border: 1px solid var(--line); background: rgba(161,132,88,.045); }
.context-target span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.context-target strong { color: var(--text); font: 650 10px/1 ui-monospace, monospace; }
.context-target small { color: var(--muted); font-size: 9px; }
.context-target button { margin-left: auto; min-height: 28px; border: 1px solid var(--line-strong); background: transparent; color: var(--text); cursor: pointer; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .07em; }
.research-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; min-height: 50px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.research-head h2 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .1em; }
.research-head > div:first-child > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.engine-status { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.engine-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.engine-status i.ok { background: var(--ok); }
.engine-status b { color: var(--subtle); border: 1px solid var(--line-strong); border-radius: 0; padding: 2px 5px; font-size: 8px; letter-spacing: .06em; }
.research-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 0; align-items: stretch; min-width: 0; border: 1px solid var(--line); }
.toolbox { display: flex; flex-direction: column; gap: 0; min-width: 0; border-right: 1px solid var(--line); }
.tool-section, .panel { background: rgba(239,235,227,.52); border: 0; border-bottom: 1px solid var(--line); border-radius: 0; }
.tool-section { padding: 13px; }
.tool-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 9px; }
.tool-head > div { display: flex; flex-direction: column; gap: 3px; }
.tool-head b { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .06em; }
.tool-head span { color: var(--subtle); font-size: 8px; }
.text-action { border: 0; background: transparent; color: var(--accent-strong); padding: 1px 0; font-size: 9px; cursor: pointer; }
.text-action:disabled { opacity: .42; cursor: not-allowed; }
.quote-snapshot { display: grid; grid-template-columns: 1fr auto; gap: 3px 8px; align-items: baseline; margin-top: 10px; padding: 9px 0; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; }
.quote-snapshot > b { color: var(--accent-strong); font-size: 19px; line-height: 1; font-weight: 680; font-variant-numeric: tabular-nums; }
.quote-snapshot > span { font-size: 9px; font-variant-numeric: tabular-nums; }
.quote-snapshot small { grid-column: 1 / -1; color: var(--subtle); font-size: 8px; }
.input, textarea { width: 100%; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); color: var(--text); border-radius: 0; outline: none; font-size: 10px; }
.input { height: 32px; padding: 0 4px; margin-top: 9px; }
textarea { padding: 8px 4px; margin-top: 9px; resize: vertical; line-height: 1.5; }
.input:focus, textarea:focus { border-color: #695b40; }
.tool-section :deep(.data-state) { margin-top: 9px; }
.tool-output { margin-top: 9px; max-height: 170px; overflow: auto; border-left: 2px solid var(--accent); background: var(--surface); padding: 8px 9px; color: var(--text); font-size: 9px; line-height: 1.6; }
.tool-empty { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.55; }
.conversation-panel { display: flex; flex-direction: column; min-width: 0; padding: 13px; background: rgba(245,242,235,.35) !important; }
.conversation-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.conversation-head > div { display: flex; align-items: baseline; gap: 8px; }
.conversation-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.conversation-head span { color: var(--subtle); font-size: 9px; }
.context-note { color: var(--muted) !important; }
.prompt-templates { display: flex; align-items: center; gap: 6px; padding: 9px 0; overflow-x: auto; }
.prompt-templates > span { color: var(--subtle); font-size: 8px; white-space: nowrap; margin-right: 2px; }
.prompt-templates button { flex: 0 0 auto; border: 1px solid var(--line-strong); background: transparent; color: var(--muted); border-radius: 0; padding: 5px 7px; font-size: 9px; cursor: pointer; }
.prompt-templates button:hover { border-color: #5b503b; color: var(--text); background: rgba(201,166,95,.04); }
.chat-window { flex: 1; min-height: 470px; max-height: 620px; overflow: auto; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; }
.message-row { display: grid; grid-template-columns: 122px minmax(0, 1fr); gap: 12px; padding: 13px 5px; border-bottom: 1px solid var(--line); }
.message-row:last-child { border-bottom: 0; }
.message-row.user { background: rgba(209,201,188,.13); }
.message-row.assistant { background: transparent; }
.message-meta { display: flex; align-items: flex-start; gap: 6px; color: var(--subtle); font-size: 8px; letter-spacing: .045em; }
.message-meta i { width: 4px; height: 4px; margin-top: 4px; border-radius: 50%; background: #555a60; }
.message-row.assistant .message-meta i { background: var(--accent); }
.message-content { color: var(--text); font-size: 11px; line-height: 1.75; overflow-wrap: anywhere; }
.composer { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 7px; margin-top: 9px; }
.composer input { width: 100%; height: 38px; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); border-radius: 0; color: var(--text); padding: 0 5px; font-size: 10px; outline: none; }
.composer input:focus { border-color: #695b40; }
.send-btn { min-width: 104px; border: 1px solid #383b33; border-radius: 0; background: #383b33; color: #f2eee6; padding: 0 12px; font: 700 8px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; }
.send-btn.stop { border-color: #684043; background: rgba(239,83,80,.08); color: #e47d79; }
.research-disclaimer { margin-top: 6px; color: var(--subtle); font-size: 8px; line-height: 1.5; }
.pos { color: #27c46b; } .neg { color: #ef5350; }
@media (max-width: 980px) { .research-layout { grid-template-columns: 270px minmax(0, 1fr); } .chat-window { min-height: 430px; } }
@media (max-width: 760px) { .research-layout { grid-template-columns: 1fr; } .toolbox { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .tool-section:first-child { grid-column: 1 / -1; } .message-row { grid-template-columns: 1fr; gap: 5px; } }
@media (max-width: 520px) { .research-head { align-items: flex-start; flex-direction: column; } .toolbox { grid-template-columns: 1fr; } .tool-section:first-child { grid-column: auto; } .chat-window { min-height: 390px; } .composer { grid-template-columns: 1fr; } .send-btn { min-height: 36px; } }
</style>
