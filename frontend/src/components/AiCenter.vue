<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { api } from '../api/client'

// ---- 对话 ----
const messages = ref([])
const input = ref('')
const sending = ref(false)
const aiStatus = ref(null)
const chatBox = ref(null)
let currentChatAbort = null

// ---- 智能报价 ----
const quoteData = ref(null)
const quoteLoading = ref(false)
const quoteResult = ref('')

// ---- 财报解析 ----
const reportText = ref('')
const reportLoading = ref(false)
const reportResult = ref('')

// ---- 产业链 ----
const chainNode = ref('黄金')
const chainLoading = ref(false)
const chainResult = ref('')

const sugg = [
  '当前黄金ETF适合定投吗？',
  '分析一下黄金的产业链逻辑',
  '金价处于什么位置，风险如何？',
]

async function loadStatus() {
  try {
    const d = await api.aiStatus()
    aiStatus.value = d.data
  } catch (e) { aiStatus.value = { available: false, message: 'AI服务未连接' } }
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
  currentChatAbort = new AbortController()
  await scrollChatToBottom()
  try {
    await api.aiChatStream(history, ({ event, data }) => {
      if (event === 'delta' && data?.content) {
        messages.value[assistantIndex].content += data.content
        scrollChatToBottom()
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

// 智能报价解读
async function runQuote() {
  quoteLoading.value = true; quoteResult.value = ''
  try {
    const q = await api.marketPrices()
    const rt = q.data?.gold_etf
    if (!rt) { quoteResult.value = '未获取到行情' ; return }
    quoteData.value = rt
    const d = await api.aiQuote(rt)
    quoteResult.value = d.data?.content || '（无回复）'
  } catch (e) { quoteResult.value = '⚠️ ' + e }
  finally { quoteLoading.value = false }
}

// 财报解析
async function runReport() {
  if (!reportText.value.trim()) return
  reportLoading.value = true; reportResult.value = ''
  try {
    const d = await api.aiFinancialReport(reportText.value.trim())
    reportResult.value = d.data?.content || '（无回复）'
  } catch (e) { reportResult.value = '⚠️ ' + e }
  finally { reportLoading.value = false }
}

// 产业链
async function runChain() {
  chainLoading.value = true; chainResult.value = ''
  try {
    const d = await api.aiChain(chainNode.value.trim())
    chainResult.value = d.data?.content || '（无回复）'
  } catch (e) { chainResult.value = '⚠️ ' + e }
  finally { chainLoading.value = false }
}

onMounted(() => {
  loadStatus()
  messages.value.push({ role: 'assistant', content: '欢迎使用贾维斯投研助手。你可以直接询问行情结构、策略风险、财报数据或产业链逻辑。' })
})
</script>

<template>
  <div class="ai">
    <!-- 状态 -->
    <div class="status-bar">
      <span class="dot" :class="aiStatus?.available ? 'ok' : 'bad'"></span>
      研究引擎 · {{ aiStatus?.provider || 'DeepSeek' }} / {{ aiStatus?.model || '...' }}
      <span class="hint" style="margin-left:auto">{{ aiStatus?.available ? '已连接' : aiStatus?.message }}</span>
    </div>

    <div class="grid">
      <!-- 对话 -->
      <div class="panel chat-panel">
        <div class="panel-head"><h2>投研助手</h2></div>
        <div class="chat-window" ref="chatBox">
          <div v-for="(m, i) in messages" :key="i" class="chat-item" :class="m.role">
            <div class="role">{{ m.role === 'user' ? '你' : '研究助手' }}</div>
            <div class="bubble">{{ m.content }}</div>
          </div>
        </div>
        <div class="sugg">
          <button v-for="s in sugg" :key="s" class="chip" @click="useSuggestion(s)">{{ s }}</button>
        </div>
        <div class="chat-input">
          <input v-model="input" @keyup.enter="sendChat" placeholder="输入市场、策略或财报问题" :disabled="sending" />
          <button class="btn primary" @click="sending ? stopChat() : sendChat()">{{ sending ? '停止生成' : '发送' }}</button>
        </div>
      </div>

      <!-- 功能卡片 -->
      <div class="side">
        <!-- 智能报价 -->
        <div class="panel">
          <div class="panel-head"><h2>行情解读</h2></div>
          <div class="row">
            <button class="btn" @click="runQuote" :disabled="quoteLoading">{{ quoteLoading ? '分析中…' : '生成研究摘要' }}</button>
          </div>
          <div v-if="quoteData" class="quote-mini">
            现价 <b>{{ quoteData.price }}</b> · 昨收 {{ quoteData.prev_close }}
            <span :class="quoteData.change >= 0 ? 'pos' : 'neg'">{{ quoteData.change }} ({{ quoteData.change_pct }}%)</span>
          </div>
          <div v-if="quoteResult" class="out">{{ quoteResult }}</div>
        </div>

        <!-- 财报解析 -->
        <div class="panel">
          <div class="panel-head"><h2>财报解析</h2></div>
          <textarea v-model="reportText" placeholder="粘贴财报内容或关键数据…" rows="4"></textarea>
          <button class="btn" @click="runReport" :disabled="reportLoading">{{ reportLoading ? '解析中…' : '解析财报' }}</button>
          <div v-if="reportResult" class="out">{{ reportResult }}</div>
        </div>

        <!-- 产业链 -->
        <div class="panel">
          <div class="panel-head"><h2>产业链研究</h2></div>
          <div class="row">
            <input v-model="chainNode" class="input" placeholder="输入产业链节点，如：黄金" />
            <button class="btn" @click="runChain" :disabled="chainLoading">{{ chainLoading ? '分析中…' : '分析' }}</button>
          </div>
          <div v-if="chainResult" class="out">{{ chainResult }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai { display: flex; flex-direction: column; gap: 12px; }
.status-bar { display: flex; align-items: center; gap: 8px; min-height: 36px; color: var(--muted); font-size: 11px; padding: 7px 10px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.dot.ok { background: var(--ok); }
.grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(320px, .75fr); gap: 12px; }
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.panel-head h2 { margin: 0 0 12px; font-size: 15px; font-weight: 650; color: var(--text); }
.chat-panel { display: flex; flex-direction: column; }
.chat-window { flex: 1; height: 420px; overflow: auto; background: var(--surface); border: 1px solid #222529; border-radius: var(--radius-sm); padding: 14px; }
.chat-item { margin-bottom: 14px; }
.chat-item.user .bubble { background: var(--accent-soft); border: 1px solid rgba(215,181,109,.24); }
.chat-item.assistant .bubble { background: #17191b; border: 1px solid var(--line); }
.role { font-size: 10px; color: var(--subtle); margin-bottom: 4px; letter-spacing: .04em; }
.bubble { padding: 10px 12px; border-radius: var(--radius-sm); font-size: 13px; line-height: 1.7; white-space: pre-wrap; color: var(--text); }
.sugg { display: flex; gap: 6px; flex-wrap: wrap; margin: 10px 0; }
.chip { background: transparent; border: 1px solid var(--line-strong); color: var(--muted); border-radius: var(--radius-sm); padding: 5px 9px; font-size: 11px; cursor: pointer; }
.chat-input { display: flex; gap: 8px; }
.chat-input input, .input { flex: 1; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 10px 11px; outline: none; }
textarea { width: 100%; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 10px; margin-bottom: 10px; resize: vertical; outline: none; }
.btn { background: #1c1f22; border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 8px 14px; cursor: pointer; font-size: 12px; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #17140e; font-weight: 650; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.out { margin-top: 12px; background: var(--surface); border: 1px solid #282b2f; border-left: 2px solid #72684f; border-radius: var(--radius-sm); padding: 11px 12px; white-space: pre-wrap; font-size: 12px; line-height: 1.7; color: var(--text); min-height: 40px; max-height: 260px; overflow: auto; }
.quote-mini { margin: 12px 0; font-size: 12px; color: var(--text); }
.pos { color: #27c46b; } .neg { color: #ef5350; }
.side { display: flex; flex-direction: column; gap: 12px; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
</style>
