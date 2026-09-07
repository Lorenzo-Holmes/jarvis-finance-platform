<script setup>
defineProps({
  state: { type: String, required: true },
  title: { type: String, default: '' },
  message: { type: String, default: '' },
  overlay: { type: Boolean, default: false },
  retryable: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
})
const emit = defineEmits(['retry'])
</script>

<template>
  <div class="data-state" :class="[{ overlay, compact }, state]" role="status" aria-live="polite">
    <div v-if="state === 'loading'" class="spinner" aria-hidden="true"></div>
    <div v-else class="state-mark" aria-hidden="true">{{ state === 'error' ? '!' : '—' }}</div>
    <div class="state-copy">
      <b>{{ title || (state === 'loading' ? '正在加载' : state === 'error' ? '加载失败' : '暂无数据') }}</b>
      <span v-if="message">{{ message }}</span>
    </div>
    <button v-if="state === 'error' && retryable" type="button" @click="emit('retry')">重试</button>
  </div>
</template>

<style scoped>
.data-state { min-height: 110px; display: flex; align-items: center; justify-content: center; gap: 10px; padding: 18px; color: var(--muted); background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.data-state.compact { min-height: 64px; justify-content: flex-start; padding: 10px 11px; }
.data-state.overlay { position: absolute; inset: 0; z-index: 3; min-height: 0; border: 0; background: rgba(18,20,22,.94); }
.spinner { width: 16px; height: 16px; border: 2px solid #34383d; border-top-color: var(--accent); border-radius: 50%; animation: spin .75s linear infinite; flex: 0 0 auto; }
.state-mark { width: 20px; height: 20px; display: grid; place-items: center; border: 1px solid var(--line-strong); border-radius: 50%; color: var(--subtle); font-size: 10px; flex: 0 0 auto; }
.error .state-mark { color: #ef8585; border-color: rgba(228,99,99,.35); }
.state-copy { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.state-copy b { color: var(--text); font-size: 11px; font-weight: 650; }
.state-copy span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
button { margin-left: 4px; border: 1px solid var(--line-strong); background: #1c1f22; color: var(--text); border-radius: var(--radius-sm); padding: 5px 9px; font-size: 9px; cursor: pointer; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
