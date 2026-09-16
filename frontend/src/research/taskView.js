/*
 * 研究任务的展示逻辑（纯函数）。
 *
 * 放在这里而不是写在 .vue 里，是因为前端这套测试用 `node --test` 直接跑纯 JS 模块，
 * 而 .vue 组件需要浏览器环境才能渲染。把"状态怎么显示、草稿怎么校验"这类判断抽出来，
 * 就能像后端一样把这些规则钉住——它们实际上就是接口契约的一部分。
 */

/** 任务状态 → 展示元信息。与 Java 的 ResearchTaskStatus 一一对应。 */
export const STATUS_META = Object.freeze({
  PENDING: { label: '待执行', tone: 'idle' },
  RUNNING: { label: '执行中', tone: 'busy' },
  SUCCEEDED: { label: '已完成', tone: 'ok' },
  FAILED: { label: '失败', tone: 'bad' },
})

export function statusMeta(status) {
  return STATUS_META[status] || { label: status || '未知', tone: 'idle' }
}

/** 任务类型 → 中文名。与 Java 的 ResearchTaskType 一一对应。 */
export const TASK_TYPE_LABELS = Object.freeze({
  REPORT: '综合研究',
  SENTIMENT: '情绪研判',
  CHAIN: '产业链',
  RISK: '风险',
  TREND: '趋势',
  STRATEGY: '策略',
})

export const TASK_TYPE_OPTIONS = Object.freeze(
  Object.keys(TASK_TYPE_LABELS).map(value => ({ value, label: TASK_TYPE_LABELS[value] })),
)

export function taskTypeLabel(type) {
  return TASK_TYPE_LABELS[type] || TASK_TYPE_LABELS.REPORT
}

/** 与后端字段长度校验保持一致，避免让用户提交一个必然 400 的表单。 */
export const FIELD_LIMITS = Object.freeze({
  title: 200,
  question: 2000,
  symbol: 32,
  market: 20,
})

/**
 * 校验草稿并返回要提交的 payload。
 *
 * 返回 { ok, payload, error }：错误用**返回**而不是抛出，因为表单校验是正常流程，
 * 不是异常。前端先拦一道，后端仍然会独立校验（前端校验只是体验，不是安全边界）。
 */
export function buildTaskPayload(draft = {}) {
  const title = clean(draft.title)
  const question = clean(draft.question)
  const symbol = clean(draft.symbol)
  const market = clean(draft.market)
  const taskType = clean(draft.taskType) || 'REPORT'

  if (!question && !symbol) {
    return { ok: false, error: '请填写研究问题或标的代码' }
  }
  if (title && title.length > FIELD_LIMITS.title) {
    return { ok: false, error: `标题最多 ${FIELD_LIMITS.title} 字` }
  }
  if (question && question.length > FIELD_LIMITS.question) {
    return { ok: false, error: `问题最多 ${FIELD_LIMITS.question} 字` }
  }
  if (symbol && symbol.length > FIELD_LIMITS.symbol) {
    return { ok: false, error: `标的代码最多 ${FIELD_LIMITS.symbol} 字` }
  }
  if (market && market.length > FIELD_LIMITS.market) {
    return { ok: false, error: `市场最多 ${FIELD_LIMITS.market} 字` }
  }
  if (!TASK_TYPE_LABELS[taskType]) {
    return { ok: false, error: `未知的任务类型：${taskType}` }
  }

  return {
    ok: true,
    error: '',
    payload: {
      title: title || null,
      question: question || null,
      symbol: symbol || null,
      market: market || null,
      task_type: taskType,
    },
  }
}

/**
 * 把报告整理成可渲染的小节列表。
 *
 * 报告可能还没生成（PENDING/RUNNING）、可能解析失败（只有原文）、
 * 也可能结构不合规。三种情况都要给出可显示的内容——空白页面是最糟的结果。
 */
export function reportSections(report) {
  if (!report || typeof report !== 'object') return []

  if (typeof report.raw === 'string') {
    return [{ title: '原始内容', content: report.raw }]
  }

  const sections = []
  const summary = clean(report.summary)
  if (summary) sections.push({ title: '结论', content: summary })

  if (Array.isArray(report.sections)) {
    for (const section of report.sections) {
      if (!section || typeof section !== 'object') continue
      const content = clean(section.content)
      if (!content) continue
      sections.push({ title: clean(section.title) || '分析', content })
    }
  }

  if (Array.isArray(report.risks)) {
    const risks = report.risks.map(clean).filter(Boolean)
    if (risks.length) sections.push({ title: '风险提示', content: risks.join('\n') })
  }

  return sections
}

/** 数据缺口要单独显示：它们决定了这份报告的结论可信到什么程度。 */
export function dataGaps(report) {
  if (!report || typeof report !== 'object' || !Array.isArray(report.data_gaps)) return []
  return report.data_gaps.map(clean).filter(Boolean)
}

/** 历史列表的一行摘要：标的/类型/状态 + 时间。 */
export function taskSummaryLine(task = {}) {
  const parts = []
  if (task.symbol) parts.push(task.symbol)
  if (task.market) parts.push(task.market)
  parts.push(taskTypeLabel(task.task_type))
  parts.push(statusMeta(task.status).label)
  return parts.join(' · ')
}

/** 时间显示成 `MM-DD HH:mm`；解析不出来时原样返回，不显示 Invalid Date。 */
export function formatTime(value) {
  if (!value) return ''
  const text = String(value)
  const match = text.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (match) return `${match[2]}-${match[3]} ${match[4]}:${match[5]}`
  const parsed = new Date(text)
  if (Number.isNaN(parsed.getTime())) return text
  const pad = number => String(number).padStart(2, '0')
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} `
    + `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

function clean(value) {
  return value == null ? '' : String(value).trim()
}