<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

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
        <h1>财报智能解析</h1>
        <span>营收利润 · 财务健康 · 风险点 · 稳健建议</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '输入待完善' : '输入可解析' }}</span>
    </div>

    <div class="fr-layout">
      <aside class="panel fr-input-panel">
        <div class="fr-panel-title">财报文本</div>

        <textarea v-model="content" rows="14" aria-label="财报内容" placeholder="粘贴财报原文、关键财务数据或业绩摘要…" />

        <div class="fr-quota">
          <span>{{ content.length.toLocaleString() }} / {{ MAX_CONTENT_CHARS.toLocaleString() }} 字符</span>
        </div>

        <div v-if="validation" class="fr-validation">{{ validation }}</div>
        <button class="btn primary fr-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          {{ analyzing ? '正在解析…' : '运行财报解析' }}
        </button>
        <div class="fr-actions">
          <button type="button" class="text-action" @click="clearAll">清空</button>
        </div>
        <div class="fr-note">解析营收/利润变动、毛利率、资产负债、现金流与风险点，并给出稳健投资建议。</div>
      </aside>

      <div class="fr-main">
        <template v-if="result">
          <div class="panel fr-result-panel">
            <div class="fr-result-head">
              <div><b>解析结论</b><span>基于文本的结构化财务解读，仅供参考，请以原始财报为准</span></div>
            </div>
            <div class="fr-output">{{ result }}</div>
          </div>
        </template>

        <DataState v-else-if="analyzing" state="loading" title="正在解析财报" message="提取财务结构、健康度与风险点，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="财报解析失败" :message="error" retryable @retry="analyze" />

        <div v-else class="panel fr-empty">
          <div class="fr-empty-mark">财报</div>
          <b>等待解析结果</b>
          <span>在左侧粘贴财报文本后运行解析，即可查看营收、利润、现金流与风险点的结构化解读。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.fr-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.fr-layout { display: grid; grid-template-columns: 340px minmax(0, 1fr); gap: 10px; align-items: start; }
.fr-input-panel { position: sticky; top: 10px; }
.fr-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.fr-input-panel textarea { width: 100%; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); outline: none; font-size: 10px; padding: 8px 9px; resize: vertical; line-height: 1.6; margin-top: 12px; }
.fr-input-panel textarea:focus { border-color: #6a5b40; }
.fr-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.fr-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.fr-run { width: 100%; min-height: 36px; margin-top: 12px; }
.fr-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.fr-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.fr-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.fr-result-panel { display: flex; flex-direction: column; }
.fr-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.fr-result-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.fr-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.fr-output { margin-top: 10px; max-height: 560px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.fr-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.fr-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.fr-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.fr-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .fr-layout { grid-template-columns: 280px minmax(0, 1fr); } }
@media (max-width: 760px) { .fr-layout { grid-template-columns: 1fr; } .fr-input-panel { position: static; } }
</style>
