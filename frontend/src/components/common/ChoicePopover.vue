<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  ariaLabel: { type: String, default: '选择' },
})

const emit = defineEmits(['update:modelValue', 'change'])
const rootRef = ref(null)

const activeLabel = computed(() => props.options.find(item => item.value === props.modelValue)?.label ?? String(props.modelValue ?? ''))

function choose(item) {
  emit('update:modelValue', item.value)
  emit('change', item.value)
  if (rootRef.value) rootRef.value.open = false
}

function closeFromOutside(event) {
  if (rootRef.value?.open && !rootRef.value.contains(event.target)) rootRef.value.open = false
}

function onKeydown(event) {
  if (event.key !== 'Escape' || !rootRef.value?.open) return
  rootRef.value.open = false
  event.preventDefault()
}

onMounted(() => {
  document.addEventListener('pointerdown', closeFromOutside)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeFromOutside)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <details ref="rootRef" class="choice-popover">
    <summary :aria-label="ariaLabel">
      <span>{{ activeLabel }}</span>
      <i aria-hidden="true"></i>
    </summary>
    <div class="choice-popover-menu" role="menu" :aria-label="ariaLabel">
      <button
        v-for="item in options"
        :key="String(item.value)"
        type="button"
        :class="{ active: item.value === modelValue }"
        @click="choose(item)"
      >
        <span>{{ item.label }}</span>
        <b>{{ item.value === modelValue ? '✓' : '' }}</b>
      </button>
    </div>
  </details>
</template>

<style scoped>
.choice-popover { position: relative; width: 100%; }
.choice-popover > summary {
  min-height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 10px;
  border: 1px solid var(--material-border, var(--line-strong));
  border-radius: 8px;
  background: color-mix(in srgb, var(--material-glass, transparent) 76%, transparent);
  color: var(--text);
  cursor: pointer;
  list-style: none;
  font-size: 10px;
  transition: background var(--motion-fast, 110ms) ease, border-color var(--motion-standard, 190ms) ease, transform var(--motion-fast, 110ms) ease;
}
.choice-popover > summary::-webkit-details-marker { display: none; }
.choice-popover > summary:hover,
.choice-popover[open] > summary { background: var(--material-glass, transparent); border-color: color-mix(in srgb, var(--text) 18%, transparent); }
.choice-popover > summary:active { transform: scale(.99); }
.choice-popover > summary i { width: 6px; height: 6px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(45deg) translateY(-1px); transition: transform var(--motion-standard, 190ms) var(--motion-ease, cubic-bezier(.22,1,.36,1)); }
.choice-popover[open] > summary i { transform: rotate(225deg) translate(-1px,-1px); }
.choice-popover-menu {
  position: absolute;
  z-index: 40;
  top: calc(100% + 7px);
  left: 0;
  right: 0;
  padding: 6px;
  border: 1px solid var(--material-border, var(--line-strong));
  border-radius: var(--material-popover-radius, 12px);
  background: var(--material-elevated, var(--panel-raised));
  box-shadow: var(--material-shadow-elevated, 0 22px 64px rgba(0,0,0,.2));
  backdrop-filter: blur(var(--material-blur-elevated, 28px));
  -webkit-backdrop-filter: blur(var(--material-blur-elevated, 28px));
  transform-origin: top center;
  animation: choice-popover-in var(--motion-standard, 190ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
.choice-popover-menu button {
  width: 100%;
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 9px;
}
.choice-popover-menu button:hover,
.choice-popover-menu button.active { color: var(--text); background: rgba(255,255,255,.05); }
.choice-popover-menu b { color: var(--accent-strong); font-size: 9px; }
@keyframes choice-popover-in {
  from { opacity: 0; transform: translateY(-3px) scale(.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
@media (prefers-reduced-motion: reduce) {
  .choice-popover > summary,
  .choice-popover > summary i { transition: none !important; }
  .choice-popover-menu { animation: none !important; }
}
</style>
