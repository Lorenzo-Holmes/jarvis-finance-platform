export const MODULE_LANES = [
  { key: 'market', label: 'MARKET / 市场' },
  { key: 'research', label: 'RESEARCH / 研究' },
  { key: 'intelligence', label: 'INTELLIGENCE / 情报' },
  { key: 'strategy', label: 'STRATEGY / 策略' },
  { key: 'execution', label: 'EXECUTION / 执行' },
]

/**
 * 模块的功能分组（二级导航用）。
 *
 * 顺序是刻意排的，按"看数据 → 做研究 → 盯风险 → 下单 → 运维"的工作流来，
 * 而不是按字母或按模块编号。模块归哪一组由各自的 category 字段决定。
 *
 * 注意与上面 MODULE_LANES 的区别：那是**归档版面**的 5 条泳道（没有 SYSTEM），
 * 而 category 有 6 种（多了运维）。分组导航必须按 category 走，否则运维（模块 12）
 * 会无处可去；两类标签的措辞保持一致只是为了读起来统一。
 */
export const MODULE_CATEGORY_ORDER = [
  { key: 'MARKET', labelEn: 'MARKET', labelZh: '市场' },
  { key: 'RESEARCH', labelEn: 'RESEARCH', labelZh: '研究' },
  { key: 'INTELLIGENCE', labelEn: 'INTELLIGENCE', labelZh: '情报' },
  { key: 'STRATEGY', labelEn: 'STRATEGY', labelZh: '策略' },
  { key: 'EXECUTION', labelEn: 'EXECUTION', labelZh: '执行' },
  { key: 'SYSTEM', labelEn: 'SYSTEM', labelZh: '系统' },
]

/**
 * 按功能把模块分组，供二级导航渲染。
 *
 * 两条不变量：**不丢模块**（未知或缺失 category 的模块落到 OTHER 组并排在最后，
 * 不被静默吞掉）、**组内保持原顺序**（模块数组本身按 no 升序，分组不改动它）。
 *
 * 分组只影响导航的呈现：归档版面（lane/row）与键盘循环仍按扁平模块表走，
 * 所以这个函数不改变、也不该改变任何模块数据。
 */
export function groupModulesByCategory(modules = JARVIS_MODULES) {
  const known = new Set(MODULE_CATEGORY_ORDER.map(group => group.key))
  const groups = MODULE_CATEGORY_ORDER.map(group => ({ ...group, modules: [] }))
  const other = { key: 'OTHER', labelEn: 'OTHER', labelZh: '其它', modules: [] }

  for (const module of modules) {
    if (!module || !module.category || !known.has(module.category)) {
      other.modules.push(module)
      continue
    }
    groups.find(group => group.key === module.category).modules.push(module)
  }

  const result = groups.filter(group => group.modules.length)
  if (other.modules.length) result.push(other)
  return result
}

export const ADMIN_WORKSPACE_MODULE = Object.freeze({
  id: 'module:admin', no: 16, lane: 4, row: 3, key: 'admin',
  labelEn: 'ADMIN', labelZh: '管理后台', category: 'SYSTEM', routeKey: '管理后台',
  code: 'SY-15', summary: '管理用户状态、角色、配额与功能权限。',
  capabilities: ['USERS', 'ROLES', 'QUOTAS'], availability: 'ready', adminOnly: true,
})

export const JARVIS_MODULES = [
  {
    id: 'module:market', no: 1, lane: 0, row: 0, key: 'market',
    labelEn: 'MARKET', labelZh: '行情', category: 'MARKET', routeKey: '行情',
    code: 'MK-01', summary: '观察实时价格、量能、结构与市场状态。',
    capabilities: ['LIVE FEED', 'K LINE', 'INDICATORS'], availability: 'ready',
  },
  {
    id: 'module:cross-market', no: 2, lane: 0, row: 1, key: 'cross-market',
    labelEn: 'CROSS MARKET', labelZh: '多市场', category: 'MARKET', routeKey: '多市场',
    code: 'MK-02', summary: '比较 A 股、美股、加密与商品市场的强弱、波动和关联。',
    capabilities: ['RELATIVE STRENGTH', 'CORRELATION', 'FLOW'], availability: 'ready',
  },
  {
    id: 'module:backtest', no: 3, lane: 3, row: 0, key: 'backtest',
    labelEn: 'BACKTEST', labelZh: '回测', category: 'STRATEGY', routeKey: '回测',
    code: 'ST-03', summary: '把策略假设放进历史数据中进行可复现实验。',
    capabilities: ['EXPERIMENT', 'DRAWDOWN', 'TRADE LOG'], availability: 'ready',
  },
  {
    id: 'module:sim-trade', no: 4, lane: 4, row: 0, key: 'sim-trade',
    labelEn: 'SIM TRADE', labelZh: '模拟盘', category: 'EXECUTION', routeKey: '模拟盘',
    code: 'EX-04', summary: '在专业交易工作台中进行模拟下单、持仓和止损管理。',
    capabilities: ['ORDER TICKET', 'POSITIONS', 'STOPS'], availability: 'ready',
  },
  {
    id: 'module:ai-research', no: 5, lane: 1, row: 0, key: 'ai-research',
    labelEn: 'AI RESEARCH', labelZh: '研究助手', category: 'RESEARCH', routeKey: '研究助手',
    code: 'RS-05', summary: '围绕当前研究对象组织论点、证据、反证和风险。',
    capabilities: ['THESIS', 'EVIDENCE', 'COUNTER CASE'], availability: 'ready',
  },
  {
    id: 'module:bull-bear', no: 6, lane: 1, row: 1, key: 'bull-bear',
    labelEn: 'BULL / BEAR', labelZh: '多空研报', category: 'RESEARCH', routeKey: '多空研报',
    code: 'RS-06', summary: '并列呈现多头与空头证据，保留争议核心与失效条件。',
    capabilities: ['BULL CASE', 'BEAR CASE', 'SYNTHESIS'], availability: 'ready',
  },
  {
    id: 'module:financial', no: 7, lane: 1, row: 2, key: 'financial',
    labelEn: 'FINANCIAL', labelZh: '财报解析', category: 'RESEARCH', routeKey: '财报解析',
    code: 'RS-07', summary: '阅读财务结构、盈利质量、现金流与异常变化。',
    capabilities: ['FUNDAMENTALS', 'FILING', 'QUALITY'], availability: 'ready',
  },
  {
    id: 'module:industry-chain', no: 8, lane: 2, row: 0, key: 'industry-chain',
    labelEn: 'INDUSTRY CHAIN', labelZh: '产业链图谱', category: 'INTELLIGENCE', routeKey: '产业链图谱',
    code: 'IN-08', summary: '沿上下游节点追踪公司、产业位置和关联风险。',
    capabilities: ['GRAPH', 'NODE FILE', 'RELATION'], availability: 'ready',
  },
  {
    id: 'module:risk', no: 9, lane: 2, row: 1, key: 'risk',
    labelEn: 'RISK', labelZh: '风险预警', category: 'INTELLIGENCE', routeKey: '风险预警',
    code: 'IN-09', summary: '监控风险事件、触发依据、影响范围和解除条件。',
    capabilities: ['ALERT', 'TRIGGER', 'TIMELINE'], availability: 'ready',
  },
  {
    id: 'module:smart-quote', no: 10, lane: 2, row: 2, key: 'smart-quote',
    labelEn: 'SMART QUOTE', labelZh: '智能报价', category: 'INTELLIGENCE', routeKey: '智能报价',
    code: 'IN-10', summary: '查看标的实时报价，并基于服务端行情生成可解释的趋势区间。',
    capabilities: ['LIVE QUOTE', 'TREND BAND', 'FORECAST'], availability: 'ready',
  },
  {
    id: 'module:strategy', no: 11, lane: 3, row: 1, key: 'strategy',
    labelEn: 'STRATEGY', labelZh: '策略生成', category: 'STRATEGY', routeKey: '策略生成',
    code: 'ST-11', summary: '把目标、约束与风险预算整理为结构化策略草案。',
    capabilities: ['OBJECTIVE', 'RULES', 'VALIDATION'], availability: 'ready',
  },
  {
    id: 'module:ops', no: 12, lane: 4, row: 1, key: 'ops',
    labelEn: 'OPS', labelZh: '运维', category: 'SYSTEM', routeKey: '运维',
    code: 'EX-12', summary: '查看 Java API、AI、数据库、行情流和认证服务状态。',
    capabilities: ['HEALTH', 'LATENCY', 'AUDIT'], availability: 'ready',
  },
  {
    id: 'module:market-trend', no: 13, lane: 2, row: 3, key: 'market-trend',
    labelEn: 'MARKET TREND', labelZh: '市场趋势预测', category: 'INTELLIGENCE', routeKey: '市场趋势预测',
    code: 'IN-13', summary: '基于服务端自营日 K 数据，展示统计趋势区间、技术依据与 AI 解读。',
    capabilities: ['TREND BAND', 'TECHNICAL BASIS', 'AI INTERPRETATION'], availability: 'ready',
  },
  {
    id: 'module:scheduled-tasks', no: 14, lane: 4, row: 2, key: 'scheduled-tasks',
    labelEn: 'AUTOMATION', labelZh: '定时任务', category: 'SYSTEM', routeKey: '定时任务',
    code: 'SY-14', summary: '管理周期任务、立即执行、暂停恢复、执行历史与自动化通知。',
    capabilities: ['SCHEDULE', 'RUN HISTORY', 'NOTIFY'], availability: 'ready',
  },
  {
    id: 'module:rss-news', no: 15, lane: 2, row: 4, key: 'rss-news',
    labelEn: 'RSS / NEWS', labelZh: 'RSS资讯', category: 'INTELLIGENCE', routeKey: 'RSS资讯',
    code: 'IN-15', summary: '聚合多来源财经资讯，按来源与主题订阅并保留抓取状态。',
    capabilities: ['10+ SOURCES', 'SUBSCRIPTIONS', 'DIGEST'], availability: 'ready',
  },
  {
    id: 'module:community', no: 17, lane: 1, row: 3, key: 'community',
    labelEn: 'COMMUNITY', labelZh: '社区', category: 'RESEARCH', routeKey: '社区',
    code: 'RS-17', summary: '围绕研究主题建立小组、公开观点、用户连接与站内私信。',
    capabilities: ['GROUPS', 'FEED', 'MESSAGES', 'SHARE'], availability: 'ready',
  },
  {
    id: 'module:profile', no: 18, lane: 4, row: 3, key: 'profile',
    labelEn: 'PROFILE', labelZh: '个人中心', category: 'SYSTEM', routeKey: '个人中心',
    code: 'SY-18', summary: '维护个人档案、隐私设置、研究动态与长期成就。',
    capabilities: ['PROFILE', 'PRIVACY', 'ACHIEVEMENTS'], availability: 'ready',
  },
]

export function wrap(value, count) {
  return ((value % count) + count) % count
}

export function modulesForLane(lane, modules = JARVIS_MODULES) {
  const canonicalLane = wrap(lane, MODULE_LANES.length)
  return modules
    .filter(module => module.lane === canonicalLane)
    .sort((a, b) => a.row - b.row)
}

export function moduleAtCell(lane, row, modules = JARVIS_MODULES) {
  const list = modulesForLane(lane, modules)
  if (!list.length) return modules[0] || null
  return list[wrap(row, list.length)]
}

export function nearestOccurrence(value, center, period) {
  if (!period) return value
  return value + Math.floor((center - value + period / 2) / period) * period
}

export function cellForModule(key, currentCell = { lane: 0, row: 0 }, modules = JARVIS_MODULES) {
  const module = modules.find(item => item.key === key) || modules[0]
  if (!module) return { lane: 0, row: 0 }
  const lane = nearestOccurrence(module.lane, currentCell.lane, MODULE_LANES.length)
  const laneList = modulesForLane(module.lane, modules)
  const moduleRow = Math.max(0, laneList.findIndex(item => item.key === module.key))
  const row = nearestOccurrence(moduleRow, currentCell.row, Math.max(1, laneList.length))
  return { lane, row }
}

export function moduleByKey(key, modules = JARVIS_MODULES) {
  return modules.find(module => module.key === key) || modules[0] || null
}
