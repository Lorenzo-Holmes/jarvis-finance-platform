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
      <div><h2>服务健康</h2><span>核心后端、数据库与研究服务状态</span></div>
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
      <span>最后检查 <b>{{ lastCheck || '--' }}</b></span>
      <span>自动刷新间隔 <b>10 秒</b></span>
      <button type="button" @click="check">立即检查</button>
    </div>
  </div>
</template>

<style scoped>
.ops { display: flex; flex-direction: column; gap: 10px; }
.ops-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.ops-head h2 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; }
.ops-head > div:first-child > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.ops-summary { display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 3px; background: var(--surface); padding: 4px 7px; color: var(--muted); font-size: 8px; font-weight: 700; letter-spacing: .055em; }
.ops-summary i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.ops-summary.ok { color: #67c98e; }
.ops-summary.ok i { background: var(--ok); }
.ops-summary.bad { color: #e47d79; }
.health-panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.health-table-wrap { width: 100%; overflow-x: auto; }
.health-table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: 10px; }
.health-table th, .health-table td { text-align: left; padding: 11px 12px; border-bottom: 1px solid #24272b; color: var(--muted); white-space: nowrap; }
.health-table th { position: sticky; top: 0; z-index: 2; background: #131517; color: var(--subtle); font-size: 9px; font-weight: 550; letter-spacing: .02em; box-shadow: 0 1px 0 #24272b; }
.health-table tbody tr:last-child td { border-bottom: 0; }
.health-table tbody tr:hover td { background: #17191b; }
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
.ops-footer button { margin-left: auto; border: 1px solid var(--line-strong); background: #1c1f22; color: var(--text); border-radius: var(--radius-sm); padding: 5px 9px; font-size: 9px; cursor: pointer; }
@media (max-width: 650px) { .ops-head { align-items: flex-start; flex-direction: column; } .ops-footer { align-items: flex-start; flex-wrap: wrap; } .ops-footer button { margin-left: 0; } }
</style>
