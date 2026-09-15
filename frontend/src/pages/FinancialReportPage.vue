<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'
import MarkdownContent from '../components/common/MarkdownContent.vue'
import { extractFinancialDocument } from '../utils/financialDocumentImport'

const props = defineProps({
  researchContext: { type: Object, default: null },
})

// 后端约束: content 最大 50000 字符 (backend/app/ai_routes.py ReportReq)
const MAX_CONTENT_CHARS = 50_000

const content = ref('')
const analyzing = ref(false)
const result = ref('')
const error = ref('')
const fileInput = ref(null)
const importing = ref(false)
const importProgress = ref(0)
const importStatus = ref('')
const importError = ref('')
const pendingImport = ref(null)
let importController = null

const validation = computed(() => {
  if (!content.value.trim()) return '请粘贴财报内容'
  if (content.value.length > MAX_CONTENT_CHARS) return `财报文本不能超过 ${MAX_CONTENT_CHARS.toLocaleString()} 字符`
  return ''
})
const companyContextLabel = computed(() => props.researchContext?.name || props.researchContext?.symbol || '')

async function analyze() {
  if (validation.value || analyzing.value) return
  analyzing.value = true
  error.value = ''
  result.value = ''
  try {
    const response = await api.aiFinancialReport(content.value.trim())
    if (response.code !== 200 || !response.data) throw new Error(response.message || '财报解析失败')
    result.value = response.data.content || '（暂无解析结论）'
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    analyzing.value = false
  }
}

function chooseFile() {
  fileInput.value?.click()
}

async function handleFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return

  importController?.abort()
  const controller = new AbortController()
  importController = controller
  importing.value = true
  importProgress.value = 0
  importStatus.value = `正在读取 ${file.name}`
  importError.value = ''
  pendingImport.value = null

  try {
    const extracted = await extractFinancialDocument(file, {
      signal: controller.signal,
      onProgress(update) {
        importStatus.value = update.status || `正在识别 ${file.name}`
        if (Number.isFinite(update.progress)) importProgress.value = Math.max(0, Math.min(1, update.progress))
      },
    })
    if (controller.signal.aborted) return
    if (!extracted.text.trim()) throw new Error('没有从文件中提取到文字；请尝试更清晰的扫描件或直接粘贴文本。')

    pendingImport.value = {
      fileName: file.name,
      text: extracted.text,
      warnings: extracted.warnings || [],
    }
    importStatus.value = `已从 ${file.name} 提取 ${extracted.text.length.toLocaleString()} 个字符`
    importProgress.value = 1
    if (!content.value.trim()) applyImportedText('replace')
  } catch (e) {
    if (e?.name !== 'AbortError') {
      importError.value = e?.message || '文件识别失败，请尝试粘贴文本。'
      importStatus.value = ''
    }
  } finally {
    if (importController === controller) importController = null
    if (!controller.signal.aborted) importing.value = false
  }
}

function applyImportedText(mode) {
  if (!pendingImport.value) return
  const importedText = pendingImport.value.text
  if (mode === 'append' && content.value.trim()) {
    content.value = `${content.value.trimEnd()}\n\n${importedText}`
  } else {
    content.value = importedText
  }
  result.value = ''
  error.value = ''
  importStatus.value = `${mode === 'append' ? '已追加' : '已替换'}：${pendingImport.value.fileName}`
  pendingImport.value = null
}

function cancelImport() {
  importController?.abort()
  importController = null
  importing.value = false
  importStatus.value = '已取消文件识别'
}

function clearAll() {
  content.value = ''
  result.value = ''
  error.value = ''
  importController?.abort()
  importController = null
  importing.value = false
  importProgress.value = 0
  importStatus.value = ''
  importError.value = ''
  pendingImport.value = null
}

onBeforeUnmount(() => importController?.abort())
</script>

<template>
  <section class="fr-workspace">
    <div class="section-bar">
      <div>
        <h1>COMPANY FILE / FINANCIAL FILING</h1>
        <span>原始披露文本 → 财务结构 → 盈利质量 → 风险与异常变化</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? 'SOURCE REQUIRED' : 'SOURCE READY' }}</span>
    </div>

    <div v-if="props.researchContext" class="context-target">
      <span>COMPANY CONTEXT</span>
      <strong>{{ props.researchContext.symbol || props.researchContext.name }}</strong>
      <small>{{ props.researchContext.name || props.researchContext.market || '—' }}</small>
      <em>仅作为 Company File 标识；仍需提供真实财报原文，不自动生成财务数字。</em>
    </div>

    <div class="filing-index" aria-label="财报解析流程">
      <span class="active"><b>01</b>SOURCE DOCUMENT</span>
      <span :class="{ active: analyzing }"><b>02</b>PROCESSING</span>
      <span :class="{ active: !!result }"><b>03</b>ANALYSIS DOSSIER</span>
      <span :class="{ active: !!error }"><b>04</b>RISK / ERROR</span>
    </div>

    <div class="fr-layout">
      <aside class="panel fr-input-panel">
        <div class="fr-panel-title"><span>SOURCE DOCUMENT</span><strong>财报原文</strong></div>

        <input
          ref="fileInput"
          class="fr-file-input"
          type="file"
          accept=".pdf,.docx,.md,.markdown,.txt,.png,.jpg,.jpeg,.webp,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/markdown,text/plain,image/png,image/jpeg,image/webp"
          aria-label="选择财报文件"
          @change="handleFileChange"
        />
        <div class="fr-import-tools">
          <button class="btn fr-file-button" type="button" :disabled="importing" @click="chooseFile">
            {{ importing ? '正在识别…' : '选择文件' }}
          </button>
          <span>PDF · DOCX · MD · TXT · PNG · JPG · WEBP</span>
        </div>

        <div v-if="importing" class="fr-import-progress" role="status" aria-live="polite">
          <div><span>{{ importStatus }}</span><button type="button" class="text-action" @click="cancelImport">取消</button></div>
          <progress max="100" :value="Math.round(importProgress * 100)">{{ Math.round(importProgress * 100) }}%</progress>
        </div>
        <div v-else-if="importStatus" class="fr-import-status" role="status" aria-live="polite">{{ importStatus }}</div>
        <div v-if="importError" class="fr-import-error" role="alert">{{ importError }}</div>

        <div v-if="pendingImport" class="fr-import-review" aria-live="polite">
          <b>EXTRACTED SOURCE READY</b>
          <span>{{ pendingImport.fileName }} · {{ pendingImport.text.length.toLocaleString() }} 字符</span>
          <ul v-if="pendingImport.warnings.length">
            <li v-for="warning in pendingImport.warnings" :key="warning">{{ warning }}</li>
          </ul>
          <div>
            <button class="btn fr-file-button" type="button" @click="applyImportedText('replace')">替换输入区</button>
            <button class="btn fr-file-button" type="button" :disabled="!content.trim()" @click="applyImportedText('append')">追加到末尾</button>
          </div>
        </div>

        <textarea v-model="content" rows="14" aria-label="财报内容" placeholder="粘贴财报原文、关键财务数据或业绩摘要…" />

        <div class="fr-quota">
          <span>{{ content.length.toLocaleString() }} / {{ MAX_CONTENT_CHARS.toLocaleString() }} 字符</span>
        </div>

        <div v-if="validation" class="fr-validation">{{ validation }}</div>
        <button class="btn primary fr-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          {{ analyzing ? 'PROCESSING…' : 'ANALYZE FILING' }}
        </button>
        <div class="fr-actions">
          <button type="button" class="text-action" @click="clearAll">清空</button>
        </div>
        <div class="fr-note">文件提取与 OCR 在当前浏览器完成，源文件不会上传；仅点击分析后，输入框文本才发送至现有财报解析接口。首次 OCR 需要加载引擎与中文/英文语言包。</div>
      </aside>

      <div class="fr-main">
        <template v-if="result">
          <div class="panel fr-result-panel">
            <div class="fr-result-head">
              <div><b>ANALYSIS DOSSIER{{ companyContextLabel ? ` / ${companyContextLabel}` : '' }}</b><span>基于输入文本的财务解读，仅供研究参考，请以原始财报为准</span></div>
              <span class="result-state">AI / COMPLETE</span>
            </div>
            <MarkdownContent class="fr-output" :content="result" />
          </div>
        </template>

        <DataState v-else-if="analyzing" state="loading" title="正在解析财报" message="提取财务结构、健康度与风险点，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="财报解析失败" :message="error" retryable @retry="analyze" />

        <div v-else class="panel fr-empty">
          <div class="fr-empty-mark">FILE</div>
          <b>WAITING FOR SOURCE DOCUMENT</b>
          <span>在左侧粘贴财报文本后运行解析，即可查看营收、利润、现金流与风险点的结构化解读。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.fr-workspace { display: flex; flex-direction: column; gap: 12px; margin: 0; }
.context-target { min-height: 38px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border: 1px solid var(--line); background: rgba(161,132,88,.045); }
.context-target span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.context-target strong { color: var(--text); font: 650 10px/1 ui-monospace, monospace; }
.context-target small { color: var(--muted); font-size: 9px; }
.context-target em { margin-left: auto; max-width: 520px; color: var(--subtle); font-size: 9px; line-height: 1.4; font-style: normal; text-align: right; }
.section-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 52px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.section-bar h1 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .11em; }
.section-bar > div > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font: 600 9px/1 ui-monospace, monospace; letter-spacing: .07em; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.filing-index { min-height: 37px; display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); border-top: 1px solid var(--line); border-left: 1px solid var(--line); }
.filing-index span { display: flex; align-items: center; gap: 10px; padding: 0 12px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.filing-index b { color: #aaa398; font-weight: 600; }
.filing-index span.active { background: rgba(161,132,88,.08); color: #4e4d47; }
.filing-index span.active b { color: var(--accent-strong); }
.fr-layout { display: grid; grid-template-columns: 360px minmax(0, 1fr); gap: 0; align-items: stretch; border: 1px solid var(--line); }
.fr-input-panel { position: sticky; top: 10px; align-self: start; border: 0; border-right: 1px solid var(--line); background: var(--workspace-panel-wash, var(--panel)); border-radius: 0; }
.fr-panel-title { display: grid; gap: 6px; color: var(--text); padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.fr-panel-title span { color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .12em; }
.fr-panel-title strong { font-size: 12px; font-weight: 650; }
.fr-file-input { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
.fr-import-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.fr-import-tools > span { color: var(--muted); font: 550 8px/1.5 ui-monospace, monospace; }
.fr-file-button { min-height: 30px; padding: 6px 9px; border-radius: 0 !important; color: var(--text) !important; background: var(--workspace-panel-soft, var(--surface)) !important; border-color: var(--line-strong) !important; font-size: 9px; }
.fr-file-button:hover:not(:disabled) { background: var(--workspace-hover-bg, var(--panel-raised)) !important; }
.fr-file-button:disabled { opacity: .45; cursor: not-allowed; }
.fr-import-progress, .fr-import-status { margin-top: 9px; color: var(--muted); font-size: 9px; line-height: 1.5; }
.fr-import-progress > div { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
.fr-import-progress progress { width: 100%; height: 5px; margin-top: 6px; accent-color: var(--accent); }
.fr-import-error { margin-top: 9px; padding: 8px 9px; color: var(--bad); background: var(--danger-soft); border-left: 2px solid var(--bad); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }
.fr-import-review { display: grid; gap: 6px; margin-top: 10px; padding: 9px; color: var(--text); background: var(--workspace-accent-wash, rgba(161,132,88,.08)); border-left: 2px solid var(--accent); }
.fr-import-review > b { color: var(--accent-strong); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.fr-import-review > span, .fr-import-review li { color: var(--muted); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }
.fr-import-review ul { margin: 0; padding-left: 16px; }
.fr-import-review > div { display: flex; flex-wrap: wrap; gap: 6px; }
.fr-input-panel textarea { width: 100%; min-height: 320px; background: var(--workspace-panel-soft, var(--surface)); border: 1px solid var(--line); border-bottom: 1px solid var(--line-strong); color: var(--text); border-radius: 0; outline: none; font-size: 10px; padding: 12px; resize: vertical; line-height: 1.75; margin-top: 10px; caret-color: var(--accent-strong); }
.fr-input-panel textarea::placeholder { color: var(--muted); opacity: 1; }
.fr-input-panel textarea:focus { border-color: var(--accent); }
.fr-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.fr-validation { margin-top: 10px; color: #8e6b3e; background: rgba(161,122,66,.06); border-left: 2px solid #9a7744; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.fr-run { width: 100%; min-height: 38px; margin-top: 12px; border-radius: 0 !important; background: #353830 !important; color: #f2eee6 !important; border-color: #353830 !important; }
.fr-run:disabled { opacity: .4; }
.fr-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.fr-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.fr-main { display: flex; flex-direction: column; gap: 0; min-width: 0; background: var(--workspace-panel-soft, var(--panel)); }
.fr-result-panel { display: flex; flex-direction: column; border: 0; border-radius: 0; background: transparent; }
.fr-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.fr-result-head b { color: var(--text); font: 650 10px/1 ui-monospace, monospace; letter-spacing: .1em; }
.fr-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.fr-result-head .result-state { color: #66705f; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.fr-output { margin-top: 0; min-height: 500px; max-height: 650px; overflow: auto; background: transparent; border: 0; border-radius: 0; padding: 20px 2px; color: var(--text); font-size: 11px; line-height: 1.82; overflow-wrap: anywhere; }
.fr-empty { min-height: 520px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; padding: 46px 20px; text-align: center; border: 0; border-radius: 0; background: transparent; }
.fr-empty-mark { color: var(--accent-strong); border: 1px solid var(--line-strong); border-radius: 0; width: 64px; height: 44px; display: grid; place-items: center; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .12em; }
.fr-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.fr-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .fr-layout { grid-template-columns: 300px minmax(0, 1fr); } }
@media (max-width: 760px) { .filing-index { grid-template-columns: 1fr 1fr; } .fr-layout { grid-template-columns: 1fr; } .fr-input-panel { position: static; border-right: 0; border-bottom: 1px solid var(--line); } .fr-input-panel textarea { min-height: 220px; } .fr-empty, .fr-output { min-height: 360px; } }
</style>
