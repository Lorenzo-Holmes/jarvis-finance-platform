<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'

const props = defineProps({
  module: { type: Object, required: true },
  modules: { type: Array, default: () => [] },
  user: { type: Object, default: null },
  context: { type: Object, default: null },
  nightMode: { type: Boolean, default: false },
})

const emit = defineEmits(['return', 'navigate-module', 'legacy-admin', 'logout', 'update-profile', 'toggle-night-mode'])
const returning = ref(false)
const switching = ref(false)
const entering = ref(true)
const editingProfile = ref(false)
const displayName = ref('')
const moduleTitleRef = ref(null)
let returnTimer = 0
let switchTimer = 0
let entryTimer = 0

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
  if (returning.value) return
  returning.value = true
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  returnTimer = window.setTimeout(() => emit('return'), reduced ? 20 : 180)
}

function requestModule(next) {
  if (!next || next.key === props.module.key || switching.value || returning.value) return
  switching.value = true
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (switchTimer) window.clearTimeout(switchTimer)
  switchTimer = window.setTimeout(() => emit('navigate-module', next.routeKey), reduced ? 20 : 180)
}

function focusModuleTitle() {
  nextTick(() => moduleTitleRef.value?.focus?.({ preventScroll: true }))
}

function onKeydown(event) {
  if (event.key !== 'Escape' || returning.value) return
  const target = event.target
  if (target instanceof HTMLInputElement
    || target instanceof HTMLTextAreaElement
    || target instanceof HTMLSelectElement
    || target?.isContentEditable) return
  if (document.querySelector('[role="dialog"][aria-modal="true"]')) return
  event.preventDefault()
  requestReturn()
}

watch(() => props.module.key, () => {
  requestAnimationFrame(() => {
    switching.value = false
    focusModuleTitle()
  })
})

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  entryTimer = window.setTimeout(() => {
    entering.value = false
    focusModuleTitle()
  }, reduced ? 20 : 560)
})

onBeforeUnmount(() => {
  if (returnTimer) window.clearTimeout(returnTimer)
  if (switchTimer) window.clearTimeout(switchTimer)
  if (entryTimer) window.clearTimeout(entryTimer)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section class="workspace-shell" :class="{ returning, switching, entering, 'is-night': props.nightMode }">
    <div v-if="entering" class="workspace-entry-bridge" aria-hidden="true">
      <section class="entry-file">
        <div class="entry-file-frame">
          <header>
            <span>JARVIS / ANALYSIS OS</span>
            <strong>{{ props.module.code }}</strong>
          </header>
          <div class="entry-file-id">
            <span>MODULE / {{ String(props.module.no).padStart(2, '0') }}</span>
            <b>{{ props.module.labelEn }}</b>
            <small>{{ props.module.labelZh }}</small>
          </div>
          <div class="entry-rings"><i></i><i></i><b></b></div>
          <footer>{{ props.module.category }} / ARCHIVE FILE</footer>
        </div>
      </section>
      <section class="entry-detail">
        <span>FILE / {{ props.module.code }}</span>
        <h2>{{ props.module.labelEn }}</h2>
        <small>{{ props.module.labelZh }}</small>
        <div class="entry-detail-rule"></div>
        <dl>
          <div v-for="item in props.module.capabilities" :key="item"><dt>CAPABILITY</dt><dd>{{ item }}</dd></div>
        </dl>
        <p>{{ props.module.summary }}</p>
        <footer>WORKSPACE ONLINE / TRANSFERRING CONTROL</footer>
      </section>
    </div>

    <header class="workspace-header">
      <div class="workspace-brand">
        <span>JARVIS / ANALYSIS OS</span>
        <strong>ARCHIVE WORKSPACE</strong>
      </div>

      <div class="workspace-module">
        <span>MODULE {{ String(props.module.no).padStart(2, '0') }} / {{ props.module.category }}</span>
        <div>
          <h1 ref="moduleTitleRef" tabindex="-1">{{ props.module.labelEn }}</h1>
          <small>{{ props.module.labelZh }}</small>
        </div>
      </div>

      <div class="workspace-actions">
        <div class="context-readout">
          <span>CONTEXT</span>
          <strong>{{ props.context?.symbol || 'NO GLOBAL CONTEXT' }}</strong>
        </div>
        <button type="button" class="return-button" @click="requestReturn">← RETURN TO ARCHIVE</button>
        <button
          type="button"
          class="theme-toggle"
          :aria-pressed="props.nightMode"
          :aria-label="props.nightMode ? '关闭夜间模式' : '开启夜间模式'"
          @click="emit('toggle-night-mode')"
        >
          <span class="theme-toggle-indicator" aria-hidden="true"></span>
          夜间
        </button>
        <button
          v-if="props.user?.role === 'ADMIN'"
          type="button"
          class="legacy-admin-button"
          title="返回旧版管理后台"
          @click="emit('legacy-admin')"
        >
          旧版后台
        </button>
        <div v-if="props.user" class="account-strip">
          <template v-if="editingProfile">
            <input v-model="displayName" maxlength="60" aria-label="昵称" @keyup.enter="saveProfile" @keyup.esc="editingProfile = false" />
            <button type="button" @click="saveProfile">SAVE</button>
          </template>
          <button v-else type="button" @click="editingProfile = true">
            {{ props.user.displayName || props.user.email || 'ACCOUNT' }}
          </button>
          <button type="button" @click="api.githubBindAuthorize()">GITHUB</button>
          <button type="button" @click="emit('logout')">LOGOUT</button>
        </div>
      </div>
    </header>

    <nav v-if="props.modules.length" class="workspace-module-index" aria-label="工作区模块索引">
      <button
        v-for="item in props.modules"
        :key="item.key"
        type="button"
        :class="{ active: item.key === props.module.key }"
        :aria-current="item.key === props.module.key ? 'page' : undefined"
        @click="requestModule(item)"
      >
        <span>{{ String(item.no).padStart(2, '0') }}</span>
        <strong>{{ item.labelEn }}</strong>
      </button>
    </nav>

    <div class="workspace-status">
      <span>{{ props.module.code }}</span>
      <span>{{ props.module.capabilities.join(' / ') }}</span>
      <strong>{{ props.module.availability.toUpperCase() }}</strong>
    </div>

    <main class="workspace-body">
      <slot />
    </main>

    <footer class="workspace-footer">
      <span>JARVIS FINANCIAL RESEARCH PLATFORM</span>
      <span>MODULE WORKSPACE / {{ props.module.labelEn }}</span>
      <strong>仅供研究参考，不构成投资建议</strong>
    </footer>
  </section>
</template>

<style scoped>
.workspace-shell {
  --bg: #e8e5e1;
  --panel: #efebe3;
  --panel-raised: #f3f0e9;
  --surface: #e5e0d6;
  --surface-2: #ddd7cc;
  --line: #c9c2b6;
  --line-strong: #aaa397;
  --text: #20221d;
  --muted: #68645c;
  --subtle: #928c82;
  --accent: #a18458;
  --accent-strong: #6d5a3b;
  --ok: #5d765f;
  --bad: #9a5b53;
  --warn: #a17a42;
  --workspace-panel-wash: #f0ece4;
  --workspace-panel-soft: #edeae3;
  --workspace-panel-rail: #f0ece4;
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
  --radius: 0px;
  --radius-sm: 0px;
  min-height: 100dvh;
  background: #e8e5e1;
  color: #20221d;
  transition: opacity .18s ease, transform .18s cubic-bezier(.4,0,1,1);
  font-family: "IBM Plex Sans", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}
.workspace-shell.entering { overflow: hidden; }
.workspace-entry-bridge {
  position: fixed; inset: 0; z-index: 120; display: grid; grid-template-columns: 52% 48%;
  background: #e8e5e1; color: #292b25; pointer-events: none;
  animation: workspace-bridge-out .56s cubic-bezier(.22,1,.36,1) both;
}
.entry-file { position: relative; display: grid; place-items: center; border-right: 1px solid rgba(126,119,108,.28); overflow: hidden; }
.entry-file::after { content: ''; position: absolute; inset: 0; pointer-events: none; background: radial-gradient(circle at 48% 46%, rgba(255,255,255,.22), transparent 43%); }
.entry-file-frame {
  position: relative; width: min(420px, 68%); aspect-ratio: .72; padding: 28px 26px 22px;
  border: 1px solid #8e897f; box-shadow: inset 0 0 0 8px rgba(228,223,214,.9), inset 0 0 0 9px #c8c0b4;
  background: rgba(235,231,222,.74); transform: translateY(1.5%);
}
.entry-file-frame header { display: flex; align-items: center; justify-content: space-between; color: #8c867c; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.entry-file-frame header strong { color: #5d5b54; font-weight: 650; }
.entry-file-id { margin-top: 26px; display: grid; gap: 6px; }
.entry-file-id span { color: #918b81; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.entry-file-id b { color: #383a34; font: 650 18px/1 ui-monospace, monospace; letter-spacing: -.025em; }
.entry-file-id small { color: #77736a; font-size: 11px; }
.entry-rings { position: absolute; left: 50%; top: 57%; width: 170px; height: 260px; transform: translate(-50%,-50%); }
.entry-rings i { position: absolute; left: 50%; width: 118px; height: 118px; margin-left: -59px; border: 4px solid #5e6058; border-radius: 50%; opacity: .72; }
.entry-rings i:first-child { top: 0; }
.entry-rings i:nth-child(2) { bottom: 0; }
.entry-rings b { position: absolute; left: 50%; top: 20px; bottom: 20px; width: 4px; margin-left: -2px; background: #5e6058; opacity: .72; }
.entry-file-frame footer { position: absolute; left: 26px; right: 26px; bottom: 22px; padding-top: 9px; border-top: 1px solid rgba(124,118,108,.28); color: #999287; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em; }
.entry-detail { align-self: center; width: min(520px, 78%); margin-left: 8%; }
.entry-detail > span { color: #918b81; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.entry-detail h2 { margin: 16px 0 0; color: #292b25; font: 650 clamp(28px,3vw,44px)/.95 ui-monospace, monospace; letter-spacing: -.045em; }
.entry-detail > small { display: block; margin-top: 8px; color: #77736a; font-size: 13px; }
.entry-detail-rule { height: 1px; margin-top: 24px; background: #969085; transform-origin: left; animation: workspace-rule-in .38s .05s ease both; }
.entry-detail dl { margin: 0; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); border-left: 1px solid rgba(123,117,107,.25); }
.entry-detail dl > div { min-height: 64px; padding: 13px 12px; border-right: 1px solid rgba(123,117,107,.25); border-bottom: 1px solid rgba(123,117,107,.25); }
.entry-detail dt { color: #aaa398; font: 600 6px/1 ui-monospace, monospace; letter-spacing: .1em; }
.entry-detail dd { margin: 9px 0 0; color: #605e57; font: 650 8px/1.3 ui-monospace, monospace; }
.entry-detail p { margin: 24px 0 0; color: #6f6b63; font-size: 10px; line-height: 1.8; max-width: 460px; }
.entry-detail footer { margin-top: 32px; padding-top: 10px; border-top: 1px solid rgba(123,117,107,.25); color: #948e84; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.workspace-shell.returning { opacity: 0; transform: translateY(8px); pointer-events: none; }
.workspace-shell.switching .workspace-body { opacity: 0; transform: translateY(7px); pointer-events: none; }
.workspace-header {
  min-height: 94px; padding: 18px 24px 14px;
  display: grid; grid-template-columns: 220px minmax(260px, 1fr) auto; align-items: end; gap: 28px;
  border-bottom: 1px solid #bdb7ac;
}
.workspace-module h1:focus-visible { outline: 1px solid var(--accent-strong); outline-offset: 4px; }
.workspace-brand { display: grid; gap: 7px; align-self: center; }
.workspace-brand span { color: #8b867d; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .16em; }
.workspace-brand strong { color: #34362f; font: 700 12px/1 ui-monospace, monospace; letter-spacing: .08em; }
.workspace-module > span { color: #918b81; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .13em; }
.workspace-module > div { display: flex; align-items: baseline; gap: 15px; margin-top: 8px; }
.workspace-module h1 { margin: 0; font: 650 clamp(24px, 2.6vw, 38px)/1 ui-monospace, monospace; letter-spacing: -.035em; }
.workspace-module small { color: #65625b; font-size: 14px; }
.workspace-actions { align-self: stretch; display: flex; align-items: center; gap: 15px; }
.context-readout { min-width: 120px; display: grid; gap: 5px; }
.context-readout span { color: #99948a; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.context-readout strong { color: #575950; font: 600 9px/1 ui-monospace, monospace; white-space: nowrap; }
.return-button, .legacy-admin-button, .account-strip button {
  min-height: 34px; border: 0; background: transparent; color: #5f5c55; cursor: pointer;
  font: 650 8px/1 ui-monospace, monospace; letter-spacing: .1em;
}
.theme-toggle {
  min-height: 34px; display: inline-flex; align-items: center; gap: 7px; padding: 0 10px;
  border: 1px solid var(--line-strong); background: transparent; color: var(--muted);
  cursor: pointer; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .06em; white-space: nowrap;
}
.theme-toggle:hover { color: var(--text); border-color: var(--accent); }
.theme-toggle-indicator { width: 7px; height: 7px; border: 1px solid currentColor; border-radius: 50%; background: transparent; }
.is-night .theme-toggle-indicator { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 9px rgba(214,179,106,.5); }
.return-button { padding: 0 12px; border-left: 1px solid #beb8ad; border-right: 1px solid #beb8ad; }
.legacy-admin-button { padding: 0 10px; border-right: 1px solid #beb8ad; }
.return-button:hover, .legacy-admin-button:hover, .account-strip button:hover { color: #20221d; background: rgba(209,201,188,.28); }
.account-strip { display: flex; align-items: center; gap: 4px; }
.account-strip input { width: 110px; height: 31px; border: 0; border-bottom: 1px solid #8c877d; outline: 0; background: transparent; color: #20221d; font-size: 11px; }
.workspace-module-index {
  height: 43px; padding: 0 24px; display: flex; align-items: stretch; overflow-x: auto;
  border-bottom: 1px solid #c6c0b5; scrollbar-width: none;
}
.workspace-module-index::-webkit-scrollbar { display: none; }
.workspace-module-index button {
  position: relative; flex: 0 0 auto; min-width: 112px; padding: 5px 14px 6px;
  border: 0; border-left: 1px solid rgba(186,179,167,.58); background: transparent; color: #918b81;
  text-align: left; cursor: pointer;
}
.workspace-module-index button:last-child { border-right: 1px solid rgba(186,179,167,.58); }
.workspace-module-index button::after { content: ''; position: absolute; left: 13px; right: 13px; bottom: 0; height: 2px; background: #7c6746; transform: scaleX(0); transition: transform .18s ease; }
.workspace-module-index button span { display: block; color: #aaa398; font: 600 7px/1 ui-monospace, monospace; }
.workspace-module-index button strong { display: block; margin-top: 5px; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .07em; white-space: nowrap; }
.workspace-module-index button:hover { color: #34362f; background: rgba(209,201,188,.2); }
.workspace-module-index button.active { color: #20221d; background: rgba(209,201,188,.27); }
.workspace-module-index button.active::after { transform: scaleX(1); }
.workspace-status {
  min-height: 30px; padding: 0 24px; display: flex; align-items: center; gap: 20px;
  border-bottom: 1px solid #d1cbc0; color: #8f8a80; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em;
}
.workspace-status strong { margin-left: auto; color: #686c5d; }
.workspace-body { min-height: calc(100dvh - 198px); padding: 14px 20px 26px; background: var(--bg); transition: opacity .18s ease, transform .18s ease; }
.workspace-body :deep(.panel) {
  border-radius: 0 !important;
  box-shadow: none !important;
  border-color: var(--line) !important;
  background: rgba(239,235,227,.58) !important;
}
.workspace-body :deep(.section-bar) {
  min-height: 50px;
  padding: 0 2px 10px;
  border-bottom: 1px solid var(--line);
}
.workspace-body :deep(.section-bar h1) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 1;
  font-weight: 650;
  letter-spacing: .1em;
}
.workspace-body :deep(.btn.primary) {
  border-radius: 0 !important;
  border-color: #383b33 !important;
  background: #383b33 !important;
  color: #f2eee6 !important;
}
.workspace-body :deep(.btn:not(.primary)),
.workspace-body :deep(.select) {
  border-radius: 0 !important;
  border-color: var(--line-strong) !important;
  background: transparent !important;
  color: var(--text) !important;
}
.workspace-footer {
  min-height: 31px; padding: 0 24px; display: flex; align-items: center; gap: 22px;
  border-top: 1px solid #c6c0b5; color: #938e84; font: 500 7px/1 ui-monospace, monospace; letter-spacing: .08em;
}
.workspace-footer strong { margin-left: auto; color: #77736a; font-weight: 500; }

.workspace-shell.is-night {
  --bg: #0b0f13;
  --panel: #141a20;
  --panel-raised: #1b2229;
  --surface: #11171d;
  --surface-2: #1b2229;
  --line: #252e37;
  --line-strong: #3a4651;
  --text: #eee9de;
  --muted: #a5afb8;
  --subtle: #7f8a94;
  --accent: #d6b36a;
  --accent-strong: #e6c77e;
  --ok: #35b978;
  --bad: #e46363;
  --warn: #d3a64f;
  --workspace-panel-wash: #141a20;
  --workspace-panel-soft: #11171d;
  --workspace-panel-rail: #141a20;
  --workspace-chart-bg: #0b0f13;
  --workspace-node-bg: #1b2229;
  --workspace-node-focus-bg: #252e37;
  --workspace-hover-bg: #222a32;
  --workspace-accent-wash: rgba(214,179,106,.12);
  --workspace-row-divider: #252e37;
  --workspace-graph-grid: rgba(127,138,148,.12);
  --workspace-graph-edge: rgba(127,138,148,.58);
  --workspace-graph-outline: rgba(214,179,106,.32);
  --workspace-node-shadow: rgba(0,0,0,.28);
  background: var(--bg);
  color: var(--text);
}
.workspace-shell.is-night .workspace-entry-bridge { background: var(--bg); color: var(--text); }
.workspace-shell.is-night .entry-file { border-color: var(--line); }
.workspace-shell.is-night .entry-file-frame { border-color: var(--line-strong); background: var(--panel); box-shadow: inset 0 0 0 8px var(--surface), inset 0 0 0 9px var(--line); }
.workspace-shell.is-night .entry-file-frame header,
.workspace-shell.is-night .entry-file-id span,
.workspace-shell.is-night .entry-file-frame footer,
.workspace-shell.is-night .entry-detail > span,
.workspace-shell.is-night .entry-detail dt,
.workspace-shell.is-night .entry-detail footer { color: var(--subtle); }
.workspace-shell.is-night .entry-file-frame header strong,
.workspace-shell.is-night .entry-file-id b,
.workspace-shell.is-night .entry-detail h2 { color: var(--text); }
.workspace-shell.is-night .entry-file-id small,
.workspace-shell.is-night .entry-detail > small,
.workspace-shell.is-night .entry-detail dd,
.workspace-shell.is-night .entry-detail p { color: var(--muted); }
.workspace-shell.is-night .entry-rings i { border-color: var(--accent); }
.workspace-shell.is-night .entry-rings b { background: var(--accent); }
.workspace-shell.is-night .entry-detail-rule { background: var(--line-strong); }
.workspace-shell.is-night .entry-detail dl,
.workspace-shell.is-night .entry-detail dl > div { border-color: var(--line); }
.workspace-shell.is-night .workspace-header,
.workspace-shell.is-night .workspace-module-index,
.workspace-shell.is-night .workspace-status,
.workspace-shell.is-night .workspace-footer { border-color: var(--line); }
.workspace-shell.is-night .workspace-brand span,
.workspace-shell.is-night .workspace-module > span,
.workspace-shell.is-night .context-readout span,
.workspace-shell.is-night .workspace-module-index button span,
.workspace-shell.is-night .workspace-status { color: var(--subtle); }
.workspace-shell.is-night .workspace-brand strong,
.workspace-shell.is-night .workspace-module h1,
.workspace-shell.is-night .workspace-module-index button.active,
.workspace-shell.is-night .workspace-module-index button:hover { color: var(--text); }
.workspace-shell.is-night .workspace-module small,
.workspace-shell.is-night .context-readout strong,
.workspace-shell.is-night .workspace-module-index button,
.workspace-shell.is-night .workspace-footer strong,
.workspace-shell.is-night .workspace-actions button,
.workspace-shell.is-night .workspace-status strong { color: var(--muted); }
.workspace-shell.is-night .return-button,
.workspace-shell.is-night .legacy-admin-button,
.workspace-shell.is-night .workspace-actions { border-color: var(--line); }
.workspace-shell.is-night .workspace-module-index button,
.workspace-shell.is-night .workspace-module-index button:last-child { border-color: var(--line); }
.workspace-shell.is-night .workspace-module-index button.active,
.workspace-shell.is-night .workspace-module-index button:hover { background: rgba(255,255,255,.045); }
.workspace-shell.is-night .workspace-module-index button::after { background: var(--accent); }
.workspace-shell.is-night .account-strip input { border-color: var(--line-strong); color: var(--text); }
.workspace-shell.is-night .account-strip button:hover,
.workspace-shell.is-night .return-button:hover,
.workspace-shell.is-night .legacy-admin-button:hover,
.workspace-shell.is-night .theme-toggle:hover { color: var(--text); background: rgba(255,255,255,.055); }
.workspace-shell.is-night .workspace-body :deep(.panel),
.workspace-shell.is-night .workspace-body :deep(.card),
.workspace-shell.is-night .workspace-body :deep(.tool-section),
.workspace-shell.is-night .workspace-body :deep(.conversation-panel) { background: var(--panel) !important; }
.workspace-shell.is-night .workspace-body :deep(.tool-output) { background: var(--surface); }
.workspace-shell.is-night .workspace-body :deep(.btn.primary) { border-color: var(--accent) !important; background: var(--accent) !important; color: #17140e !important; }

@keyframes workspace-bridge-out {
  0%, 58% { opacity: 1; }
  100% { opacity: 0; }
}
@keyframes workspace-rule-in { from { transform: scaleX(0); } to { transform: scaleX(1); } }

@media (max-width: 1050px) {
  .workspace-header { grid-template-columns: 160px 1fr; }
  .workspace-actions { grid-column: 1 / -1; justify-content: flex-end; border-top: 1px solid #d1cbc0; padding-top: 8px; }
  .workspace-brand { align-self: end; }
  .workspace-module-index { padding: 0 14px; }
}

@media (max-width: 700px) {
  .workspace-entry-bridge { grid-template-columns: 1fr; }
  .entry-file { display: none; }
  .entry-detail { width: auto; margin: 0 22px; }
  .entry-detail dl { grid-template-columns: 1fr; }
  .entry-detail dl > div { min-height: 44px; }
  .workspace-header { padding: 14px 14px 10px; grid-template-columns: 1fr auto; gap: 12px; }
  .workspace-brand { display: none; }
  .workspace-module { min-width: 0; }
  .workspace-module h1 { font-size: 23px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .workspace-module small { font-size: 12px; }
  .workspace-actions { grid-column: auto; padding: 0; border: 0; justify-content: flex-end; }
  .context-readout, .account-strip { display: none; }
  .return-button { min-height: 44px; padding: 0 10px; font-size: 7px; }
  .workspace-status { padding: 0 14px; overflow: hidden; }
  .workspace-status span:nth-child(2) { display: none; }
  .workspace-module-index { height: 46px; padding: 0 12px; }
  .workspace-module-index button { min-width: 96px; padding-left: 10px; padding-right: 10px; }
  .workspace-body { min-height: calc(100dvh - 179px); padding: 10px 12px 18px; }
  .workspace-footer { padding: 0 14px; }
  .workspace-footer span:nth-child(2), .workspace-footer strong { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-shell { transition-duration: .01ms; }
}
</style>
