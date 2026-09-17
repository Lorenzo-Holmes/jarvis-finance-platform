<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import { buildModuleNavGroups } from '../data/moduleNav'

const props = defineProps({
  module: { type: Object, required: true },
  active: { type: Boolean, default: true },
  revealed: { type: Boolean, default: true },
  modules: { type: Array, default: () => [] },
  workspaceTabs: { type: Array, default: () => [] },
  splitRoute: { type: String, default: '' },
  user: { type: Object, default: null },
  context: { type: Object, default: null },
  nightMode: { type: Boolean, default: false },
})

const emit = defineEmits(['return', 'navigate-module', 'close-workspace-tab', 'open-split', 'close-split', 'legacy-admin', 'logout', 'update-profile', 'toggle-night-mode'])
const returning = ref(false)
const switching = ref(false)
const editingProfile = ref(false)
const displayName = ref('')
const moduleTitleRef = ref(null)
const accountMenuRef = ref(null)
const entityMoreRef = ref(null)
const splitMenuRef = ref(null)
const entityViewsRef = ref(null)
const entityLensStyle = ref({ opacity: '0' })
const commandOpen = ref(false)
const commandQuery = ref('')
const commandActiveIndex = ref(0)
const navigationHistory = ref([props.module.routeKey])
const navigationIndex = ref(0)
const historyTravel = ref(false)
let returnTimer = 0
let switchTimer = 0
let entityLensRaf = 0

const moduleGroups = computed(() => buildModuleNavGroups(props.modules))
const workspaceTabModules = computed(() => props.workspaceTabs
  .map(routeKey => props.modules.find(item => item.routeKey === routeKey))
  .filter(Boolean))
const commandResults = computed(() => {
  const q = commandQuery.value.trim().toLowerCase()
  if (!q) return props.modules
  return props.modules.filter(item => [item.labelZh, item.labelEn, item.routeKey, item.category, item.code]
    .filter(Boolean)
    .some(value => String(value).toLowerCase().includes(q)))
})
const contextTitle = computed(() => props.context?.name || props.context?.symbol || '选择研究对象')
const contextSubtitle = computed(() => props.context?.symbol && props.context?.name
  ? `${props.context.symbol} · ${props.context.market || '研究上下文'}`
  : props.context?.symbol || '从行情页选择研究对象')
const ENTITY_VIEW_KEYS = ['market', 'financial', 'ai-research', 'industry-chain', 'risk', 'strategy', 'sim-trade']
const ENTITY_VIEW_LABELS = Object.freeze({
  market: '行情',
  financial: '财务',
  'ai-research': '研究',
  'industry-chain': '产业链',
  risk: '风险',
  strategy: '策略',
  'sim-trade': '交易',
})
const entityViews = computed(() => ENTITY_VIEW_KEYS
  .map(key => props.modules.find(item => item.key === key))
  .filter(Boolean))
const secondaryViews = computed(() => props.modules.filter(item => !ENTITY_VIEW_KEYS.includes(item.key)))
const secondaryViewActive = computed(() => secondaryViews.value.some(item => item.key === props.module.key))
const canGoBack = computed(() => navigationIndex.value > 0)
const canGoForward = computed(() => navigationIndex.value < navigationHistory.value.length - 1)
const SPLIT_ROUTES = Object.freeze(['行情', '研究助手', '财报解析', '风险预警'])
const SPLIT_LABELS = Object.freeze({
  '行情': '行情',
  '研究助手': '研究',
  '财报解析': '财务',
  '风险预警': '风险',
})
const splitCandidates = computed(() => SPLIT_ROUTES
  .filter(routeKey => routeKey !== props.module.routeKey)
  .map(routeKey => props.modules.find(item => item.routeKey === routeKey))
  .filter(Boolean))
const splitLabel = computed(() => SPLIT_LABELS[props.splitRoute] || '')

function moduleGroupLabel(item) {
  return moduleGroups.value.find(group => group.modules.some(module => module.key === item?.key))?.labelZh || '工作区'
}

watch(() => props.user?.displayName, value => {
  displayName.value = value || ''
}, { immediate: true })

function saveProfile() {
  const value = displayName.value.trim()
  if (!value) return
  emit('update-profile', value)
  editingProfile.value = false
}

function requestReturn() {
  if (!props.active || returning.value) return
  returning.value = true
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  returnTimer = window.setTimeout(() => emit('return'), reduced ? 20 : 180)
}

function requestModule(next) {
  if (!props.active || !next || next.key === props.module.key || switching.value || returning.value) return
  switching.value = true
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (switchTimer) window.clearTimeout(switchTimer)
  switchTimer = window.setTimeout(() => {
    const navigate = () => emit('navigate-module', next.routeKey)
    if (!reduced && typeof document.startViewTransition === 'function') {
      document.startViewTransition(async () => {
        navigate()
        await nextTick()
      })
      return
    }
    navigate()
  }, reduced ? 20 : 180)
}

function syncEntitySelectionLens() {
  if (entityLensRaf) window.cancelAnimationFrame(entityLensRaf)
  entityLensRaf = window.requestAnimationFrame(() => {
    entityLensRaf = 0
    const nav = entityViewsRef.value
    if (!nav) return
    const target = nav.querySelector(':scope > button[aria-current="page"], :scope > .entity-more.active > summary')
    if (!target) {
      entityLensStyle.value = { opacity: '0' }
      return
    }
    entityLensStyle.value = {
      opacity: '1',
      width: `${target.offsetWidth}px`,
      transform: `translate3d(${target.offsetLeft}px, 0, 0)`,
    }
  })
}

function navigateHistory(delta) {
  const nextIndex = navigationIndex.value + delta
  if (nextIndex < 0 || nextIndex >= navigationHistory.value.length) return
  const routeKey = navigationHistory.value[nextIndex]
  const next = props.modules.find(item => item.routeKey === routeKey)
  if (!next || next.key === props.module.key) return
  historyTravel.value = true
  navigationIndex.value = nextIndex
  requestModule(next)
}

function closeWorkspaceTab(event, item) {
  event?.stopPropagation?.()
  if (!item) return
  emit('close-workspace-tab', item.routeKey)
}

function openCommandPalette() {
  commandQuery.value = ''
  commandActiveIndex.value = 0
  commandOpen.value = true
  nextTick(() => document.querySelector('.command-palette input')?.focus?.())
}

function closeCommandPalette() {
  commandOpen.value = false
  commandQuery.value = ''
  commandActiveIndex.value = 0
}

function chooseCommandModule(item) {
  closeCommandPalette()
  requestModule(item)
}

function focusModuleTitle() {
  nextTick(() => moduleTitleRef.value?.focus?.({ preventScroll: true }))
}

function closeMenusFromOutside(event) {
  const accountMenu = accountMenuRef.value
  if (accountMenu?.open && !accountMenu.contains(event.target)) {
    accountMenu.open = false
    editingProfile.value = false
  }
  const entityMore = entityMoreRef.value
  if (entityMore?.open && !entityMore.contains(event.target)) entityMore.open = false
  const splitMenu = splitMenuRef.value
  if (splitMenu?.open && !splitMenu.contains(event.target)) splitMenu.open = false
}

function onKeydown(event) {
  if (!props.active || returning.value) return
  if (event.defaultPrevented) return
  const target = event.target
  const editable = target instanceof HTMLInputElement
    || target instanceof HTMLTextAreaElement
    || target instanceof HTMLSelectElement
    || target?.isContentEditable
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    openCommandPalette()
    return
  }
  if (!commandOpen.value && event.key === '/' && !editable) {
    event.preventDefault()
    openCommandPalette()
    return
  }
  if (commandOpen.value && ['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key)) {
    event.preventDefault()
    if (!commandResults.value.length) return
    if (event.key === 'ArrowDown') commandActiveIndex.value = (commandActiveIndex.value + 1) % commandResults.value.length
    else if (event.key === 'ArrowUp') commandActiveIndex.value = (commandActiveIndex.value - 1 + commandResults.value.length) % commandResults.value.length
    else chooseCommandModule(commandResults.value[Math.min(commandActiveIndex.value, commandResults.value.length - 1)])
    return
  }
  if (event.key !== 'Escape') return
  if (commandOpen.value) {
    event.preventDefault()
    closeCommandPalette()
    return
  }
  if (entityMoreRef.value?.open) {
    event.preventDefault()
    entityMoreRef.value.open = false
    return
  }
  if (splitMenuRef.value?.open) {
    event.preventDefault()
    splitMenuRef.value.open = false
    return
  }
  if (document.querySelector('.choice-popover[open], .toolbar-popover[open], .source-peek')) return
  if (editable) return
  if (document.querySelector('[role="dialog"][aria-modal="true"]')) return
  event.preventDefault()
  requestReturn()
}

watch(() => props.module.key, () => {
  if (!props.active) return
  const routeKey = props.module.routeKey
  if (historyTravel.value) {
    historyTravel.value = false
  } else if (navigationHistory.value[navigationIndex.value] !== routeKey) {
    const next = navigationHistory.value.slice(0, navigationIndex.value + 1)
    next.push(routeKey)
    navigationHistory.value = next.slice(-20)
    navigationIndex.value = navigationHistory.value.length - 1
  }
  requestAnimationFrame(() => {
    switching.value = false
    focusModuleTitle()
    syncEntitySelectionLens()
    if (entityMoreRef.value) entityMoreRef.value.open = false
    if (splitMenuRef.value) splitMenuRef.value.open = false
  })
})

watch(commandQuery, () => {
  commandActiveIndex.value = 0
})

watch(() => props.active, active => {
  if (!active) {
    returning.value = false
    switching.value = false
    return
  }
  requestAnimationFrame(() => {
    focusModuleTitle()
    syncEntitySelectionLens()
  })
}, { flush: 'post' })

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', syncEntitySelectionLens)
  document.addEventListener('pointerdown', closeMenusFromOutside)
  if (props.active) requestAnimationFrame(() => {
    focusModuleTitle()
    syncEntitySelectionLens()
  })
})

onBeforeUnmount(() => {
  if (returnTimer) window.clearTimeout(returnTimer)
  if (switchTimer) window.clearTimeout(switchTimer)
  if (entityLensRaf) window.cancelAnimationFrame(entityLensRaf)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', syncEntitySelectionLens)
  document.removeEventListener('pointerdown', closeMenusFromOutside)
})
</script>

<template>
  <section
    class="workspace-shell"
    :class="[
      {
        returning,
        switching,
        preparing: !props.active || !props.revealed,
        'is-night': props.nightMode,
      },
      `ambient-${props.module.key}`,
    ]"
    :aria-hidden="!props.active"
  >
    <aside class="global-rail" aria-label="全局导航">
      <div class="rail-mark" aria-hidden="true">J</div>
      <nav class="rail-shortcuts" aria-label="主要入口">
        <button type="button" aria-label="返回档案" title="档案" @click="requestReturn">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 10.2 12 4l7.5 6.2v8.3a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5Z"/><path d="M9 20v-6h6v6"/></svg>
        </button>
      </nav>
      <div class="rail-spacer"></div>
      <button type="button" class="rail-search" aria-label="快速搜索" title="搜索 · Ctrl/⌘ K" @click="openCommandPalette">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="5.5"/><path d="m14.7 14.7 4.8 4.8"/></svg>
      </button>
    </aside>

    <div class="workspace-frame">
      <header class="workspace-header">
        <div class="workspace-brand">
          <strong>JARVIS</strong>
        </div>

        <span ref="moduleTitleRef" class="workspace-module visually-hidden" tabindex="-1">{{ props.module.labelZh }}</span>

        <button type="button" class="global-search-field" @click="openCommandPalette">
          <span aria-hidden="true">⌕</span>
          <strong>搜索证券、数据或命令…</strong>
          <kbd>Ctrl/⌘ K</kbd>
        </button>

        <div class="workspace-actions">
          <details v-if="props.user" ref="accountMenuRef" class="account-menu">
            <summary>
              <span>{{ props.user.displayName || 'Account' }}</span>
              <i aria-hidden="true"></i>
            </summary>
            <div class="account-popover">
              <div class="account-identity">
                <span>账户</span>
                <strong>{{ props.user.email || props.user.displayName || 'LOCAL ACCOUNT' }}</strong>
              </div>
              <template v-if="editingProfile">
                <label class="account-edit">
                  <span>显示名称</span>
                  <input v-model="displayName" maxlength="60" aria-label="昵称" @keyup.enter="saveProfile" @keyup.esc="editingProfile = false" />
                </label>
                <div class="account-edit-actions">
                  <button type="button" @click="saveProfile">保存</button>
                  <button type="button" @click="editingProfile = false">取消</button>
                </div>
              </template>
              <button v-else type="button" @click="editingProfile = true">修改昵称</button>
              <button
                type="button"
                class="theme-menu-button"
                :aria-pressed="props.nightMode"
                :aria-label="props.nightMode ? '关闭夜间模式' : '开启夜间模式'"
                @click="emit('toggle-night-mode')"
              >
                <span>夜间模式</span>
                <i class="theme-toggle-indicator" aria-hidden="true"></i>
              </button>
              <button v-if="props.user?.role === 'ADMIN'" type="button" aria-label="旧版后台" @click="emit('legacy-admin')">旧版后台</button>
              <button type="button" @click="api.githubBindAuthorize()">GitHub</button>
              <button type="button" class="account-logout" @click="emit('logout')">退出登录</button>
            </div>
          </details>
        </div>
      </header>

      <section class="entity-bar" aria-label="研究对象与视图导航">
        <div class="entity-history" aria-label="浏览历史">
          <button type="button" aria-label="后退" :disabled="!canGoBack" @click="navigateHistory(-1)">←</button>
          <button type="button" aria-label="前进" :disabled="!canGoForward" @click="navigateHistory(1)">→</button>
        </div>

        <div class="entity-switcher entity-context-display" :class="{ 'has-context': props.context }" aria-label="当前研究对象">
          <i class="context-presence" aria-hidden="true"></i>
          <span>
            <strong>{{ contextTitle }}</strong>
            <small>{{ contextSubtitle }}</small>
          </span>
        </div>

        <nav ref="entityViewsRef" class="entity-views" aria-label="当前研究对象视图">
          <i class="entity-selection-lens" aria-hidden="true" :style="entityLensStyle"></i>
          <button
            v-for="item in entityViews"
            :key="item.key"
            type="button"
            :class="{ active: item.key === props.module.key }"
            :aria-current="item.key === props.module.key ? 'page' : undefined"
            @click="requestModule(item)"
          >
            {{ ENTITY_VIEW_LABELS[item.key] }}
          </button>
          <details ref="entityMoreRef" class="entity-more" :class="{ active: secondaryViewActive }">
            <summary>更多</summary>
            <div class="entity-more-popover">
              <button
                v-for="item in secondaryViews"
                :key="item.key"
                type="button"
                :class="{ active: item.key === props.module.key }"
                @click="requestModule(item)"
              >
                <span>{{ item.labelZh }}</span>
                <small>{{ item.summary }}</small>
              </button>
            </div>
          </details>
          <details ref="splitMenuRef" class="split-control" :class="{ active: props.splitRoute }">
            <summary>{{ props.splitRoute ? `▥ ${splitLabel}` : '▥ 并排' }}</summary>
            <div class="split-popover">
              <div class="split-popover-head">
                <span>并排查看</span>
                <small>共享当前研究对象</small>
              </div>
              <button
                v-for="item in splitCandidates"
                :key="item.key"
                type="button"
                :class="{ active: props.splitRoute === item.routeKey }"
                @click="emit('open-split', item.routeKey); splitMenuRef.open = false"
              >
                <span>{{ SPLIT_LABELS[item.routeKey] || item.labelZh }}</span>
                <small>{{ item.summary }}</small>
              </button>
              <button v-if="props.splitRoute" type="button" class="split-close-action" @click="emit('close-split'); splitMenuRef.open = false">
                <span>关闭并排视图</span>
                <small>恢复单一工作面</small>
              </button>
            </div>
          </details>
        </nav>
      </section>

      <main class="workspace-body">
        <slot />
      </main>

      <footer class="workspace-footer">
        <strong>仅供研究参考，不构成投资建议</strong>
      </footer>
    </div>

    <div v-if="commandOpen" class="command-backdrop" @click.self="closeCommandPalette">
      <section class="command-palette" role="dialog" aria-modal="true" aria-label="快速导航">
        <div class="command-search-row">
          <span aria-hidden="true">⌕</span>
          <input v-model="commandQuery" type="search" placeholder="搜索证券、页面或命令…" aria-label="搜索工作区" />
          <kbd>ESC</kbd>
        </div>
        <div class="command-section-label">工作区</div>
        <div class="command-results">
          <button
            v-for="(item, index) in commandResults"
            :key="item.key"
            type="button"
            :class="{ active: commandActiveIndex === index }"
            :aria-selected="commandActiveIndex === index"
            @mouseenter="commandActiveIndex = index"
            @click="chooseCommandModule(item)"
          >
            <span><strong>{{ item.labelZh }}</strong><small>{{ item.summary }}</small></span>
                <em>{{ moduleGroupLabel(item) }}</em>
          </button>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.workspace-shell {
  --bg: #e8e5e1;
  --panel: #efebe3;
  --panel-raised: #f3f0e9;
  --surface: #e5e0d6;
  --surface-2: #ddd7cc;
  --line: #bcb6ab;
  --line-strong: #989186;
  --text: #20221d;
  --muted: #77736a;
  --subtle: #aaa398;
  --accent: #8a7657;
  --accent-strong: #6e6049;
  --ok: #5d765f;
  --bad: #9a5b53;
  --warn: #a17a42;
  --danger-soft: rgba(154,91,83,.075);
  --success-soft: rgba(93,118,95,.075);
  --workspace-panel-wash: rgba(240,236,228,.82);
  --workspace-panel-soft: rgba(237,234,227,.92);
  --workspace-panel-rail: rgba(240,236,228,.94);
  --workspace-chart-bg: transparent;
  --workspace-node-bg: #efebe3;
  --workspace-node-focus-bg: #ddd7cc;
  --workspace-hover-bg: #e1ddd5;
  --workspace-accent-wash: rgba(161,132,88,.08);
  --workspace-row-divider: #c9c2b6;
  --workspace-graph-grid: rgba(132,126,116,.08);
  --workspace-graph-edge: rgba(112,105,94,.6);
  --workspace-graph-outline: rgba(138,118,87,.24);
  --workspace-node-shadow: rgba(77,68,55,.05);
  --workspace-focus: #8a7657;
  --workspace-action-bg: #34362f;
  --workspace-action-bg-hover: #46483f;
  --workspace-action-border: #34362f;
  --workspace-action-text: #f2eee6;
  --workspace-control-bg: rgba(255,255,255,.10);
  --workspace-neutral-dot: #74786f;
  --workspace-track: rgba(132,126,116,.18);
  --workspace-track-fill: #676b64;
  --workspace-warning-text: #8e6b3e;
  --workspace-warning-bg: rgba(161,122,66,.06);
  --workspace-warning-border: #9a7744;
  --workspace-body-grid: rgba(87,83,75,.016);
  --workspace-body-glow: rgba(138,118,87,.038);
  --radius: 0px;
  --radius-sm: 0px;
  position: fixed; inset: 0; z-index: 100;
  width: 100%; height: 100dvh; overflow: hidden;
  background: var(--bg);
  color: var(--text);
  opacity: 1;
  transition: opacity .28s cubic-bezier(.22,1,.36,1), transform .18s cubic-bezier(.4,0,1,1);
  contain: layout paint style;
  will-change: opacity;
  font-family: "IBM Plex Sans", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}
.workspace-shell.preparing {
  opacity: 0;
  pointer-events: none;
  visibility: visible;
}
.workspace-shell.returning { opacity: 0; transform: translateY(8px); pointer-events: none; }
.workspace-shell.switching .workspace-body { opacity: 0; transform: translateY(7px); pointer-events: none; }
.workspace-frame {
  height: 100dvh;
  margin-left: 62px;
  display: grid;
  grid-template-rows: 58px 42px minmax(0, 1fr) 30px;
  min-width: 0;
  background: var(--bg);
}
.visually-hidden {
  position: absolute !important;
  width: 1px !important;
  height: 1px !important;
  padding: 0 !important;
  margin: -1px !important;
  overflow: hidden !important;
  clip: rect(0, 0, 0, 0) !important;
  white-space: nowrap !important;
  border: 0 !important;
}
.workspace-header {
  min-height: 58px;
  padding: 0 28px;
  display: grid;
  grid-template-columns: minmax(120px, 1fr) auto;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
}
.workspace-brand { display: flex; align-items: center; min-width: 0; }
.workspace-brand strong {
  color: var(--text);
  font: 700 14px/1 Inter, "MiSans", "PingFang SC", sans-serif;
  letter-spacing: -.01em;
}
.workspace-actions {
  align-self: stretch;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  min-width: 0;
}
.context-readout {
  display: flex;
  align-items: center;
  margin-right: 8px;
  padding-right: 14px;
  border-right: 1px solid var(--line);
}
.context-readout strong { color: var(--muted); font: 600 10px/1 ui-monospace, monospace; white-space: nowrap; }
.return-button {
  min-height: 34px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  padding: 0 10px;
  font: 600 9px/1 Inter, "MiSans", sans-serif;
  letter-spacing: .01em;
  white-space: nowrap;
  transition: color .16s ease, background .16s ease;
}
.return-button { display: inline-flex; align-items: center; gap: 7px; }
.return-button > span:first-child { color: var(--subtle); font-size: 12px; transition: transform .18s ease, color .18s ease; }
.return-button:hover > span:first-child { color: var(--accent-strong); transform: translateX(-2px); }
.return-button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.theme-toggle-indicator { width: 6px; height: 6px; border: 1px solid currentColor; border-radius: 50%; background: transparent; }
.is-night .theme-toggle-indicator { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 7px rgba(173,149,109,.18); }
.account-menu { position: relative; margin-left: 2px; }
.account-menu > summary {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 10px;
  color: var(--muted);
  cursor: pointer;
  list-style: none;
  font-size: 9px;
  white-space: nowrap;
  transition: color .16s ease, background .16s ease;
}
.account-menu > summary::-webkit-details-marker { display: none; }
.account-menu > summary:hover,
.account-menu[open] > summary { color: var(--text); background: var(--workspace-hover-bg); }
.account-menu > summary i {
  width: 5px;
  height: 5px;
  border-right: 1px solid currentColor;
  border-bottom: 1px solid currentColor;
  transform: rotate(45deg) translateY(-1px);
  transition: transform .18s ease;
}
.account-menu[open] > summary i { transform: rotate(225deg) translate(-1px, -1px); }
.account-popover {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  right: 0;
  width: 218px;
  padding: 10px;
  border: 1px solid var(--line-strong);
  background: var(--panel-raised);
  box-shadow: 0 18px 46px rgba(0,0,0,.18);
}
.account-identity { display: grid; gap: 6px; padding: 4px 5px 10px; border-bottom: 1px solid var(--line); }
.account-identity span,
.account-edit > span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.account-identity strong { color: var(--muted); font-size: 9px; font-weight: 520; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-popover > button,
.account-edit-actions button {
  width: 100%;
  min-height: 32px;
  padding: 0 6px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  text-align: left;
  cursor: pointer;
  font-size: 9px;
}
.theme-menu-button { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.account-popover > button:hover,
.account-edit-actions button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.account-popover .account-logout { color: var(--bad); border-bottom: 0; }
.account-edit { display: grid; gap: 7px; padding: 10px 5px 5px; }
.account-edit input {
  width: 100%;
  height: 30px;
  border: 0;
  border-bottom: 1px solid var(--line-strong);
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 10px;
}
.account-edit input:focus { border-color: var(--workspace-focus); }
.account-edit-actions { display: grid; grid-template-columns: 1fr 1fr; }
.account-edit-actions button { border-right: 1px solid var(--line); text-align: center; }
.account-edit-actions button:last-child { border-right: 0; }
.workspace-module-index {
  position: relative;
  height: 46px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  gap: 24px;
  overflow: visible;
  border-bottom: 1px solid var(--line);
}
.workspace-module-index button {
  position: relative;
  flex: 0 0 auto;
  width: auto;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  text-align: center;
  cursor: pointer;
  font: 550 12px/1 Inter, "MiSans", "PingFang SC", sans-serif;
  transition: color .16s ease;
}
.workspace-module-index button::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--accent);
  transform: scaleX(0);
  transform-origin: center;
  transition: transform .24s cubic-bezier(.22,1,.36,1);
}
.workspace-module-index button:hover { color: var(--text); }
.workspace-module-index button.active { color: var(--text); }
.workspace-module-index button.active::after { transform: scaleX(1); }
.workspace-more-menu { position: relative; height: 100%; }
.workspace-more-menu > summary {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  color: var(--muted);
  cursor: pointer;
  list-style: none;
  font: 550 12px/1 Inter, "MiSans", "PingFang SC", sans-serif;
  transition: color .16s ease;
}
.workspace-more-menu > summary::-webkit-details-marker { display: none; }
.workspace-more-menu > summary::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--accent);
  transform: scaleX(0);
  transition: transform .24s cubic-bezier(.22,1,.36,1);
}
.workspace-more-menu:hover > summary,
.workspace-more-menu[open] > summary,
.workspace-more-menu.active > summary { color: var(--text); }
.workspace-more-menu.active > summary::after { transform: scaleX(1); }
.workspace-more-popover {
  position: absolute;
  z-index: 40;
  top: calc(100% + 8px);
  left: -12px;
  width: 250px;
  padding: 8px;
  border: 1px solid var(--line-strong);
  background: var(--panel-raised);
  box-shadow: 0 18px 46px rgba(0,0,0,.18);
}
.workspace-more-popover button {
  width: 100%;
  height: auto;
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
}
.workspace-more-popover button:last-child { border-bottom: 0; }
.workspace-more-popover button::after { display: none; }
.workspace-more-popover button:hover,
.workspace-more-popover button.active { background: var(--workspace-hover-bg); }
.workspace-more-popover button span { color: var(--text); font-size: 11px; }

/* Professional research workspace navigation */
.global-rail {
  position: fixed;
  z-index: 120;
  inset: 0 auto 0 0;
  width: 62px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  border-right: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg) 94%, var(--panel-raised));
}
.rail-mark {
  height: 58px;
  display: grid;
  place-items: center;
  border-bottom: 1px solid var(--line);
  color: var(--text);
  font: 700 17px/1 Inter, "MiSans", sans-serif;
  letter-spacing: -.04em;
}
.rail-groups { display: grid; gap: 3px; padding: 10px 7px; }
.rail-groups > button,
.rail-search {
  min-height: 48px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  display: grid;
  place-items: center;
  gap: 3px;
  padding: 6px 2px;
  transition: color .16s ease, background .16s ease;
}
.rail-groups > button:hover,
.rail-groups > button.open,
.rail-search:hover { color: var(--text); background: var(--workspace-hover-bg); }
.rail-groups > button.active { color: var(--text); }
.rail-groups > button.active::before {
  content: '';
  position: absolute;
  left: 0;
  width: 2px;
  height: 22px;
  background: var(--accent);
}
.rail-groups > button { position: relative; }
.rail-glyph { font-size: 12px; font-weight: 650; }
.rail-label,
.rail-search small { font-size: 8px; font-weight: 550; }
.rail-spacer { flex: 1; }
.rail-search { margin: 0 7px 10px; }
.rail-search > span { font-size: 18px; line-height: 1; }
.rail-flyout {
  position: absolute;
  z-index: 150;
  left: calc(100% + 8px);
  top: 68px;
  width: 262px;
  padding: 9px;
  border: 1px solid var(--line-strong);
  background: var(--panel-raised);
  box-shadow: 0 18px 48px rgba(0,0,0,.22);
}
.rail-flyout header {
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 7px 8px;
  border-bottom: 1px solid var(--line);
}
.rail-flyout header strong { color: var(--text); font-size: 11px; font-weight: 650; }
.rail-flyout header button { border: 0; background: transparent; color: var(--subtle); cursor: pointer; font-size: 16px; }
.rail-module {
  width: 100%;
  min-height: 54px;
  display: grid;
  gap: 5px;
  padding: 9px 8px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}
.rail-module:last-child { border-bottom: 0; }
.rail-module:hover,
.rail-module.active { background: var(--workspace-hover-bg); color: var(--text); }
.rail-module span { font-size: 11px; font-weight: 620; }
.rail-module small { color: var(--subtle); font-size: 8px; line-height: 1.45; }

.workspace-header {
  min-height: 58px;
  padding: 0 22px;
  display: grid;
  grid-template-columns: minmax(170px, 1fr) auto;
  align-items: center;
  gap: 18px;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
}
.workspace-brand { display: flex; align-items: baseline; gap: 9px; min-width: 0; }
.workspace-brand strong { color: var(--text); font: 700 14px/1 Inter, "MiSans", sans-serif; letter-spacing: -.015em; }
.workspace-brand > span:not(.visually-hidden) { color: var(--subtle); font-size: 9px; }
.workspace-actions { align-self: stretch; display: flex; align-items: center; justify-content: flex-end; gap: 3px; min-width: 0; }
.header-search,
.return-button {
  min-height: 34px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  padding: 0 9px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 9px;
  transition: color .16s ease, background .16s ease;
}
.header-search:hover,
.return-button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.header-search span { font-size: 15px; }
.header-search small { font-size: 9px; }
.account-menu > summary i { width: 5px; height: 5px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(45deg) translateY(-1px); }

.workspace-tabs {
  min-width: 0;
  height: 42px;
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg) 97%, var(--panel-raised));
  scrollbar-width: none;
}
.workspace-tabs::-webkit-scrollbar { display: none; }
.workspace-tab {
  position: relative;
  flex: 0 0 auto;
  min-width: 112px;
  max-width: 210px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 27px;
  align-items: stretch;
  border-right: 1px solid var(--line);
  background: transparent;
}
.workspace-tab.active { background: var(--workspace-panel-soft); }
.workspace-tab.active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 0;
  height: 2px;
  background: var(--accent);
}
.workspace-tab-select,
.workspace-tab-close,
.workspace-tab-add {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.workspace-tab-select { min-width: 0; display: flex; align-items: center; gap: 6px; padding: 0 4px 0 12px; text-align: left; }
.workspace-tab-select span { color: inherit; font-size: 10px; font-weight: 580; white-space: nowrap; }
.workspace-tab-select small { color: var(--subtle); font: 500 7px/1 ui-monospace, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-tab-close { width: 27px; font-size: 13px; opacity: .45; }
.workspace-tab:hover .workspace-tab-select,
.workspace-tab.active .workspace-tab-select { color: var(--text); }
.workspace-tab:hover .workspace-tab-close { opacity: 1; color: var(--text); }
.workspace-tab-add { flex: 0 0 40px; font-size: 17px; }
.workspace-tab-add:hover { color: var(--text); background: var(--workspace-hover-bg); }

.command-backdrop {
  position: fixed;
  z-index: 240;
  inset: 0;
  display: grid;
  place-items: start center;
  padding-top: min(15vh, 130px);
  background: rgba(0,0,0,.38);
}
.command-palette {
  width: min(620px, calc(100vw - 32px));
  max-height: min(650px, 72vh);
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  padding: 12px;
  border: 1px solid var(--line-strong);
  background: var(--panel-raised);
  box-shadow: 0 28px 80px rgba(0,0,0,.34);
}
.command-palette header { display: flex; align-items: center; justify-content: space-between; padding: 2px 3px 10px; }
.command-palette header span { color: var(--text); font-size: 11px; font-weight: 650; }
.command-palette kbd { color: var(--subtle); font: 500 8px/1 ui-monospace, monospace; }
.command-palette input {
  width: 100%; height: 42px; border: 1px solid var(--line-strong); outline: 0; background: var(--surface);
  color: var(--text); padding: 0 12px; font-size: 11px;
}
.command-palette input:focus { border-color: var(--workspace-focus); }
.command-results { min-height: 0; overflow: auto; margin-top: 8px; }
.command-results > button {
  width: 100%; min-height: 56px; display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 9px 10px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: var(--muted); text-align: left; cursor: pointer;
}
.command-results > button:hover { color: var(--text); background: var(--workspace-hover-bg); }
.command-results > button > span { min-width: 0; display: grid; gap: 5px; }
.command-results strong { color: inherit; font-size: 11px; }
.command-results small { color: var(--subtle); font-size: 8px; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.command-results em { color: var(--subtle); font: 500 7px/1 ui-monospace, monospace; font-style: normal; white-space: nowrap; }

.workspace-body {
  min-height: 0; padding: 18px 20px 26px; overflow: auto;
  background: radial-gradient(circle at 72% -10%, var(--workspace-body-glow) 0, transparent 38%), var(--bg);
  transition: opacity .18s ease, transform .18s ease;
}
.workspace-body :deep(.panel),
.workspace-body :deep(.card),
.workspace-body :deep(.tool-section),
.workspace-body :deep(.conversation-panel) {
  border-radius: 0 !important;
  box-shadow: none !important;
  border-color: var(--line) !important;
  background: var(--workspace-panel-wash) !important;
}
.workspace-body :deep(.section-bar) {
  min-height: 50px;
  padding: 0 2px 10px;
  border-bottom: 1px solid var(--line);
}
.workspace-body :deep(.section-bar h1) {
  font-family: Inter, "MiSans", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 14px;
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: .015em;
}
.workspace-body :deep(.research-head h2),
.workspace-body :deep(.cross-heading h1),
.workspace-body :deep(.sim-title h1),
.workspace-body :deep(.ops-head h2) {
  font-family: Inter, "MiSans", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 14px;
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: .015em;
}
.workspace-body :deep(.btn.primary) {
  border-radius: 0 !important;
  border-color: var(--workspace-action-border) !important;
  background: var(--workspace-action-bg) !important;
  color: var(--workspace-action-text) !important;
}
.workspace-body :deep(.btn.primary:hover:not(:disabled)) { background: var(--workspace-action-bg-hover) !important; border-color: var(--workspace-action-bg-hover) !important; }
.workspace-body :deep(.btn:not(.primary)),
.workspace-body :deep(.select) {
  border-radius: 0 !important;
  border-color: var(--line-strong) !important;
  background: var(--workspace-control-bg) !important;
  color: var(--text) !important;
}
.workspace-body :deep(.btn:not(.primary):hover:not(:disabled)) { background: var(--workspace-hover-bg) !important; border-color: var(--workspace-focus) !important; }
.workspace-body :deep(.table th) { background: var(--workspace-panel-soft); color: var(--muted); }
.workspace-body :deep(.table td),
.workspace-body :deep(.table th) { border-color: var(--workspace-row-divider); }
.workspace-body :deep(.table tbody tr:hover) { background: var(--workspace-hover-bg); }
.workspace-body :deep(.chart) { background: var(--workspace-chart-bg); border-color: var(--line); }
.workspace-body :deep(.metric) { background: var(--workspace-panel-soft); border-color: var(--line); }
.workspace-footer {
  min-height: 31px; padding: 0 24px; display: flex; align-items: center; gap: 22px;
  border-top: 1px solid var(--line); color: var(--subtle); font: 500 7px/1 ui-monospace, monospace; letter-spacing: .08em;
}
.workspace-footer strong { margin-left: auto; color: var(--muted); font-weight: 500; }

.workspace-shell.is-night {
  --bg: #0b0f13;
  --panel: #12171b;
  --panel-raised: #171d22;
  --surface: #0f1418;
  --surface-2: #171d22;
  --line: rgba(205,201,193,.115);
  --line-strong: rgba(205,201,193,.22);
  --text: #eee8de;
  --muted: #aaa69f;
  --subtle: #666a6c;
  --accent: #ad956d;
  --accent-strong: #d0b47b;
  --ok: #35b978;
  --bad: #e46363;
  --warn: #c8a76a;
  --danger-soft: rgba(228,99,99,.08);
  --success-soft: rgba(53,185,120,.08);
  --workspace-panel-wash: rgba(18,23,27,.90);
  --workspace-panel-soft: #0f1418;
  --workspace-panel-rail: #11161a;
  --workspace-chart-bg: #0d1215;
  --workspace-node-bg: #171d22;
  --workspace-node-focus-bg: #20272d;
  --workspace-hover-bg: rgba(238,232,222,.05);
  --workspace-accent-wash: rgba(173,149,109,.09);
  --workspace-row-divider: rgba(205,201,193,.105);
  --workspace-graph-grid: rgba(205,201,193,.055);
  --workspace-graph-edge: rgba(170,166,159,.50);
  --workspace-graph-outline: rgba(173,149,109,.30);
  --workspace-node-shadow: rgba(0,0,0,.28);
  --workspace-focus: #b89d6f;
  --workspace-action-bg: #ad956d;
  --workspace-action-bg-hover: #d0b47b;
  --workspace-action-border: #ad956d;
  --workspace-action-text: #17140e;
  --workspace-control-bg: rgba(238,232,222,.018);
  --workspace-neutral-dot: #777c7d;
  --workspace-track: rgba(205,201,193,.085);
  --workspace-track-fill: #848884;
  --workspace-warning-text: #c8a76a;
  --workspace-warning-bg: rgba(200,167,106,.075);
  --workspace-warning-border: rgba(200,167,106,.48);
  --workspace-body-grid: rgba(205,201,193,.012);
  --workspace-body-glow: rgba(173,149,109,.042);
  background: var(--bg);
  color: var(--text);
}
.workspace-shell.is-night .workspace-header,
.workspace-shell.is-night .workspace-module-index,
.workspace-shell.is-night .workspace-footer { border-color: var(--line); }
.workspace-shell.is-night .workspace-brand strong,
.workspace-shell.is-night .workspace-module h1,
.workspace-shell.is-night .workspace-module-index button.active,
.workspace-shell.is-night .workspace-module-index button:hover { color: var(--text); }
.workspace-shell.is-night .workspace-module small,
.workspace-shell.is-night .context-readout strong,
.workspace-shell.is-night .workspace-module-index button,
.workspace-shell.is-night .workspace-footer strong { color: var(--muted); }
.workspace-shell.is-night .workspace-module-index button::after { background: var(--accent); }
.workspace-shell.is-night .return-button:hover { color: var(--text); background: rgba(255,255,255,.055); }
.workspace-shell.is-night .workspace-body :deep(.panel),
.workspace-shell.is-night .workspace-body :deep(.card),
.workspace-shell.is-night .workspace-body :deep(.tool-section),
.workspace-shell.is-night .workspace-body :deep(.conversation-panel) { background: var(--workspace-panel-wash) !important; }
.workspace-shell.is-night .workspace-body :deep(.tool-output) { background: var(--surface); }
.workspace-shell.is-night .workspace-body :deep(.btn.primary) { border-color: var(--workspace-action-border) !important; background: var(--workspace-action-bg) !important; color: var(--workspace-action-text) !important; }

@media (max-width: 1180px) {
  .workspace-header { grid-template-columns: minmax(120px, 1fr) auto; gap: 12px; padding-left: 16px; padding-right: 16px; }
  .workspace-brand > span:not(.visually-hidden) { display: none; }
  .workspace-tab { min-width: 104px; }
}

@media (max-width: 920px) {
  .global-rail { width: 54px; }
  .workspace-frame { margin-left: 54px; }
  .rail-groups { padding-left: 5px; padding-right: 5px; }
  .rail-label, .rail-search small { display: none; }
  .rail-groups > button, .rail-search { min-height: 44px; }
  .rail-search { margin-left: 5px; margin-right: 5px; }
  .rail-flyout { left: calc(100% + 6px); width: 244px; }
  .workspace-header { min-height: 58px; grid-template-columns: minmax(0, 1fr) auto; }
  .header-search small { display: none; }
  .workspace-body { padding-left: 14px; padding-right: 14px; }
}

@media (max-width: 700px) {
  .global-rail { width: 46px; }
  .workspace-frame { margin-left: 46px; grid-template-rows: 54px 40px minmax(0, 1fr) 28px; }
  .rail-mark { height: 54px; font-size: 14px; }
  .rail-groups > button { min-height: 40px; }
  .rail-glyph { font-size: 11px; }
  .rail-search { min-height: 40px; }
  .rail-flyout { position: fixed; left: 52px; right: 8px; top: 60px; width: auto; max-width: 280px; }
  .workspace-header { min-height: 54px; padding: 0 12px; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
  .workspace-brand strong { font-size: 12px; }
  .workspace-actions { padding: 0; border: 0; justify-content: flex-end; gap: 0; }
  .header-search { display: none; }
  .return-button { min-height: 40px; padding: 0 8px; font-size: 0; }
  .return-button > span:first-child { font-size: 15px; }
  .theme-toggle-indicator { width: 8px; height: 8px; }
  .account-menu > summary { min-height: 40px; max-width: 72px; padding: 0 7px; overflow: hidden; text-overflow: ellipsis; }
  .account-menu > summary > span { overflow: hidden; text-overflow: ellipsis; }
  .account-popover { position: fixed; top: 54px; right: 10px; width: min(230px, calc(100vw - 20px)); }
  .workspace-tabs { height: 40px; }
  .workspace-tab { min-width: 96px; }
  .workspace-tab-select { padding-left: 9px; }
  .workspace-tab-select small { display: none; }
  .workspace-body { padding: 12px 10px 18px; }
  .workspace-footer { padding: 0 14px; }
  .workspace-footer strong { font-size: 6px; }
}

/* Entity-first institutional workspace */
.workspace-shell {
  --radius: 7px;
  --radius-sm: 5px;
}
.workspace-shell.is-night {
  --bg: #090d10;
  --panel: #10151a;
  --panel-raised: #161c22;
  --surface: #0d1216;
  --surface-2: #141a20;
  --line: rgba(235,232,225,.075);
  --line-strong: rgba(235,232,225,.145);
  --text: #ece9e2;
  --muted: #9a9fa4;
  --subtle: #686e74;
  --accent: #b49a6a;
  --accent-strong: #ccb17c;
  --workspace-panel-wash: #10151a;
  --workspace-panel-soft: #0d1216;
  --workspace-panel-rail: #0e1317;
  --workspace-hover-bg: rgba(236,233,226,.045);
  --workspace-accent-wash: rgba(180,154,106,.07);
  --workspace-body-glow: rgba(180,154,106,.018);
}
.workspace-frame {
  margin-left: 48px;
  grid-template-rows: 54px 48px minmax(0, 1fr) 28px;
}
.global-rail {
  width: 48px;
  background: color-mix(in srgb, var(--bg) 97%, var(--panel-raised));
}
.rail-mark { height: 54px; font-size: 15px; }
.rail-shortcuts { display: grid; gap: 2px; padding: 10px 6px; }
.rail-shortcuts button,
.rail-search {
  min-height: 42px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--subtle);
  cursor: pointer;
  font-size: 15px;
  transition: color .14s ease, background .14s ease;
}
.rail-shortcuts svg,
.rail-search svg { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.rail-shortcuts button:hover,
.rail-search:hover { color: var(--text); background: var(--workspace-hover-bg); }
.rail-search { margin: 0 6px 9px; padding: 0; display: grid; place-items: center; }

.workspace-header {
  min-height: 54px;
  padding: 0 16px;
  grid-template-columns: 120px minmax(260px, 560px) minmax(120px, 1fr);
  gap: 16px;
  background: color-mix(in srgb, var(--bg) 97%, var(--panel));
}
.workspace-brand { align-items: center; }
.workspace-brand strong { font-size: 14px; letter-spacing: -.02em; }
.global-search-field {
  height: 34px;
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: color-mix(in srgb, var(--surface) 84%, transparent);
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}
.global-search-field:hover { border-color: var(--line-strong); background: var(--surface); }
.global-search-field > span { color: var(--subtle); font-size: 14px; }
.global-search-field strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 9px; font-weight: 500; }
.global-search-field kbd { color: var(--subtle); font: 500 7px/1 ui-monospace, monospace; }
.workspace-actions { min-width: 0; }

.entity-bar {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(176px, 252px) minmax(0, 1fr);
  align-items: stretch;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg) 98%, var(--panel));
}
.entity-history { display: flex; align-items: center; padding: 0 6px 0 12px; }
.entity-history button {
  width: 28px;
  height: 32px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--subtle);
  cursor: pointer;
  font-size: 12px;
}
.entity-history button:hover:not(:disabled) { color: var(--text); background: var(--workspace-hover-bg); }
.entity-history button:disabled { opacity: .25; cursor: default; }
.entity-switcher { position: relative; min-width: 0; }
.entity-context-display {
  min-width: 0;
  display: flex;
  align-items: center;
  padding: 0 14px;
  color: var(--text);
}
.entity-context-display > span { min-width: 0; display: grid; gap: 4px; }
.entity-context-display strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 650; letter-spacing: -.01em; }
.entity-context-display small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--subtle); font: 500 8px/1 ui-monospace, monospace; }

.entity-views {
  min-width: 0;
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 9px;
  overflow-x: auto;
  overflow-y: visible;
  scrollbar-width: none;
}
.entity-views::-webkit-scrollbar { display: none; }
.entity-views > button,
.entity-more > summary,
.split-control > summary {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  padding: 0 11px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  white-space: nowrap;
  font-size: 10px;
  font-weight: 550;
  list-style: none;
}
.entity-views > button:hover,
.entity-more > summary:hover,
.split-control > summary:hover,
.entity-views > button.active,
.entity-more.active > summary,
.split-control.active > summary { color: var(--text); background: var(--workspace-hover-bg); }
.entity-views > button.active::after,
.entity-more.active > summary::after,
.split-control.active > summary::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 18px;
  height: 2px;
  background: var(--accent);
  transform: translateX(-50%);
}
.entity-more { position: relative; }
.entity-more > summary::-webkit-details-marker { display: none; }
.split-control { position: relative; margin-left: auto; }
.split-control > summary::-webkit-details-marker { display: none; }
.entity-more-popover {
  position: absolute;
  z-index: 170;
  right: 0;
  top: calc(100% + 8px);
  width: 270px;
  padding: 8px;
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  background: var(--panel-raised);
  box-shadow: 0 20px 56px rgba(0,0,0,.24);
}
.entity-more-popover button {
  width: 100%;
  min-height: 52px;
  display: grid;
  gap: 5px;
  padding: 9px 10px;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}
.entity-more-popover button:last-child { border-bottom: 0; }
.entity-more-popover button:hover,
.entity-more-popover button.active { color: var(--text); background: var(--workspace-hover-bg); }
.entity-more-popover span { font-size: 10px; font-weight: 620; }
.entity-more-popover small { color: var(--subtle); font-size: 8px; line-height: 1.45; }

.split-popover {
  position: absolute;
  z-index: 175;
  right: 0;
  top: calc(100% + 8px);
  width: 290px;
  padding: 8px;
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  background: var(--panel-raised);
  box-shadow: 0 20px 56px rgba(0,0,0,.24);
}
.split-popover-head { display: grid; gap: 4px; padding: 6px 8px 10px; border-bottom: 1px solid var(--line); }
.split-popover-head span { color: var(--text); font-size: 10px; font-weight: 650; }
.split-popover-head small { color: var(--subtle); font-size: 8px; }
.split-popover > button {
  width: 100%;
  min-height: 48px;
  display: grid;
  gap: 5px;
  padding: 8px 9px;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  text-align: left;
  cursor: pointer;
}
.split-popover > button:last-child { border-bottom: 0; }
.split-popover > button:hover,
.split-popover > button.active { color: var(--text); background: var(--workspace-hover-bg); }
.split-popover > button span { font-size: 10px; font-weight: 620; }
.split-popover > button small { color: var(--subtle); font-size: 8px; line-height: 1.45; }
.split-popover .split-close-action { color: var(--bad); }

.workspace-body {
  padding: 16px 18px 20px;
  background: radial-gradient(circle at 70% -8%, var(--workspace-body-glow) 0, transparent 32%), var(--bg);
}
.workspace-body :deep(.panel),
.workspace-body :deep(.card),
.workspace-body :deep(.tool-section),
.workspace-body :deep(.conversation-panel) {
  border-radius: 7px !important;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.018), 0 12px 34px rgba(0,0,0,.08) !important;
}
.workspace-footer { min-height: 28px; }

@media (max-width: 1080px) {
  .workspace-header { grid-template-columns: 100px minmax(220px, 1fr) auto; gap: 10px; }
  .entity-bar { grid-template-columns: auto minmax(160px, 220px) minmax(0, 1fr); }
  .entity-views { padding-right: 6px; }
  .entity-views > button, .entity-more > summary, .split-control > summary { padding: 0 8px; font-size: 9px; }
}

@media (max-width: 980px) {
  .split-control { display: none; }
}

@media (max-width: 820px) {
  .global-rail { width: 44px; }
  .workspace-frame { margin-left: 44px; grid-template-rows: 54px 54px minmax(0, 1fr) 26px; }
  .rail-mark { height: 54px; font-size: 14px; }
  .rail-shortcuts { padding-left: 4px; padding-right: 4px; }
  .rail-shortcuts button, .rail-search { min-height: 38px; font-size: 13px; }
  .rail-search { margin-left: 4px; margin-right: 4px; }
  .workspace-header { min-height: 54px; padding: 0 10px; grid-template-columns: 72px minmax(150px, 1fr) auto; }
  .workspace-brand strong { font-size: 12px; }
  .global-search-field { height: 32px; }
  .global-search-field kbd { display: none; }
  .account-menu > summary { max-width: 72px; }
  .entity-bar { grid-template-columns: auto minmax(135px, 180px) minmax(0, 1fr); }
  .entity-history { padding-left: 5px; padding-right: 2px; }
  .entity-history button { width: 24px; }
  .entity-context-display { padding: 0 8px; }
  .entity-context-display strong { font-size: 10px; }
  .entity-context-display small { display: none; }
  .entity-views { padding: 0 5px; }
  .workspace-body { padding: 12px 10px 16px; }
}

@media (max-width: 620px) {
  .workspace-header { grid-template-columns: auto minmax(0, 1fr) auto; }
  .global-search-field strong { font-size: 0; }
  .global-search-field strong::after { content: '搜索'; font-size: 9px; }
  .entity-bar { grid-template-columns: auto minmax(116px, 150px) minmax(0, 1fr); }
  .entity-views > button, .entity-more > summary, .split-control > summary { padding: 0 7px; }
  .workspace-footer { display: none; }
  .workspace-frame { grid-template-rows: 54px 54px minmax(0, 1fr); }
}

/* Rhine terminal parity: transparent records, hairline structure, amber ticks. */
.workspace-shell {
  --radius: 0px;
  --radius-sm: 0px;
  --material-glass: rgba(246,243,237,.76);
  --material-elevated: rgba(250,248,243,.88);
  --material-border: rgba(55,52,46,.12);
  --material-shadow: 0 16px 42px rgba(52,45,35,.10);
  --material-shadow-elevated: 0 24px 70px rgba(52,45,35,.16);
  --material-blur: 18px;
  --material-blur-elevated: 28px;
  --material-toolbar-radius: 10px;
  --material-popover-radius: 12px;
  --material-dialog-radius: 14px;
  --motion-fast: 110ms;
  --motion-standard: 190ms;
  --motion-layout: 300ms;
  --motion-ease: cubic-bezier(.22,1,.36,1);
  --workspace-panel-wash: transparent;
  --workspace-panel-soft: transparent;
  --workspace-panel-rail: transparent;
  --workspace-chart-bg: transparent;
  --workspace-control-bg: transparent;
  --workspace-hover-bg: rgba(155,114,71,.085);
  --workspace-accent-wash: rgba(197,161,107,.05);
  --workspace-body-glow: transparent;
  --workspace-action-bg: transparent;
  --workspace-action-bg-hover: rgba(155,114,71,.11);
  --workspace-action-border: var(--accent);
  --workspace-action-text: var(--accent-strong);
  --workspace-track: rgba(170,165,154,.12);
  --workspace-track-fill: var(--accent);
}
.workspace-shell.is-night {
  --bg: #11181b;
  --panel: #11181b;
  --panel-raised: #151d20;
  --surface: #11181b;
  --surface-2: #151d20;
  --line: rgba(83,97,102,.54);
  --line-strong: #536166;
  --text: #e0e3dc;
  --muted: #a6b0b1;
  --subtle: #77756d;
  --accent: #c5a16b;
  --accent-strong: #d4b57e;
  --workspace-node-bg: rgba(17,24,27,.94);
  --workspace-node-focus-bg: rgba(24,32,35,.96);
  --workspace-graph-grid: rgba(83,97,102,.18);
  --workspace-graph-edge: rgba(166,176,177,.42);
  --workspace-graph-outline: rgba(197,161,107,.42);
  --workspace-node-shadow: transparent;
  --workspace-focus: #c5a16b;
  --workspace-warning-text: #c5a16b;
  --workspace-warning-bg: rgba(197,161,107,.045);
  --workspace-warning-border: rgba(197,161,107,.62);
  --material-glass: rgba(24,27,30,.74);
  --material-elevated: rgba(34,37,41,.82);
  --material-border: rgba(255,255,255,.07);
  --material-shadow: 0 16px 46px rgba(0,0,0,.18);
  --material-shadow-elevated: 0 26px 76px rgba(0,0,0,.28);
}
.global-rail,
.workspace-header,
.entity-bar { background: var(--bg); }
.global-rail { border-right-color: color-mix(in srgb, var(--line) 64%, transparent); }
.rail-shortcuts button,
.rail-search {
  border-radius: 8px;
  transition: color var(--motion-standard) ease, background var(--motion-standard) ease, transform var(--motion-fast) ease;
}
.rail-shortcuts button:hover,
.rail-search:hover {
  background: rgba(255,255,255,.04);
  color: var(--accent-strong);
  box-shadow: none;
}
.rail-shortcuts button:active,
.rail-search:active { transform: scale(.96); }
.global-search-field {
  height: 35px;
  border: 1px solid var(--material-border);
  border-radius: 9px;
  background: color-mix(in srgb, var(--material-glass) 72%, transparent);
  padding-left: 10px;
  padding-right: 10px;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.025);
  transition: border-color var(--motion-standard) ease, background var(--motion-standard) ease, transform var(--motion-fast) ease;
}
.global-search-field:hover { border-color: color-mix(in srgb, var(--text) 18%, transparent); background: var(--material-glass); }
.global-search-field:active { transform: scale(.99); }
.workspace-header { border-bottom-color: color-mix(in srgb, var(--line) 64%, transparent); }
.entity-bar { border-bottom-color: color-mix(in srgb, var(--line) 58%, transparent); }
.entity-history button { border-radius: 7px; }
.entity-history button:hover:not(:disabled) { color: var(--text); background: rgba(255,255,255,.035); }
.entity-context-display { margin: 5px 4px; height: calc(100% - 10px); padding-left: 12px; padding-right: 12px; }
.entity-views > button:hover,
.entity-more > summary:hover,
.split-control > summary:hover,
.entity-views > button.active,
.entity-more.active > summary,
.split-control.active > summary {
  color: var(--text);
  background: transparent;
}
.entity-views > button.active::before,
.entity-more.active > summary::before,
.split-control.active > summary::before {
  display: none;
}
.entity-views > button.active::after,
.entity-more.active > summary::after,
.split-control.active > summary::after {
  width: 18px;
  height: 2px;
  left: 50%;
  right: auto;
  transform: translateX(-50%);
  border-radius: 999px;
  transition: width var(--motion-standard) var(--motion-ease), transform var(--motion-standard) var(--motion-ease);
}
.entity-views > button,
.entity-more > summary,
.split-control > summary {
  border-radius: 7px;
  transition: color var(--motion-standard) ease, background var(--motion-standard) ease, transform var(--motion-fast) ease;
}
.entity-views > button:hover,
.entity-more > summary:hover,
.split-control > summary:hover { background: rgba(255,255,255,.032); }
.entity-views > button:active,
.entity-more > summary:active,
.split-control > summary:active { transform: scale(.97); }
.entity-more-popover,
.split-popover,
.account-popover,
.command-palette {
  border-color: var(--material-border);
  border-radius: var(--material-popover-radius);
  background: var(--material-elevated);
  box-shadow: var(--material-shadow-elevated);
  backdrop-filter: blur(var(--material-blur-elevated));
  -webkit-backdrop-filter: blur(var(--material-blur-elevated));
}
.entity-more-popover button,
.split-popover > button,
.command-results > button { border-radius: 8px; }
.entity-more-popover button:hover,
.entity-more-popover button.active,
.split-popover > button:hover,
.split-popover > button.active,
.command-results > button:hover,
.command-results > button.active { background: rgba(255,255,255,.05); }

.command-backdrop {
  padding-top: min(12vh, 108px);
  background: rgba(4,7,10,.24);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}
.command-palette {
  width: min(660px, calc(100vw - 32px));
  padding: 10px;
  border-radius: var(--material-dialog-radius);
  animation: command-palette-in var(--motion-standard) var(--motion-ease);
}
.command-search-row {
  min-height: 50px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 0 10px;
  border-bottom: 1px solid var(--material-border);
}
.command-search-row > span { color: var(--subtle); font-size: 18px; }
.command-search-row input {
  width: 100%;
  height: 48px;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 15px;
  letter-spacing: -.01em;
}
.command-search-row input::placeholder { color: var(--subtle); }
.command-search-row kbd { color: var(--subtle); font: 500 8px/1 ui-monospace, monospace; }
.command-section-label { padding: 12px 10px 5px; color: var(--subtle); font-size: 9px; font-weight: 600; }
.command-results { margin-top: 0; padding: 2px; }
.command-results > button {
  min-height: 54px;
  margin: 2px 0;
  padding: 9px 10px;
  border-bottom: 0;
  transition: color var(--motion-standard) ease, background var(--motion-standard) ease, transform var(--motion-fast) ease;
}
.command-results > button:active { transform: scale(.99); }
@keyframes command-palette-in {
  from { opacity: 0; transform: translateY(-5px) scale(.985); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.workspace-body { background: var(--bg); }
.workspace-body :deep(.panel),
.workspace-body :deep(.card),
.workspace-body :deep(.tool-section),
.workspace-body :deep(.conversation-panel),
.workspace-body :deep(.metric) {
  border-radius: 0 !important;
  box-shadow: none !important;
  background: transparent !important;
}
.workspace-body :deep(.btn.primary) {
  border-radius: 0 !important;
  border-color: var(--accent) !important;
  background: transparent !important;
  color: var(--accent-strong) !important;
  box-shadow: inset 2px 0 0 var(--accent);
}
.workspace-body :deep(.btn.primary:hover:not(:disabled)) {
  border-color: var(--accent) !important;
  background: rgba(155,114,71,.11) !important;
}
.workspace-body :deep(.btn:not(.primary)),
.workspace-body :deep(.select) {
  border-radius: 0 !important;
  background: transparent !important;
}
.workspace-shell.is-night .workspace-body :deep(.panel),
.workspace-shell.is-night .workspace-body :deep(.card),
.workspace-shell.is-night .workspace-body :deep(.tool-section),
.workspace-shell.is-night .workspace-body :deep(.conversation-panel),
.workspace-shell.is-night .workspace-body :deep(.metric) {
  background: transparent !important;
  box-shadow: none !important;
}
.workspace-shell.is-night .workspace-body :deep(.tool-output) {
  background: transparent !important;
}
.workspace-shell.is-night .workspace-body :deep(.btn.primary) {
  border-color: var(--accent) !important;
  background: transparent !important;
  color: var(--accent-strong) !important;
}
.workspace-shell.is-night .workspace-body :deep(.btn.primary:hover:not(:disabled)) {
  background: rgba(155,114,71,.11) !important;
}
.workspace-footer { border-top-color: var(--line); }

/* V4 — modern financial workspace. Rhine is now an accent, not the layout system. */
.workspace-shell {
  --radius: 12px;
  --radius-sm: 8px;
  --material-toolbar-radius: 12px;
  --material-popover-radius: 14px;
  --material-dialog-radius: 16px;
  --workspace-body-glow: transparent;
}
.workspace-shell.is-night {
  --bg: #0a0d10;
  --panel: #0f1317;
  --panel-raised: #171c22;
  --surface: #101418;
  --surface-2: #161b20;
  --line: rgba(255,255,255,.055);
  --line-strong: rgba(255,255,255,.105);
  --text: #f0f2f3;
  --muted: #9ba3aa;
  --subtle: #656d75;
  --accent: #b79a67;
  --accent-strong: #cfb47f;
  --workspace-hover-bg: rgba(255,255,255,.042);
  --material-glass: rgba(24,28,33,.76);
  --material-elevated: rgba(30,35,41,.88);
  --material-border: rgba(255,255,255,.065);
  --material-shadow: 0 18px 52px rgba(0,0,0,.18);
  --material-shadow-elevated: 0 28px 84px rgba(0,0,0,.30);
}
.global-rail {
  width: 42px;
  border-right: 1px solid color-mix(in srgb, var(--line) 72%, transparent);
  background: color-mix(in srgb, var(--bg) 96%, var(--panel));
}
.workspace-frame {
  margin-left: 42px;
  grid-template-rows: 52px 52px minmax(0, 1fr);
}
.rail-mark { height: 52px; font-size: 14px; opacity: .82; }
.rail-shortcuts { padding: 11px 4px; }
.rail-shortcuts button,
.rail-search {
  min-height: 36px;
  border-radius: 9px;
}
.rail-shortcuts button:hover,
.rail-search:hover { color: var(--text); background: rgba(255,255,255,.045); }
.rail-search { margin: 0 4px 10px; }
.workspace-header {
  min-height: 52px;
  padding: 0 20px;
  grid-template-columns: 116px minmax(280px, 520px) minmax(100px, 1fr);
  gap: 18px;
  border-bottom: 0;
  background: color-mix(in srgb, var(--bg) 96%, var(--panel));
}
.workspace-brand strong { font-size: 15px; font-weight: 690; letter-spacing: -.03em; }
.global-search-field {
  height: 34px;
  border: 1px solid var(--material-border);
  border-radius: 11px;
  background: rgba(255,255,255,.025);
  box-shadow: none;
}
.global-search-field:hover { background: rgba(255,255,255,.045); border-color: rgba(255,255,255,.10); }
.global-search-field strong { font-size: 10px; }
.entity-bar {
  min-height: 52px;
  grid-template-columns: auto minmax(176px, 236px) minmax(0, 1fr);
  border-top: 1px solid color-mix(in srgb, var(--line) 65%, transparent);
  border-bottom: 1px solid color-mix(in srgb, var(--line) 72%, transparent);
  background: color-mix(in srgb, var(--bg) 98%, var(--panel));
}
.entity-history { padding: 0 6px 0 14px; }
.entity-history button { width: 30px; height: 34px; border-radius: 9px; }
.entity-context-display {
  margin: 0;
  height: auto;
  padding: 0 14px;
}
.entity-context-display > span { gap: 3px; }
.entity-context-display strong { font-size: 13px; font-weight: 660; }
.entity-context-display small {
  font-family: Inter, "MiSans", "PingFang SC", sans-serif;
  font-size: 9px;
  letter-spacing: 0;
}
.entity-views { gap: 4px; padding: 0 12px; }
.entity-views > button,
.entity-more > summary,
.split-control > summary {
  padding: 0 12px;
  border-radius: 9px;
  font-size: 11px;
  font-weight: 560;
}
.entity-views > button:hover,
.entity-more > summary:hover,
.split-control > summary:hover { background: rgba(255,255,255,.038); }
.entity-views > button.active::after,
.entity-more.active > summary::after,
.split-control.active > summary::after {
  bottom: 5px;
  width: 16px;
  height: 2px;
}
.workspace-body {
  padding: 24px 28px 32px;
  background: var(--bg);
}
.workspace-body :deep(.market-workspace),
.workspace-body :deep(.research-workspace),
.workspace-body :deep(.ch-workspace),
.workspace-body :deep(.sg-workspace) {
  animation: workspace-surface-in 240ms cubic-bezier(.22,1,.36,1) both;
}
@keyframes workspace-surface-in {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}
.workspace-footer { display: none; }
.workspace-body :deep(.btn.primary) {
  border-radius: 9px !important;
  box-shadow: none !important;
}
.workspace-body :deep(.btn:not(.primary)),
.workspace-body :deep(.select) { border-radius: 8px !important; }

@media (max-width: 820px) {
  .global-rail { width: 40px; }
  .workspace-frame { margin-left: 40px; grid-template-rows: 50px 50px minmax(0, 1fr); }
  .workspace-header { min-height: 50px; padding: 0 10px; }
  .entity-bar { min-height: 50px; }
  .workspace-body { padding: 14px 12px 20px; }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-shell { transition-duration: .01ms; }
  .command-palette { animation: none !important; }
  .workspace-body :deep(.market-workspace),
  .workspace-body :deep(.research-workspace),
  .workspace-body :deep(.ch-workspace),
  .workspace-body :deep(.sg-workspace) { animation: none !important; }
  .entity-views > button,
  .entity-more > summary,
  .split-control > summary,
  .global-search-field,
  .rail-shortcuts button,
  .rail-search { transition: none !important; }
}

/* V5 — visual polish only: reduce route-switch flashing and standardize interaction feedback. */
.workspace-shell {
  font-family: Inter, "MiSans", "PingFang SC", "Microsoft YaHei", sans-serif;
}
.workspace-shell.switching .workspace-body {
  opacity: .46;
  transform: translateY(2px);
}
@keyframes workspace-surface-in {
  from { opacity: .78; transform: translateY(3px); }
  to { opacity: 1; transform: translateY(0); }
}
.workspace-header,
.entity-bar,
.global-rail {
  transition: background .18s ease, border-color .18s ease;
}
.global-search-field:focus-visible,
.entity-history button:focus-visible,
.entity-views > button:focus-visible,
.entity-more > summary:focus-visible,
.split-control > summary:focus-visible,
.rail-shortcuts button:focus-visible,
.rail-search:focus-visible {
  outline: 0;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 13%, transparent);
}
.entity-views > button,
.entity-more > summary,
.split-control > summary { font-size: 11.5px; }
.workspace-body { padding: 20px 24px 28px; }
.workspace-body :deep(.section-bar > div:first-child > span),
.workspace-body :deep(.research-head > div:first-child > span) {
  font-size: 11px;
  line-height: 1.45;
}
.workspace-body :deep(.btn) {
  min-height: 34px;
  transition: color .16s ease, background .16s ease, border-color .16s ease, box-shadow .16s ease, transform .10s ease !important;
}
.workspace-body :deep(.btn:active:not(:disabled)) { transform: scale(.98); }
.workspace-body :deep(.btn:focus-visible),
.workspace-body :deep(.select:focus-visible),
.workspace-body :deep(input:focus-visible),
.workspace-body :deep(textarea:focus-visible) {
  outline: 0;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 11%, transparent);
}
.workspace-body :deep(.table tbody tr) {
  transition: background .14s ease;
}
@media (max-width: 1180px) {
  .workspace-body { padding: 16px 16px 22px; }
}
@media (max-width: 820px) {
  .workspace-body { padding: 12px 10px 18px; }
}
@media (prefers-reduced-motion: reduce) {
  .workspace-shell.switching .workspace-body { opacity: 1; transform: none; }
  .workspace-body :deep(.btn),
  .workspace-header,
  .entity-bar,
  .global-rail { transition: none !important; }
}

/* V6 — selected-state surface language and tighter application chrome. */
.global-search-field {
  transition: border-color .16s ease, background .16s ease, box-shadow .16s ease, transform .10s ease;
}
.global-search-field:focus-visible {
  border-color: color-mix(in srgb, var(--accent) 32%, var(--material-border));
  background: color-mix(in srgb, var(--material-glass) 86%, transparent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 9%, transparent), inset 0 1px 0 rgba(255,255,255,.028);
}
.account-menu > summary {
  min-height: 34px;
  border-radius: 9px;
  font-size: 10.5px;
  transition: color .16s ease, background .16s ease, transform .10s ease;
}
.account-menu > summary:active { transform: scale(.97); }
.account-menu[open] > summary {
  background: color-mix(in srgb, var(--workspace-hover-bg) 78%, transparent);
}
.entity-views > button.active,
.entity-more.active > summary,
.split-control.active > summary {
  color: var(--text);
  background: color-mix(in srgb, var(--workspace-hover-bg) 82%, transparent);
}
.entity-views > button.active::after,
.entity-more.active > summary::after,
.split-control.active > summary::after { display: none; }
.entity-views > button:hover:not(.active),
.entity-more > summary:hover,
.split-control > summary:hover {
  background: color-mix(in srgb, var(--workspace-hover-bg) 60%, transparent);
}
.entity-context-display {
  position: relative;
}
.entity-context-display::after {
  content: '';
  position: absolute;
  right: 0;
  top: 13px;
  bottom: 13px;
  width: 1px;
  background: color-mix(in srgb, var(--line) 72%, transparent);
}
.command-results > button strong { font-size: 11.5px; }
.command-results > button small { font-size: 9.5px; }
.command-results > button em { font-size: 8.5px; }
@media (max-width: 820px) {
  .entity-context-display::after { display: none; }
  .account-menu > summary { font-size: 10px; }
}
@media (prefers-reduced-motion: reduce) {
  .global-search-field,
  .account-menu > summary { transition: none !important; }
}

/* V7 — Ambient Financial Workspace: persistent context + liquid selection. */
.workspace-shell {
  --module-aura: rgba(108, 125, 138, .028);
  --context-accent: color-mix(in srgb, var(--accent) 68%, var(--text));
}
.workspace-shell.ambient-market { --module-aura: rgba(82, 126, 158, .045); --context-accent: #7894a7; }
.workspace-shell.ambient-financial { --module-aura: rgba(118, 132, 119, .040); --context-accent: #819083; }
.workspace-shell.ambient-ai-research { --module-aura: rgba(162, 126, 81, .045); --context-accent: #a18462; }
.workspace-shell.ambient-industry-chain { --module-aura: rgba(148, 118, 78, .052); --context-accent: #a17f57; }
.workspace-shell.ambient-risk { --module-aura: rgba(151, 90, 82, .045); --context-accent: #9d716a; }
.workspace-shell.ambient-strategy { --module-aura: rgba(103, 122, 108, .042); --context-accent: #768778; }
.workspace-shell.ambient-sim-trade { --module-aura: rgba(79, 113, 137, .046); --context-accent: #6d8799; }
.workspace-body {
  background:
    radial-gradient(ellipse 58% 44% at 56% -8%, var(--module-aura), transparent 72%),
    var(--bg);
}
.entity-context-display {
  min-height: 38px;
  margin: 6px 4px;
  padding: 0 13px 0 12px;
  gap: 9px;
  border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 74%, transparent);
  border-radius: 12px;
  background:
    linear-gradient(130deg, color-mix(in srgb, var(--context-accent) 4%, transparent), transparent 44%),
    color-mix(in srgb, var(--material-glass, transparent) 42%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.022);
  view-transition-name: jarvis-context-capsule;
  transition: border-color .22s ease, background .22s ease, box-shadow .22s ease, transform .22s cubic-bezier(.22,1,.36,1), opacity .18s ease;
}
.entity-context-display::after { display: none; }
.entity-context-display.has-context {
  border-color: color-mix(in srgb, var(--context-accent) 22%, var(--material-border, var(--line)));
}
.context-presence {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  border: 1px solid color-mix(in srgb, var(--context-accent) 72%, var(--line-strong));
  background: transparent;
  box-shadow: 0 0 0 0 transparent;
  transition: background .22s ease, border-color .22s ease, box-shadow .22s ease;
}
.entity-context-display.has-context .context-presence {
  border-color: var(--context-accent);
  background: var(--context-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--context-accent) 7%, transparent);
}
.workspace-shell.switching .entity-context-display {
  opacity: .74;
  transform: translateY(-1px) scale(.985);
}
.entity-views {
  position: relative;
  isolation: isolate;
}
.entity-selection-lens {
  position: absolute;
  z-index: 0;
  top: 7px;
  left: 0;
  height: calc(100% - 14px);
  border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 76%, transparent);
  border-radius: 10px;
  background:
    linear-gradient(180deg, rgba(255,255,255,.025), transparent),
    color-mix(in srgb, var(--workspace-hover-bg) 74%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.025);
  pointer-events: none;
  transition:
    transform .34s cubic-bezier(.22,1,.36,1),
    width .34s cubic-bezier(.22,1,.36,1),
    opacity .16s ease,
    background .22s ease;
}
.entity-views > button,
.entity-more,
.split-control { position: relative; z-index: 1; }
.entity-views > button.active,
.entity-more.active > summary {
  background: transparent;
}
.split-control.active > summary {
  color: var(--accent-strong);
  background: color-mix(in srgb, var(--workspace-accent-wash) 48%, transparent);
}
.entity-context-display:hover {
  border-color: color-mix(in srgb, var(--context-accent) 26%, var(--material-border, var(--line)));
  background:
    linear-gradient(130deg, color-mix(in srgb, var(--context-accent) 5.5%, transparent), transparent 48%),
    color-mix(in srgb, var(--material-glass, transparent) 52%, transparent);
}
:global(::view-transition-old(root)),
:global(::view-transition-new(root)) { animation: none; mix-blend-mode: normal; }
:global(::view-transition-group(jarvis-context-capsule)) {
  animation-duration: 320ms;
  animation-timing-function: cubic-bezier(.22,1,.36,1);
}
:global(::view-transition-old(jarvis-context-capsule)) { animation: context-capsule-out 180ms ease both; }
:global(::view-transition-new(jarvis-context-capsule)) { animation: context-capsule-in 300ms cubic-bezier(.22,1,.36,1) both; }
@keyframes context-capsule-out { to { opacity: .35; transform: scale(.985); } }
@keyframes context-capsule-in { from { opacity: .42; transform: scale(.985); } to { opacity: 1; transform: scale(1); } }
@media (max-width: 820px) {
  .entity-context-display { margin-left: 2px; margin-right: 2px; padding-left: 9px; padding-right: 9px; }
  .context-presence { display: none; }
  .entity-selection-lens { top: 6px; height: calc(100% - 12px); }
}
@media (prefers-reduced-motion: reduce) {
  .entity-selection-lens,
  .entity-context-display,
  .context-presence { transition: none !important; }
}
</style>
