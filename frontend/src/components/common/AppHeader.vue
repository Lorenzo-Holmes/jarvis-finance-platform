<script setup>
const props = defineProps({
  user: { type: Object, default: null },
})

const emit = defineEmits(['logout'])
</script>

<template>
  <header class="navbar">
    <div class="brand">
      <img src="/favicon.svg" class="brand-icon" alt="Jarvis Finance" />
      <span class="brand-name">贾维斯 · 金融投研</span>
    </div>
    <div class="nav-right">
      <div v-if="props.user" class="user-chip">
        <span class="email">{{ props.user.email || '已登录' }}</span>
        <span v-if="props.user.role === 'ADMIN'" class="role-tag">ADMIN</span>
        <button class="btn small" type="button" @click="emit('logout')">退出</button>
      </div>
      <div v-else class="guest-state">研究终端</div>
    </div>
  </header>
</template>

<style scoped>
.navbar { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 650; letter-spacing: .01em; }
.brand-icon { width: 30px; height: 30px; display: inline-block; object-fit: contain; vertical-align: middle; }
.nav-right { display: flex; align-items: center; gap: 12px; }
.user-chip { display: flex; align-items: center; gap: 10px; }
.email { color: var(--muted); font-size: 12px; }
.role-tag { color: var(--accent-strong); border: 1px solid #5f5138; background: rgba(201,166,95,.08); border-radius: 3px; padding: 2px 5px; font-size: 9px; font-weight: 700; letter-spacing: .05em; }
.guest-state { color: var(--subtle); font-size: 11px; letter-spacing: .04em; }
.btn { background: #1c1f22; border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); cursor: pointer; font-weight: 550; }
.btn.small { padding: 5px 12px; font-size: 12px; }
@media (max-width: 620px) { .email { display: none; } .brand { font-size: 15px; } }
</style>
