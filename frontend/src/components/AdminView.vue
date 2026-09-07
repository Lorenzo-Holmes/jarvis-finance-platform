<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'

const users = ref([])
const selected = ref(null)
const query = ref('')
const loading = ref(false)
const message = ref('')
const error = ref('')
const quota = reactive({ dailyRequestLimit: 100, monthlyTokenLimit: 0, reason: '' })
const permissions = ref('')

function showError(value) {
  error.value = value?.message || value || '操作失败'
  message.value = ''
}

async function loadUsers() {
  loading.value = true
  error.value = ''
  try {
    const response = await api.adminUsers(query.value.trim())
    if (response.code !== 200) throw new Error(response.message || '用户列表加载失败')
    users.value = response.data?.items || []
    if (selected.value && !users.value.some(u => u.id === selected.value.id)) selected.value = null
  } catch (e) { showError(e) }
  finally { loading.value = false }
}

async function selectUser(user) {
  error.value = ''
  try {
    const response = await api.adminUser(user.id)
    if (response.code !== 200) throw new Error(response.message || '用户详情加载失败')
    selected.value = response.data
    Object.assign(quota, {
      dailyRequestLimit: response.data.quota?.dailyRequestLimit ?? 100,
      monthlyTokenLimit: response.data.quota?.monthlyTokenLimit ?? 0,
      reason: '',
    })
    permissions.value = (response.data.permissions || []).join(', ')
  } catch (e) { showError(e) }
}

async function updateStatus() {
  if (!selected.value) return
  try {
    const response = await api.adminUpdateStatus(selected.value.id, !selected.value.enabled)
    if (response.code !== 200) throw new Error(response.message || '账户状态更新失败')
    selected.value = { ...selected.value, ...response.data }
    message.value = '账户状态已更新'
    await loadUsers()
  } catch (e) { showError(e) }
}

async function updateRole() {
  if (!selected.value) return
  try {
    const response = await api.adminUpdateRole(selected.value.id, selected.value.role)
    if (response.code !== 200) throw new Error(response.message || '角色更新失败')
    selected.value = { ...selected.value, ...response.data }
    message.value = '账户角色已更新'
    await loadUsers()
  } catch (e) { showError(e) }
}

async function updateQuota() {
  if (!selected.value || !quota.reason.trim()) {
    showError('请填写配额调整原因')
    return
  }
  try {
    const response = await api.adminUpdateQuota(selected.value.id, {
      dailyRequestLimit: Number(quota.dailyRequestLimit),
      monthlyTokenLimit: Number(quota.monthlyTokenLimit),
      reason: quota.reason.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '配额更新失败')
    await selectUser(selected.value)
    message.value = 'AI 配额已更新'
  } catch (e) { showError(e) }
}

async function updatePermissions() {
  if (!selected.value) return
  try {
    const response = await api.adminUpdatePermissions(selected.value.id, {
      features: permissions.value.split(',').map(v => v.trim()).filter(Boolean),
      reason: '管理员后台调整功能权限',
    })
    if (response.code !== 200) throw new Error(response.message || '权限更新失败')
    selected.value = response.data
    message.value = '功能权限已更新'
  } catch (e) { showError(e) }
}

onMounted(loadUsers)
</script>

<template>
  <div class="admin-view">
    <div class="panel">
      <div class="panel-head">
        <div>
          <h2>账户管理</h2>
          <div class="hint">管理员权限 · 账户状态、角色、AI 配额与功能权限</div>
        </div>
        <div class="search-row">
          <input v-model="query" class="input" placeholder="搜索邮箱或昵称" @keyup.enter="loadUsers" />
          <button class="btn" @click="loadUsers" :disabled="loading">搜索</button>
        </div>
      </div>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="message" class="success">{{ message }}</div>
      <div class="admin-layout">
        <div class="user-list">
          <button v-for="user in users" :key="user.id" class="user-row"
                  :class="{ active: selected?.id === user.id }" @click="selectUser(user)">
            <span><b>{{ user.displayName || user.email }}</b><small>{{ user.email }}</small></span>
            <span class="user-state" :class="user.enabled ? 'pos' : 'neg'">{{ user.enabled ? '启用' : '禁用' }}</span>
          </button>
          <div v-if="!users.length" class="hint">暂无用户</div>
        </div>

        <div v-if="selected" class="user-detail">
          <div class="detail-head">
            <div><h3>{{ selected.displayName || selected.email }}</h3><div class="hint">{{ selected.email }}</div></div>
            <button class="btn" @click="updateStatus">{{ selected.enabled ? '禁用账户' : '启用账户' }}</button>
          </div>
          <div class="form-row">
            <label>角色
              <select v-model="selected.role" class="select" @change="updateRole">
                <option value="USER">普通用户</option>
                <option value="ADMIN">管理员</option>
              </select>
            </label>
          </div>
          <div class="sub-panel">
            <h3>AI 配额</h3>
            <div class="form-row">
              <label>每日请求上限<input v-model.number="quota.dailyRequestLimit" class="num" type="number" min="0" /></label>
              <label>月 Token 上限<input v-model.number="quota.monthlyTokenLimit" class="num wide" type="number" min="0" /></label>
            </div>
            <div class="hint">已用请求 {{ selected.quota?.dailyRequestUsed ?? 0 }}；本月已用 Token {{ selected.quota?.monthlyTokenUsed ?? 0 }}。</div>
            <input v-model="quota.reason" class="input full" placeholder="配额调整原因（必填）" />
            <button class="btn primary" @click="updateQuota">保存配额</button>
          </div>
          <div class="sub-panel">
            <h3>功能权限</h3>
            <input v-model="permissions" class="input full" placeholder="用逗号分隔，如 AI_CHAT, MARKET_ADVANCED" />
            <button class="btn primary" @click="updatePermissions">保存权限</button>
          </div>
        </div>
        <div v-else class="empty-detail hint">选择用户查看详情</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-view { display: flex; flex-direction: column; gap: 12px; }
.panel, .sub-panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
.panel-head, .detail-head, .search-row, .form-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.panel-head, .detail-head { justify-content: space-between; }
h2, h3 { margin: 0 0 8px; color: var(--text); font-weight: 650; }
.hint { color: var(--muted); font-size: 11px; line-height: 1.6; }
.input, .select, .num { background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 8px 10px; }
.input { min-width: 220px; } .input.full { width: 100%; margin: 12px 0; } .num { width: 100px; margin-left: 6px; } .num.wide { width: 140px; }
.btn { background: #1c1f22; border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 8px 14px; cursor: pointer; font-size: 12px; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #17140e; font-weight: 650; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.admin-layout { display: grid; grid-template-columns: minmax(260px, .8fr) 1.4fr; gap: 12px; margin-top: 14px; }
.user-list { display: flex; flex-direction: column; gap: 6px; }
.user-row { display: flex; justify-content: space-between; text-align: left; gap: 8px; background: var(--surface); border: 1px solid #222529; color: var(--text); border-radius: var(--radius-sm); padding: 10px 12px; cursor: pointer; }
.user-row.active { border-color: #75664b; background: var(--accent-soft); }
.user-row span:first-child { display: flex; flex-direction: column; gap: 4px; min-width: 0; } .user-row small { color: var(--muted); overflow: hidden; text-overflow: ellipsis; }
.user-state { font-size: 12px; } .pos { color: #27c46b; } .neg { color: #ef5350; }
.user-detail { display: flex; flex-direction: column; gap: 14px; }
.sub-panel { padding: 14px; } .sub-panel h3 { font-size: 15px; }
.success { color: #27c46b; padding: 8px 10px; background: rgba(39,196,107,.1); border-radius: 6px; margin-top: 12px; }
.error { color: #ef5350; padding: 8px 10px; background: rgba(239,83,80,.1); border-radius: 6px; margin-top: 12px; }
.empty-detail { display: flex; align-items: center; justify-content: center; min-height: 260px; border: 1px dashed var(--line-strong); border-radius: var(--radius-sm); }
@media (max-width: 800px) { .admin-layout { grid-template-columns: 1fr; } .input { min-width: 0; flex: 1; } }
</style>
