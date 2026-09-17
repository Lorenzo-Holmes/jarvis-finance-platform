<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'
import MarkdownContent from '../components/common/MarkdownContent.vue'

// ---- 研报输入 ----
// 后端约束: reports 1-20 篇, 总长度 <= 100000 字符 (backend/app/ai_routes.py SentimentReq)
const MAX_REPORTS = 20
const MAX_TOTAL_CHARS = 100_000

let nextReportId = 1
const createReport = () => ({ id: nextReportId++, text: '' })
const reports = ref([createReport()])
const analyzing = ref(false)
const result = ref('')
const sections = ref({}) // 模型按固定小节输出的正文（情感摘要 / 趋势判断 / 评级与目标价）
const disputes = ref([]) // 争议焦点卡片：由后端确定性切分，前端不做语义推断
const error = ref('')

const totalChars = computed(() => reports.value.reduce((sum, report) => sum + report.text.length, 0))
const filledReports = computed(() => reports.value.map(report => report.text.trim()).filter(Boolean))
const validation = computed(() => {
  if (!filledReports.value.length) return '请至少填写一篇研报内容'
  if (totalChars.value > MAX_TOTAL_CHARS) return `研报文本总长度不能超过 ${MAX_TOTAL_CHARS.toLocaleString()} 字符`
  return ''
})

function addReport() {
  if (reports.value.length < MAX_REPORTS) reports.value.push(createReport())
}

function removeReport(index) {
  if (reports.value.length > 1) reports.value.splice(index, 1)
}

function clearAll() {
  reports.value = [createReport()]
  result.value = ''
  sections.value = {}
  disputes.value = []
  error.value = ''
}

// ---- 分节卡片：只渲染模型确实输出的小节，缺哪个就不显示哪个 ----
const SECTION_ORDER = ['情感摘要', '趋势判断', '评级与目标价']
const sectionCards = computed(() => SECTION_ORDER
  .filter(name => sections.value[name])
  .map(name => ({ id: name, title: name, text: sections.value[name] })))

// ---- 关键词提及统计：不把自由文本中的词频伪装成结构化信号 ----
const sentimentStats = computed(() => {
  const text = result.value
  if (!text) return null
  const count = keyword => (text.match(new RegExp(keyword, 'g')) || []).length
  return { bullish: count('看多'), bearish: count('看空'), neutral: count('中性') }
})

async function analyze() {
  if (validation.value || analyzing.value) return
  analyzing.value = true
  error.value = ''
  result.value = ''
  sections.value = {}
  disputes.value = []
  try {
    const response = await api.aiSentiment(filledReports.value)
    if (response.code !== 200 || !response.data) throw new Error(response.message || '情感分析失败')
    result.value = response.data.content || '（暂无分析结论）'
    sections.value = response.data.sections || {}
    disputes.value = Array.isArray(response.data.disputes) ? response.data.disputes : []
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    analyzing.value = false
  }
}
</script>

<template>
  <section class="sentiment-workspace">
    <div class="section-bar">
      <div>
        <h1>多空研报</h1>
        <span>多方论据 · 空方论据 · 争议核心 · 失效条件与综合研判</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '请添加研报' : '可分析' }}</span>
    </div>

    <div class="sentiment-layout">
      <aside class="panel st-input-panel">
        <div class="st-panel-title">研报文本</div>

        <div v-for="(report, index) in reports" :key="report.id" class="st-report-item">
          <div class="st-report-head">
            <b>研报 {{ index + 1 }}</b>
            <span>{{ report.text.length }} 字</span>
            <button v-if="reports.length > 1" type="button" class="text-action" aria-label="删除该研报" @click="removeReport(index)">移除</button>
          </div>
          <textarea v-model="report.text" rows="4" :aria-label="`研报 ${index + 1} 内容`" placeholder="粘贴研报摘要、观点段落或关键结论…" />
        </div>

        <div class="st-input-actions">
          <button type="button" class="btn" :disabled="reports.length >= 20" @click="addReport">添加研报</button>
          <button type="button" class="btn" @click="clearAll">清空</button>
        </div>

        <div class="st-quota">
          <span>共 {{ filledReports.length }} / 20 篇 · {{ totalChars.toLocaleString() }} / 100,000 字符</span>
        </div>

        <div v-if="validation" class="st-validation">{{ validation }}</div>
        <button class="btn primary st-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          {{ analyzing ? '分析中…' : '开始分析' }}
        </button>
        <div class="st-note">逐篇给出看多/看空/中性倾向、置信度与关键论据，并汇总综合判断。</div>
      </aside>

      <div class="st-main">
        <template v-if="result">
          <div v-if="sentimentStats" class="st-metrics">
            <div class="st-metric"><span>研报篇数</span><b>{{ filledReports.length }}</b></div>
            <div class="st-metric"><span>看多提及</span><b class="bull">{{ sentimentStats.bullish }}</b></div>
            <div class="st-metric"><span>看空提及</span><b class="bear">{{ sentimentStats.bearish }}</b></div>
            <div class="st-metric"><span>中性提及</span><b>{{ sentimentStats.neutral }}</b></div>
          </div>

          <div v-if="disputes.length" class="st-disputes">
            <div class="st-block-head">
              <b>争议焦点</b>
              <span>观点相左的研报按论据对比 · 共 {{ disputes.length }} 项 · 由模型归纳、服务端切分</span>
            </div>
            <div v-for="item in disputes" :key="item.id" class="st-dispute">
              <div class="st-dispute-topic">{{ item.topic }}</div>
              <div class="st-sides">
                <div class="st-side bull">
                  <span>多方论据</span>
                  <p>{{ item.bull || '未给出' }}</p>
                </div>
                <div class="st-side bear">
                  <span>空方论据</span>
                  <p>{{ item.bear || '未提出明确反对论据' }}</p>
                </div>
              </div>
            </div>
          </div>

          <div v-if="sectionCards.length" class="st-sections">
            <div v-for="card in sectionCards" :key="card.id" class="panel st-section">
              <div class="st-block-head"><b>{{ card.title }}</b></div>
              <MarkdownContent class="st-section-body" :content="card.text" />
            </div>
          </div>

          <div class="panel st-result-panel">
            <div class="st-result-head">
              <div><b>完整分析</b><span>模型原文，多空倾向自动识别，仅供参考，请结合原文独立判断</span></div>
            </div>
            <MarkdownContent class="st-output" :content="result" />
          </div>
        </template>

        <DataState v-else-if="analyzing" state="loading" title="正在分析研报情感" message="逐篇提取多空倾向与关键论据，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="情感分析失败" :message="error" retryable @retry="analyze" />

        <div v-else class="panel st-empty">
          <div class="st-empty-mark">多空</div>
          <b>等待研报分析</b>
          <span>在左侧粘贴一篇或多篇研报文本后运行分析，即可查看逐篇多空倾向、争议焦点对比与综合研判。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.sentiment-workspace { display: flex; flex-direction: column; gap: 12px; margin: 0; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.sentiment-layout { display: grid; grid-template-columns: 330px minmax(0, 1fr); gap: 0; align-items: start; border: 1px solid var(--line); }
.st-input-panel { position: sticky; top: 10px; border: 0 !important; border-right: 1px solid var(--line) !important; }
.st-panel-title { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .08em; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.st-report-item { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.st-report-head { display: flex; align-items: baseline; gap: 8px; }
.st-report-head b { color: var(--muted); font-size: 10px; font-weight: 650; }
.st-report-head span { color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.st-report-head .text-action { margin-left: auto; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.st-report-head .text-action:hover { color: #e47d79; }
.st-report-item textarea { width: 100%; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); color: var(--text); border-radius: 0; outline: none; font-size: 10px; padding: 8px 4px; resize: vertical; line-height: 1.6; }
.st-report-item textarea:focus { border-color: var(--workspace-focus); }
.st-input-actions { display: flex; gap: 7px; margin-top: 10px; }
.st-input-actions .btn { flex: 1; min-height: 30px; font-size: 10px; }
.st-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.st-validation { margin-top: 10px; color: var(--workspace-warning-text); background: var(--workspace-warning-bg); border-left: 2px solid var(--workspace-warning-border); padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.st-run { width: 100%; min-height: 36px; margin-top: 12px; }
.st-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.st-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.st-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0; border-top: 1px solid var(--line); border-left: 1px solid var(--line); }
.st-metric { background: transparent; border: 0; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.st-metric span { color: var(--subtle); font-size: 9px; }
.st-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.st-metric b.bull { color: #ef5350; }
.st-metric b.bear { color: #27c46b; }
.st-disputes { display: flex; flex-direction: column; gap: 8px; }
.st-block-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.st-block-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.st-block-head span { color: var(--subtle); font-size: 9px; }
.st-dispute { background: transparent; border: 1px solid var(--line); border-radius: 0; padding: 12px; display: flex; flex-direction: column; gap: 9px; }
.st-dispute-topic { color: var(--accent); font-size: 11px; font-weight: 650; }
.st-sides { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; }
.st-side { border-left: 2px solid var(--line-strong); background: var(--workspace-panel-soft); border-radius: 0; padding: 9px 11px; display: flex; flex-direction: column; gap: 4px; }
.st-side.bull { border-left-color: #ef5350; }
.st-side.bear { border-left-color: #27c46b; }
.st-side span { color: var(--muted); font-size: 9px; }
.st-side.bull span { color: #ef5350; }
.st-side.bear span { color: #27c46b; }
.st-side p { margin: 0; color: var(--text); font-size: 10px; line-height: 1.6; }
.st-sections { display: flex; flex-direction: column; gap: 8px; }
.st-section { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; }
.st-section-body { color: var(--text); font-size: 11px; line-height: 1.75; overflow-wrap: anywhere; }
.st-result-panel { display: flex; flex-direction: column; }
.st-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.st-result-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.st-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.st-output { margin-top: 10px; max-height: 520px; overflow: auto; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; padding: 14px 2px; color: var(--text); font-size: 11px; line-height: 1.78; overflow-wrap: anywhere; }
.st-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.st-empty-mark { color: var(--accent-strong); border: 1px solid var(--line-strong); border-radius: 0; width: 58px; height: 40px; display: grid; place-items: center; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .08em; }
.st-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.st-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .sentiment-layout { grid-template-columns: 280px minmax(0, 1fr); } .st-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .sentiment-layout { grid-template-columns: 1fr; } .st-input-panel { position: static; } }
</style>
