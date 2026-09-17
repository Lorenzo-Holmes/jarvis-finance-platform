<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
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
const inputPanelOpen = ref(typeof window === 'undefined' || window.innerWidth > 1180)
const hoveredGraphKey = ref('')
const analysisPhase = ref('idle')
const nodeStates = ref({})
const activeEdgeKeys = ref([])
const processedEdgeKeys = ref([])
const ambientFocus = ref('center')
let analysisRunId = 0

const validation = computed(() => {
  if (!node.value.trim()) return '请填写要分析的产业节点'
  if (node.value.length > MAX_NODE_CHARS) return `节点名称不能超过 ${MAX_NODE_CHARS} 字符`
  if (context.value.length > MAX_CONTEXT_CHARS) return `背景说明不能超过 ${MAX_CONTEXT_CHARS.toLocaleString()} 字符`
  return ''
})
const graphNodes = computed(() => [
  { key: 'upstream', no: '01', label: '上游输入', zh: '资源与供应端', x: 16, y: 25, note: '原材料、资源、基础能力与供应端索引。' },
  { key: 'enablers', no: '02', label: '关键能力', zh: '技术与基础设施', x: 16, y: 72, note: '技术、设备、基础设施与关键服务索引。' },
  { key: 'focus', no: '03', label: node.value.trim() || '当前节点', zh: '当前研究对象', x: 48, y: 48, note: '当前研究对象；实体事实以用户输入和分析结果为准。' },
  { key: 'processing', no: '04', label: '中游加工', zh: '生产与流通', x: 73, y: 24, note: '生产、加工、平台、流通等中间环节索引。' },
  { key: 'downstream', no: '05', label: '下游需求', zh: '客户与应用', x: 84, y: 62, note: '客户、应用、渠道与最终需求侧索引。' },
  { key: 'risk', no: '06', label: '风险与替代', zh: '独立验证', x: 58, y: 82, note: '政策、替代、供需冲击与其他风险验证入口。' },
])
const graphEdges = Object.freeze([
  { key: 'focus-upstream', source: 'focus', target: 'upstream', x1: 48, y1: 48, x2: 18, y2: 27 },
  { key: 'focus-enablers', source: 'focus', target: 'enablers', x1: 48, y1: 50, x2: 18, y2: 72 },
  { key: 'focus-processing', source: 'focus', target: 'processing', x1: 51, y1: 46, x2: 71, y2: 26 },
  { key: 'focus-downstream', source: 'focus', target: 'downstream', x1: 51, y1: 50, x2: 82, y2: 61 },
  { key: 'focus-risk', source: 'focus', target: 'risk', x1: 50, y1: 52, x2: 58, y2: 79 },
  { key: 'processing-downstream', source: 'processing', target: 'downstream', x1: 74, y1: 28, x2: 82, y2: 58 },
])
const propagationWaves = Object.freeze([
  ['upstream', 'enablers', 'processing'],
  ['downstream', 'risk'],
])
const NODE_ACTIVITY_LABELS = Object.freeze({
  upstream: '供给读取',
  enablers: '能力匹配',
  processing: '结构匹配',
  downstream: '需求验证',
  risk: '风险验证',
})
const AMBIENT_POINTS = Object.freeze({
  center: { x: '50%', y: '50%' },
  firstWave: { x: '42%', y: '42%' },
  secondWave: { x: '68%', y: '57%' },
  validate: { x: '52%', y: '50%' },
})
const selectedGraphNode = computed(() => graphNodes.value.find(item => item.key === selectedGraphKey.value) || graphNodes.value[2])
const hoverRelatedEdgeKeys = computed(() => {
  if (!hoveredGraphKey.value) return []
  return graphEdges
    .filter(edge => edge.source === hoveredGraphKey.value || edge.target === hoveredGraphKey.value)
    .map(edge => edge.key)
})
const hoverRelatedNodeKeys = computed(() => {
  if (!hoveredGraphKey.value) return new Set()
  const keys = new Set([hoveredGraphKey.value])
  graphEdges.forEach(edge => {
    if (edge.source === hoveredGraphKey.value || edge.target === hoveredGraphKey.value) {
      keys.add(edge.source)
      keys.add(edge.target)
    }
  })
  return keys
})
const ambientPosition = computed(() => AMBIENT_POINTS[ambientFocus.value] || AMBIENT_POINTS.center)
const analysisStatus = computed(() => {
  if (analysisPhase.value === 'locking') return { label: '建立分析上下文', detail: '正在锁定研究对象与产业结构范围' }
  if (analysisPhase.value === 'propagating') return { label: '并行计算结构', detail: '供给、能力与加工路径正在同步计算' }
  if (analysisPhase.value === 'expanding') return { label: '扩展需求与风险', detail: '下游需求与替代风险进入分析队列' }
  if (analysisPhase.value === 'validating') return { label: '交叉验证关系', detail: '正在整理各结构维度并等待真实分析结果' }
  if (analysisPhase.value === 'settling') return { label: '整理分析结论', detail: '正在把计算态收束为可阅读结果' }
  if (analysisPhase.value === 'completed') return { label: '分析完成', detail: '结构特征与分析文本已生成' }
  if (analysisPhase.value === 'error') return { label: '分析中断', detail: error.value || '请检查输入或服务状态' }
  return { label: '等待分析', detail: '输入产业节点后开始结构计算' }
})
const researchPlan = computed(() => {
  const rows = graphNodes.value.filter(item => item.key !== 'focus').map(item => ({
    key: item.key,
    label: item.label,
    detail: NODE_ACTIVITY_LABELS[item.key],
    state: nodeStates.value[item.key] || 'pending',
  }))
  rows.push({
    key: 'summary',
    label: '汇总结论',
    detail: '结果整理',
    state: analysisPhase.value === 'completed'
      ? 'done'
      : analysisPhase.value === 'settling'
        ? 'active'
        : ['locking', 'propagating', 'expanding', 'validating'].includes(analysisPhase.value)
          ? 'pending'
          : 'idle',
  })
  return rows
})

function wait(ms) {
  return new Promise(resolve => window.setTimeout(resolve, ms))
}

function updateNodeStates(keys, state) {
  const next = { ...nodeStates.value }
  keys.forEach(key => { next[key] = state })
  nodeStates.value = next
}

function edgesForTargets(keys) {
  return graphEdges.filter(edge => keys.includes(edge.target)).map(edge => edge.key)
}

function edgePath(edge) {
  const midX = (edge.x1 + edge.x2) / 2
  const midY = (edge.y1 + edge.y2) / 2
  const dx = edge.x2 - edge.x1
  const dy = edge.y2 - edge.y1
  const bend = edge.target === 'upstream'
    ? -0.075
    : edge.target === 'enablers'
      ? 0.075
      : edge.target === 'risk'
        ? 0.055
        : -0.035
  const controlX = midX - dy * bend
  const controlY = midY + dx * bend
  return `M ${edge.x1} ${edge.y1} Q ${controlX.toFixed(2)} ${controlY.toFixed(2)} ${edge.x2} ${edge.y2}`
}

async function runPropagationWave(runId, keys, focusKey, duration = 760) {
  if (runId !== analysisRunId) return false
  ambientFocus.value = focusKey
  updateNodeStates(keys, 'active')
  activeEdgeKeys.value = edgesForTargets(keys)
  await wait(duration)
  if (runId !== analysisRunId) return false
  updateNodeStates(keys, 'done')
  processedEdgeKeys.value = [...new Set([...processedEdgeKeys.value, ...activeEdgeKeys.value])]
  activeEdgeKeys.value = []
  return true
}

async function runAnalysisAnimation(runId) {
  analysisPhase.value = 'locking'
  ambientFocus.value = 'center'
  nodeStates.value = {}
  activeEdgeKeys.value = []
  processedEdgeKeys.value = []
  await wait(360)
  if (runId !== analysisRunId) return

  analysisPhase.value = 'propagating'
  if (!await runPropagationWave(runId, propagationWaves[0], 'firstWave', 820)) return
  await wait(140)
  if (runId !== analysisRunId) return

  analysisPhase.value = 'expanding'
  if (!await runPropagationWave(runId, propagationWaves[1], 'secondWave', 720)) return

  if (runId !== analysisRunId) return
  analysisPhase.value = 'validating'
  ambientFocus.value = 'validate'
  activeEdgeKeys.value = processedEdgeKeys.value
  await wait(760)
  if (runId !== analysisRunId) return
  activeEdgeKeys.value = []
}

function nodeAnalysisClass(key) {
  const state = nodeStates.value[key]
  return {
    'target-locked': key === 'focus' && ['locking', 'propagating', 'expanding', 'validating', 'settling', 'completed'].includes(analysisPhase.value),
    computing: state === 'active',
    processed: state === 'done',
    'glass-dormant': analyzing.value && key !== 'focus' && !state,
    'glass-revealed': state === 'active' || state === 'done',
    'analysis-result': analysisPhase.value === 'completed' && (key === 'focus' || state === 'done'),
  }
}

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
  const runId = ++analysisRunId
  analyzing.value = true
  error.value = ''
  result.value = ''
  try {
    const animationPromise = runAnalysisAnimation(runId)
    const outcome = await api.aiChain(node.value.trim(), context.value.trim())
      .then(response => ({ response, requestError: null }))
      .catch(requestError => ({ response: null, requestError }))
    if (runId !== analysisRunId) return
    if (outcome.requestError) throw outcome.requestError
    await animationPromise
    if (runId !== analysisRunId) return
    const response = outcome.response
    if (response.code !== 200 || !response.data) throw new Error(response.message || '产业链分析失败')
    analysisPhase.value = 'settling'
    ambientFocus.value = 'center'
    await wait(460)
    if (runId !== analysisRunId) return
    result.value = response.data.content || '（暂无分析结论）'
    analysisPhase.value = 'completed'
  } catch (e) {
    if (runId !== analysisRunId) return
    error.value = e?.message || String(e)
    analysisPhase.value = 'error'
  } finally {
    if (runId === analysisRunId) {
      analyzing.value = false
      activeEdgeKeys.value = []
    }
  }
}

function clearAll() {
  if (analyzing.value) return
  analysisRunId += 1
  node.value = ''
  context.value = ''
  result.value = ''
  error.value = ''
  analysisPhase.value = 'idle'
  nodeStates.value = {}
  activeEdgeKeys.value = []
  processedEdgeKeys.value = []
  ambientFocus.value = 'center'
}

onBeforeUnmount(() => {
  analysisRunId += 1
})
</script>

<template>
  <section class="ch-workspace">
    <div class="section-bar">
      <div>
        <h1>产业链图谱</h1>
        <span>上游 · 中游 · 下游 · 关键节点 · 供需格局与关联风险</span>
      </div>
      <div class="section-actions">
        <button
          type="button"
          class="panel-toggle"
          :class="{ active: inputPanelOpen }"
          :aria-expanded="inputPanelOpen"
          aria-controls="chain-input-panel"
          @click="inputPanelOpen = !inputPanelOpen"
        >
          <i aria-hidden="true"></i>
          参数
        </button>
        <span class="section-status" :class="{ analyzing, completed: analysisPhase === 'completed' }">
          <i :class="{ ok: !validation && !analyzing, pulse: analyzing }"></i>
          {{ analyzing ? analysisStatus.label : analysisPhase === 'completed' ? '分析完成' : validation ? '请填写产业节点' : '可分析' }}
        </span>
      </div>
    </div>

    <div v-if="props.researchContext" class="context-target">
      <span>研究上下文</span>
      <strong>{{ props.researchContext.symbol || props.researchContext.name }}</strong>
      <small>{{ props.researchContext.name || props.researchContext.market || '—' }}</small>
      <button type="button" :disabled="analyzing" @click="node = props.researchContext.name || props.researchContext.symbol || node">作为节点使用</button>
    </div>

    <div class="ch-layout" :class="{ 'input-collapsed': !inputPanelOpen }">
      <aside id="chain-input-panel" class="panel ch-input-panel" :class="{ open: inputPanelOpen }">
        <button type="button" class="input-panel-close" aria-label="收起分析参数" @click="inputPanelOpen = false">×</button>
        <div class="ch-panel-title">分析主题</div>

        <div class="ch-field">
          <label>产业节点</label>
          <input v-model="node" class="ch-input" :disabled="analyzing" aria-label="产业节点" placeholder="黄金 / 铜 / 芯片 / 锂电…" />
          <span>待分析的产业环节或标的名称。</span>
        </div>

        <div class="ch-field">
          <label>背景说明（可选）</label>
          <textarea v-model="context" rows="4" :disabled="analyzing" aria-label="背景说明" placeholder="行业/市场背景、关注的维度或补充信息…" />
          <span>用于补充行业或市场背景，提升分析针对性。</span>
        </div>

        <div class="ch-quota">
          <span>节点 {{ node.length }} / {{ MAX_NODE_CHARS }} · 背景 {{ context.length.toLocaleString() }} / {{ MAX_CONTEXT_CHARS.toLocaleString() }}</span>
        </div>

        <div v-if="validation" class="ch-validation">{{ validation }}</div>
        <button class="btn primary ch-run" type="button" :disabled="analyzing || !!validation" @click="analyze">
          <i v-if="analyzing" class="ch-run-indicator" aria-hidden="true"></i>
          {{ analyzing ? analysisStatus.label : result ? '重新分析' : '开始分析' }}
        </button>
        <div class="ch-actions">
          <button type="button" class="text-action" :disabled="analyzing" @click="clearAll">清空</button>
        </div>
        <div class="ch-note">梳理上下游、供需格局、关键厂商与景气度，并归纳投资逻辑。</div>
      </aside>

      <div class="ch-main">
        <section class="industry-graph" aria-label="产业链结构图谱">
          <header>
            <div><span>产业结构</span><strong>{{ node.trim() || '当前节点' }}</strong></div>
            <small>结构索引不代表已验证的具体供应关系；事实结论以分析文本和原始数据为准。</small>
          </header>
          <div
            class="graph-stage"
            :class="[`phase-${analysisPhase}`, { analyzing }]"
            :style="{ '--analysis-x': ambientPosition.x, '--analysis-y': ambientPosition.y }"
          >
            <div v-if="analyzing || analysisPhase === 'completed'" class="analysis-status-overlay" aria-live="polite">
              <i class="analysis-status-dot" :class="{ pulse: analyzing }"></i>
              <span><b>{{ analysisStatus.label }}</b><small>{{ analysisStatus.detail }}</small></span>
            </div>
            <div v-if="analyzing" class="graph-ambient-field" aria-hidden="true"></div>
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              <g
                v-for="edge in graphEdges"
                :key="edge.key"
                class="graph-edge"
                :class="{
                  'hover-linked': !analyzing && hoverRelatedEdgeKeys.includes(edge.key),
                  'hover-muted': !analyzing && hoveredGraphKey && !hoverRelatedEdgeKeys.includes(edge.key),
                }"
              >
                <path class="edge-base" :d="edgePath(edge)" fill="none" />
                <path
                  class="edge-scan"
                  :class="{
                    active: activeEdgeKeys.includes(edge.key),
                    processed: processedEdgeKeys.includes(edge.key),
                    validating: analysisPhase === 'validating' && processedEdgeKeys.includes(edge.key),
                    settled: analysisPhase === 'completed' && processedEdgeKeys.includes(edge.key),
                  }"
                  pathLength="1"
                  :d="edgePath(edge)"
                  fill="none"
                />
              </g>
            </svg>
            <button
              v-for="item in graphNodes"
              :key="item.key"
              type="button"
              class="graph-node"
              :class="[
                {
                  active: selectedGraphKey === item.key,
                  focus: item.key === 'focus',
                  'hover-linked': !analyzing && hoveredGraphKey && hoverRelatedNodeKeys.has(item.key),
                  'hover-muted': !analyzing && hoveredGraphKey && !hoverRelatedNodeKeys.has(item.key),
                },
                nodeAnalysisClass(item.key),
              ]"
              :style="{ left: `${item.x}%`, top: `${item.y}%` }"
              @mouseenter="hoveredGraphKey = item.key"
              @mouseleave="hoveredGraphKey = ''"
              @focus="hoveredGraphKey = item.key"
              @blur="hoveredGraphKey = ''"
              @click="selectGraphNode(item.key)"
            >
              <i v-if="item.key === 'focus'" class="node-calibration" aria-hidden="true"></i>
              <span>{{ item.no }}</span>
              <strong>{{ item.label }}</strong>
              <small>{{ item.zh }}</small>
              <em v-if="analyzing && item.key !== 'focus'" class="node-compute-state">
                <i></i>{{ nodeStates[item.key] === 'active' ? NODE_ACTIVITY_LABELS[item.key] : nodeStates[item.key] === 'done' ? '就绪' : '等待' }}
              </em>
            </button>
          </div>
          <footer>
            <span>上游 → 当前节点 → 中游 → 下游</span>
            <span>风险与替代作为独立验证轨</span>
            <button type="button" @click="nodeFileOpen = true">查看节点详情</button>
          </footer>
        </section>

        <aside class="node-file" :class="{ open: nodeFileOpen }" aria-label="产业节点详情">
          <button type="button" class="node-file-close" aria-label="关闭节点详情" @click="nodeFileOpen = false">×</button>
          <div class="node-file-head">
            <span>节点 {{ selectedGraphNode.no }}</span>
            <strong>{{ selectedGraphNode.label }}</strong>
            <small>{{ selectedGraphNode.zh }}</small>
          </div>
          <p>{{ selectedGraphNode.note }}</p>
          <div class="node-file-meta">
            <div><span>研究对象</span><b>{{ node.trim() || '—' }}</b></div>
            <div><span>上下文</span><b>{{ props.researchContext?.symbol || '手动输入' }}</b></div>
            <div><span>分析状态</span><b>{{ result ? '已完成' : analyzing ? '分析中' : '待分析' }}</b></div>
          </div>

          <div v-if="result" class="ch-result-panel">
            <div class="ch-result-head"><div><b>分析结果</b><span>模型分析原文，不从文本中伪造结构化关系</span></div></div>
            <MarkdownContent class="ch-output" :content="result" />
          </div>
          <div v-else-if="analyzing" class="chain-analysis-plan" aria-live="polite">
            <div class="chain-analysis-plan-head">
              <span><b>{{ analysisStatus.label }}</b><small>{{ analysisStatus.detail }}</small></span>
              <i class="plan-activity" aria-hidden="true"><b></b><b></b><b></b></i>
            </div>
            <ol>
              <li v-for="item in researchPlan" :key="item.key" :class="[`state-${item.state}`]">
                <i aria-hidden="true"></i>
                <span><b>{{ item.label }}</b><small>{{ item.detail }}</small></span>
                <em>{{ item.state === 'done' ? '完成' : item.state === 'active' ? '处理中' : item.state === 'pending' ? '等待' : '—' }}</em>
              </li>
            </ol>
          </div>
          <DataState v-else-if="error" state="error" title="产业链分析失败" :message="error" retryable @retry="analyze" />
          <div v-else class="ch-empty">
            <div class="ch-empty-mark">节点</div>
            <b>等待产业链分析</b>
            <span>选择图谱结构节点可浏览研究维度；运行分析后，右侧显示真实模型分析文本。</span>
          </div>
        </aside>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ch-workspace { display: flex; flex-direction: column; gap: 14px; margin: 0; }
.context-target { position: relative; min-height: 42px; display: flex; align-items: center; gap: 12px; padding: 0 13px 0 15px; border: 1px solid var(--material-border, var(--line)); border-radius: 10px; background: color-mix(in srgb, var(--material-glass, transparent) 56%, transparent); box-shadow: inset 0 1px 0 rgba(255,255,255,.022); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); }
.context-target::before { content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 1px; background: var(--accent); }
.context-target span { color: var(--subtle); font: 600 9px/1 ui-monospace, monospace; letter-spacing: .06em; }
.context-target strong { color: var(--text); font: 650 12px/1 Inter, "MiSans", "PingFang SC", sans-serif; }
.context-target small { color: var(--muted); font-size: 10px; }
.context-target button { margin-left: auto; min-height: 30px; border: 1px solid transparent; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; padding: 0 9px; font: 600 10px/1 Inter, "MiSans", "PingFang SC", sans-serif; transition: color .16s ease, background .16s ease, transform .1s ease; }
.context-target button:hover { color: var(--accent-strong); border-color: var(--accent); }
.context-target button:active { transform: scale(.97); }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 48px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 21px; line-height: 1.1; font-weight: 660; letter-spacing: -.018em; }
.section-bar > div:first-child > span { display: block; margin-top: 5px; color: var(--subtle); font-size: 11px; }
.section-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.panel-toggle { min-height: 31px; display: inline-flex; align-items: center; gap: 7px; padding: 0 10px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font: 600 10px/1 Inter, "MiSans", "PingFang SC", sans-serif; transition: color .16s ease, background .16s ease, border-color .16s ease, transform .1s ease; }
.panel-toggle > i { width: 10px; height: 10px; border: 1px solid currentColor; border-radius: 2px; box-shadow: inset 3px 0 0 color-mix(in srgb, currentColor 42%, transparent); opacity: .72; }
.panel-toggle:hover { color: var(--text); background: var(--workspace-hover-bg, rgba(255,255,255,.035)); }
.panel-toggle.active { color: var(--accent-strong); border-color: color-mix(in srgb, var(--accent) 38%, var(--material-border, var(--line))); background: color-mix(in srgb, var(--accent) 5%, transparent); }
.panel-toggle:active { transform: scale(.97); }
.panel-toggle:focus-visible { outline: 0; box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus, var(--accent)) 12%, transparent); }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.section-status.analyzing { color: var(--accent-strong); }
.section-status.completed { color: var(--muted); }
.section-status i.pulse { background: var(--accent); animation: chain-status-pulse 1.15s ease-in-out infinite; }
.ch-layout { display: grid; grid-template-columns: 236px minmax(0, 1fr); gap: 14px; align-items: stretch; border: 0; transition: grid-template-columns .3s cubic-bezier(.22,1,.36,1), gap .24s ease; }
.ch-layout.input-collapsed { grid-template-columns: 0 minmax(0, 1fr); gap: 0; }
.ch-input-panel { position: sticky; top: 8px; align-self: start; min-width: 0; padding: 14px !important; border: 1px solid var(--material-border, var(--line)) !important; border-radius: 12px !important; background: color-mix(in srgb, var(--material-glass, transparent) 58%, transparent) !important; box-shadow: inset 0 1px 0 rgba(255,255,255,.024), 0 16px 40px rgba(0,0,0,.06) !important; backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); opacity: 1; transform: translateX(0); transform-origin: left center; transition: opacity .18s ease, transform .28s cubic-bezier(.22,1,.36,1), padding .24s ease, border-color .18s ease; }
.ch-layout.input-collapsed .ch-input-panel { padding-left: 0 !important; padding-right: 0 !important; border-color: transparent !important; box-shadow: none !important; opacity: 0; pointer-events: none; transform: translateX(-14px); overflow: hidden; }
.input-panel-close { display: none; position: absolute; top: 10px; right: 10px; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 7px; background: rgba(255,255,255,.025); color: var(--subtle); cursor: pointer; font-size: 17px; line-height: 1; transition: color .16s ease, background .16s ease, transform .1s ease; }
.input-panel-close:hover { color: var(--text); background: rgba(255,255,255,.05); }
.input-panel-close:active { transform: scale(.94); }
.ch-panel-title { color: var(--text); font: 650 12px/1.1 Inter, "MiSans", "PingFang SC", sans-serif; letter-spacing: -.01em; padding-bottom: 12px; border-bottom: 1px solid color-mix(in srgb, var(--line) 54%, transparent); }
.ch-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.ch-field label { color: var(--muted); font-size: 11px; }
.ch-field > span { color: var(--subtle); font-size: 10px; line-height: 1.55; }
.ch-input { width: 100%; height: 36px; background: rgba(255,255,255,.018); border: 1px solid transparent; border-bottom-color: color-mix(in srgb, var(--line-strong) 76%, transparent); border-radius: 8px 8px 4px 4px; color: var(--text); padding: 0 9px; font-size: 12px; outline: none; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.ch-input:focus { border-color: var(--workspace-focus); }
.ch-input:focus { background: rgba(255,255,255,.03); box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus) 8%, transparent); }
.ch-field textarea { width: 100%; background: rgba(255,255,255,.018); border: 1px solid transparent; border-bottom-color: color-mix(in srgb, var(--line-strong) 76%, transparent); color: var(--text); border-radius: 8px 8px 4px 4px; outline: none; font-size: 11px; padding: 9px; resize: vertical; line-height: 1.6; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.ch-field textarea:focus { border-color: var(--workspace-focus); }
.ch-field textarea:focus { background: rgba(255,255,255,.03); box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus) 8%, transparent); }
.ch-quota { margin-top: 10px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.ch-validation { margin-top: 10px; color: var(--workspace-warning-text); background: var(--workspace-warning-bg); border-left: 2px solid var(--workspace-warning-border); padding: 8px 9px; font-size: 10px; line-height: 1.5; }
.ch-run { width: 100%; min-height: 36px; margin-top: 12px; display: inline-flex; align-items: center; justify-content: center; gap: 9px; }
.ch-run-indicator { width: 20px; height: 1px; position: relative; overflow: hidden; background: color-mix(in srgb, var(--accent) 18%, transparent); }
.ch-run-indicator::after { content: ''; position: absolute; inset: 0; width: 45%; background: var(--accent-strong); transform: translateX(-120%); animation: chain-button-scan .9s linear infinite; }
.ch-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.text-action { border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.text-action:disabled { opacity: .35; cursor: default; }
.ch-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.ch-main { position: relative; min-width: 0; min-height: 640px; overflow: hidden; border: 1px solid color-mix(in srgb, var(--line) 46%, transparent); border-radius: 14px; background: color-mix(in srgb, var(--surface) 42%, transparent); box-shadow: inset 0 1px 0 rgba(255,255,255,.018); }
.industry-graph { min-width: 0; min-height: 640px; display: flex; flex-direction: column; padding: 18px 20px 14px; background: transparent; }
.industry-graph > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 14px; border-bottom: 1px solid color-mix(in srgb, var(--line) 42%, transparent); }
.industry-graph > header div { display: grid; gap: 6px; }
.industry-graph > header span { color: var(--muted); font: 600 9px/1 ui-monospace, monospace; letter-spacing: .08em; }
.industry-graph > header strong { color: var(--text); font-size: 16px; font-weight: 650; letter-spacing: -.01em; }
.industry-graph > header small { max-width: 420px; color: var(--subtle); font-size: 10px; line-height: 1.55; text-align: right; }
.graph-stage { position: relative; flex: 1; min-height: 520px; overflow: hidden; isolation: isolate; }
.graph-stage::before { content: ''; position: absolute; inset: 8% 5%; opacity: .46; background-image: linear-gradient(color-mix(in srgb, var(--workspace-graph-grid, rgba(132,126,116,.08)) 58%, transparent) 1px, transparent 1px), linear-gradient(90deg, color-mix(in srgb, var(--workspace-graph-grid, rgba(132,126,116,.08)) 58%, transparent) 1px, transparent 1px); background-size: 68px 68px; -webkit-mask-image: radial-gradient(ellipse at 50% 48%, #000 0 46%, rgba(0,0,0,.74) 66%, transparent 100%); mask-image: radial-gradient(ellipse at 50% 48%, #000 0 46%, rgba(0,0,0,.74) 66%, transparent 100%); }
.graph-stage::after { content: ''; position: absolute; z-index: 0; inset: 5% 3%; pointer-events: none; opacity: 0; background: radial-gradient(circle at var(--analysis-x, 50%) var(--analysis-y, 50%), color-mix(in srgb, var(--accent) 8%, transparent) 0, color-mix(in srgb, var(--accent) 3%, transparent) 18%, transparent 43%); transition: opacity .34s ease, background-position .46s cubic-bezier(.22,1,.36,1); }
.graph-stage.analyzing::before { opacity: .62; }
.graph-stage.analyzing::after { opacity: 1; }
.graph-stage svg { position: absolute; inset: 7% 5%; width: 90%; height: 86%; overflow: visible; }
.graph-edge .edge-base { stroke: color-mix(in srgb, var(--workspace-graph-edge, rgba(112,105,94,.6)) 72%, transparent); stroke-width: .2; vector-effect: non-scaling-stroke; }
.graph-edge .edge-scan { stroke: transparent; stroke-width: .48; vector-effect: non-scaling-stroke; stroke-linecap: round; }
.graph-edge .edge-scan.processed { stroke: color-mix(in srgb, var(--accent) 22%, transparent); stroke-width: .28; transition: stroke .28s ease, stroke-width .28s ease; }
.graph-edge .edge-scan.active {
  stroke: var(--accent-strong);
  stroke-width: .62;
  stroke-dasharray: .09 .91;
  stroke-dashoffset: 1;
  filter: drop-shadow(0 0 2px color-mix(in srgb, var(--accent) 25%, transparent));
  animation: chain-edge-packets 1.05s linear infinite;
}
.graph-edge .edge-scan.validating { stroke: color-mix(in srgb, var(--accent) 48%, transparent); stroke-width: .36; stroke-dasharray: .035 .965; animation: chain-edge-packets 1.7s linear infinite; }
.graph-edge .edge-scan.settled { stroke: color-mix(in srgb, var(--accent) 34%, transparent); stroke-width: .3; stroke-dasharray: none; }
.graph-ambient-field { position: absolute; z-index: 1; inset: 6% 4%; pointer-events: none; }
.graph-ambient-field::before,
.graph-ambient-field::after { content: ''; position: absolute; border-radius: 50%; filter: blur(12px); opacity: .18; transition: transform .5s cubic-bezier(.22,1,.36,1), opacity .3s ease; }
.graph-ambient-field::before { width: 180px; height: 180px; left: calc(var(--analysis-x, 50%) - 90px); top: calc(var(--analysis-y, 50%) - 90px); background: radial-gradient(circle, color-mix(in srgb, var(--accent) 28%, transparent), transparent 68%); animation: chain-ambient-breathe 2.2s ease-in-out infinite; }
.graph-ambient-field::after { width: 96px; height: 96px; left: calc(var(--analysis-x, 50%) - 48px); top: calc(var(--analysis-y, 50%) - 48px); border: 1px solid color-mix(in srgb, var(--accent) 18%, transparent); animation: chain-orbit-drift 4.2s linear infinite; }
.analysis-status-overlay { position: absolute; z-index: 5; left: 18px; top: 16px; display: flex; align-items: center; gap: 9px; min-height: 38px; padding: 7px 10px; border: 1px solid color-mix(in srgb, var(--line-strong) 64%, transparent); background: color-mix(in srgb, var(--workspace-node-bg, #11181b) 84%, transparent); backdrop-filter: blur(9px); pointer-events: none; }
.analysis-status-overlay > span { display: grid; gap: 3px; }
.analysis-status-overlay b { color: var(--text); font-size: 9px; font-weight: 650; }
.analysis-status-overlay small { color: var(--subtle); font-size: 8px; }
.analysis-status-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }
.analysis-status-dot.pulse { background: var(--accent); animation: chain-status-pulse 1.15s ease-in-out infinite; }
.graph-node {
  position: absolute;
  z-index: 2;
  width: min(164px, 23%);
  min-height: 76px;
  transform: translate(-50%,-50%);
  padding: 11px 13px 11px 14px;
  overflow: hidden;
  border: 1px solid rgba(255,255,255,.085);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255,255,255,.025), transparent 38%),
    color-mix(in srgb, var(--workspace-node-bg, rgba(17,24,27,.94)) 58%, transparent);
  color: var(--text);
  text-align: left;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.035),
    0 12px 30px rgba(0,0,0,.16);
  backdrop-filter: blur(14px) saturate(118%);
  -webkit-backdrop-filter: blur(14px) saturate(118%);
  transition:
    opacity .32s ease,
    transform .38s cubic-bezier(.22,1,.36,1),
    border-color .28s ease,
    box-shadow .28s ease,
    background .28s ease;
  will-change: transform, opacity;
}
.graph-node::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: .34;
  background:
    linear-gradient(120deg, rgba(255,255,255,.055), transparent 25% 74%, rgba(197,161,107,.035));
}
.graph-node::before { content: ''; position: absolute; left: 0; top: 9px; bottom: 9px; width: 1px; background: transparent; }
.graph-node > span { display: block; color: var(--subtle); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .04em; }
.graph-node strong { display: block; margin-top: 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 650 13px/1.1 Inter, "MiSans", "PingFang SC", sans-serif; letter-spacing: -.01em; }
.graph-node small { display: block; margin-top: 7px; color: var(--muted); font-size: 10.5px; line-height: 1.25; }
.graph-node:hover:not(.glass-dormant) { transform: translate(-50%,-50%) translateY(-2px) scale(1.008); border-color: color-mix(in srgb, var(--text) 13%, rgba(255,255,255,.08)); box-shadow: inset 0 1px 0 rgba(255,255,255,.05), 0 18px 38px rgba(0,0,0,.19); }
.graph-node:active:not(.glass-dormant) { transform: translate(-50%,-50%) translateY(-1px) scale(.99); }
.graph-node:focus-visible { outline: 0; box-shadow: inset 0 1px 0 rgba(255,255,255,.05), 0 0 0 3px color-mix(in srgb, var(--workspace-focus, var(--accent)) 13%, transparent), 0 18px 38px rgba(0,0,0,.18); }
.graph-node.focus {
  z-index: 4;
  border-color: color-mix(in srgb, var(--accent) 68%, rgba(255,255,255,.08));
  background:
    linear-gradient(180deg, rgba(255,255,255,.04), transparent 42%),
    color-mix(in srgb, var(--workspace-node-focus-bg, rgba(24,32,35,.96)) 64%, transparent);
  color: var(--text);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.045),
    0 18px 38px rgba(0,0,0,.2),
    0 0 0 1px color-mix(in srgb, var(--accent) 10%, transparent);
}
.graph-node.focus::before { background: color-mix(in srgb, var(--accent) 56%, transparent); }
.graph-node.active::before { background: var(--accent); }
.graph-node.active { outline: 0; border-color: color-mix(in srgb, var(--text) 14%, rgba(255,255,255,.08)); color: var(--text); background: linear-gradient(180deg, rgba(255,255,255,.04), transparent 42%), color-mix(in srgb, var(--workspace-node-bg, rgba(17,24,27,.94)) 62%, transparent); }
.graph-node.target-locked {
  border-color: color-mix(in srgb, var(--accent) 84%, rgba(255,255,255,.08));
  background:
    linear-gradient(180deg, rgba(255,255,255,.055), transparent 44%),
    color-mix(in srgb, var(--workspace-node-focus-bg, #182023) 70%, var(--accent) 4%);
  transform: translate(-50%,-50%) translateY(-2px) scale(1.01);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.055),
    0 20px 44px rgba(0,0,0,.22),
    0 0 24px color-mix(in srgb, var(--accent) 7%, transparent);
}
.graph-node.computing {
  z-index: 5;
  border-color: color-mix(in srgb, var(--accent-strong) 82%, rgba(255,255,255,.12));
  background:
    linear-gradient(180deg, rgba(255,255,255,.065), transparent 42%),
    color-mix(in srgb, var(--workspace-node-focus-bg, #182023) 66%, var(--accent) 5%);
  transform: translate(-50%,-50%) translateY(-5px) scale(1.022);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.07),
    0 22px 46px rgba(0,0,0,.24),
    0 0 30px color-mix(in srgb, var(--accent) 9%, transparent);
  animation: chain-glass-rise .36s cubic-bezier(.22,1,.36,1) both;
}
.graph-node.computing::after {
  opacity: .8;
  background:
    linear-gradient(110deg, transparent 0 34%, rgba(255,255,255,.12) 46%, rgba(197,161,107,.08) 51%, transparent 63% 100%);
  background-size: 220% 100%;
  animation: chain-glass-sheen .72s cubic-bezier(.22,1,.36,1) 1;
}
.graph-node.computing strong { color: #f2eadb; }
.graph-node.processed {
  z-index: 3;
  border-color: color-mix(in srgb, var(--accent) 32%, rgba(255,255,255,.08));
  transform: translate(-50%,-50%) translateY(-2px);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.04),
    0 16px 34px rgba(0,0,0,.18);
}
.graph-node.analysis-result {
  border-color: color-mix(in srgb, var(--accent) 46%, rgba(255,255,255,.08));
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.045),
    0 16px 34px rgba(0,0,0,.18);
}
.graph-node.glass-dormant {
  opacity: .44;
  transform: translate(-50%,-50%) translateY(10px) scale(.985);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.018), 0 6px 18px rgba(0,0,0,.08);
}
.graph-node.glass-revealed { opacity: 1; }
.graph-stage.phase-validating .graph-node.processed { opacity: .9; }
.graph-stage.phase-settling .graph-node { transition: opacity .42s ease, border-color .42s ease, background .42s ease, transform .42s cubic-bezier(.22,1,.36,1); }
.node-compute-state { position: absolute; right: 9px; top: 8px; display: inline-flex; align-items: center; gap: 5px; color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; font-style: normal; letter-spacing: .02em; }
.node-compute-state > i { width: 4px; height: 4px; border-radius: 50%; border: 1px solid var(--line-strong); }
.graph-node.computing .node-compute-state { color: var(--accent-strong); }
.graph-node.computing .node-compute-state > i { border-color: var(--accent); background: var(--accent); animation: chain-status-pulse 1.05s ease-in-out infinite; }
.graph-node.processed .node-compute-state > i { border-color: color-mix(in srgb, var(--accent) 58%, var(--line-strong)); background: color-mix(in srgb, var(--accent) 34%, transparent); }
.node-calibration { position: absolute; inset: -5px; opacity: 0; pointer-events: none; background:
  linear-gradient(var(--accent),var(--accent)) left top / 10px 1px no-repeat,
  linear-gradient(var(--accent),var(--accent)) left top / 1px 10px no-repeat,
  linear-gradient(var(--accent),var(--accent)) right top / 10px 1px no-repeat,
  linear-gradient(var(--accent),var(--accent)) right top / 1px 10px no-repeat,
  linear-gradient(var(--accent),var(--accent)) left bottom / 10px 1px no-repeat,
  linear-gradient(var(--accent),var(--accent)) left bottom / 1px 10px no-repeat,
  linear-gradient(var(--accent),var(--accent)) right bottom / 10px 1px no-repeat,
  linear-gradient(var(--accent),var(--accent)) right bottom / 1px 10px no-repeat;
  transition: opacity .22s ease;
}
.graph-node.target-locked .node-calibration { opacity: .76; }
.industry-graph > footer { display: flex; align-items: center; justify-content: flex-end; gap: 10px; padding-top: 10px; border-top: 1px solid color-mix(in srgb, var(--line) 36%, transparent); color: var(--subtle); font-size: 9px; }
.industry-graph > footer > span { display: none; }
.industry-graph > footer button { display: inline-flex; align-items: center; min-height: 30px; padding: 0 10px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: color-mix(in srgb, var(--material-glass, transparent) 52%, transparent); color: var(--muted); font: 600 9px/1 Inter, "MiSans", "PingFang SC", sans-serif; cursor: pointer; backdrop-filter: blur(10px); transition: color .16s ease, background .16s ease, transform .1s ease; }
.industry-graph > footer button:hover { color: var(--text); background: color-mix(in srgb, var(--material-glass, transparent) 78%, transparent); }
.industry-graph > footer button:active { transform: scale(.97); }
.node-file { position: absolute; z-index: 30; top: 12px; right: 12px; bottom: 12px; width: min(304px, calc(100% - 36px)); min-width: 0; padding: 18px; display: flex; flex-direction: column; gap: 14px; overflow: auto; border: 1px solid var(--material-border, var(--line)); border-radius: 14px; background: color-mix(in srgb, var(--material-elevated, var(--surface-2)) 88%, transparent); box-shadow: -18px 0 56px rgba(0,0,0,.18), inset 0 1px 0 rgba(255,255,255,.035); backdrop-filter: blur(22px) saturate(112%); -webkit-backdrop-filter: blur(22px) saturate(112%); opacity: 0; pointer-events: none; transform: translateX(20px) scale(.99); transition: opacity .2s ease, transform .28s cubic-bezier(.22,1,.36,1); }
.node-file.open { opacity: 1; pointer-events: auto; transform: translateX(0) scale(1); }
.node-file-close { display: grid; place-items: center; position: absolute; top: 10px; right: 10px; width: 30px; height: 30px; border: 0; border-radius: 8px; background: rgba(255,255,255,.028); color: var(--subtle); font-size: 18px; cursor: pointer; transition: color .16s ease, background .16s ease, transform .1s ease; }
.node-file-close:hover { color: var(--text); background: rgba(255,255,255,.05); }
.node-file-close:active { transform: scale(.94); }
.node-file-head { display: grid; gap: 6px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.node-file-head span { color: var(--muted); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .1em; }
.node-file-head strong { color: var(--text); font-size: 17px; font-weight: 650; overflow-wrap: anywhere; }
.node-file-head small { color: var(--muted); font-size: 11px; }
.node-file > p { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.7; }
.node-file-meta { border-top: 1px solid var(--line); }
.node-file-meta > div { display: flex; justify-content: space-between; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); }
.node-file-meta span { color: var(--muted); font-size: 10px; }
.node-file-meta b { color: var(--text); font: 600 10px/1.25 ui-monospace, monospace; text-align: right; overflow-wrap: anywhere; }
.ch-result-panel { display: flex; min-height: 0; flex-direction: column; }
.ch-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.ch-result-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.ch-result-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.ch-output { margin-top: 10px; max-height: 330px; overflow: auto; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; padding: 12px 2px; color: var(--text); font-size: 10px; line-height: 1.72; overflow-wrap: anywhere; }
.ch-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 28px 8px; text-align: center; }
.ch-empty-mark { color: var(--accent-strong); border: 0; border-left: 1px solid var(--accent); border-right: 1px solid var(--line-strong); width: 58px; height: 40px; display: grid; place-items: center; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .08em; }
.ch-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.ch-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
.chain-analysis-plan { margin-top: 2px; padding-top: 12px; border-top: 1px solid color-mix(in srgb, var(--line) 50%, transparent); }
.chain-analysis-plan-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 3px 0 12px; }
.chain-analysis-plan-head > span { display: grid; gap: 4px; }
.chain-analysis-plan-head b { color: var(--text); font-size: 10px; font-weight: 650; }
.chain-analysis-plan-head small { color: var(--subtle); font-size: 8px; }
.plan-activity { display: inline-flex; align-items: flex-end; gap: 2px; height: 13px; font-style: normal; }
.plan-activity b { width: 2px; height: 5px; background: var(--accent); opacity: .35; animation: chain-plan-level 1s ease-in-out infinite; }
.plan-activity b:nth-child(2) { height: 10px; animation-delay: .16s; }
.plan-activity b:nth-child(3) { height: 7px; animation-delay: .32s; }
.chain-analysis-plan ol { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; }
.chain-analysis-plan li { min-height: 38px; display: grid; grid-template-columns: 12px minmax(0,1fr) auto; align-items: center; gap: 8px; padding: 4px 3px; border-radius: 7px; color: var(--subtle); transition: color .2s ease, background .2s ease; }
.chain-analysis-plan li > i { width: 6px; height: 6px; border: 1px solid var(--line-strong); border-radius: 50%; }
.chain-analysis-plan li > span { display: grid; gap: 3px; }
.chain-analysis-plan li b { color: inherit; font-size: 9px; font-weight: 600; }
.chain-analysis-plan li small { color: var(--subtle); font-size: 7px; }
.chain-analysis-plan li em { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; font-style: normal; }
.chain-analysis-plan li.state-active { color: var(--text); background: rgba(255,255,255,.028); }
.chain-analysis-plan li.state-active > i { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 7%, transparent); animation: chain-status-pulse 1.05s ease-in-out infinite; }
.chain-analysis-plan li.state-active em { color: var(--accent-strong); }
.chain-analysis-plan li.state-done { color: var(--muted); }
.chain-analysis-plan li.state-done > i { border-color: color-mix(in srgb, var(--accent) 62%, var(--line-strong)); background: color-mix(in srgb, var(--accent) 40%, transparent); }
@keyframes chain-edge-packets { from { stroke-dashoffset: 1; opacity: .15; } 18% { opacity: 1; } 82% { opacity: .75; } to { stroke-dashoffset: -1; opacity: .15; } }
@keyframes chain-ambient-breathe { 0%,100% { transform: scale(.86); opacity: .10; } 50% { transform: scale(1.12); opacity: .22; } }
@keyframes chain-orbit-drift { from { transform: rotate(0deg) scale(.9); opacity: .06; } 50% { opacity: .18; } to { transform: rotate(360deg) scale(1.05); opacity: .06; } }
@keyframes chain-status-pulse { 0%,100% { opacity: .42; transform: scale(.86); } 50% { opacity: 1; transform: scale(1.12); } }
@keyframes chain-button-scan { to { transform: translateX(240%); } }
@keyframes chain-plan-level { 0%,100% { transform: scaleY(.65); opacity: .28; } 50% { transform: scaleY(1.18); opacity: .9; } }
@keyframes chain-glass-rise {
  from {
    opacity: .42;
    transform: translate(-50%,-50%) translateY(14px) scale(.978);
  }
  58% { opacity: 1; }
  to {
    opacity: 1;
    transform: translate(-50%,-50%) translateY(-5px) scale(1.022);
  }
}
@keyframes chain-glass-sheen {
  from { background-position: 180% 0; opacity: 0; }
  20% { opacity: .75; }
  to { background-position: -70% 0; opacity: .08; }
}
@media (max-width: 1180px) {
  .ch-layout,
  .ch-layout.input-collapsed { position: relative; grid-template-columns: minmax(0, 1fr); gap: 0; }
  .ch-input-panel { position: absolute; z-index: 24; top: 8px; left: 8px; width: min(286px, calc(100% - 16px)); max-height: calc(100% - 16px); overflow: auto; transform: translateX(0); }
  .ch-layout.input-collapsed .ch-input-panel { padding: 14px !important; border-color: var(--material-border, var(--line)) !important; opacity: 0; transform: translateX(calc(-100% - 20px)); }
  .input-panel-close { display: grid; }
  .ch-panel-title { padding-right: 32px; }
  .ch-main { min-height: 540px; }
  .industry-graph { min-height: 540px; padding-left: 16px; padding-right: 16px; }
  .graph-stage { min-height: 430px; }
  .graph-node { width: min(156px, 27%); }
  .node-file { width: min(290px, calc(100% - 28px)); }
}
@media (max-width: 760px) {
  .section-bar { align-items: flex-start; }
  .section-actions { gap: 8px; }
  .section-status { display: none; }
  .ch-layout,
  .ch-layout.input-collapsed { grid-template-columns: 1fr; }
  .ch-input-panel { position: fixed; z-index: 95; left: 12px; right: 12px; top: auto; bottom: 12px; width: auto; max-height: min(72vh, 620px); transform: translateY(0); }
  .ch-layout.input-collapsed .ch-input-panel { opacity: 0; transform: translateY(calc(100% + 28px)); }
  .graph-stage { min-height: 390px; }
  .industry-graph > header { flex-direction: column; }
  .industry-graph > header small { text-align: left; }
  .industry-graph > footer { flex-direction: column; }
  .industry-graph > footer button { display: block; align-self: flex-start; }
  .graph-node { width: 30%; }
  .node-file { position: fixed; z-index: 90; left: 12px; right: 12px; top: auto; bottom: 12px; width: auto; max-height: min(72vh, 620px); transform: translateY(calc(100% + 32px)); opacity: 1; pointer-events: auto; }
  .node-file.open { transform: translateY(0); }
  .node-file:not(.open) { pointer-events: none; }
  .node-file-head { padding-right: 44px; }
}

@media (prefers-reduced-motion: reduce) {
  .node-file { transition-duration: .01ms; }
  .section-status i.pulse,
  .ch-run-indicator::after,
  .graph-edge .edge-scan.active,
  .graph-edge .edge-scan.validating,
  .graph-ambient-field::before,
  .graph-ambient-field::after,
  .analysis-status-dot.pulse,
  .node-compute-state > i,
  .plan-activity b,
  .chain-analysis-plan li.state-active > i,
  .graph-node.computing,
  .graph-node.computing::after { animation: none !important; }
  .graph-node.computing { transform: translate(-50%,-50%); transition: none; }
  .graph-node.glass-dormant { opacity: .68; transform: translate(-50%,-50%); }
}

/* V5 — visual-only refinement: calmer canvas, readable research typography, relationship focus. */
.graph-stage::before {
  opacity: .30;
  background-size: 72px 72px;
}
.graph-stage.analyzing::before { opacity: .42; }
.graph-edge .edge-base {
  opacity: .72;
  transition: opacity .18s ease, stroke .18s ease, stroke-width .18s ease;
}
.graph-edge.hover-linked .edge-base {
  opacity: 1;
  stroke: color-mix(in srgb, var(--accent) 52%, var(--workspace-graph-edge));
  stroke-width: .34;
}
.graph-edge.hover-muted .edge-base { opacity: .20; }
.graph-edge .edge-scan.active { filter: none; }
.analysis-status-overlay {
  min-height: 42px;
  padding: 8px 11px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--material-glass, var(--workspace-node-bg)) 88%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.035), 0 12px 28px rgba(0,0,0,.10);
}
.analysis-status-overlay b { font-size: 10.5px; }
.analysis-status-overlay small { font-size: 9.5px; }
.graph-node {
  width: min(168px, 23%);
  min-height: 78px;
  border-color: rgba(255,255,255,.072);
}
.graph-node::after {
  opacity: .46;
  background: linear-gradient(132deg, rgba(255,255,255,.075), transparent 28% 76%, rgba(197,161,107,.028));
}
.graph-node strong { font-size: 13.5px; }
.graph-node small { font-size: 11px; }
.graph-node.hover-linked:not(.active):not(.computing) {
  border-color: color-mix(in srgb, var(--accent) 24%, rgba(255,255,255,.08));
}
.graph-node.hover-muted {
  opacity: .55;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.018), 0 6px 18px rgba(0,0,0,.07);
}
.node-calibration { display: none; }
.node-compute-state { font-size: 9px; }
.chain-analysis-plan-head b { font-size: 11px; }
.chain-analysis-plan-head small { font-size: 9.5px; line-height: 1.4; }
.chain-analysis-plan li { min-height: 42px; }
.chain-analysis-plan li b { font-size: 10.5px; }
.chain-analysis-plan li small { font-size: 9px; }
.chain-analysis-plan li em { font-size: 9px; }
.node-file-head span { font-size: 9.5px; }
.node-file > p { font-size: 11.5px; }
.node-file-meta span,
.node-file-meta b { font-size: 10.5px; }
.ch-result-head span,
.ch-empty span { font-size: 10.5px; }
.ch-empty-mark {
  width: auto;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--material-border, var(--line));
  border-radius: 999px;
  background: rgba(255,255,255,.02);
  letter-spacing: .04em;
}
@media (max-width: 1180px) {
  .graph-node { width: min(160px, 27%); }
  .ch-workspace:has(.context-target) .ch-main,
  .ch-workspace:has(.context-target) .industry-graph { min-height: 486px; }
  .ch-workspace:has(.context-target) .graph-stage { min-height: 376px; }
}
@media (max-width: 760px) {
  .graph-node { width: 30%; min-height: 72px; }
  .graph-node strong { font-size: 12px; }
  .graph-node small { font-size: 9.5px; }
}

/* V6 — give the graph a spatial field so glass reads as material, not boxed flowchart UI. */
.graph-stage {
  border-radius: 12px;
  background:
    radial-gradient(ellipse at 49% 48%, color-mix(in srgb, var(--accent) 4%, transparent) 0, transparent 31%),
    radial-gradient(ellipse at 19% 52%, color-mix(in srgb, var(--text) 2.4%, transparent) 0, transparent 24%),
    radial-gradient(ellipse at 79% 47%, color-mix(in srgb, var(--text) 2%, transparent) 0, transparent 25%);
}
.graph-stage::before { inset: 10% 7%; }
.graph-stage svg { inset: 8% 6%; width: 88%; height: 84%; }
.graph-node {
  background:
    linear-gradient(145deg, rgba(255,255,255,.052), transparent 34%),
    linear-gradient(180deg, rgba(255,255,255,.012), transparent 52%),
    color-mix(in srgb, var(--workspace-node-bg, rgba(17,24,27,.94)) 55%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.042), 0 14px 34px rgba(0,0,0,.13);
}
.graph-node:hover:not(.glass-dormant),
.graph-node:focus-visible {
  box-shadow: inset 0 1px 0 rgba(255,255,255,.058), 0 20px 44px rgba(0,0,0,.17);
}
.graph-node.active:not(.focus):not(.computing) {
  border-color: color-mix(in srgb, var(--text) 13%, rgba(255,255,255,.07));
  background:
    linear-gradient(145deg, rgba(255,255,255,.067), transparent 36%),
    color-mix(in srgb, var(--workspace-node-bg, rgba(17,24,27,.94)) 68%, transparent);
}
.graph-node.active:not(.focus)::before {
  top: 17px;
  bottom: 17px;
  width: 2px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 72%, transparent);
}
.node-file {
  border-color: color-mix(in srgb, var(--material-border, var(--line)) 86%, transparent);
  box-shadow: -18px 0 52px rgba(0,0,0,.14), inset 0 1px 0 rgba(255,255,255,.038);
}
.node-file.open { transform: translateX(0) scale(1); }
@media (prefers-reduced-motion: reduce) {
  .graph-node,
  .graph-edge .edge-base { transition: none !important; }
}

/* V7 — Material Propagation: relationships illuminate; no travelling HUD packets. */
.graph-edge .edge-scan.active {
  stroke: color-mix(in srgb, var(--accent-strong) 84%, var(--text));
  stroke-width: .54;
  stroke-dasharray: none;
  stroke-dashoffset: 0;
  filter: none;
  animation: chain-relation-bloom .82s cubic-bezier(.22,1,.36,1) both;
}
.graph-edge .edge-scan.validating {
  stroke: color-mix(in srgb, var(--accent) 42%, transparent);
  stroke-width: .34;
  stroke-dasharray: none;
  stroke-dashoffset: 0;
  animation: chain-relation-breathe 2.4s ease-in-out infinite;
}
.graph-ambient-field::after {
  opacity: .08;
  animation: none;
  transform: scale(1);
  border-color: color-mix(in srgb, var(--accent) 12%, transparent);
}
.graph-node.computing {
  animation: chain-material-lift .38s cubic-bezier(.22,1,.36,1) both;
}
.graph-node.processed {
  transition-duration: .34s;
}
.ch-main:has(.graph-node:hover) .industry-graph > header,
.ch-main:has(.graph-node:focus-visible) .industry-graph > header,
.ch-main:has(.graph-node:hover) .industry-graph > footer,
.ch-main:has(.graph-node:focus-visible) .industry-graph > footer {
  opacity: .68;
}
.industry-graph > header,
.industry-graph > footer {
  transition: opacity .18s ease;
}
@keyframes chain-relation-bloom {
  0% { opacity: 0; stroke-width: .18; }
  58% { opacity: .94; stroke-width: .62; }
  100% { opacity: .72; stroke-width: .48; }
}
@keyframes chain-relation-breathe {
  0%,100% { opacity: .24; }
  50% { opacity: .58; }
}
@keyframes chain-material-lift {
  from { opacity: .58; transform: translate(-50%,-50%) translateY(7px) scale(.988); }
  to { opacity: 1; transform: translate(-50%,-50%) translateY(-5px) scale(1.018); }
}
@media (prefers-reduced-motion: reduce) {
  .graph-edge .edge-scan.active,
  .graph-edge .edge-scan.validating,
  .graph-node.computing { animation: none !important; }
}
</style>
