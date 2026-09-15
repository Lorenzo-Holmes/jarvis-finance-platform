<script setup>
import { computed, ref, onMounted } from 'vue'
import { api } from '../api/client'
import { usePolling } from '../composables/usePolling'

const java = ref(null)   // Java + DB readiness
const db = ref(null)     // 数据库详细状态（登录后）
const py = ref(null)     // Python AI 服务（经 Java 代理检查）
const engine = ref(null) // AI provider/model
const lastCheck = ref('')

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
}

function ok(v) { return !v || v.error ? 'bad' : 'ok' }

const services = computed(() => [
  {
    name: 'Java API', layer: '核心后端', state: java.value?.error ? '异常' : (java.value?.status === 'ready' ? 'Ready' : '检查中'),
    health: ok(java.value), latency: java.value?.database?.latency_ms, detail: java.value?.service || java.value?.error || '等待探针',
    probe: 'https://agent.shengxia.me/api/health/ready', path: '/api/health/ready',
  },
  {
    name: 'PostgreSQL', layer: '数据层', state: db.value?.error ? '异常' : (db.value?.status === 'up' ? '可查询' : '检查中'),
    health: ok(db.value), latency: db.value?.latency_ms, detail: db.value?.product || db.value?.error || '等待探针',
  },
  {
    name: 'Python Service', layer: '研究服务', state: py.value?.error ? '异常' : (py.value?.status === 'ok' ? '运行中' : '检查中'),
    health: ok(py.value), latency: null, detail: py.value?.service || py.value?.error || '等待探针',
    probe: 'https://agent.shengxia.me/api/health/ai', path: '/api/health/ai',
  },
  {
    name: 'Research Engine', layer: '模型能力', state: engine.value?.error ? '异常' : (engine.value ? '已配置' : '检查中'),
    health: ok(engine.value), latency: null,
    detail: engine.value?.error || (engine.value ? `${engine.value.provider || '-'} / ${engine.value.display_name || engine.value.model || '-'}` : '等待探针'),
    probe: 'https://agent.shengxia.me/api/ai/capabilities', path: '/api/ai/capabilities',
  },
])
const allHealthy = computed(() => services.value.every(service => service.health === 'ok'))

const polling = usePolling(check, 10000)
onMounted(() => { check(); polling.start() })
</script>

<template>
  <div class="ops">
    <div class="ops-head">
      <div><h2>SYSTEM OPERATIONS / SERVICE TOPOLOGY</h2><span>Java API · PostgreSQL · Python AI · Research Engine</span></div>
      <div class="ops-summary" :class="allHealthy ? 'ok' : 'bad'" role="status" aria-live="polite"><i></i>{{ allHealthy ? 'ALL SYSTEMS OPERATIONAL' : 'ATTENTION REQUIRED' }}</div>
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

    <div class="ops-footer">
      <span>LAST CHECK <b>{{ lastCheck || '--' }}</b></span>
      <span>AUTO REFRESH <b>10s</b></span>
      <button type="button" @click="check">RUN PROBE</button>
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
.service-name b { color: var(--text); font-size: 10px; font-weight: 650; }
.state-text { color: var(--muted); font-weight: 600; }
.state-text.ok { color: #67c98e; }
.state-text.bad, .bad-text { color: #e47d79 !important; }
.detail-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; }
.health-table a { color: var(--accent-strong); text-decoration: none; font-size: 9px; }
.health-table a:hover { text-decoration: underline; }
.ops-footer { display: flex; align-items: center; gap: 18px; color: var(--subtle); font-size: 9px; }
.ops-footer b { color: var(--muted); font-weight: 600; font-variant-numeric: tabular-nums; }
.ops-footer button { margin-left: auto; border: 1px solid #383b33; background: #383b33; color: #f2eee6; border-radius: 0; padding: 6px 10px; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; }
@media (max-width: 650px) { .ops-head { align-items: flex-start; flex-direction: column; } .ops-footer { align-items: flex-start; flex-wrap: wrap; } .ops-footer button { margin-left: 0; } }
</style>
