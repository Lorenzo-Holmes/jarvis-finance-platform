<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

// 后端约束: node 最大 100 字符, context 最大 10000 字符 (backend/app/ai_routes.py ChainReq)
const MAX_NODE_CHARS = 100
const MAX_CONTEXT_CHARS = 10_000

const node = ref('')
const context = ref('')
const analyzing = ref(false)
const result = ref('')
const error = ref('')

const validation = computed(() => {
  if (!node.value.trim()) return '请填写要分析的产业节点'
  if (node.value.length > MAX_NODE_CHARS) return `节点名称不能超过 ${MAX_NODE_CHARS} 字符`
  if (context.value.length > MAX_CONTEXT_CHARS) return `背景说明不能超过 ${MAX_CONTEXT_CHARS.toLocaleString()} 字符`
  return ''
})

async function analyze() {
  if (validation.value || analyzing.value) return
  analyzing.value = true
  error.value = ''
  result.value = ''
  try {
    const response = await api.aiChain(node.value.trim(), context.value.trim())
    if (response.code !== 200 || !response.data) throw new Error(response.message || '产业链分析失败')
    result.value = response.data.content || '（暂无分析结论）'
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    analyzing.value = false
  }
}

function clearAll() {
  node.value = ''
  context.value = ''
  result.value = ''
  error.value = ''
}
</script>

<template>
  <section class="ch-workspace">
    <div class="section-bar">
      <div>
        <h1>产业链图谱</h1>
        <span>上下游 · 供需格局 · 关键厂商 · 景气度与投资逻辑</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? '输入待完善' : '输入可分析' }}</span>
    </div>

    <div class="ch-layout">
      <aside class="panel ch-input-panel">
        <div class="ch-panel-title">分析主题</div>

        <div class="ch-field">
          <label>产业节点</label>
          <input v-model="node" class="ch-input" aria-label="产业节点" placeholder="黄金 / 铜 / 芯片 / 锂电…" />
          <span>待分析的产业环节或标的名称。</span>
        </div>

        <div class="ch-field">
          <label>背景说明（可选）</label>
          <textarea v-model="context" rows="4" aria-label="背景说明" placeholder="行业/市场背景、关注的维度或补充信息…" />
          <span>用于补充行业或市场背景，提升分析针对性。</span>
        </div>

        <div class="ch-quota">
          <span>节点 {{ node.length }} / {{ MAX_NODE_CHARS }} · 背景 {{ context.length.toLocaleString() }} / {{ MAX_CONTEXT_CHARS.toLocaleString() }}</span>
        </div>

        <div v-if="validation" class="ch-validation">{{ validation }}</div>
        <button class="btn primary ch-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          {{ analyzing ? '正在分析…' : '生成产业链分析' }}
        </button>
        <div class="ch-actions">
          <button type="button" class="text-action" @click="clearAll">清空</button>
        </div>
        <div class="ch-note">梳理上下游、供需格局、关键厂商与景气度，并归纳投资逻辑。</div>
      </aside>

      <div class="ch-main">
        <template v-if="result">
          <div class="panel ch-result-panel">
            <div class="ch-result-head">
              <div><b>{{ node.trim() }} 产业链分析</b><span>产业研究参考，请结合最新数据独立判断</span></div>
            </div>
            <div class="ch-output">{{ result }}</div>
          </div>
        </template>

        <DataState v-else-if="analyzing" state="loading" title="正在分析产业链" message="梳理上下游与供需格局，通常需要数十秒" />

        <DataState v-else-if="error" state="error" title="产业链分析失败" :message="error" retryable @retry="analyze" />

        <div v-else class="panel ch-empty">
          <div class="ch-empty-mark">图谱</div>
          <b>等待分析结果</b>
          <span>在左侧填写产业节点后运行分析，即可查看上下游、供需、关键厂商与投资逻辑。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ch-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.ch-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 10px; align-items: start; }
.ch-input-panel { position: sticky; top: 10px; }
.ch-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.ch-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.ch-field label { color: var(--muted); font-size: 10px; }
.ch-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.ch-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; outline: none; }
.ch-input:focus { border-color: #6a5b40; }
.ch-field textarea { width: 100%; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); outline: none; font-size: 10px; padding: 8px 9px; resize: vertical; line-height: 1.5; }
.ch-field textarea:focus { border-color: #6a5b40; }
.ch-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.ch-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.ch-run { width: 100%; min-height: 36px; margin-top: 12px; }
.ch-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.ch-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.ch-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.ch-result-panel { display: flex; flex-direction: column; }
.ch-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.ch-result-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.ch-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.ch-output { margin-top: 10px; max-height: 560px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.ch-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.ch-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.ch-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.ch-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .ch-layout { grid-template-columns: 280px minmax(0, 1fr); } }
@media (max-width: 760px) { .ch-layout { grid-template-columns: 1fr; } .ch-input-panel { position: static; } }
</style>
