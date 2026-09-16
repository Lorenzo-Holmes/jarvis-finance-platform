import test from 'node:test'
import assert from 'node:assert/strict'
import {
  FIELD_LIMITS,
  TASK_TYPE_OPTIONS,
  buildTaskPayload,
  dataGaps,
  formatTime,
  reportSections,
  statusMeta,
  taskSummaryLine,
  taskTypeLabel,
} from '../src/research/taskView.js'

// ==================== 状态与类型 ====================

test('every Java task status has a label, and unknown ones degrade instead of breaking', () => {
  assert.equal(statusMeta('PENDING').label, '待执行')
  assert.equal(statusMeta('RUNNING').label, '执行中')
  assert.equal(statusMeta('SUCCEEDED').label, '已完成')
  assert.equal(statusMeta('FAILED').label, '失败')

  // 后端加了新状态时前端不该崩，也不该显示 undefined
  assert.equal(statusMeta('SOMETHING_NEW').label, 'SOMETHING_NEW')
  assert.equal(statusMeta(undefined).label, '未知')
  assert.equal(statusMeta(null).tone, 'idle')
})

test('the six task types match the cross-language protocol', () => {
  assert.deepEqual(TASK_TYPE_OPTIONS.map(option => option.value),
    ['REPORT', 'SENTIMENT', 'CHAIN', 'RISK', 'TREND', 'STRATEGY'])
  assert.equal(taskTypeLabel('RISK'), '风险')
  assert.equal(taskTypeLabel('NOT_A_TYPE'), '综合研究', '未知类型退回综合研究')
  assert.equal(taskTypeLabel(null), '综合研究')
})

test('summary line prefers the symbol then the market', () => {
  assert.equal(taskSummaryLine({ symbol: 'sh600519', market: 'a_share', task_type: 'RISK', status: 'SUCCEEDED' }),
    'sh600519 · a_share · 风险 · 已完成')
  assert.equal(taskSummaryLine({ task_type: 'REPORT', status: 'PENDING' }), '综合研究 · 待执行')
  assert.equal(taskSummaryLine({}), '综合研究 · 未知')
})

// ==================== 草稿校验 ====================

test('a draft needs a question or a symbol', () => {
  assert.equal(buildTaskPayload({}).ok, false)
  assert.equal(buildTaskPayload({ title: '只有标题' }).ok, false)
  assert.equal(buildTaskPayload({ title: '只有标题' }).error, '请填写研究问题或标的代码')
  assert.equal(buildTaskPayload({ question: '  ' }).ok, false)

  assert.equal(buildTaskPayload({ question: '值得关注吗？' }).ok, true)
  assert.equal(buildTaskPayload({ symbol: 'sh600519' }).ok, true)
})

test('the payload is trimmed and blanks become null rather than empty strings', () => {
  const { payload } = buildTaskPayload({
    title: '  贵州茅台  ',
    question: '  值得关注吗？  ',
    symbol: ' sh600519 ',
    market: ' a_share ',
    taskType: ' RISK ',
  })

  assert.deepEqual(payload, {
    title: '贵州茅台',
    question: '值得关注吗？',
    symbol: 'sh600519',
    market: 'a_share',
    task_type: 'RISK',
  })
})

test('a draft without a task type defaults to REPORT', () => {
  assert.equal(buildTaskPayload({ symbol: 'sh600519' }).payload.task_type, 'REPORT')
})

test('an unknown task type is rejected instead of silently submitted', () => {
  const result = buildTaskPayload({ symbol: 'sh600519', taskType: 'MADE_UP' })

  assert.equal(result.ok, false)
  assert.match(result.error, /未知的任务类型/)
})

test('the frontend field limits mirror the backend ones', () => {
  // 与后端 ResearchTaskService 的 MAX_* 一致；前端先拦一道只是体验，
  // 后端仍会独立校验（前端校验不是安全边界）
  assert.equal(FIELD_LIMITS.title, 200)
  assert.equal(FIELD_LIMITS.question, 2000)
  assert.equal(FIELD_LIMITS.symbol, 32)
  assert.equal(FIELD_LIMITS.market, 20)

  assert.equal(buildTaskPayload({ symbol: 's', title: 'x'.repeat(200) }).ok, true)
  assert.equal(buildTaskPayload({ symbol: 's', title: 'x'.repeat(201) }).ok, false)
  assert.equal(buildTaskPayload({ symbol: 's', question: 'x'.repeat(2000) }).ok, true)
  assert.equal(buildTaskPayload({ symbol: 's', question: 'x'.repeat(2001) }).ok, false)
  assert.equal(buildTaskPayload({ symbol: 'x'.repeat(32) }).ok, true)
  assert.equal(buildTaskPayload({ symbol: 'x'.repeat(33) }).ok, false)
  assert.equal(buildTaskPayload({ symbol: 's', market: 'x'.repeat(21) }).ok, false)
})

// ==================== 报告渲染 ====================

test('a complete report renders summary, sections and risks in order', () => {
  const sections = reportSections({
    summary: '偏乐观',
    sections: [
      { title: '走势', content: '站上20日线' },
      { title: '量能', content: '成交量偏低' },
    ],
    risks: ['政策风险', '流动性风险'],
  })

  assert.deepEqual(sections.map(section => section.title), ['结论', '走势', '量能', '风险提示'])
  assert.equal(sections[3].content, '政策风险\n流动性风险')
})

test('a report without a summary starts at the first section', () => {
  const sections = reportSections({ sections: [{ title: '走势', content: '内容' }] })

  assert.deepEqual(sections, [{ title: '走势', content: '内容' }])
})

test('unparsable reports still render their raw text', () => {
  // 模型没守 JSON 格式时后端会把原文放进 raw；显示空白是最糟的结果
  assert.deepEqual(reportSections({ raw: '模型写了一段散文' }),
    [{ title: '原始内容', content: '模型写了一段散文' }])
})

test('malformed sections are skipped individually', () => {
  const sections = reportSections({
    sections: [
      { title: '好的', content: '内容' },
      null,
      '不是对象',
      { title: '没有内容', content: '   ' },
      { content: '没有标题但有内容' },
    ],
  })

  assert.deepEqual(sections, [
    { title: '好的', content: '内容' },
    { title: '分析', content: '没有标题但有内容' },
  ])
})

test('missing or wrong-typed reports render as nothing, without throwing', () => {
  for (const value of [null, undefined, {}, 'string', 42, []]) {
    assert.deepEqual(reportSections(value), [])
  }
})

test('data gaps are surfaced for the report', () => {
  assert.deepEqual(dataGaps({ data_gaps: ['未取到最新报价', '  ', null, '日K不足'] }),
    ['未取到最新报价', '日K不足'])
  assert.deepEqual(dataGaps({}), [])
  assert.deepEqual(dataGaps(null), [])
})

// ==================== 时间 ====================

test('ISO timestamps render as MM-DD HH:mm', () => {
  assert.equal(formatTime('2026-09-16T07:30:00'), '09-16 07:30')
  assert.equal(formatTime('2026-09-16 07:30:00'), '09-16 07:30')
  assert.equal(formatTime('2026-09-16T07:30:00.123456'), '09-16 07:30')
})

test('an unparsable timestamp is shown as-is rather than Invalid Date', () => {
  assert.equal(formatTime('不是时间'), '不是时间')
  assert.equal(formatTime(''), '')
  assert.equal(formatTime(null), '')
})