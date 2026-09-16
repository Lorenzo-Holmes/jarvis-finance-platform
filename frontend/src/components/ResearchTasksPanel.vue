<script setup>
/*
 * 研究任务面板：创建 → 执行 → 历史 → 报告。
 *
 * 与旁边的对话面板的区别在于**有状态**：任务落库、可回看、失败有原因。
 * 所以这里的交互不是"发一条消息等回复"，而是"建一条任务、显式执行、回看结果"——
 * 一次研究要花一次 AI 调用和几十秒，隐式触发对用户不友好。
 *
 * 展示逻辑（状态映射、草稿校验、报告分段）都在 src/research/taskView.js 里，
 * 那样才能用 node --test 直接钉住——这套前端测试不渲染 .vue 组件。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import MarkdownContent from './common/MarkdownContent.vue'
import {
  TASK_TYPE_OPTIONS,
  buildTaskPayload,
  dataGaps,
  formatTime,
  reportSections,
  statusMeta,
  taskSummaryLine,
  taskTypeLabel,
} from '../research/taskView'

const draft = ref({ title: '', taskType: 'REPORT', market: '', symbol: '', question: '' })
const formError = ref('')
const creating = ref(false)
const runningId = ref(null)

const tasks = ref([])
const listLoading = ref(false)
const listError = ref('')

const selected = ref(null)
const detailLoading = ref(false)
const detailError = ref('')

const selectedReport = computed(() => reportSections(selected.value?.report))
const selectedGaps = computed(() => dataGaps(selected.value?.report))

async function loadTasks() {
  listLoading.value = true
  listError.value = ''
  try {
    const res = await api.researchTasks(0, 20)
    if (res.code !== 200) {
      listError.value = res.message || '研究任务列表加载失败'
      tasks.value = []
      return
    }
    tasks.value = res.data?.items || []
  } catch (e) {
    listError.value = e?.message || '研究任务列表加载失败'
    tasks.value = []
  } finally {
    listLoading.value = false
  }
}

async function createTask() {
  const result = buildTaskPayload(draft.value)
  if (!result.ok) {
    formError.value = result.error
    return
  }
  formError.value = ''
  creating.value = true
  try {
    const res = await api.createResearchTask(result.payload)
    if (res.code !== 200) {
      formError.value = res.message || '创建研究任务失败'
      return
    }
    draft.value = { title: '', taskType: result.payload.task_type, market: '', symbol: '', question: '' }
    await loadTasks()
    await openTask(res.data?.id)
  } catch (e) {
    formError.value = e?.message || '创建研究任务失败'
  } finally {
    creating.value = false
  }
}

async function openTask(id) {
  if (!id) return
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await api.researchTask(id)
    if (res.code !== 200) {
      detailError.value = res.message || '研究任务详情加载失败'
      return
    }
    selected.value = res.data
  } catch (e) {
    detailError.value = e?.message || '研究任务详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

/**
 * 执行任务。
 *
 * 后端在 AI 调用失败时**仍然返回 200**（失败的是研究本身，原因在 error_message 里），
 * 所以这里不能只看 HTTP 状态：要按 status 字段决定显示"已完成"还是"失败 + 原因"。
 */
async function runTask(id) {
  if (!id || runningId.value) return
  runningId.value = id
  try {
    const res = await api.runResearchTask(id)
    if (res.code !== 200) {
      detailError.value = res.message || '执行研究任务失败'
      return
    }
    selected.value = res.data
    await loadTasks()
  } catch (e) {
    detailError.value = e?.message || '执行研究任务失败'
  } finally {
    runningId.value = null
  }
}

onMounted(loadTasks)
</script>

<template>
  <div class="rt" data-testid="research-task-panel">
    <section class="rt-form" data-testid="research-task-form">
      <div class="rt-head">
        <div><b>NEW RESEARCH TASK</b><span>选好标的与问题，任务会保存下来、可回看</span></div>
        <button type="button" class="rt-primary" data-testid="research-task-create-btn" :disabled="creating" @click="createTask">
          {{ creating ? '创建中' : '创建任务' }}
        </button>
      </div>

      <div class="rt-grid">
        <label>
          <span>任务类型</span>
          <select v-model="draft.taskType" data-testid="research-task-form-type" aria-label="任务类型">
            <option v-for="option in TASK_TYPE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>
          <span>市场</span>
          <input v-model="draft.market" data-testid="research-task-form-market" aria-label="市场" placeholder="a_share" />
        </label>
        <label>
          <span>标的</span>
          <input v-model="draft.symbol" data-testid="research-task-form-symbol" aria-label="标的代码" placeholder="sh600519" />
        </label>
        <label class="wide">
          <span>标题</span>
          <input v-model="draft.title" data-testid="research-task-form-title" aria-label="标题" placeholder="可留空，例如：贵州茅台基本面复核" />
        </label>
        <label class="wide">
          <span>研究问题</span>
          <textarea v-model="draft.question" data-testid="research-task-form-question" aria-label="研究问题" rows="3"
            placeholder="想弄清楚的判断，例如：当前估值下还值得配置吗？"></textarea>
        </label>
      </div>

      <p v-if="formError" class="rt-error" data-testid="research-task-form-error" role="alert">{{ formError }}</p>
      <p v-else class="rt-hint">研究问题与标的至少填一个。数值口径由后端确定性计算，报告只做解释。</p>
    </section>

    <div class="rt-split">
      <section class="rt-list">
        <div class="rt-head">
          <div><b>HISTORY</b><span>最近的 20 条研究任务</span></div>
          <button type="button" class="rt-text" data-testid="research-task-refresh-btn" :disabled="listLoading" @click="loadTasks">
            {{ listLoading ? '加载中' : '刷新' }}
          </button>
        </div>

        <DataState v-if="listLoading" state="loading" title="正在加载研究任务" compact />
        <DataState v-else-if="listError" state="error" title="任务列表加载失败" :message="listError" compact retryable @retry="loadTasks" />
        <p v-else-if="!tasks.length" class="rt-empty" data-testid="research-task-empty">还没有研究任务。左侧创建一条开始。</p>
        <ul v-else class="rt-items" data-testid="research-task-list">
          <li v-for="task in tasks" :key="task.id">
            <button type="button" class="rt-item" :class="{ on: selected?.id === task.id }"
              data-testid="research-task-item" @click="openTask(task.id)">
              <b>{{ task.title || task.question || task.symbol || '未命名研究' }}</b>
              <span>{{ taskSummaryLine(task) }}</span>
              <i :class="statusMeta(task.status).tone">{{ statusMeta(task.status).label }}</i>
              <small>{{ formatTime(task.created_at) }}</small>
            </button>
            <button type="button" class="rt-run" data-testid="research-task-run-btn"
              :disabled="runningId === task.id || task.status === 'RUNNING'"
              @click="runTask(task.id)">
              {{ runningId === task.id ? '执行中' : (task.status === 'SUCCEEDED' ? '重新研究' : '执行') }}
            </button>
          </li>
        </ul>
      </section>

      <section class="rt-detail" data-testid="research-task-detail">
        <DataState v-if="detailLoading" state="loading" title="正在加载研究详情" compact />
        <DataState v-else-if="detailError" state="error" title="研究详情加载失败" :message="detailError" compact />
        <p v-else-if="!selected" class="rt-empty">选择左侧任意一条任务查看报告。</p>
        <template v-else>
          <div class="rt-detail-head">
            <div>
              <b>{{ selected.title || '未命名研究' }}</b>
              <span>{{ taskTypeLabel(selected.task_type) }} · {{ selected.symbol || '无标的' }}</span>
            </div>
            <i :class="statusMeta(selected.status).tone">{{ statusMeta(selected.status).label }}</i>
          </div>

          <p v-if="selected.question" class="rt-question">{{ selected.question }}</p>

          <p v-if="selected.error_message" class="rt-error" data-testid="research-task-error" role="alert">
            {{ selected.error_message }}
          </p>

          <div v-if="selectedGaps.length" class="rt-gaps" data-testid="research-task-gaps">
            <b>数据缺口</b>
            <ul><li v-for="gap in selectedGaps" :key="gap">{{ gap }}</li></ul>
          </div>

          <div v-if="selectedReport.length" class="rt-report" data-testid="research-task-report">
            <article v-for="(section, index) in selectedReport" :key="index">
              <h4>{{ section.title }}</h4>
              <MarkdownContent :content="section.content" />
            </article>
          </div>
          <p v-else-if="selected.status === 'SUCCEEDED'" class="rt-empty">这份报告没有可显示的内容。</p>
          <p v-else-if="selected.status !== 'FAILED'" class="rt-empty">任务尚未执行，点右侧「执行」生成报告。</p>

          <div class="rt-actions">
            <button type="button" class="rt-primary" data-testid="research-task-detail-run-btn"
              :disabled="runningId === selected.id || selected.status === 'RUNNING'" @click="runTask(selected.id)">
              {{ runningId === selected.id ? '执行中' : '执行研究' }}
            </button>
            <small v-if="selected.model">模型 {{ selected.model }}</small>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.rt { display: flex; flex-direction: column; gap: 0; border: 1px solid var(--line); }
.rt-form, .rt-list, .rt-detail { padding: 13px; background: rgba(239,235,227,.52); border-bottom: 1px solid var(--line); }
.rt-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.rt-head b { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .09em; }
.rt-head span { display: block; margin-top: 6px; color: var(--subtle); font-size: 9px; }
.rt-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.rt-grid label { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.rt-grid label.wide { grid-column: 1 / -1; }
.rt-grid span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .08em; }
.rt-grid input, .rt-grid select, .rt-grid textarea { width: 100%; min-height: 30px; padding: 5px 8px; border: 1px solid var(--line); background: rgba(255,255,255,.5); color: var(--text); font: 400 11px/1.4 inherit; }
.rt-grid textarea { resize: vertical; }
.rt-primary { min-height: 30px; padding: 0 14px; border: 1px solid var(--line-strong); background: var(--text); color: var(--bg, #f5f2ec); cursor: pointer; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .07em; }
.rt-primary:disabled { opacity: .5; cursor: default; }
.rt-text, .rt-run { min-height: 26px; padding: 0 10px; border: 1px solid var(--line-strong); background: transparent; color: var(--text); cursor: pointer; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .07em; }
.rt-run:disabled { opacity: .5; cursor: default; }
.rt-hint { margin: 9px 0 0; color: var(--subtle); font-size: 9px; }
.rt-error { margin: 9px 0 0; color: var(--bad); font-size: 10px; }
.rt-split { display: grid; grid-template-columns: 340px minmax(0, 1fr); }
.rt-list { border-right: 1px solid var(--line); border-bottom: 0; }
.rt-detail { border-bottom: 0; }
.rt-items { display: flex; flex-direction: column; gap: 0; margin: 0; padding: 0; list-style: none; }
.rt-items li { display: flex; align-items: stretch; gap: 6px; border-top: 1px solid var(--line); }
.rt-item { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 9px 8px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.rt-item.on { background: rgba(161,132,88,.09); }
.rt-item b { color: var(--text); font: 650 10px/1.3 inherit; }
.rt-item span { color: var(--muted); font-size: 9px; }
.rt-item small { color: var(--subtle); font: 400 8px/1 ui-monospace, monospace; }
.rt-item i, .rt-detail-head i { align-self: flex-start; padding: 2px 5px; border: 1px solid var(--line-strong); color: var(--muted); font: 650 7px/1 ui-monospace, monospace; font-style: normal; }
.rt-item i.ok, .rt-detail-head i.ok { color: var(--ok); border-color: var(--ok); }
.rt-item i.bad, .rt-detail-head i.bad { color: var(--bad); border-color: var(--bad); }
.rt-item i.busy, .rt-detail-head i.busy { color: var(--warn, #b8860b); border-color: var(--warn, #b8860b); }
.rt-empty { margin: 0; color: var(--subtle); font-size: 10px; }
.rt-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.rt-detail-head b { color: var(--text); font: 650 12px/1.3 inherit; }
.rt-detail-head span { display: block; margin-top: 5px; color: var(--subtle); font-size: 9px; }
.rt-question { margin: 11px 0 0; padding: 9px 10px; border-left: 2px solid var(--line-strong); background: rgba(161,132,88,.05); color: var(--text); font-size: 11px; }
.rt-gaps { margin-top: 11px; padding: 9px 10px; border: 1px dashed var(--line-strong); }
.rt-gaps b { color: var(--warn, #b8860b); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.rt-gaps ul { margin: 7px 0 0; padding-left: 16px; }
.rt-gaps li { color: var(--muted); font-size: 10px; }
.rt-report { margin-top: 13px; display: flex; flex-direction: column; gap: 13px; }
.rt-report h4 { margin: 0 0 6px; color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .09em; }
.rt-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.rt-actions small { color: var(--subtle); font: 400 8px/1 ui-monospace, monospace; }
@media (max-width: 900px) { .rt-split { grid-template-columns: 1fr; } .rt-list { border-right: 0; border-bottom: 1px solid var(--line); } .rt-grid { grid-template-columns: 1fr; } }
</style>