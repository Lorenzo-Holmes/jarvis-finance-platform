<script setup>
import { nextTick, ref } from 'vue'

const props = defineProps({
  tabs: { type: Array, required: true },
  active: { type: String, required: true },
})

const emit = defineEmits(['change'])
const tabButtons = ref([])

function setTabRef(element, index) {
  if (element) tabButtons.value[index] = element
}

async function moveFocus(event, index) {
  const last = props.tabs.length - 1
  let next = index
  if (event.key === 'ArrowRight') next = index === last ? 0 : index + 1
  else if (event.key === 'ArrowLeft') next = index === 0 ? last : index - 1
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = last
  else return

  event.preventDefault()
  emit('change', props.tabs[next])
  await nextTick()
  tabButtons.value[next]?.focus()
}
</script>

<template>
  <nav class="tabs" role="tablist" aria-label="工作台导航">
    <button v-for="(tab, index) in props.tabs" :key="tab" :ref="el => setTabRef(el, index)" type="button" class="tab-btn"
            role="tab" :aria-selected="props.active === tab" :tabindex="props.active === tab ? 0 : -1"
            :class="{ active: props.active === tab }" @click="emit('change', tab)" @keydown="moveFocus($event, index)">
      <span class="tab-mark" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
      <span>{{ tab }}</span>
    </button>
  </nav>
</template>

<style scoped>
.tabs {
  position: sticky;
  top: 72px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  height: calc(100vh - 94px);
  padding: 9px 14px 12px 0;
  border-right: 1px solid var(--line);
  overflow-y: auto;
  scrollbar-width: none;
}
.tabs::-webkit-scrollbar { display: none; }
.tab-btn {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  flex: 0 0 auto;
  color: var(--muted);
  background: transparent;
  border: 0;
  border-left: 2px solid transparent;
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  padding: 9px 9px 9px 10px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 550;
  text-align: left;
  transition: color .15s ease, background .15s ease, border-color .15s ease;
}
.tab-mark { width: 18px; color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; letter-spacing: .04em; }
.tab-btn:hover { color: var(--text); background: rgba(255,255,255,.025); }
.tab-btn.active { color: var(--text); border-left-color: var(--accent); background: var(--accent-soft); }
.tab-btn.active .tab-mark { color: var(--accent-strong); }

@media (max-width: 1080px) {
  .tabs {
    position: static;
    flex-direction: row;
    gap: 20px;
    width: auto;
    height: auto;
    margin: 0 0 16px;
    padding: 0;
    border-right: 0;
    border-bottom: 1px solid var(--line);
    overflow-x: auto;
    overflow-y: hidden;
  }
  .tab-btn { width: auto; border-left: 0; border-bottom: 2px solid transparent; border-radius: 0; padding: 11px 1px 10px; background: transparent; }
  .tab-btn:hover { background: transparent; }
  .tab-btn.active { border-left-color: transparent; border-bottom-color: var(--accent); background: transparent; }
  .tab-mark { display: none; }
}
</style>
