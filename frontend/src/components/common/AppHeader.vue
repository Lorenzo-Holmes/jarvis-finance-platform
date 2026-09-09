<script setup>
import { ref, watch } from 'vue'
import { api } from '../../api/client'

const props = defineProps({
  user: { type: Object, default: null },
})

const emit = defineEmits(['logout', 'update-profile'])
const editingProfile = ref(false)
const displayName = ref('')

watch(() => props.user?.displayName, value => {
  displayName.value = value || ''
}, { immediate: true })

function saveProfile() {
  const value = displayName.value.trim()
  if (!value) return
  emit('update-profile', value)
  editingProfile.value = false
}
</script>

<template>
  <header class="navbar">
    <div class="brand">
      <img src="/favicon.svg" class="brand-icon" alt="Jarvis Finance" />
      <span class="brand-name">JARVIS</span>
      <span class="brand-context">RESEARCH</span>
    </div>
    <div class="nav-right">
      <div v-if="props.user" class="user-chip">
        <template v-if="editingProfile">
          <input v-model="displayName" class="profile-input" maxlength="60" aria-label="昵称" @keyup.enter="saveProfile" @keyup.esc="editingProfile = false" />
          <button class="btn small" type="button" @click="saveProfile">保存</button>
        </template>
        <button v-else class="profile-name" type="button" title="修改昵称" @click="editingProfile = true">
          {{ props.user.displayName || props.user.email || '已登录' }}
        </button>
        <span class="email">{{ props.user.email || '已登录' }}</span>
        <span v-if="props.user.role === 'ADMIN'" class="role-tag">ADMIN</span>
        <button class="btn small" type="button" title="绑定 GitHub 账号" @click="api.githubBindAuthorize()">GitHub</button>
        <button class="btn small" type="button" @click="emit('logout')">退出</button>
      </div>
      <div v-else class="guest-state">研究终端</div>
    </div>
  </header>
</template>

<style scoped>
.navbar { display: flex; align-items: center; justify-content: space-between; min-height: 62px; padding: 9px 0; border-bottom: 1px solid var(--line); margin-bottom: 4px; }
.brand { display: flex; align-items: center; gap: 9px; font-weight: 650; letter-spacing: .04em; }
.brand-icon { width: 27px; height: 27px; display: inline-block; object-fit: contain; vertical-align: middle; }
.brand-name { font-size: 15px; }
.brand-context { color: var(--subtle); font-size: 9px; font-weight: 650; letter-spacing: .16em; border-left: 1px solid var(--line-strong); padding-left: 9px; }
.nav-right { display: flex; align-items: center; gap: 12px; }
.user-chip { display: flex; align-items: center; gap: 10px; }
.email { color: var(--muted); font-size: 12px; }
.profile-name { border: 0; background: transparent; color: var(--text); padding: 0; cursor: pointer; font-size: 12px; }
.profile-name:hover { color: var(--accent-strong); }
.profile-input { width: 120px; height: 30px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 8px; font-size: 12px; outline: none; }
.profile-input:focus { border-color: #695b40; }
.role-tag { color: var(--accent-strong); border: 1px solid #5f5138; background: rgba(201,166,95,.08); border-radius: 3px; padding: 2px 5px; font-size: 9px; font-weight: 700; letter-spacing: .05em; }
.guest-state { color: var(--subtle); font-size: 11px; letter-spacing: .04em; }
.btn { background: #171a1c; border: 1px solid var(--line); color: var(--muted); border-radius: var(--radius-sm); cursor: pointer; font-weight: 550; }
.btn:hover { color: var(--text); border-color: var(--line-strong); }
.btn.small { padding: 5px 10px; font-size: 11px; }
@media (max-width: 820px) { .email { display: none; } }
@media (max-width: 620px) { .brand-context { display: none; } .user-chip { gap: 6px; } }
</style>
