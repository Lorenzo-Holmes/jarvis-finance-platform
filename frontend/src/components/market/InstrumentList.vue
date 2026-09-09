<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  marketLabel: { type: String, default: '市场' },
  defaultInstruments: { type: Array, default: () => [] },
  watchlistInstruments: { type: Array, default: () => [] },
  hiddenDefaultCount: { type: Number, default: 0 },
  selectedSymbol: { type: String, default: '' },
})
const emit = defineEmits(['select', 'add-to-watchlist', 'edit-watchlist', 'remove-watchlist', 'remove-default', 'restore-defaults'])

const search = ref('')
function matches(item) {
  const keyword = search.value.trim().toLowerCase()
  return !keyword || String(item.name || '').toLowerCase().includes(keyword)
    || String(item.symbol || '').toLowerCase().includes(keyword)
}

const filteredDefaults = computed(() => props.defaultInstruments.filter(matches))
const filteredWatchlist = computed(() => props.watchlistInstruments.filter(matches))
const visibleCount = computed(() => filteredDefaults.value.length + filteredWatchlist.value.length)

function select(item) {
  emit('select', item.symbol)
}

function selectAndAdd(item) {
  emit('add-to-watchlist', item)
  emit('select', item.symbol)
}

watch(() => props.marketLabel, () => { search.value = '' })
</script>

<template>
  <aside class="instrument-panel">
    <div class="list-head">
      <div><b>{{ marketLabel }}</b><span>{{ defaultInstruments.length }} 默认 · {{ watchlistInstruments.length }} 自选</span></div>
    </div>
    <input v-model="search" class="search-input" aria-label="搜索标的名称或代码" placeholder="搜索名称 / 代码" />
    <div class="instrument-groups" role="listbox" aria-label="可选交易标的">
      <section class="instrument-group">
        <div class="group-head">
          <span>默认标的</span>
          <button v-if="hiddenDefaultCount" type="button" class="text-action" @click="emit('restore-defaults')">
            恢复 {{ hiddenDefaultCount }} 个
          </button>
        </div>
        <div v-for="item in filteredDefaults" :key="`default-${item.symbol}`" class="instrument-row"
             role="option" :aria-selected="selectedSymbol === item.symbol"
             :class="{ active: selectedSymbol === item.symbol }" @click="select(item)">
          <button type="button" class="instrument-main" @click.stop="select(item)">
            <span><b>{{ item.name }}</b><small>{{ item.symbol }}</small></span>
            <i></i>
          </button>
          <button type="button" class="instrument-action" title="加入自选" @click.stop="selectAndAdd(item)">加自选</button>
          <button type="button" class="instrument-action remove" title="移除默认标的" @click.stop="emit('remove-default', item)">移除</button>
        </div>
        <div v-if="!filteredDefaults.length && !hiddenDefaultCount" class="empty-list">没有匹配默认标的</div>
        <div v-else-if="!filteredDefaults.length" class="empty-list">默认标的已移除，可点击恢复</div>
      </section>

      <section class="instrument-group">
        <div class="group-head"><span>自选标的</span><span class="group-count">{{ watchlistInstruments.length }} 个</span></div>
        <div v-for="item in filteredWatchlist" :key="`watchlist-${item.symbol}`" class="instrument-row"
             role="option" :aria-selected="selectedSymbol === item.symbol"
             :class="{ active: selectedSymbol === item.symbol }" @click="select(item)">
          <button type="button" class="instrument-main" @click.stop="select(item)">
            <span><b>{{ item.name }}</b><small>{{ item.symbol }}</small></span>
            <i></i>
          </button>
          <button type="button" class="instrument-action" title="修改自选标的" @click.stop="emit('edit-watchlist', item)">修改</button>
          <button type="button" class="instrument-action remove" title="移除自选标的" @click.stop="emit('remove-watchlist', item)">移除</button>
        </div>
        <div v-if="!filteredWatchlist.length" class="empty-list">解析标的或点击“加自选”后显示</div>
      </section>

      <div v-if="!visibleCount && search" class="empty-list">没有匹配标的</div>
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
.instrument-groups { display: flex; flex-direction: column; gap: 12px; max-height: 526px; overflow: auto; margin-top: 8px; }
.instrument-group { display: flex; flex-direction: column; gap: 3px; }
.group-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 8px 3px; color: var(--subtle); font-size: 9px; letter-spacing: .04em; }
.group-count { color: var(--subtle); }
.text-action { padding: 0; border: 0; background: transparent; color: var(--accent-strong); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.instrument-row { display: flex; align-items: center; gap: 4px; width: 100%; border: 1px solid transparent; background: transparent; color: var(--text); border-radius: 3px; padding: 3px 4px 3px 8px; cursor: pointer; text-align: left; }
.instrument-row:hover { background: #1a1d20; }
.instrument-row.active { border-color: #5f523a; background: rgba(201,166,95,.065); }
.instrument-main { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex: 1; min-width: 0; border: 0; background: transparent; color: var(--text); padding: 5px 0; cursor: pointer; text-align: left; }
.instrument-main > span { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.instrument-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 600; }
.instrument-row small { color: var(--subtle); font-size: 9px; font-variant-numeric: tabular-nums; }
.instrument-row i { width: 4px; height: 4px; border-radius: 50%; background: #555a5f; flex: 0 0 auto; }
.instrument-row.active i { background: var(--accent); }
.instrument-action { border: 0; background: transparent; color: var(--subtle); padding: 4px 2px; cursor: pointer; font-size: 9px; white-space: nowrap; }
.instrument-action:hover { color: var(--accent-strong); }
.instrument-action.remove:hover { color: var(--bad); }
.empty-list { color: var(--subtle); font-size: 10px; line-height: 1.6; padding: 16px 8px; text-align: center; }
@media (max-width: 700px) { .instrument-panel { max-height: 235px; } .instrument-groups { max-height: 170px; } }
</style>
