<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'
import MarkdownContent from '../components/common/MarkdownContent.vue'

const props = defineProps({
  researchContext: { type: Object, default: null },
})

// 后端约束: node 最大 100 字符, context 最大 10000 字符 (backend/app/ai_routes.py ChainReq)
const MAX_NODE_CHARS = 100
const MAX_CONTEXT_CHARS = 10_000

const node = ref('')
const context = ref('')
const analyzing = ref(false)
const result = ref('')
const error = ref('')
const selectedGraphKey = ref('focus')
const nodeFileOpen = ref(false)

const validation = computed(() => {
  if (!node.value.trim()) return '请填写要分析的产业节点'
  if (node.value.length > MAX_NODE_CHARS) return `节点名称不能超过 ${MAX_NODE_CHARS} 字符`
  if (context.value.length > MAX_CONTEXT_CHARS) return `背景说明不能超过 ${MAX_CONTEXT_CHARS.toLocaleString()} 字符`
  return ''
})
const graphNodes = computed(() => [
  { key: 'upstream', no: '01', label: 'UPSTREAM', zh: '上游输入', x: 16, y: 25, note: '原材料、资源、基础能力与供应端索引。' },
  { key: 'enablers', no: '02', label: 'ENABLERS', zh: '关键能力', x: 16, y: 72, note: '技术、设备、基础设施与关键服务索引。' },
  { key: 'focus', no: '03', label: node.value.trim() || 'TARGET NODE', zh: '当前研究节点', x: 48, y: 48, note: '当前研究对象；实体事实以用户输入和分析结果为准。' },
  { key: 'processing', no: '04', label: 'PROCESSING', zh: '中游加工', x: 73, y: 24, note: '生产、加工、平台、流通等中间环节索引。' },
  { key: 'downstream', no: '05', label: 'DOWNSTREAM', zh: '下游需求', x: 84, y: 62, note: '客户、应用、渠道与最终需求侧索引。' },
  { key: 'risk', no: '06', label: 'RISK / SUBSTITUTE', zh: '风险与替代', x: 58, y: 82, note: '政策、替代、供需冲击与其他风险验证入口。' },
])
const selectedGraphNode = computed(() => graphNodes.value.find(item => item.key === selectedGraphKey.value) || graphNodes.value[2])

function selectGraphNode(key) {
  selectedGraphKey.value = key
  nodeFileOpen.value = true
}

watch(() => props.researchContext, context => {
  if (!context || node.value.trim()) return
  node.value = context.name || context.symbol || ''
}, { immediate: true, deep: true })

watch(node, () => {
  if (selectedGraphKey.value === 'focus') return
  selectedGraphKey.value = 'focus'
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
        <h1>INDUSTRY GRAPH / NODE DOSSIER</h1>
        <span>上游 · 中游 · 下游 · 关键节点 · 供需格局与关联风险</span>
      </div>
      <span class="section-status"><i :class="{ ok: !validation }"></i>{{ validation ? 'NODE REQUIRED' : 'NODE READY' }}</span>
    </div>

    <div v-if="props.researchContext" class="context-target">
      <span>GLOBAL RESEARCH CONTEXT</span>
      <strong>{{ props.researchContext.symbol || props.researchContext.name }}</strong>
      <small>{{ props.researchContext.name || props.researchContext.market || '—' }}</small>
      <button type="button" @click="node = props.researchContext.name || props.researchContext.symbol || node">USE AS NODE</button>
    </div>

    <div class="ch-layout">
      <aside class="panel ch-input-panel">
        <div class="ch-panel-title">NODE INDEX / 分析主题</div>

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
          {{ analyzing ? 'PROCESSING…' : 'ANALYZE NODE' }}
        </button>
        <div class="ch-actions">
          <button type="button" class="text-action" @click="clearAll">清空</button>
        </div>
        <div class="ch-note">梳理上下游、供需格局、关键厂商与景气度，并归纳投资逻辑。</div>
      </aside>

      <div class="ch-main">
        <section class="industry-graph" aria-label="产业链结构图谱">
          <header>
            <div><span>INDUSTRY GRAPH / STRUCTURAL INDEX</span><strong>{{ node.trim() || 'TARGET NODE' }}</strong></div>
            <small>结构索引不代表已验证的具体供应关系；事实结论以分析文本和原始数据为准。</small>
          </header>
          <div class="graph-stage">
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              <line x1="18" y1="27" x2="46" y2="47" />
              <line x1="18" y1="72" x2="46" y2="51" />
              <line x1="51" y1="46" x2="71" y2="26" />
              <line x1="51" y1="50" x2="82" y2="61" />
              <line x1="50" y1="52" x2="58" y2="79" />
              <line x1="74" y1="28" x2="82" y2="58" />
            </svg>
            <button
              v-for="item in graphNodes"
              :key="item.key"
              type="button"
              class="graph-node"
              :class="{ active: selectedGraphKey === item.key, focus: item.key === 'focus' }"
              :style="{ left: `${item.x}%`, top: `${item.y}%` }"
              @click="selectGraphNode(item.key)"
            >
              <span>{{ item.no }}</span>
              <strong>{{ item.label }}</strong>
              <small>{{ item.zh }}</small>
            </button>
          </div>
          <footer>
            <span>UPSTREAM → TARGET → PROCESSING → DOWNSTREAM</span>
            <span>RISK / SUBSTITUTE 作为独立验证轨</span>
            <button type="button" @click="nodeFileOpen = true">OPEN NODE FILE</button>
          </footer>
        </section>

        <aside class="node-file" :class="{ open: nodeFileOpen }" aria-label="产业节点详情">
          <button type="button" class="node-file-close" aria-label="关闭节点详情" @click="nodeFileOpen = false">×</button>
          <div class="node-file-head">
            <span>NODE FILE / {{ selectedGraphNode.no }}</span>
            <strong>{{ selectedGraphNode.label }}</strong>
            <small>{{ selectedGraphNode.zh }}</small>
          </div>
          <p>{{ selectedGraphNode.note }}</p>
          <div class="node-file-meta">
            <div><span>TARGET</span><b>{{ node.trim() || '—' }}</b></div>
            <div><span>CONTEXT</span><b>{{ props.researchContext?.symbol || 'LOCAL INPUT' }}</b></div>
            <div><span>ANALYSIS</span><b>{{ result ? 'AVAILABLE' : analyzing ? 'PROCESSING' : 'PENDING' }}</b></div>
          </div>

          <div v-if="result" class="ch-result-panel">
            <div class="ch-result-head"><div><b>ANALYSIS DOSSIER</b><span>模型分析原文，不从文本中伪造结构化关系</span></div></div>
            <MarkdownContent class="ch-output" :content="result" />
          </div>
          <DataState v-else-if="analyzing" state="loading" title="正在分析产业链" message="梳理上下游与供需格局，通常需要数十秒" />
          <DataState v-else-if="error" state="error" title="产业链分析失败" :message="error" retryable @retry="analyze" />
          <div v-else class="ch-empty">
            <div class="ch-empty-mark">NODE</div>
            <b>WAITING FOR NODE ANALYSIS</b>
            <span>选择图谱结构节点可浏览研究维度；运行分析后，右侧显示真实模型分析文本。</span>
          </div>
        </aside>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ch-workspace { display: flex; flex-direction: column; gap: 12px; margin: 0; }
.context-target { min-height: 38px; display: flex; align-items: center; gap: 12px; padding: 0 12px; border: 1px solid var(--line); background: rgba(161,132,88,.045); }
.context-target span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.context-target strong { color: var(--text); font: 650 10px/1 ui-monospace, monospace; }
.context-target small { color: var(--muted); font-size: 9px; }
.context-target button { margin-left: auto; min-height: 28px; border: 1px solid var(--line-strong); background: transparent; color: var(--text); cursor: pointer; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .07em; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.ch-layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 0; align-items: stretch; border: 1px solid var(--line); }
.ch-input-panel { position: sticky; top: 10px; border: 0 !important; border-right: 1px solid var(--line) !important; }
.ch-panel-title { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .08em; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.ch-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.ch-field label { color: var(--muted); font-size: 10px; }
.ch-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.ch-input { width: 100%; height: 34px; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); border-radius: 0; color: var(--text); padding: 0 4px; font-size: 12px; outline: none; }
.ch-input:focus { border-color: #6a5b40; }
.ch-field textarea { width: 100%; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); color: var(--text); border-radius: 0; outline: none; font-size: 10px; padding: 8px 4px; resize: vertical; line-height: 1.6; }
.ch-field textarea:focus { border-color: #6a5b40; }
.ch-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.ch-validation { margin-top: 10px; color: #e3b466; background: rgba(227,180,102,.07); border-left: 2px solid #8a6d3e; padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.ch-run { width: 100%; min-height: 36px; margin-top: 12px; }
.ch-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.ch-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.ch-main { display: grid; grid-template-columns: minmax(0, 1.9fr) minmax(260px, .72fr); min-width: 0; min-height: 600px; }
.industry-graph { min-width: 0; display: flex; flex-direction: column; padding: 16px; border-right: 1px solid var(--line); background: rgba(245,242,235,.28); }
.industry-graph > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.industry-graph > header div { display: grid; gap: 6px; }
.industry-graph > header span { color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .1em; }
.industry-graph > header strong { color: var(--text); font-size: 14px; font-weight: 650; }
.industry-graph > header small { max-width: 360px; color: var(--subtle); font-size: 9px; line-height: 1.5; text-align: right; }
.graph-stage { position: relative; flex: 1; min-height: 470px; overflow: hidden; }
.graph-stage::before { content: ''; position: absolute; inset: 9% 7%; background-image: linear-gradient(rgba(132,126,116,.08) 1px, transparent 1px), linear-gradient(90deg, rgba(132,126,116,.08) 1px, transparent 1px); background-size: 44px 44px; }
.graph-stage svg { position: absolute; inset: 7% 5%; width: 90%; height: 86%; overflow: visible; }
.graph-stage line { stroke: rgba(112,105,94,.6); stroke-width: .22; vector-effect: non-scaling-stroke; }
.graph-node { position: absolute; width: min(148px, 22%); min-height: 64px; transform: translate(-50%,-50%); padding: 8px 10px; border: 1px solid var(--line-strong); background: rgba(238,234,226,.94); color: var(--muted); text-align: left; cursor: pointer; box-shadow: 0 8px 24px rgba(77,68,55,.05); }
.graph-node > span { display: block; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.graph-node strong { display: block; margin-top: 7px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .04em; }
.graph-node small { display: block; margin-top: 5px; color: var(--subtle); font-size: 9px; }
.graph-node.focus { border-color: var(--accent); background: rgba(218,209,194,.92); color: var(--text); }
.graph-node.active { outline: 2px solid rgba(138,118,87,.24); outline-offset: 3px; color: var(--text); }
.industry-graph > footer { display: flex; justify-content: space-between; gap: 14px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--subtle); font: 600 7px/1.4 ui-monospace, monospace; letter-spacing: .05em; }
.industry-graph > footer button { display: none; border: 1px solid var(--line-strong); background: transparent; color: var(--text); min-height: 32px; padding: 0 10px; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .07em; cursor: pointer; }
.node-file { min-width: 0; padding: 16px; display: flex; flex-direction: column; gap: 14px; background: rgba(239,235,227,.48); }
.node-file-close { display: none; position: absolute; top: 10px; right: 12px; width: 36px; height: 36px; border: 1px solid var(--line-strong); background: rgba(239,235,227,.96); color: var(--muted); font-size: 22px; cursor: pointer; }
.node-file-head { display: grid; gap: 6px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.node-file-head span { color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .1em; }
.node-file-head strong { color: var(--text); font-size: 16px; font-weight: 650; overflow-wrap: anywhere; }
.node-file-head small { color: var(--muted); font-size: 10px; }
.node-file > p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.65; }
.node-file-meta { border-top: 1px solid var(--line); }
.node-file-meta > div { display: flex; justify-content: space-between; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); }
.node-file-meta span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .08em; }
.node-file-meta b { color: var(--text); font: 600 9px/1 ui-monospace, monospace; text-align: right; overflow-wrap: anywhere; }
.ch-result-panel { display: flex; min-height: 0; flex-direction: column; }
.ch-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.ch-result-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.ch-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.ch-output { margin-top: 10px; max-height: 330px; overflow: auto; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; padding: 12px 2px; color: var(--text); font-size: 10px; line-height: 1.72; overflow-wrap: anywhere; }
.ch-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 28px 8px; text-align: center; }
.ch-empty-mark { color: var(--accent-strong); border: 1px solid var(--line-strong); border-radius: 0; width: 58px; height: 40px; display: grid; place-items: center; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .08em; }
.ch-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.ch-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 1100px) { .ch-layout { grid-template-columns: 260px minmax(0, 1fr); } .ch-main { grid-template-columns: 1fr; } .industry-graph { border-right: 0; border-bottom: 1px solid var(--line); } .graph-stage { min-height: 430px; } }
@media (max-width: 760px) {
  .ch-layout { grid-template-columns: 1fr; }
  .ch-input-panel { position: static; border-right: 0 !important; border-bottom: 1px solid var(--line) !important; }
  .graph-stage { min-height: 390px; }
  .industry-graph > header { flex-direction: column; }
  .industry-graph > header small { text-align: left; }
  .industry-graph > footer { flex-direction: column; }
  .industry-graph > footer button { display: block; align-self: flex-start; }
  .graph-node { width: 30%; }
  .node-file { position: fixed; z-index: 90; left: 12px; right: 12px; bottom: 12px; max-height: min(72vh, 620px); overflow: auto; border: 1px solid var(--line-strong); box-shadow: 0 24px 70px rgba(67,58,46,.22); transform: translateY(calc(100% + 32px)); transition: transform .22s ease; background: rgba(239,235,227,.985); }
  .node-file.open { transform: translateY(0); }
  .node-file-close { display: block; }
  .node-file-head { padding-right: 44px; }
}

@media (prefers-reduced-motion: reduce) {
  .node-file { transition-duration: .01ms; }
}
</style>
