<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

const props = defineProps({
  researchContext: { type: Object, default: null },
})

// 后端约束: content 最大 50000 字符 (backend/app/ai_routes.py ReportReq)
const MAX_CONTENT_CHARS = 50_000

const content = ref('')
const analyzing = ref(false)
const result = ref('')
const error = ref('')

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

function clearAll() {
  content.value = ''
  result.value = ''
  error.value = ''
}
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
        <div class="fr-note">当前接口接受原始披露文本并返回 AI 解析结论；接口未提供的结构化指标不会在此处伪造。</div>
      </aside>

      <div class="fr-main">
        <template v-if="result">
          <div class="panel fr-result-panel">
            <div class="fr-result-head">
              <div><b>ANALYSIS DOSSIER{{ companyContextLabel ? ` / ${companyContextLabel}` : '' }}</b><span>基于输入文本的财务解读，仅供研究参考，请以原始财报为准</span></div>
              <span class="result-state">AI / COMPLETE</span>
            </div>
            <div class="fr-output">{{ result }}</div>
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
.fr-input-panel { position: sticky; top: 10px; align-self: start; border: 0; border-right: 1px solid var(--line); background: rgba(239,235,227,.48); border-radius: 0; }
.fr-panel-title { display: grid; gap: 6px; color: var(--text); padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.fr-panel-title span { color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .12em; }
.fr-panel-title strong { font-size: 12px; font-weight: 650; }
.fr-input-panel textarea { width: 100%; min-height: 320px; background: rgba(232,229,225,.62); border: 0; border-bottom: 1px solid var(--line-strong); color: var(--text); border-radius: 0; outline: none; font-size: 10px; padding: 12px 1px; resize: vertical; line-height: 1.75; margin-top: 10px; }
.fr-input-panel textarea:focus { border-color: var(--accent); }
.fr-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.fr-validation { margin-top: 10px; color: #8e6b3e; background: rgba(161,122,66,.06); border-left: 2px solid #9a7744; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.fr-run { width: 100%; min-height: 38px; margin-top: 12px; border-radius: 0 !important; background: #353830 !important; color: #f2eee6 !important; border-color: #353830 !important; }
.fr-run:disabled { opacity: .4; }
.fr-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.fr-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.fr-main { display: flex; flex-direction: column; gap: 0; min-width: 0; background: rgba(245,242,235,.34); }
.fr-result-panel { display: flex; flex-direction: column; border: 0; border-radius: 0; background: transparent; }
.fr-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.fr-result-head b { color: var(--text); font: 650 10px/1 ui-monospace, monospace; letter-spacing: .1em; }
.fr-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.fr-result-head .result-state { color: #66705f; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.fr-output { margin-top: 0; min-height: 500px; max-height: 650px; overflow: auto; background: transparent; border: 0; border-radius: 0; padding: 20px 2px; color: var(--text); font-size: 11px; line-height: 1.82; white-space: pre-wrap; overflow-wrap: anywhere; }
.fr-empty { min-height: 520px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; padding: 46px 20px; text-align: center; border: 0; border-radius: 0; background: transparent; }
.fr-empty-mark { color: var(--accent-strong); border: 1px solid var(--line-strong); border-radius: 0; width: 64px; height: 44px; display: grid; place-items: center; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .12em; }
.fr-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.fr-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .fr-layout { grid-template-columns: 300px minmax(0, 1fr); } }
@media (max-width: 760px) { .filing-index { grid-template-columns: 1fr 1fr; } .fr-layout { grid-template-columns: 1fr; } .fr-input-panel { position: static; border-right: 0; border-bottom: 1px solid var(--line); } .fr-input-panel textarea { min-height: 220px; } .fr-empty, .fr-output { min-height: 360px; } }
</style>
