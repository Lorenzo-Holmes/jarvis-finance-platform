<script setup>
import { computed, ref, onMounted } from 'vue'
import { api } from '../api/client'
import { usePolling } from '../composables/usePolling'

const java = ref(null)   // Java + DB readiness
const db = ref(null)     // 数据库详细状态（登录后）
const py = ref(null)     // Python AI 服务（经 Java 代理检查）
const engine = ref(null) // AI provider/model
const auditEvents = ref([])
const auditError = ref('')
const report = ref(null)
const reportError = ref('')
const lastCheck = ref('')
const healthHistory = ref([]) // 本次会话最近 60 次探针结果
const notifData = ref(null)  // 站内通知（最近若干条 + 未读数）
const notifError = ref('')
const notifBusy = ref(false)
const HEALTHY_STATUSES = new Set(['ok', 'ready', 'up', 'healthy'])

function hasHealthyStatus(value, expected = []) {
  if (!value || value.error) return false
  const accepted = expected.length ? new Set(expected) : HEALTHY_STATUSES
  return accepted.has(String(value.status || '').toLowerCase())
}

async function check() {
  lastCheck.value = new Date().toLocaleTimeString('zh-CN')
  try {
    const d = await api.healthReady()
    java.value = d.code === 200 && d.data?.status === 'ready' ? d.data : { error: d.message || 'Java/DB not ready' }
  } catch (e) { java.value = { error: String(e) } }
  try {
    const d = await api.databaseHealth()
    db.value = d.code === 200 ? d.data : { error: d.message || '数据库不可用' }
  } catch (e) { db.value = { error: String(e) } }
  try {
    const d = await api.aiServiceHealth()
    const value = d.data ?? d
    py.value = d.code === 200 || d.status === 'ready' ? value : { error: d.message || 'AI服务不可用' }
  } catch (e) { py.value = { error: String(e) } }
  try {
    const d = await api.aiStatus()
    engine.value = d.data?.available ? d.data : { error: d.data?.message || d.message || 'AI引擎不可用' }
  } catch (e) { engine.value = { error: String(e) } }
  healthHistory.value.push({
    time: new Date(),
    statuses: [
      { name: 'Java API', ok: hasHealthyStatus(java.value, ['ready']) },
      { name: 'PostgreSQL', ok: hasHealthyStatus(db.value, ['up']) },
      { name: 'Python Service', ok: hasHealthyStatus(py.value) },
      { name: 'Research Engine', ok: !engine.value?.error },
    ],
  })
  if (healthHistory.value.length > 60) healthHistory.value.shift()
}

async function loadAudit() {
  auditError.value = ''
  try {
    const response = await api.auditRecent(20)
    if (response?.code !== 200) throw new Error(response?.message || '审计日志加载失败')
    auditEvents.value = Array.isArray(response?.data) ? response.data : []
  } catch (e) {
    auditError.value = e?.message || '审计日志暂不可用'
  }
}

function formatAuditTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

async function loadReport() {
  reportError.value = ''
  try {
    const response = await api.auditReport(7)
    if (response?.code !== 200) throw new Error(response?.message || '运维报表加载失败')
    report.value = response.data || null
  } catch (e) {
    reportError.value = e?.message || '运维报表暂不可用'
  }
}

const reportCards = computed(() => {
  const r = report.value
  if (!r) return []
  const top = r.actions?.[0] || null
  return [
    { label: '近 7 天事件', value: r.total ?? 0 },
    { label: '今日事件', value: r.today ?? 0 },
    { label: '涉及动作', value: r.actions?.length ?? 0 },
    { label: '最活跃动作', value: top ? top.action : '—', isText: true, count: top ? top.count : null },
  ]
})

const reportDailyMax = computed(() => Math.max(1, ...(report.value?.daily || []).map(d => d.count || 0)))

async function loadNotifications() {
  notifError.value = ''
  try {
    const response = await api.notifications(false, 0, 6)
    if (response?.code !== 200) throw new Error(response?.message || '通知加载失败')
    notifData.value = response.data || null
  } catch (e) {
    notifError.value = e?.message || '通知暂不可用'
  }
}

async function markAllNotifsRead() {
  if (notifBusy.value || !notifData.value?.unread) return
  notifBusy.value = true
  try {
    await api.markAllNotificationsRead()
    await loadNotifications()
  } finally {
    notifBusy.value = false
  }
}

const NOTIF_LEVEL = {
  RISK: { text: '高风险', cls: 'risk' },
  WARN: { text: '警告', cls: 'warn' },
  INFO: { text: '信息', cls: 'info' },
}
const NOTIF_TYPE = {
  TASK_FAILED: '任务失败',
  TASK_AUTO_PAUSED: '任务自动暂停',
  RISK_ALERT: '风险警报',
}
function notifLevel(level) { return NOTIF_LEVEL[level] || { text: level || '—', cls: 'info' } }
function notifTypeText(type) { return NOTIF_TYPE[type] || type || '—' }

const uptimeRows = computed(() => services.value.map(service => {
  const checks = healthHistory.value.map(h => {
    const s = h.statuses.find(x => x.name === service.name)
    return { time: h.time, ok: s ? s.ok : null }
  })
  const valid = checks.filter(c => c.ok !== null)
  return {
    name: service.name,
    checks,
    uptime: valid.length ? Math.round((valid.filter(c => c.ok).length / valid.length) * 100) : null,
  }
}))

function csvEscape(value) {
  const s = String(value ?? '')
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

function downloadCsv(filename, rows) {
  const csv = '\uFEFF' + rows.map(r => r.map(csvEscape).join(',')).join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function exportAuditCsv() {
  if (!auditEvents.value.length) return
  const rows = [['时间', '动作', '目标', '详情']]
  auditEvents.value.forEach(e => rows.push([formatAuditTime(e.createdAt), e.action, e.target || '', e.detail || '']))
  downloadCsv(`jarvis-audit-${todayStr()}.csv`, rows)
}

function exportReportCsv() {
  const r = report.value
  if (!r) return
  const rows = [['日期', '事件数']]
  ;(r.daily || []).forEach(d => rows.push([d.date, d.count]))
  rows.push([], ['动作', '次数', '占比%'])
  ;(r.actions || []).forEach(a => rows.push([a.action, a.count, Math.round((a.count / (r.total || 1)) * 100)]))
  downloadCsv(`jarvis-ops-report-${todayStr()}.csv`, rows)
}

function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function isToday(date) {
  return String(date || '') === todayStr()
}

function barHeight(count) {
  if (!count) return 3
  return Math.max(8, Math.round((count / reportDailyMax.value) * 150))
}

function reportDayLabel(date) {
  return String(date || '').slice(5)
}

function health(value, ready) {
  if (!value) return 'pending'
  return !value.error && ready ? 'ok' : 'bad'
}

const services = computed(() => [
  {
    name: 'Java API', layer: '核心后端', state: java.value?.error ? '异常' : (java.value?.status === 'ready' ? 'Ready' : '检查中'),
    health: health(java.value, hasHealthyStatus(java.value, ['ready'])), latency: java.value?.database?.latency_ms, detail: java.value?.service || java.value?.error || '等待探针',
    probe: 'https://agent.shengxia.me/api/health/ready', path: '/api/health/ready',
  },
  {
    name: 'PostgreSQL', layer: '数据层', state: db.value?.error ? '异常' : (db.value?.status === 'up' ? '可查询' : '检查中'),
    health: health(db.value, hasHealthyStatus(db.value, ['up'])), latency: db.value?.latency_ms, detail: db.value?.product || db.value?.error || '等待探针',
  },
  {
    name: 'Python Service', layer: '研究服务', state: py.value?.error ? '异常' : (hasHealthyStatus(py.value) ? '运行中' : '检查中'),
    health: health(py.value, hasHealthyStatus(py.value)), latency: null, detail: py.value?.service || py.value?.error || '等待探针',
    probe: 'https://agent.shengxia.me/api/health/ai', path: '/api/health/ai',
  },
  {
    name: 'Research Engine', layer: '模型能力', state: engine.value?.error ? '异常' : (engine.value ? '已配置' : '检查中'),
    health: health(engine.value, Boolean(engine.value && !engine.value.error)), latency: null,
    detail: engine.value?.error || (engine.value ? `${engine.value.provider || '-'} / ${engine.value.display_name || engine.value.model || '-'}` : '等待探针'),
    probe: 'https://agent.shengxia.me/api/ai/capabilities', path: '/api/ai/capabilities',
  },
])
const allHealthy = computed(() => services.value.every(service => service.health === 'ok'))

const polling = usePolling(check, 10000)
const auditPolling = usePolling(() => { loadAudit(); loadReport() }, 30000)
const opsPolling = usePolling(loadNotifications, 60000)
onMounted(() => { check(); loadAudit(); loadReport(); loadNotifications(); polling.start(); auditPolling.start(); opsPolling.start() })
</script>

<template>
  <div class="ops">
    <div class="ops-head">
      <div><h2>运维</h2><span>Java API · PostgreSQL · Python AI · Research Engine</span></div>
      <div class="ops-summary" :class="allHealthy ? 'ok' : 'bad'" role="status" aria-live="polite"><i></i>{{ allHealthy ? '服务正常' : '需要检查' }}</div>
    </div>

    <section class="health-panel">
      <div class="health-table-wrap">
        <table class="health-table">
          <thead><tr><th>服务</th><th>层级</th><th>状态</th><th>延迟</th><th>详情</th><th>探针</th></tr></thead>
          <tbody>
            <tr v-for="service in services" :key="service.name">
              <td><div class="service-name"><i :class="service.health"></i><b>{{ service.name }}</b></div></td>
              <td>{{ service.layer }}</td>
              <td><span class="state-text" :class="service.health">{{ service.state }}</span></td>
              <td>{{ service.latency == null ? '-' : service.latency + ' ms' }}</td>
              <td class="detail-cell" :class="service.health === 'bad' ? 'bad-text' : ''">{{ service.detail }}</td>
              <td>
                <a v-if="service.probe" :href="service.probe" target="_blank" rel="noopener">{{ service.path }}</a>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="uptime-panel">
      <header>
        <div class="report-title"><strong>服务可用率</strong><span>本次会话 · 最近 60 次探针 · 每 10 秒一次</span></div>
      </header>
      <div class="uptime-list">
        <div v-for="row in uptimeRows" :key="row.name" class="uptime-row">
          <b class="uptime-name">{{ row.name }}</b>
          <div class="uptime-strip">
            <i
              v-for="(c, i) in row.checks"
              :key="i"
              :class="c.ok === null ? 'pending' : (c.ok ? 'up' : 'down')"
              :title="`${c.time.toLocaleTimeString('zh-CN')} · ${c.ok === null ? '采集中' : (c.ok ? '正常' : '异常')}`"
            ></i>
          </div>
          <span class="uptime-pct" :class="row.uptime == null ? '' : (row.uptime >= 90 ? 'up' : 'down')">{{ row.uptime == null ? '—' : row.uptime + '%' }}</span>
        </div>
      </div>
      <p v-if="!healthHistory.length" class="audit-state">探针数据采集中，稍候…</p>
    </section>

    <section class="audit-panel">
      <header>
        <div><strong>最近审计事件</strong><span>当前账号 · 最近 20 条</span></div>
        <div class="panel-actions">
          <button type="button" :disabled="!auditEvents.length" @click="exportAuditCsv">导出 CSV</button>
          <button type="button" @click="loadAudit(); loadReport()">刷新</button>
        </div>
      </header>
      <p v-if="auditError && !auditEvents.length" class="audit-state">{{ auditError }}</p>
      <p v-else-if="!auditEvents.length" class="audit-state">暂无审计事件。</p>
      <div v-else class="audit-table-wrap">
        <table class="audit-table">
          <thead><tr><th>时间</th><th>动作</th><th>目标</th><th>详情</th></tr></thead>
          <tbody><tr v-for="event in auditEvents" :key="event.id"><td>{{ formatAuditTime(event.createdAt) }}</td><td><code>{{ event.action }}</code></td><td>{{ event.target || '—' }}</td><td class="audit-detail">{{ event.detail || '—' }}</td></tr></tbody>
        </table>
      </div>
    </section>

    <section class="report-panel">
      <header>
        <div class="report-title"><strong>运维报表</strong><span>当前账号 · 近 7 天审计事件聚合</span></div>
        <div class="panel-actions">
          <button type="button" :disabled="!report" @click="exportReportCsv">导出 CSV</button>
          <button type="button" @click="loadReport"><i class="btn-dot"></i>刷新报表</button>
        </div>
      </header>
      <p v-if="reportError && !report" class="audit-state">{{ reportError }}</p>
      <template v-else-if="report">
        <div class="report-cards">
          <div v-for="(card, idx) in reportCards" :key="card.label" class="report-card" :class="`accent-${idx}`">
            <span class="report-card-label">{{ card.label }}</span>
            <b class="report-card-value" :title="card.isText ? card.value : ''">{{ card.value }}</b>
            <span v-if="card.count != null" class="report-card-sub">{{ card.count }} 次 · 占比 {{ Math.round((card.count / (report.total || 1)) * 100) }}%</span>
            <span v-else class="report-card-sub">&nbsp;</span>
          </div>
        </div>
        <div class="report-body">
          <div class="report-charts">
            <div class="chart-block">
              <h4><i class="chart-dot ok"></i>按日事件趋势</h4>
              <div class="daily-bars">
                <div
                  v-for="day in report.daily"
                  :key="day.date"
                  class="daily-bar-col"
                  :class="{ today: isToday(day.date) }"
                  :title="`${day.date}${isToday(day.date) ? '（今天）' : ''} · ${day.count} 次`"
                >
                  <span class="daily-bar-count">{{ day.count || '' }}</span>
                  <div class="daily-bar-slot">
                    <i class="daily-bar" :style="{ height: barHeight(day.count) + 'px' }" :class="{ zero: !day.count }"></i>
                  </div>
                  <span class="daily-bar-label">{{ reportDayLabel(day.date) }}</span>
                  <span v-if="isToday(day.date)" class="daily-bar-tag">今天</span>
                </div>
              </div>
            </div>
            <div class="chart-block">
              <h4><i class="chart-dot blue"></i>动作分布（Top 10）</h4>
              <p v-if="!report.actions?.length" class="audit-state">近 7 天无审计动作。</p>
              <ul v-else class="action-list">
                <li v-for="(item, i) in report.actions" :key="item.action">
                  <span class="action-rank" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                  <code :title="item.action">{{ item.action }}</code>
                  <div class="action-bar-track"><i :style="{ width: Math.round((item.count / (report.total || 1)) * 100) + '%' }"></i></div>
                  <span class="action-count">{{ item.count }} 次 · {{ Math.round((item.count / (report.total || 1)) * 100) }}%</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </template>
      <p v-else class="audit-state">报表加载中…</p>
    </section>

    <section class="notif-panel">
      <header>
        <div class="report-title"><strong>通知概览</strong><span>任务失败 / 自动暂停 / 风险事件 · 最近 6 条</span></div>
        <div class="panel-actions">
          <button type="button" :disabled="!notifData?.unread || notifBusy" @click="markAllNotifsRead">{{ notifBusy ? '处理中…' : `全部已读${notifData?.unread ? `（${notifData.unread}）` : ''}` }}</button>
          <button type="button" @click="loadNotifications">刷新</button>
        </div>
      </header>
      <p v-if="notifError && !notifData" class="audit-state">{{ notifError }}</p>
      <p v-else-if="!notifData?.items?.length" class="audit-state">暂无通知。任务失败、自动暂停和风险事件会在这里提醒。</p>
      <ul v-else class="notif-list">
        <li v-for="item in notifData.items" :key="item.id" :class="{ unread: !item.read }">
          <span class="notif-level" :class="notifLevel(item.level).cls">{{ notifLevel(item.level).text }}</span>
          <div class="notif-main">
            <div class="notif-title">
              <b>{{ item.title || notifTypeText(item.type) }}</b>
              <span v-if="(item.repeat_count || 1) > 1" class="notif-repeat">×{{ item.repeat_count }}</span>
              <i v-if="!item.read" class="notif-dot"></i>
            </div>
            <p class="notif-body">{{ item.body || '—' }}</p>
          </div>
          <span class="notif-time" :title="`首次: ${formatAuditTime(item.created_at)}\n最近: ${formatAuditTime(item.last_seen_at)}`">{{ formatAuditTime(item.last_seen_at) }}</span>
        </li>
      </ul>
    </section>

    <div class="ops-footer">
      <span>LAST CHECK <b>{{ lastCheck || '--' }}</b></span>
      <span>AUTO REFRESH <b>10s</b></span>
      <button type="button" @click="check">重新检查</button>
    </div>
  </div>
</template>

<style scoped>
.ops { display: flex; flex-direction: column; gap: 12px; }
.ops-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 50px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.ops-head h2 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .1em; }
.ops-head > div:first-child > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.ops-summary { display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 0; background: transparent; padding: 5px 8px; color: var(--muted); font: 700 8px/1 ui-monospace, monospace; letter-spacing: .055em; }
.ops-summary i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.ops-summary.ok { color: #67c98e; }
.ops-summary.ok i { background: var(--ok); }
.ops-summary.bad { color: #e47d79; }
.health-panel { background: transparent; border: 1px solid var(--line); border-radius: 0; overflow: hidden; }
.health-table-wrap { width: 100%; overflow-x: auto; }
.health-table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: 10px; }
.health-table th, .health-table td { text-align: left; padding: 11px 12px; border-bottom: 1px solid var(--line); color: var(--muted); white-space: nowrap; }
.health-table th { position: sticky; top: 0; z-index: 2; background: var(--surface-2, #242b31); color: var(--text, #eee9de); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .07em; box-shadow: 0 1px 0 var(--line-strong, #3a4651); }
.health-table tbody tr:last-child td { border-bottom: 0; }
.health-table tbody tr:hover td { background: var(--workspace-hover-bg, rgba(255,255,255,.055)); }
.service-name { display: flex; align-items: center; gap: 8px; }
.service-name i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.service-name i.ok { background: var(--ok); }
.service-name i.pending { background: var(--line-strong); opacity: .65; }
.service-name b { color: var(--text); font-size: 10px; font-weight: 650; }
.state-text { color: var(--muted); font-weight: 600; }
.state-text.ok { color: #67c98e; }
.state-text.bad, .bad-text { color: #e47d79 !important; }
.state-text.pending { color: var(--muted); }
.detail-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; }
.health-table a { color: var(--accent-strong); text-decoration: none; font-size: 9px; }
.health-table a:hover { text-decoration: underline; }
.audit-panel { border: 1px solid var(--line); overflow: hidden; }
.audit-panel > header { min-height: 44px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 12px; border-bottom: 1px solid var(--line); }
.audit-panel > header > div { display: flex; align-items: baseline; gap: 8px; }
.audit-panel header strong { color: var(--text); font-size: 10px; }
.audit-panel header span { color: var(--subtle); font-size: 8px; }
.audit-panel header button { min-height: 26px; padding: 0 8px; border: 1px solid var(--line); background: transparent; color: var(--muted); cursor: pointer; font-size: 8px; }
.audit-panel header button:hover { color: var(--text); border-color: var(--line-strong); }
.audit-state { margin: 0; padding: 20px 12px; color: var(--muted); font-size: 9px; }
.audit-table-wrap { overflow-x: auto; }
.audit-table { width: 100%; min-width: 760px; border-collapse: collapse; font-size: 9px; }
.audit-table th, .audit-table td { padding: 9px 11px; border-bottom: 1px solid var(--line); color: var(--muted); text-align: left; }
.audit-table th { color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; }
.audit-table tbody tr:last-child td { border-bottom: 0; }
.audit-table code { color: var(--text); font: 600 8px/1 ui-monospace, monospace; }
.audit-detail { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.report-panel { border: 1px solid var(--line); overflow: hidden; }
.panel-actions { display: inline-flex; align-items: center; gap: 8px; }
.panel-actions button { min-height: 30px; padding: 0 12px; border: 1px solid var(--line); background: transparent; color: var(--muted); cursor: pointer; font-size: 10px; transition: color .15s, border-color .15s; }
.panel-actions button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); }
.panel-actions button:disabled { opacity: .4; cursor: not-allowed; }
.uptime-panel { border: 1px solid var(--line); overflow: hidden; }
.uptime-panel > header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 16px; border-bottom: 1px solid var(--line); }
.uptime-list { display: flex; flex-direction: column; gap: 14px; padding: 16px; }
.uptime-row { display: grid; grid-template-columns: 130px 1fr 52px; align-items: center; gap: 14px; }
.uptime-name { color: var(--text); font-size: 10px; font-weight: 650; }
.uptime-strip { display: flex; gap: 2px; height: 20px; overflow: hidden; }
.uptime-strip i { flex: 1; min-width: 3px; max-width: 14px; }
.uptime-strip i.up { background: var(--ok); opacity: .8; }
.uptime-strip i.down { background: #e47d79; }
.uptime-strip i.pending { background: var(--line-strong); opacity: .5; }
.uptime-pct { color: var(--muted); font: 650 11px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; text-align: right; }
.uptime-pct.up { color: #67c98e; }
.uptime-pct.down { color: #e47d79; }
@media (max-width: 650px) { .uptime-row { grid-template-columns: 90px 1fr 44px; } }
.notif-panel { border: 1px solid var(--line); overflow: hidden; }
.notif-panel > header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 16px; border-bottom: 1px solid var(--line); }
.notif-list { list-style: none; margin: 0; padding: 8px 0; }
.notif-list li { display: grid; grid-template-columns: 56px 1fr 130px; align-items: start; gap: 14px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.notif-list li:last-child { border-bottom: 0; }
.notif-list li.unread { background: rgba(91,155,213,.06); }
.notif-level { display: inline-flex; align-items: center; justify-content: center; height: 20px; padding: 0 6px; font: 650 9px/1 ui-monospace, monospace; border: 1px solid transparent; }
.notif-level.risk { background: rgba(228,125,121,.15); color: #e47d79; }
.notif-level.warn { background: rgba(217,164,65,.15); color: #d9a441; }
.notif-level.info { background: rgba(255,255,255,.07); color: var(--muted); }
.notif-main { min-width: 0; }
.notif-title { display: flex; align-items: center; gap: 8px; }
.notif-title b { color: var(--text); font-size: 11px; }
.notif-repeat { color: #d9a441; font-size: 10px; }
.notif-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent-strong, #5b9bd5); }
.notif-body { margin: 4px 0 0; color: var(--muted); font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.notif-time { color: var(--subtle); font-size: 10px; font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
@media (max-width: 650px) { .notif-list li { grid-template-columns: 56px 1fr; } .notif-time { grid-column: 2; text-align: left; } }
.report-panel > header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 16px; border-bottom: 1px solid var(--line); }
.report-title { display: flex; align-items: baseline; gap: 10px; }
.report-title strong { color: var(--text); font-size: 12px; letter-spacing: .04em; }
.report-title span { color: var(--subtle); font-size: 10px; }
.report-panel header button { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 12px; border: 1px solid var(--line); background: transparent; color: var(--muted); cursor: pointer; font-size: 10px; transition: color .15s, border-color .15s; }
.report-panel header button:hover { color: var(--text); border-color: var(--line-strong); }
.btn-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }
.report-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1px; background: var(--line); border-bottom: 1px solid var(--line); }
.report-card { position: relative; display: flex; flex-direction: column; gap: 8px; padding: 18px 16px 16px; background: var(--surface-2, #242b31); transition: background .15s; }
.report-card:hover { background: var(--workspace-hover-bg, rgba(255,255,255,.04)); }
.report-card::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 2px; background: var(--line-strong); }
.report-card.accent-0::before { background: #67c98e; }
.report-card.accent-1::before { background: #5b9bd5; }
.report-card.accent-2::before { background: #d9a441; }
.report-card.accent-3::before { background: #b48ce0; }
.report-card-label { color: var(--subtle); font-size: 10px; letter-spacing: .08em; }
.report-card-value { color: var(--text); font: 650 24px/1.1 ui-monospace, monospace; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.report-card-sub { color: var(--muted); font-size: 10px; }
.report-body { padding: 18px 16px 20px; }
.report-charts { display: grid; grid-template-columns: minmax(300px, 1fr) minmax(340px, 1.2fr); gap: 28px; }
.chart-block h4 { display: flex; align-items: center; gap: 8px; margin: 0 0 14px; color: var(--muted); font: 650 11px/1 ui-monospace, monospace; letter-spacing: .07em; }
.chart-dot { width: 7px; height: 7px; border-radius: 50%; }
.chart-dot.ok { background: var(--ok); }
.chart-dot.blue { background: var(--accent-strong, #5b9bd5); }
.daily-bars { display: flex; align-items: stretch; gap: 12px; height: 232px; padding-top: 4px; }
.daily-bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; min-width: 0; }
.daily-bar-count { color: var(--muted); font-size: 11px; font-weight: 600; font-variant-numeric: tabular-nums; min-height: 16px; }
.daily-bar-slot { flex: 1; display: flex; align-items: flex-end; width: 100%; justify-content: center; border-bottom: 1px solid var(--line); }
.daily-bar { display: block; width: 60%; max-width: 44px; background: linear-gradient(180deg, rgba(103,201,142,.95), rgba(103,201,142,.55)); border-radius: 3px 3px 0 0; transition: filter .15s, opacity .15s; }
.daily-bar-col:hover .daily-bar { filter: brightness(1.25); }
.daily-bar.zero { background: var(--line-strong); opacity: .5; border-radius: 2px; }
.daily-bar-col.today .daily-bar { background: linear-gradient(180deg, rgba(91,155,213,.95), rgba(91,155,213,.5)); box-shadow: 0 0 10px rgba(91,155,213,.35); }
.daily-bar-label { margin-top: 8px; color: var(--subtle); font-size: 10px; white-space: nowrap; }
.daily-bar-col.today .daily-bar-label { color: var(--text); font-weight: 650; }
.daily-bar-tag { margin-top: 2px; color: var(--accent-strong, #5b9bd5); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .06em; min-height: 10px; }
.daily-bar-col:not(.today) .daily-bar-tag { visibility: hidden; }
.action-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.action-list li { display: grid; grid-template-columns: 20px minmax(100px, auto) 1fr auto; align-items: center; gap: 12px; }
.action-rank { display: inline-flex; align-items: center; justify-content: center; width: 18px; height: 18px; border: 1px solid var(--line); color: var(--subtle); font: 650 9px/1 ui-monospace, monospace; }
.action-rank.top { border-color: transparent; background: rgba(217,164,65,.18); color: #d9a441; }
.action-list code { color: var(--text); font: 600 10px/1.2 ui-monospace, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.action-bar-track { height: 8px; background: var(--line); border-radius: 2px; overflow: hidden; }
.action-bar-track i { display: block; height: 100%; background: linear-gradient(90deg, rgba(91,155,213,.55), var(--accent-strong, #5b9bd5)); border-radius: 2px; transition: width .3s ease; }
.action-count { color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; white-space: nowrap; }
@media (max-width: 900px) { .report-charts { grid-template-columns: 1fr; } .daily-bars { gap: 6px; } }
.ops-footer { display: flex; align-items: center; gap: 18px; color: var(--subtle); font-size: 9px; }
.ops-footer b { color: var(--muted); font-weight: 600; font-variant-numeric: tabular-nums; }
.ops-footer button { margin-left: auto; border: 1px solid var(--workspace-action-border); background: var(--workspace-action-bg); color: var(--workspace-action-text); border-radius: 0; padding: 6px 10px; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; }
@media (max-width: 650px) { .ops-head { align-items: flex-start; flex-direction: column; } .ops-footer { align-items: flex-start; flex-wrap: wrap; } .ops-footer button { margin-left: 0; } }
</style>
