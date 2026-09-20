<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'

const sources = ref([])
const selected = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')
const form = reactive({ sourceKey: '', name: '', url: '', category: 'markets', credibility: 70, enabled: true })

function resetForm() {
  selected.value = null
  Object.assign(form, { sourceKey: '', name: '', url: '', category: 'markets', credibility: 70, enabled: true })
}

function edit(source) {
  selected.value = source
  Object.assign(form, {
    sourceKey: source.sourceKey,
    name: source.name,
    url: source.url,
    category: source.category || 'general',
    credibility: source.credibility ?? 50,
    enabled: source.enabled !== false,
  })
  error.value = ''
}

async function load() {
  loading.value = true
  try {
    const response = await api.adminNewsSources()
    if (response.code !== 200) throw new Error(response.message || 'RSS 来源加载失败')
    sources.value = response.data?.items || []
  } catch (e) { error.value = e?.message || String(e) }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const payload = { ...form, credibility: Number(form.credibility) }
    const response = selected.value
      ? await api.adminUpdateNewsSource(selected.value.sourceKey, payload)
      : await api.adminCreateNewsSource(payload)
    if (response.code !== 200) throw new Error(response.message || 'RSS 来源保存失败')
    message.value = selected.value ? 'RSS 来源已更新' : 'RSS 来源已添加'
    await load()
    edit(response.data)
  } catch (e) { error.value = e?.message || String(e) }
  finally { saving.value = false }
}

async function toggle(source) {
  edit(source)
  form.enabled = !source.enabled
  await save()
}

onMounted(async () => { await load(); if (sources.value.length) edit(sources.value[0]) })
</script>

<template>
  <section class="source-admin detail-panel">
    <div class="section-title">
      <div><b>RSS 来源管理</b><span>至少保留 10 个财经来源；停用来源不会删除历史订阅记录</span></div>
      <button type="button" class="btn" @click="resetForm">新增来源</button>
    </div>
    <div v-if="error" class="source-error" role="alert">{{ error }}</div>
    <div v-if="message" class="source-message" role="status">{{ message }}</div>
    <div class="source-admin-layout">
      <div class="source-admin-list">
        <span v-if="loading" class="source-empty">正在加载…</span>
        <button v-for="source in sources" :key="source.sourceKey" type="button" class="source-admin-row" :class="{ active: selected?.sourceKey === source.sourceKey }" @click="edit(source)">
          <span><b>{{ source.name }}</b><small>{{ source.sourceKey }} · {{ source.category }}</small></span>
          <i :class="source.enabled ? 'enabled' : 'disabled'"></i>
        </button>
        <span v-if="!loading && !sources.length" class="source-empty">暂无来源</span>
      </div>
      <div class="source-form">
        <div class="form-grid">
          <label><span>Source key</span><input v-model="form.sourceKey" class="input" :disabled="Boolean(selected)" maxlength="80" /></label>
          <label><span>名称</span><input v-model="form.name" class="input" maxlength="120" /></label>
          <label class="wide"><span>RSS URL</span><input v-model="form.url" class="input" maxlength="500" /></label>
          <label><span>分类</span><input v-model="form.category" class="input" maxlength="40" /></label>
          <label><span>可信度 0-100</span><input v-model.number="form.credibility" class="input" type="number" min="0" max="100" /></label>
        </div>
        <label class="enabled-control"><input v-model="form.enabled" type="checkbox" /> 对用户可用</label>
        <div class="source-form-actions"><button type="button" class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存来源' }}</button><button v-if="selected" type="button" class="btn danger" :disabled="saving" @click="toggle(selected)">{{ form.enabled ? '停用来源' : '重新启用' }}</button></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.source-admin { margin-top: 10px; }
.source-admin-layout { display: grid; grid-template-columns: minmax(220px, 300px) minmax(0, 1fr); gap: 12px; margin-top: 11px; }
.source-admin-list { display: grid; align-content: start; gap: 3px; max-height: 280px; overflow: auto; }
.source-admin-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; border: 1px solid transparent; background: transparent; color: var(--text); padding: 8px; border-radius: var(--radius-sm); cursor: pointer; text-align: left; }
.source-admin-row:hover, .source-admin-row.active { border-color: #5f523a; background: rgba(201,166,95,.065); }
.source-admin-row span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.source-admin-row b { overflow: hidden; color: var(--text); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.source-admin-row small, .source-empty { color: var(--subtle); font-size: 8px; }
.source-admin-row i { width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: var(--bad); }
.source-admin-row i.enabled { background: var(--ok); }
.source-form { min-width: 0; padding: 10px; border: 1px solid var(--line); border-radius: var(--radius-sm); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.form-grid label { display: flex; flex-direction: column; gap: 5px; }
.form-grid label.wide { grid-column: 1 / -1; }
.form-grid label span { color: var(--muted); font-size: 8px; }
.input { width: 100%; height: 30px; padding: 0 8px; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); outline: none; font-size: 9px; }
.input:focus { border-color: #695b40; }
.enabled-control { display: flex; align-items: center; gap: 6px; margin-top: 10px; color: var(--muted); font-size: 9px; }
.enabled-control input { accent-color: var(--accent); }
.source-form-actions { display: flex; gap: 7px; margin-top: 11px; }
.btn { border: 1px solid var(--line-strong); background: #1c1f22; color: var(--text); border-radius: var(--radius-sm); padding: 6px 11px; cursor: pointer; font-size: 10px; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #17140e; font-weight: 700; }
.btn.danger { border-color: #55383a; background: rgba(239,83,80,.05); color: #d97774; }
.btn:disabled { opacity: .45; cursor: not-allowed; }
.source-error, .source-message { margin-top: 9px; font-size: 9px; }
.source-error { color: #e47d79; }.source-message { color: #67d69a; }
@media (max-width: 720px) { .source-admin-layout { grid-template-columns: 1fr; } }
</style>
