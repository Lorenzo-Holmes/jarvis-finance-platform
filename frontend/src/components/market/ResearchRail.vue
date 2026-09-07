<script setup>
defineProps({
  content: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['generate'])
</script>

<template>
  <section class="rail-panel research-rail">
    <div class="rail-title-row">
      <div class="rail-title">研究解读</div>
      <button type="button" class="text-action" :disabled="disabled || loading" @click="emit('generate')">
        {{ loading ? '生成中…' : '生成' }}
      </button>
    </div>
    <div v-if="content" class="out">{{ content }}</div>
    <div v-else class="rail-empty research-empty">基于当前报价和技术指标生成研究性解读，不构成投资建议。</div>
  </section>
</template>

<style scoped>
.rail-panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px; }
.rail-title-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.rail-title { color: var(--text); font-size: 11px; font-weight: 650; }
.text-action { border: 0; background: transparent; color: var(--accent-strong); padding: 2px 0; font-size: 10px; cursor: pointer; }
.text-action:disabled { opacity: .45; cursor: not-allowed; }
.research-rail { flex: 1; min-height: 126px; }
.research-empty { margin-top: 10px; }
.rail-empty { color: var(--subtle); font-size: 10px; line-height: 1.6; }
.out { margin-top: 9px; max-height: 245px; overflow: auto; background: var(--surface); border-left: 2px solid #72684f; padding: 9px 10px; white-space: pre-wrap; color: var(--text); font-size: 10px; line-height: 1.65; }
@media (max-width: 980px) { .research-rail { min-height: 0; } }
</style>
