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
      <div class="watch-rail-summary">
        <span>WATCH</span>
        <b>{{ marketLabel }}自选</b>
        <small>{{ visibleCount }} 个标的</small>
      </div>
    </div>
    <input v-model="search" class="search-input" aria-label="搜索标的名称或代码" placeholder="搜索名称 / 代码" />
    <div class="instrument-groups" role="listbox" aria-label="可选交易标的">
      <section class="instrument-group">
        <div class="group-head">
          <span>默认</span>
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
        <div class="group-head"><span>自选</span><span class="group-count">{{ watchlistInstruments.length }}</span></div>
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
.instrument-panel {
  padding: 0 0 8px;
  min-width: 0;
  background: transparent;
  border: 0;
  border-right: 1px solid color-mix(in srgb, var(--line) 72%, transparent);
  border-radius: 0;
}
.list-head { min-height: 52px; display: flex; align-items: center; margin: 0; padding: 0 10px; }
.watch-rail-summary { min-width: 0; display: grid; grid-template-columns: auto 1fr; align-items: baseline; gap: 4px 7px; }
.list-head span { color: var(--subtle); }
.watch-rail-summary span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.watch-rail-summary b { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); font-size: 11px; font-weight: 650; letter-spacing: -.01em; }
.watch-rail-summary small { grid-column: 2; color: var(--subtle); font-size: 8px; line-height: 1; }
.search-input {
  width: calc(100% - 16px);
  height: 32px;
  margin: 0 8px 5px;
  background: color-mix(in srgb, var(--workspace-control-bg, var(--surface)) 58%, transparent);
  border: 1px solid color-mix(in srgb, var(--line) 76%, transparent);
  border-radius: 8px;
  color: var(--text);
  padding: 0 9px;
  font-size: 9px;
  outline: none;
  transition: border-color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease;
}
.search-input:hover { background: color-mix(in srgb, var(--workspace-control-bg, var(--surface)) 78%, transparent); }
.search-input:focus { border-color: var(--workspace-focus, var(--accent)); background: var(--workspace-control-bg, var(--surface)); }
.instrument-groups { display: flex; flex-direction: column; gap: 8px; max-height: 606px; overflow: auto; margin-top: 4px; padding: 0 4px; }
.instrument-group { display: flex; flex-direction: column; gap: 1px; }
.group-head { min-height: 26px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 8px; color: var(--subtle); font-size: 8px; font-weight: 600; }
.group-count { color: var(--subtle); }
.text-action { padding: 0; border: 0; background: transparent; color: var(--accent-strong); font-size: 9px; cursor: pointer; }
.text-action:hover { color: var(--text); }
.instrument-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  width: calc(100% - 4px);
  min-height: 42px;
  margin: 0 2px;
  border: 0;
  background: transparent;
  color: var(--text);
  border-radius: 8px;
  padding: 2px 5px 2px 9px;
  cursor: pointer;
  text-align: left;
  transition: background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease;
}
.instrument-row::before { content: ''; position: absolute; left: 0; top: 9px; bottom: 9px; width: 2px; border-radius: 2px; background: transparent; }
.instrument-row:hover { background: color-mix(in srgb, var(--text) 3.5%, transparent); }
.instrument-row:active { transform: scale(.992); }
.instrument-row.active { background: color-mix(in srgb, var(--accent) 7%, transparent); }
.instrument-row.active::before { background: var(--accent); }
.instrument-main { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex: 1; min-width: 0; border: 0; background: transparent; color: var(--text); padding: 5px 0; cursor: pointer; text-align: left; }
.instrument-main > span { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.instrument-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; font-weight: 610; }
.instrument-row small { color: var(--subtle); font: 500 8px/1.2 ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.instrument-row i { width: 4px; height: 4px; border-radius: 50%; background: var(--line-strong); flex: 0 0 auto; }
.instrument-row.active i { background: var(--accent); }
.instrument-action { opacity: 0; border: 0; background: transparent; color: var(--subtle); padding: 4px 2px; cursor: pointer; font-size: 8px; white-space: nowrap; }
.instrument-row:hover .instrument-action, .instrument-row:focus-within .instrument-action { opacity: 1; }
.instrument-action:hover { color: var(--accent-strong); }
.instrument-action.remove:hover { color: var(--bad); }
.empty-list { color: var(--subtle); font-size: 10px; line-height: 1.6; padding: 16px 8px; text-align: center; }
.instrument-main:focus-visible,
.instrument-action:focus-visible,
.text-action:focus-visible,
.search-input:focus-visible { outline: 2px solid color-mix(in srgb, var(--accent) 70%, transparent); outline-offset: 1px; }
@media (max-width: 700px) { .instrument-panel { max-height: 235px; border-right: 0; border-bottom: 1px solid var(--line); } .instrument-groups { max-height: 170px; } .instrument-action { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .instrument-row, .search-input { transition: none !important; } }
</style>
