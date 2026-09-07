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
      {{ tab }}
    </button>
  </nav>
</template>

<style scoped>
.tabs { display: flex; gap: 24px; margin: 0 0 18px; border-bottom: 1px solid var(--line); overflow-x: auto; scrollbar-width: none; }
.tabs::-webkit-scrollbar { display: none; }
.tab-btn { position: relative; flex: 0 0 auto; color: var(--muted); background: transparent; border: 0; padding: 12px 1px 11px; cursor: pointer; font-size: 13px; font-weight: 550; }
.tab-btn:hover { color: var(--text); }
.tab-btn.active { color: var(--text); }
.tab-btn.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--accent); }
</style>
