<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import AnalysisArchiveScene from '../components/analysis/AnalysisArchiveScene.vue'
import { JARVIS_MODULES, MODULE_LANES, moduleByKey, modulesForLane, wrap } from '../analysis-os/data/modules'
import { useArchiveIdle } from '../analysis-os/motion/useArchiveIdle'
import { useArchiveTransition } from '../analysis-os/motion/useArchiveTransition'

const emit = defineEmits(['navigate', 'focus-change'])
const props = defineProps({
  active: { type: Boolean, default: false },
  requestedModuleKey: { type: String, default: '' },
})

const modules = JARVIS_MODULES
const focusedKey = ref(modules[0].key)
const indexOpen = ref(false)
const query = ref('')
const booting = ref(true)
const dataState = ref('loading')
const dataError = ref('')
const lastUpdated = ref('')
const clock = ref('—')
const moduleIndexRef = ref(null)
const archiveSceneRef = ref(null)
let bootTimer = 0
let clockTimer = 0
let syncVersion = 0
const reducedMotion = typeof window !== 'undefined'
  && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
const archiveIdle = useArchiveIdle({ reduced: reducedMotion })
const { environmentState, sleepAmount, hudDim } = archiveIdle
const archiveTransition = useArchiveTransition()
const { transitionState, extractionProgress } = archiveTransition
const WORKSPACE_REVEAL_HOLD_MS = 180

const focusedModule = computed(() => moduleByKey(focusedKey.value, modules))
const focusedIndex = computed(() => Math.max(0, modules.findIndex(module => module.key === focusedKey.value)))
const moduleNumber = computed(() => String((focusedModule.value?.no || 1)).padStart(2, '0'))
const moduleTotal = computed(() => String(modules.length).padStart(2, '0'))
const filteredModules = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return modules
  return modules.filter(module => [
    module.labelEn, module.labelZh, module.category, module.code, module.summary,
    ...module.capabilities,
  ].some(value => String(value || '').toLowerCase().includes(needle)))
})
const dataStateLabel = computed(() => ({
  loading: 'SYNCING', live: 'LIVE API', catalog: 'CATALOG', fallback: 'FALLBACK',
}[dataState.value] || 'UNKNOWN'))
const currentLane = computed(() => MODULE_LANES[focusedModule.value?.lane || 0])
const documentRevealAmount = computed(() => Math.max(0, Math.min(1, (extractionProgress.value - 0.72) / 0.28)))
const accessStage = computed(() => {
  const progress = extractionProgress.value
  if (progress < 0.16) return { code: 'RELEASE LOCK', detail: 'ARCHIVE LOCK RELEASED' }
  if (progress < 0.43) return { code: 'VERTICAL EXTRACTION', detail: 'ARCHIVE LIFT / CAMERA HOLD' }
  if (progress < 0.68) return { code: 'CAMERA APPROACH', detail: 'SPATIAL CONTEXT ALIGNING' }
  if (progress < 0.9) return { code: 'GLASS DECRYPT', detail: 'INTERNAL STRUCTURE REVEALED' }
  return { code: 'DOCUMENT REVEAL', detail: 'RESEARCH CONTEXT READY' }
})

async function syncSystemStatus() {
  const version = ++syncVersion
  dataState.value = 'loading'
  dataError.value = ''
  try {
    const response = await api.marketInstruments()
    if (version !== syncVersion) return
    if (response?.code !== 200 || !Array.isArray(response?.data)) {
      throw new Error(response?.message || '市场目录连接失败')
    }
    dataState.value = response.data.length ? 'live' : 'catalog'
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (version !== syncVersion) return
    dataState.value = 'fallback'
    dataError.value = error?.message || '系统数据通道连接失败'
    lastUpdated.value = ''
  }
}

function focusModule(key, source = 'index') {
  if (!moduleByKey(key, modules)) return
  if (extractionProgress.value > 0.001) return
  if (source !== 'scene') archiveIdle.activity(source)
  focusedKey.value = key
  transitionState.value = 'FOCUSED'
  emit('focus-change', key)
  if (source !== 'scene') scrollActiveIndexIntoView()
}

async function activateModule(key = focusedKey.value) {
  const module = moduleByKey(key, modules)
  if (!module) return
  if (extractionProgress.value > 0.001 || transitionState.value === 'EXTRACTING') return
  archiveIdle.activity('activate')
  focusedKey.value = module.key
  await nextTick()
  const entered = await archiveTransition.enter(reducedMotion)
  if (!entered) return
  if (!reducedMotion) await new Promise(resolve => window.setTimeout(resolve, WORKSPACE_REVEAL_HOLD_MS))
  emit('navigate', module.routeKey)
  archiveTransition.workspaceActive()
}

function handleIndexClick(module) {
  if (module.key === focusedKey.value) activateModule(module.key)
  else focusModule(module.key)
}

function handleSceneFocus(key) {
  focusModule(key, 'scene')
}

function moveLinear(delta) {
  archiveIdle.activity('module-step')
  const next = (focusedIndex.value + delta + modules.length) % modules.length
  focusModule(modules[next].key)
}

function moveVertical(delta) {
  archiveIdle.activity('module-row-step')
  const current = focusedModule.value
  if (!current) return
  const list = modulesForLane(current.lane, modules)
  const index = Math.max(0, list.findIndex(item => item.key === current.key))
  const next = list[wrap(index + delta, list.length)]
  if (next) focusModule(next.key)
}

function scrollActiveIndexIntoView() {
  nextTick(() => {
    const root = moduleIndexRef.value
    const active = root?.querySelector?.(`[data-module-key="${focusedKey.value}"]`)
    active?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest', inline: 'center' })
  })
}

function onKeydown(event) {
  if (!props.active) return
  if (indexOpen.value && event.key === 'Escape') {
    event.preventDefault()
    indexOpen.value = false
    archiveIdle.activity('escape')
    return
  }
  if (indexOpen.value) return
  const target = event.target
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveLinear(-1)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveLinear(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveVertical(-1)
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveVertical(1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    archiveIdle.activity('key')
    activateModule()
  } else if (event.key === '/') {
    event.preventDefault()
    archiveIdle.activity('search')
    indexOpen.value = true
    nextTick(() => document.querySelector('.module-search input')?.focus())
  }
}

function skipBoot() {
  booting.value = false
}

watch(focusedKey, scrollActiveIndexIntoView)
watch(() => props.requestedModuleKey, key => {
  if (!key || key === focusedKey.value || !moduleByKey(key, modules)) return
  focusModule(key, 'external')
})
watch(() => props.active, active => {
  if (!active) {
    archiveIdle.setEnabled(false)
    return
  }
  if (transitionState.value === 'WORKSPACE_ACTIVE') {
    archiveIdle.setEnabled(false)
    nextTick(async () => {
      await archiveTransition.returnToArchive(reducedMotion)
      if (props.requestedModuleKey
        && props.requestedModuleKey !== focusedKey.value
        && moduleByKey(props.requestedModuleKey, modules)) {
        focusModule(props.requestedModuleKey, 'external')
      }
      archiveIdle.setEnabled(true)
      archiveIdle.activity('return-to-archive')
      nextTick(() => {
        moduleIndexRef.value
          ?.querySelector?.(`[data-module-key="${focusedKey.value}"]`)
          ?.focus?.({ preventScroll: true })
      })
    })
    return
  }
  archiveIdle.setEnabled(true)
  archiveIdle.activity('archive-visible')
})

onMounted(() => {
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  bootTimer = window.setTimeout(() => { booting.value = false }, reduced ? 80 : 900)
  const updateClock = () => { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
  updateClock()
  clockTimer = window.setInterval(updateClock, 1000)
  window.addEventListener('keydown', onKeydown)
  syncSystemStatus()
  scrollActiveIndexIntoView()
  if (props.active) archiveIdle.start()
  if (props.requestedModuleKey && moduleByKey(props.requestedModuleKey, modules)) {
    focusModule(props.requestedModuleKey, 'external')
  } else {
    emit('focus-change', focusedKey.value)
  }
  if (typeof window !== 'undefined') {
    const debug = {}
    Object.defineProperties(debug, {
      focusedKey: { enumerable: true, get: () => focusedKey.value },
      environmentState: { enumerable: true, get: () => environmentState.value },
      sleepAmount: { enumerable: true, get: () => sleepAmount.value },
      extractionProgress: { enumerable: true, get: () => extractionProgress.value },
      transitionState: { enumerable: true, get: () => transitionState.value },
      scene: { enumerable: true, get: () => archiveSceneRef.value?.getDebugState?.() || null },
    })
    window.__jarvisArchiveDebug = debug
  }
})

onBeforeUnmount(() => {
  syncVersion += 1
  if (bootTimer) window.clearTimeout(bootTimer)
  if (clockTimer) window.clearInterval(clockTimer)
  window.removeEventListener('keydown', onKeydown)
  if (typeof window !== 'undefined') delete window.__jarvisArchiveDebug
})
</script>

<template>
  <section
    class="analysis-os"
    :class="{
      'is-idle': environmentState !== 'AWAKE',
      'is-sleeping': environmentState === 'SLEEP_DRIFT',
      'is-extracting': extractionProgress > 0.001,
    }"
    :style="{ '--hud-opacity': 1 - hudDim * 0.45 }"
    aria-label="JARVIS Analysis OS 模块档案终端"
    @pointerdown.capture="archiveIdle.activity('pointer')"
  >
    <div v-if="booting" class="boot-layer" @click="skipBoot">
      <div class="boot-orbit" aria-hidden="true"><span></span><i></i></div>
      <p>JARVIS SYSTEM</p>
      <h1>ANALYSIS OS</h1>
      <div class="boot-line"><i></i></div>
      <small>AUTHENTICATED · MODULE ARCHIVE INITIALIZING</small>
      <button type="button" @click.stop="skipBoot">ENTER SYSTEM</button>
    </div>

    <div class="archive-stage">
      <AnalysisArchiveScene
        ref="archiveSceneRef"
        :modules="modules"
        :focused-key="focusedKey"
        :sleep-amount="sleepAmount"
        :extraction-progress="extractionProgress"
        :active="props.active"
        @focus="handleSceneFocus"
        @activate="activateModule"
        @interaction="archiveIdle.activity"
      />
    </div>

    <header class="terminal-brand" aria-label="JARVIS Analysis OS">
      <h1>JARVIS</h1>
      <div>FINANCIAL RESEARCH</div>
      <p>ANALYSIS <b>OS</b></p>
    </header>

    <section class="module-index-shell" aria-label="Module Index">
      <div class="module-index-label">MODULE INDEX</div>
      <div ref="moduleIndexRef" class="module-index-track" role="tablist" aria-label="系统模块索引">
        <button
          v-for="module in modules"
          :key="module.key"
          type="button"
          role="tab"
          :data-module-key="module.key"
          :aria-selected="module.key === focusedKey"
          :class="{ active: module.key === focusedKey }"
          @click="handleIndexClick(module)"
        >
          <span>{{ String(module.no).padStart(2, '0') }}</span>
          <strong>{{ module.labelEn }}</strong>
          <small>{{ module.labelZh }}</small>
        </button>
      </div>
      <div class="module-index-tools">
        <button type="button" @click="indexOpen = true">⌕ SEARCH</button>
        <button type="button" :disabled="dataState === 'loading'" @click="syncSystemStatus">↻ SYNC</button>
        <span>{{ dataStateLabel }}</span>
        <time>{{ clock }}</time>
      </div>
    </section>

    <p v-if="dataError" class="data-warning">
      DATA CHANNEL INTERRUPTED · {{ dataError }}。模块入口仍可使用。
    </p>

    <section
      v-if="focusedModule"
      class="archive-callout"
      aria-label="当前聚焦模块"
      :style="{ opacity: Math.max(0, .86 - extractionProgress * 1.58) }"
    >
      <p>MODULE / {{ moduleNumber }} <i>/</i> {{ focusedModule.category }}</p>
      <h2>{{ focusedModule.labelEn }}</h2>
      <h3>{{ focusedModule.labelZh }}</h3>
      <div class="callout-rule"><span></span></div>
      <p class="module-summary">{{ focusedModule.summary }}</p>
      <div class="capability-line">
        <span v-for="item in focusedModule.capabilities" :key="item">{{ item }}</span>
      </div>
      <button type="button" @click="activateModule(focusedModule.key)">ACCESS MODULE <span>→</span></button>
    </section>

    <section
      v-if="extractionProgress > 0.01 && focusedModule"
      class="access-sequence"
      aria-live="polite"
      :style="{ opacity: Math.max(.04, 1 - documentRevealAmount * 1.55) }"
    >
      <span>ACCESSING MODULE / {{ moduleNumber }}</span>
      <strong>{{ focusedModule.labelEn }}</strong>
      <small>{{ accessStage.code }}</small>
      <div class="access-progress"><i :style="{ width: `${Math.round(extractionProgress * 100)}%` }"></i></div>
      <p>{{ accessStage.detail }}</p>
    </section>

    <section
      v-if="focusedModule && documentRevealAmount > 0"
      class="document-reveal"
      aria-hidden="true"
      :style="{
        opacity: documentRevealAmount * .86,
        clipPath: `inset(0 ${Math.round((1 - documentRevealAmount) * 100)}% 0 0)`,
      }"
    >
      <header>
        <span>MODULE WORKSPACE / {{ moduleNumber }}</span>
        <strong>{{ focusedModule.labelEn }}</strong>
        <small>{{ focusedModule.labelZh }}</small>
      </header>
      <div class="document-grid">
        <span v-for="item in focusedModule.capabilities" :key="item">{{ item }}</span>
      </div>
      <footer>RESEARCH SURFACE / READY</footer>
    </section>

    <div class="archive-counter" aria-live="polite">
      <span>MODULE ARCHIVE</span>
      <div class="counter-line"><strong>{{ moduleNumber }}</strong><i>/ {{ moduleTotal }}</i></div>
      <div class="archive-navigation">
        <button type="button" aria-label="上一个模块" @click="moveLinear(-1)">←</button>
        <button type="button" aria-label="下一个模块" @click="moveLinear(1)">→</button>
      </div>
    </div>

    <div class="archive-hint" aria-hidden="true">
      <span>DRAG / FREE PLANE</span><i></i><span>WHEEL / ROW</span><i></i><span>CLICK TWICE / ENTER</span>
    </div>

    <div class="column-navigation" aria-label="当前模块列">
      <span>COLUMN {{ String((focusedModule?.lane || 0) + 1).padStart(2, '0') }} / 05</span>
      <strong>{{ currentLane?.label }}</strong>
    </div>

    <section v-if="indexOpen" class="module-directory" aria-label="模块目录">
      <header>
        <div><span>MODULE DIRECTORY</span><strong>{{ filteredModules.length }} / {{ modules.length }}</strong></div>
        <button type="button" aria-label="关闭模块目录" @click="indexOpen = false">×</button>
      </header>
      <label class="module-search">
        <span aria-hidden="true"></span>
        <input v-model="query" type="search" placeholder="搜索模块、能力或分类" autocomplete="off" />
        <kbd>ESC</kbd>
      </label>
      <div class="module-directory-list">
        <button
          v-for="module in filteredModules"
          :key="module.key"
          type="button"
          :class="{ active: module.key === focusedKey }"
          @click="focusModule(module.key); indexOpen = false"
        >
          <span>{{ String(module.no).padStart(2, '0') }}</span>
          <strong>{{ module.labelEn }}</strong>
          <small>{{ module.labelZh }} · {{ module.category }}</small>
        </button>
      </div>
    </section>

    <footer class="system-footer">
      <span><i></i> SESSION AUTHORIZED</span>
      <span>WEBGL / MODULE ARCHIVE / {{ dataStateLabel }} / {{ environmentState }}</span>
      <strong>拖动档案海或使用顶部 MODULE INDEX 选择功能</strong>
    </footer>
    <div class="powered">POWERED BY <b>JARVIS</b><i></i></div>
  </section>
</template>

<style scoped>
.analysis-os {
  --paper: #e8e5e1;
  --ink: #20221d;
  --muted-ink: #77736a;
  --faint-ink: #aaa398;
  --rule: #bcb6ab;
  --accent: #8a7657;
  position: relative; width: 100%; height: 100dvh; min-height: 620px; overflow: hidden;
  color: var(--ink); background: var(--paper);
  font-family: "MiSans", "Mi Sans", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
}
.archive-stage { position: absolute; inset: 0; z-index: 0; }
.boot-layer {
  position: fixed; inset: 0; z-index: 200; display: grid; place-content: center; justify-items: center;
  background: radial-gradient(ellipse at 51% 48%, #ecebe6 0%, #e5e2dc 76%, #e4dfdb 100%);
  color: #171914; cursor: pointer; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.boot-layer p { margin: 22px 0 8px; font-size: 11px; letter-spacing: .24em; }
.boot-layer h1 { margin: 0; font: 300 clamp(35px, 5vw, 68px)/1 system-ui, sans-serif; letter-spacing: .06em; }
.boot-layer small { margin-top: 11px; font-size: 9px; letter-spacing: .15em; color: #716e66; }
.boot-layer button { margin-top: 32px; border: 0; background: transparent; font: inherit; font-size: 9px; letter-spacing: .18em; cursor: pointer; color: #77736a; }
.boot-orbit { position: relative; width: 112px; height: 112px; border: 1px solid #9d9a90; border-radius: 50%; }
.boot-orbit::before { content: ''; position: absolute; inset: 16px; border: 1px solid #c1bdb4; border-radius: 50%; }
.boot-orbit span { position: absolute; left: 50%; top: -8px; width: 1px; height: 128px; background: #8b877e; transform: rotate(36deg); animation: orbit-line 1s cubic-bezier(.22,1,.36,1) both; }
.boot-orbit i { position: absolute; left: 50%; top: 50%; width: 8px; height: 8px; margin: -4px; background: #252720; border-radius: 50%; }
.boot-line { width: min(440px, 62vw); height: 1px; background: #cac5ba; margin-top: 25px; overflow: hidden; }
.boot-line i { display: block; width: 100%; height: 100%; background: #24261f; animation: boot-line 1s ease both; }

.terminal-brand { position: absolute; z-index: 8; left: 42px; top: 33px; width: 218px; line-height: 1; user-select: none; }
.terminal-brand h1 { margin: 0; font-size: 27px; line-height: 30px; letter-spacing: 1.8px; font-weight: 760; }
.terminal-brand > div { font-size: 10px; line-height: 15px; letter-spacing: .6px; font-weight: 620; }
.terminal-brand p { margin: 1px 0 0; width: 158px; display: flex; justify-content: space-between; font-size: 19px; line-height: 24px; }
.terminal-brand p b { font-weight: 760; letter-spacing: 2px; }

.module-index-shell {
  position: absolute; z-index: 12; left: 292px; right: 30px; top: 19px;
  display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 13px;
  border-bottom: 1px solid rgba(112,108,99,.34); padding-bottom: 7px;
}
.terminal-brand, .module-index-shell, .archive-hint, .column-navigation, .system-footer, .powered {
  transition: opacity .55s cubic-bezier(.22,1,.36,1);
}
.analysis-os.is-idle .module-index-shell,
.analysis-os.is-idle .archive-hint,
.analysis-os.is-idle .column-navigation,
.analysis-os.is-idle .system-footer,
.analysis-os.is-idle .powered { opacity: var(--hud-opacity, 1); }
.analysis-os.is-sleeping .archive-hint { opacity: .24; }
.module-index-label { color: #918b80; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .15em; white-space: nowrap; }
.module-index-track { display: flex; min-width: 0; overflow-x: auto; scrollbar-width: none; scroll-behavior: smooth; mask-image: linear-gradient(90deg, transparent, #000 2%, #000 97%, transparent); }
.module-index-track::-webkit-scrollbar { display: none; }
.module-index-track button {
  position: relative; flex: 0 0 auto; min-width: 104px; height: 40px; padding: 3px 12px 5px;
  border: 0; border-left: 1px solid rgba(133,129,120,.22); background: transparent; color: #9b958b;
  text-align: left; cursor: pointer; transition: color .18s ease, background .18s ease;
}
.module-index-track button::after { content: ''; position: absolute; left: 12px; right: 12px; bottom: -8px; height: 1px; background: #6e6049; transform: scaleX(0); transition: transform .2s ease; }
.module-index-track button > span { display: block; font: 600 7px/1 ui-monospace, monospace; color: #b1aba1; }
.module-index-track button strong { display: block; margin-top: 5px; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .075em; white-space: nowrap; }
.module-index-track button small { display: block; margin-top: 3px; font-size: 8px; color: #aaa399; }
.module-index-track button.active { color: #292b25; background: rgba(210,202,189,.14); }
.module-index-track button.active::after { transform: scaleX(1); }
.module-index-track button.active > span, .module-index-track button.active small { color: #706a60; }
.module-index-tools { display: flex; align-items: center; gap: 11px; white-space: nowrap; color: #969087; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .08em; }
.module-index-tools button { border: 0; background: transparent; color: inherit; cursor: pointer; font: inherit; letter-spacing: inherit; }
.module-index-tools button:hover { color: #20221d; }
.module-index-tools button:disabled { opacity: .4; }
.module-index-tools time { color: #4d4c46; }

.data-warning { position: absolute; z-index: 11; right: 30px; top: 75px; margin: 0; max-width: 430px; color: #8b6944; font: 600 7px/1.5 ui-monospace, monospace; text-align: right; letter-spacing: .05em; }
.archive-callout { position: absolute; z-index: 8; left: 55.5%; top: 38.5%; width: min(380px, 31vw); color: #20221d; pointer-events: none; }
.archive-callout > p:first-child { margin: 0 0 10px; color: #77736a; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em; }
.archive-callout > p i { margin: 0 13px; color: #aaa398; font-style: normal; }
.archive-callout h2 { margin: 0; font-size: clamp(23px, 2.15vw, 34px); line-height: .98; font-weight: 650; letter-spacing: -.04em; }
.archive-callout h3 { margin: 5px 0 0; color: #65645d; font-size: 13px; font-weight: 500; }
.callout-rule { position: relative; height: 1px; margin: 18px 0 13px 32px; background: rgba(104,101,94,.7); }
.callout-rule::before { content: ''; position: absolute; left: -32px; top: -2px; width: 4px; height: 4px; background: #30322b; }
.module-summary { margin: 0 0 10px 32px; color: #747068; font-size: 10px; line-height: 1.65; max-width: 310px; }
.capability-line { margin-left: 32px; display: flex; flex-wrap: wrap; gap: 6px 12px; color: #9b958b; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .07em; }
.archive-callout > button { pointer-events: auto; margin: 21px 0 0 32px; border: 0; background: transparent; color: #33352f; padding: 0; font: 650 8px/1 ui-monospace, monospace; letter-spacing: .085em; cursor: pointer; }
.archive-callout > button span { margin-left: 34px; font-size: 14px; vertical-align: -1px; }
.archive-callout > button:hover { color: #8a7657; }
.access-sequence {
  position: absolute; z-index: 13; right: 7%; top: 37%; width: min(330px, 28vw);
  padding: 16px 0; color: #292b25; pointer-events: none;
}
.access-sequence > span { color: #89847a; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.access-sequence strong { display: block; margin-top: 11px; font: 650 clamp(22px, 1.9vw, 31px)/1 ui-monospace, monospace; letter-spacing: -.03em; }
.access-sequence small { display: block; margin-top: 7px; color: #979187; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em; }
.access-progress { height: 1px; margin-top: 20px; background: #c0baaf; overflow: hidden; }
.access-progress i { display: block; height: 100%; background: #34362f; transition: width .06s linear; }
.access-sequence p { margin: 11px 0 0; color: #777269; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .095em; }
.document-reveal {
  position: absolute; z-index: 6; right: 3.5%; top: 18%; width: min(560px, 39vw); height: 58%;
  display: grid; grid-template-rows: auto 1fr auto; padding: 26px 28px 20px;
  border-top: 1px solid rgba(111,106,97,.4); border-bottom: 1px solid rgba(111,106,97,.32);
  background: linear-gradient(90deg, rgba(231,226,217,.12), rgba(239,235,227,.7));
  backdrop-filter: blur(2px); pointer-events: none; transition: opacity .05s linear;
}
.document-reveal header { align-self: start; display: grid; gap: 7px; }
.document-reveal header span { color: #89847a; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.document-reveal header strong { color: #2d2f29; font: 650 24px/1 ui-monospace, monospace; letter-spacing: -.025em; }
.document-reveal header small { color: #747068; font-size: 11px; }
.document-grid { align-self: center; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); border-top: 1px solid rgba(124,119,109,.25); border-left: 1px solid rgba(124,119,109,.25); }
.document-grid span { min-height: 62px; display: grid; place-items: center start; padding: 0 11px; border-right: 1px solid rgba(124,119,109,.25); border-bottom: 1px solid rgba(124,119,109,.25); color: #777269; font: 600 7px/1.3 ui-monospace, monospace; letter-spacing: .08em; }
.document-reveal footer { color: #999287; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em; }
.analysis-os.is-extracting .module-index-shell,
.analysis-os.is-extracting .archive-counter,
.analysis-os.is-extracting .archive-hint,
.analysis-os.is-extracting .column-navigation { opacity: .28; pointer-events: none; }

.archive-counter { position: absolute; z-index: 9; left: 43px; bottom: 72px; opacity: .82; }
.archive-counter > span { color: #89847b; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.counter-line { display: flex; align-items: baseline; gap: 14px; margin-top: 12px; }
.counter-line strong { font-size: 42px; line-height: .9; font-weight: 360; letter-spacing: -1.5px; }
.counter-line i { font-style: normal; color: #989288; font-size: 18px; font-weight: 300; }
.archive-navigation { display: flex; gap: 4px; margin-top: 11px; }
.archive-navigation button { width: 31px; height: 29px; border: 1px solid #c0baaf; color: #706d65; background: transparent; cursor: pointer; font-size: 14px; }
.archive-navigation button:hover { background: #ddd3c4; color: #20221d; }
.archive-hint { position: absolute; z-index: 7; left: 345px; bottom: 44px; display: flex; align-items: center; gap: 11px; color: #918c82; font: 500 8px/1 ui-monospace, monospace; letter-spacing: .11em; pointer-events: none; }
.archive-hint i { width: 20px; height: 1px; background: #bcb6ab; }
.column-navigation { position: absolute; z-index: 7; left: 52%; bottom: 67px; display: grid; gap: 6px; min-width: 220px; }
.column-navigation span { color: #99948a; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .11em; }
.column-navigation strong { color: #44463f; font-size: 11px; font-weight: 500; }

.module-directory { position: absolute; z-index: 40; right: 34px; top: 92px; width: min(820px, 72vw); padding: 22px 24px 24px; background: rgba(239,236,228,.985); border: 1px solid #c7c1b6; box-shadow: 0 30px 100px rgba(83,72,55,.17); }
.module-directory > header { display: flex; justify-content: space-between; align-items: center; }
.module-directory > header div { display: flex; gap: 16px; color: #5e5b54; font: 650 10px/1 ui-monospace, monospace; letter-spacing: .12em; }
.module-directory > header strong { color: #99948a; font-weight: 500; }
.module-directory > header button { width: 30px; height: 30px; border: 0; background: transparent; color: #77736a; font-size: 21px; cursor: pointer; }
.module-search { height: 54px; margin-top: 10px; display: flex; align-items: center; gap: 14px; border-bottom: 1px solid #7e796f; }
.module-search > span { position: relative; width: 17px; height: 17px; border: 1px solid #77736a; border-radius: 50%; }
.module-search > span::after { content: ''; position: absolute; width: 7px; height: 1px; background: #77736a; left: -5px; bottom: -2px; transform: rotate(-45deg); }
.module-search input { min-width: 0; flex: 1; height: 100%; border: 0; outline: 0; background: transparent; color: #20221d; font-size: 13px; }
.module-search kbd { color: #9a958b; font: 600 8px/1 ui-monospace, monospace; }
.module-directory-list { margin-top: 17px; display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); border-top: 1px solid #d0cabf; border-left: 1px solid #d0cabf; }
.module-directory-list button { min-width: 0; min-height: 76px; padding: 12px 13px; border: 0; border-right: 1px solid #d0cabf; border-bottom: 1px solid #d0cabf; background: transparent; color: #6b675f; text-align: left; cursor: pointer; }
.module-directory-list button:hover, .module-directory-list button.active { background: #dcd2c3; color: #20221d; }
.module-directory-list button > span { display: block; color: #99948a; font: 600 8px/1 ui-monospace, monospace; }
.module-directory-list strong { display: block; margin-top: 7px; font: 650 11px/1.2 ui-monospace, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.module-directory-list small { display: block; margin-top: 6px; color: #948f85; font-size: 9px; }

.system-footer { position: absolute; z-index: 7; left: 49px; right: 44px; bottom: 21px; display: flex; align-items: center; gap: 22px; color: #9a958b; font: 500 8px/1 ui-monospace, monospace; letter-spacing: .08em; pointer-events: none; }
.system-footer strong { margin-left: auto; color: #7c786f; font-weight: 500; }
.system-footer > span:first-child i { display: inline-block; width: 4px; height: 4px; margin-right: 8px; background: #8b8f75; vertical-align: 1px; }
.powered { position: absolute; z-index: 8; right: 40px; bottom: 49px; display: flex; align-items: center; gap: 5px; color: #67655e; font-size: 10px; }
.powered b { color: #20221d; font-weight: 760; }
.powered i { display: inline-block; width: 18px; height: 3px; margin-left: 8px; background: #34362f; }

@keyframes boot-line { from { transform: translateX(-100%); } to { transform: translateX(0); } }
@keyframes orbit-line { from { opacity: 0; transform: rotate(-70deg) scaleY(.2); } to { opacity: 1; transform: rotate(36deg) scaleY(1); } }

@media (max-width: 1100px) {
  .terminal-brand { left: 28px; top: 28px; transform: scale(.82); transform-origin: top left; }
  .module-index-shell { left: 250px; right: 24px; }
  .module-index-tools > span, .module-index-tools time { display: none; }
  .archive-callout { left: 48%; top: 39%; width: 46vw; }
  .archive-counter { left: 30px; }
  .archive-hint { left: 275px; }
  .column-navigation { left: 49%; }
  .system-footer { left: 30px; right: 28px; }
}

@media (max-width: 700px) {
  .analysis-os { min-height: 100dvh; }
  .terminal-brand { left: 18px; top: 16px; transform: scale(.58); }
  .module-index-shell { left: 0; right: 0; top: auto; bottom: 0; grid-template-columns: 1fr; gap: 0; padding: 0 0 env(safe-area-inset-bottom); background: rgba(232,229,225,.94); border-top: 1px solid #c3bdb2; border-bottom: 0; }
  .module-index-label, .module-index-tools { display: none; }
  .module-index-track { width: 100%; }
  .module-index-track button { min-width: 102px; height: 54px; padding: 7px 12px; }
  .module-index-track button::after { bottom: 0; }
  .archive-callout { left: 18px; right: 18px; top: 23%; width: auto; }
  .archive-callout h2 { font-size: 30px; }
  .module-summary { max-width: 330px; }
  .archive-counter { left: 18px; bottom: 77px; transform: scale(.72); transform-origin: bottom left; }
  .archive-hint, .column-navigation, .system-footer, .powered { display: none; }
  .module-directory { left: 14px; right: 14px; top: 70px; width: auto; max-height: 74vh; overflow: auto; padding: 17px; }
  .module-directory-list { grid-template-columns: 1fr 1fr; }
  .data-warning { left: 18px; right: 18px; top: 86px; max-width: none; text-align: left; }
  .access-sequence { left: 18px; right: 18px; top: 28%; width: auto; }
  .document-reveal { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; scroll-behavior: auto !important; }
}
</style>
