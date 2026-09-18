<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../../api/client'

const emit = defineEmits(['navigate'])

const open = ref(false)
const items = ref([])
const unread = ref(0)
const loading = ref(false)
const error = ref('')
let timer = 0

function levelLabel(level) {
  if (level === 'RISK') return '风险'
  if (level === 'WARN') return '提醒'
  return '通知'
}

function timeLabel(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const response = await api.notifications(false, 0, 20)
    if (response?.code !== 200) throw new Error(response?.message || '通知加载失败')
    items.value = Array.isArray(response?.data?.items) ? response.data.items : []
    unread.value = Number(response?.data?.unread || 0)
  } catch (e) {
    error.value = e?.message || '通知暂不可用'
  } finally {
    loading.value = false
  }
}

async function loadUnread() {
  try {
    const response = await api.notificationUnreadCount()
    if (response?.code === 200) unread.value = Number(response?.data?.unread || 0)
  } catch (_) {
    // 角标轮询失败不打断工作区。
  }
}

async function toggle() {
  open.value = !open.value
  if (open.value) await loadList()
}

async function markRead(item) {
  if (!item?.id || item.read) return
  try {
    const response = await api.markNotificationRead(item.id)
    if (response?.code === 200) {
      item.read = true
      unread.value = Math.max(0, unread.value - 1)
    }
  } catch (_) {}
}

async function openNotification(item) {
  await markRead(item)
  if (item?.link?.kind === 'SCHEDULED_TASK') {
    emit('navigate', { routeKey: '定时任务', ref: item.link.ref })
    open.value = false
  }
}

async function markAll() {
  try {
    const response = await api.markAllNotificationsRead()
    if (response?.code === 200) {
      items.value = items.value.map(item => ({ ...item, read: true }))
      unread.value = Number(response?.data?.unread || 0)
    }
  } catch (_) {}
}

async function remove(item) {
  if (!item?.id) return
  try {
    const response = await api.deleteNotification(item.id)
    if (response?.code === 200) {
      items.value = items.value.filter(candidate => candidate.id !== item.id)
      unread.value = Number(response?.data?.unread ?? unread.value)
    }
  } catch (_) {}
}

function closeFromOutside(event) {
  if (!event.target?.closest?.('.notification-center')) open.value = false
}

onMounted(() => {
  loadUnread()
  timer = window.setInterval(loadUnread, 15000)
  document.addEventListener('pointerdown', closeFromOutside)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  document.removeEventListener('pointerdown', closeFromOutside)
})
</script>

<template>
  <div class="notification-center">
    <button type="button" class="notification-trigger" :aria-expanded="open" aria-label="站内通知" @click.stop="toggle">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9a5 5 0 0 1 10 0c0 6 2 6 2 7H5c0-1 2-1 2-7Z"/><path d="M10 19h4"/></svg>
      <span v-if="unread" class="notification-badge">{{ unread > 99 ? '99+' : unread }}</span>
    </button>

    <section v-if="open" class="notification-popover" aria-label="通知中心">
      <header>
        <div><strong>通知中心</strong><small>{{ unread }} 条未读</small></div>
        <button type="button" :disabled="!unread" @click="markAll">全部已读</button>
      </header>

      <p v-if="loading && !items.length" class="notice-state">正在同步通知…</p>
      <p v-else-if="error && !items.length" class="notice-state">{{ error }}</p>
      <p v-else-if="!items.length" class="notice-state">暂无通知</p>

      <div v-else class="notification-list">
        <article v-for="item in items" :key="item.id" :class="['notification-item', { unread: !item.read }, 'level-' + String(item.level || 'INFO').toLowerCase()]">
          <button type="button" class="notification-main" @click="openNotification(item)">
            <div class="notification-meta">
              <span>{{ levelLabel(item.level) }}</span>
              <time>{{ timeLabel(item.created_at) }}</time>
            </div>
            <strong>{{ item.title || '系统通知' }}</strong>
            <p>{{ item.body || '—' }}</p>
            <small v-if="Number(item.repeat_count) > 1">重复 {{ item.repeat_count }} 次</small>
          </button>
          <button type="button" class="notification-delete" aria-label="删除通知" @click.stop="remove(item)">×</button>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.notification-center { position: relative; }
.notification-trigger { position: relative; width: 34px; height: 34px; display: grid; place-items: center; border: 0; border-radius: 9px; background: transparent; color: var(--muted); cursor: pointer; }
.notification-trigger:hover, .notification-trigger[aria-expanded="true"] { color: var(--text); background: var(--workspace-hover-bg); }
.notification-trigger svg { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.notification-badge { position: absolute; right: -2px; top: -3px; min-width: 16px; height: 16px; display: inline-flex; align-items: center; justify-content: center; padding: 0 4px; border: 2px solid var(--bg); border-radius: 999px; background: var(--bad); color: white; font: 700 7px/1 ui-monospace, monospace; }
.notification-popover { position: absolute; z-index: 260; top: calc(100% + 8px); right: 0; width: min(380px, calc(100vw - 24px)); max-height: min(620px, 72vh); display: flex; flex-direction: column; border: 1px solid var(--line-strong); border-radius: 12px; background: var(--material-elevated, var(--panel-raised)); box-shadow: var(--material-shadow-elevated, 0 24px 70px rgba(0,0,0,.28)); overflow: hidden; }
.notification-popover > header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 12px; border-bottom: 1px solid var(--line); }
.notification-popover > header > div { display: grid; gap: 4px; }
.notification-popover header strong { color: var(--text); font-size: 11px; }
.notification-popover header small { color: var(--subtle); font-size: 8px; }
.notification-popover header button { border: 0; background: transparent; color: var(--muted); cursor: pointer; font-size: 8px; }
.notification-popover header button:hover:not(:disabled) { color: var(--text); }
.notification-popover header button:disabled { opacity: .4; }
.notification-list { overflow-y: auto; }
.notification-item { position: relative; border-bottom: 1px solid var(--line); }
.notification-item:last-child { border-bottom: 0; }
.notification-item.unread { background: color-mix(in srgb, var(--workspace-accent-wash) 54%, transparent); }
.notification-item.level-risk { box-shadow: inset 2px 0 0 var(--bad); }
.notification-item.level-warn { box-shadow: inset 2px 0 0 var(--warn); }
.notification-main { width: 100%; min-height: 94px; display: block; padding: 11px 36px 11px 12px; border: 0; background: transparent; color: var(--text); text-align: left; cursor: pointer; }
.notification-main:hover { background: var(--workspace-hover-bg); }
.notification-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.notification-main > strong { display: block; font-size: 10.5px; line-height: 1.4; }
.notification-main p { margin: 6px 0 0; color: var(--muted); font-size: 9px; line-height: 1.5; }
.notification-main > small { display: block; margin-top: 6px; color: var(--subtle); font-size: 7px; }
.notification-delete { position: absolute; right: 8px; top: 9px; width: 22px; height: 22px; border: 0; border-radius: 5px; background: transparent; color: var(--subtle); cursor: pointer; }
.notification-delete:hover { color: var(--text); background: var(--workspace-hover-bg); }
.notice-state { margin: 0; padding: 24px 14px; color: var(--muted); font-size: 9px; }
@media (max-width: 620px) { .notification-popover { position: fixed; left: 8px; right: 8px; top: 62px; width: auto; } }
</style>
