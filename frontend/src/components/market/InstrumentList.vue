<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  marketLabel: { type: String, default: '市场' },
  instruments: { type: Array, default: () => [] },
  selectedSymbol: { type: String, default: '' },
})
const emit = defineEmits(['select'])

const search = ref('')
const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return props.instruments
  return props.instruments.filter(item =>
    String(item.name || '').toLowerCase().includes(keyword)
    || String(item.symbol || '').toLowerCase().includes(keyword))
})

watch(() => props.marketLabel, () => { search.value = '' })
</script>

<template>
  <aside class="instrument-panel">
    <div class="list-head">
      <div><b>{{ marketLabel }}</b><span>{{ instruments.length }} 个标的</span></div>
    </div>
    <input v-model="search" class="search-input" aria-label="搜索标的名称或代码" placeholder="搜索名称 / 代码" />
    <div class="instrument-list" role="listbox" aria-label="可选交易标的">
      <button v-for="item in filtered" :key="item.symbol" type="button" class="instrument-row"
              role="option" :aria-selected="selectedSymbol === item.symbol"
              :class="{ active: selectedSymbol === item.symbol }" @click="emit('select', item.symbol)">
        <span><b>{{ item.name }}</b><small>{{ item.symbol }}</small></span>
        <i></i>
      </button>
      <div v-if="!filtered.length" class="empty-list">没有匹配标的</div>
    </div>
  </aside>
</template>

<style scoped>
.instrument-panel { padding: 11px; min-width: 0; background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); }
.list-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.list-head > div { display: flex; align-items: baseline; gap: 7px; }
.list-head b { color: var(--text); font-size: 12px; }
.list-head span { color: var(--subtle); font-size: 9px; }
.search-input { width: 100%; height: 31px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 10px; outline: none; }
.search-input:focus { border-color: #6a5b40; }
.instrument-list { display: flex; flex-direction: column; gap: 3px; max-height: 526px; overflow: auto; margin-top: 8px; }
.instrument-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; border: 1px solid transparent; background: transparent; color: var(--text); border-radius: 3px; padding: 8px; cursor: pointer; text-align: left; }
.instrument-row:hover { background: #1a1d20; }
.instrument-row.active { border-color: #5f523a; background: rgba(201,166,95,.065); }
.instrument-row > span { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.instrument-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 600; }
.instrument-row small { color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.instrument-row i { width: 4px; height: 4px; border-radius: 50%; background: #555a5f; flex: 0 0 auto; }
.instrument-row.active i { background: var(--accent); }
.empty-list { color: var(--subtle); font-size: 10px; line-height: 1.6; padding: 16px 8px; text-align: center; }
@media (max-width: 700px) { .instrument-panel { max-height: 235px; } .instrument-list { max-height: 170px; } }
</style>
