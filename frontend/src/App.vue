<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, ref, watch } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'
import AppHeader from './components/common/AppHeader.vue'
import AppTabs from './components/common/AppTabs.vue'
import LandingPage from './pages/LandingPage.vue'
import { useAuthSession } from './composables/useAuthSession'
import { useWorkspaceTabs } from './composables/useWorkspaceTabs'
import ArchiveWorkspaceShell from './analysis-os/components/ArchiveWorkspaceShell.vue'
import { JARVIS_MODULES } from './analysis-os/data/modules'
import { useResearchContext } from './analysis-os/state/researchContext'
import { useWorkflowHandoff } from './analysis-os/state/workflowHandoff'

const AnalysisOsPage = defineAsyncComponent(() => import('./pages/AnalysisOsPage.vue'))
const MarketPage = defineAsyncComponent(() => import('./pages/MarketPage.vue'))
const BacktestPage = defineAsyncComponent(() => import('./pages/BacktestPage.vue'))
const CrossMarketView = defineAsyncComponent(() => import('./components/CrossMarketView.vue'))
const SimTradeView = defineAsyncComponent(() => import('./components/SimTradeView.vue'))
const AiCenter = defineAsyncComponent(() => import('./components/AiCenter.vue'))
const SentimentPage = defineAsyncComponent(() => import('./pages/SentimentPage.vue'))
const FinancialReportPage = defineAsyncComponent(() => import('./pages/FinancialReportPage.vue'))
const ChainPage = defineAsyncComponent(() => import('./pages/ChainPage.vue'))
const RiskPage = defineAsyncComponent(() => import('./pages/RiskPage.vue'))
const StrategyPage = defineAsyncComponent(() => import('./pages/StrategyPage.vue'))
const QuotePage = defineAsyncComponent(() => import('./pages/QuotePage.vue'))
const TrendPage = defineAsyncComponent(() => import('./pages/TrendPage.vue'))
const OpsView = defineAsyncComponent(() => import('./components/OpsView.vue'))
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))

const session = useAuthSession()
const { user: sessionUser, isLoggedIn: sessionLoggedIn, sessionState } = session
const previewMode = ref(false)
const NIGHT_MODE_KEY = 'jarvis-ui-night-mode'
function readNightModePreference() {
  try {
    return window.localStorage.getItem(NIGHT_MODE_KEY) === 'true'
  } catch (_) {
    return false
  }
}
const nightMode = ref(readNightModePreference())

function toggleNightMode() {
  nightMode.value = !nightMode.value
  try {
    window.localStorage.setItem(NIGHT_MODE_KEY, String(nightMode.value))
  } catch (_) {
    // 当前会话仍可切换主题；存储受限时不阻塞界面。
  }
}

const LOCAL_PREVIEW_USER = Object.freeze({
  id: -1,
  email: 'preview@local.test',
  displayName: 'Local Preview',
  role: 'USER',
  preview: true,
})
const user = computed(() => previewMode.value ? LOCAL_PREVIEW_USER : sessionUser.value)
const isLoggedIn = computed(() => previewMode.value || sessionLoggedIn.value)
const workspace = useWorkspaceTabs(user)
const { activeTab, visitedTabs, tabs, switchTab } = workspace
const publicView = ref('landing')
const activeModule = computed(() => JARVIS_MODULES.find(module => module.routeKey === activeTab.value) || null)
const archiveModuleKey = ref('market')
const research = useResearchContext()
const { context: researchContext, setContext: setResearchContext, clearContext: clearResearchContext } = research
const workflow = useWorkflowHandoff()
const { backtestHandoff, setBacktestHandoff, clearBacktestHandoff } = workflow

function replacePublicQuery(mutator) {
  const url = new URL(window.location.href)
  mutator(url.searchParams)
  const query = url.searchParams.toString()
  window.history.replaceState(window.history.state, document.title, `${url.pathname}${query ? `?${query}` : ''}${url.hash}`)
}

function showLanding() {
  publicView.value = 'landing'
  replacePublicQuery((params) => params.delete('view'))
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function showLogin() {
  publicView.value = 'login'
  replacePublicQuery((params) => params.set('view', 'login'))
  window.scrollTo({ top: 0, behavior: 'auto' })
}

function clearOAuthQuery() {
  replacePublicQuery((params) => params.delete('oauth'))
}

function handleLoggedIn(value) {
  session.acceptLogin(value)
  workspace.reset()
}

async function logout() {
  if (previewMode.value) {
    previewMode.value = false
    workspace.reset()
    clearResearchContext()
    clearBacktestHandoff()
    publicView.value = 'landing'
    replacePublicQuery((params) => {
      params.delete('preview')
      params.delete('view')
    })
    return
  }
  await session.logout()
  workspace.reset()
  clearResearchContext()
  clearBacktestHandoff()
  publicView.value = 'landing'
  replacePublicQuery((params) => params.delete('view'))
}

async function updateProfile(displayName) {
  const response = await api.updateProfile(displayName)
  if (response.code === 200 && response.data) session.acceptLogin(response.data)
}

function syncArchiveModule(key) {
  if (JARVIS_MODULES.some(module => module.key === key)) archiveModuleKey.value = key
}

function navigateWorkspace(routeKey) {
  const module = JARVIS_MODULES.find(item => item.routeKey === routeKey)
  if (module) archiveModuleKey.value = module.key
  switchTab(routeKey)
}

function openLegacyAdmin() {
  if (user.value?.role !== 'ADMIN') return
  switchTab('管理')
}

function returnToArchive() {
  if (activeModule.value) archiveModuleKey.value = activeModule.value.key
  switchTab('研究终端')
}

function sendStrategyToBacktest(handoff) {
  setBacktestHandoff(handoff)
  navigateWorkspace('回测')
}

watch(sessionState, (state) => {
  if (previewMode.value) return
  if (state !== 'expired') return
  publicView.value = 'login'
  replacePublicQuery((params) => params.set('view', 'login'))
})

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const host = window.location.hostname
  const localPreview = import.meta.env.DEV
    && (host === '127.0.0.1' || host === 'localhost')
    && params.get('preview') === '1'
  if (localPreview) {
    previewMode.value = true
    workspace.reset()
    replacePublicQuery((query) => {
      query.delete('view')
      query.delete('oauth')
      query.set('preview', '1')
    })
    return
  }
  const hasOAuthResult = params.has('oauth')
  const wantsLogin = params.get('view') === 'login'
  if (hasOAuthResult || wantsLogin) publicView.value = 'login'
  session.restore()
  // 等 LoginView 读取完 OAuth 结果后再清理地址栏，避免返回官网后重复显示旧错误。
  if (hasOAuthResult) nextTick(clearOAuthQuery)
})
</script>

<template>
  <LandingPage v-if="!isLoggedIn && publicView === 'landing'" @login="showLogin" />

  <div v-else-if="!isLoggedIn" class="auth-shell">
    <button type="button" class="home-back" @click="showLanding">← 返回官网</button>
    <LoginView @logged-in="handleLoggedIn" />
  </div>

  <div
    v-else
    class="container"
    :class="{
      'container--trading': activeTab === '模拟盘',
      'container--analysis': activeTab === '研究终端',
      'container--workspace': Boolean(activeModule),
    }"
  >
    <AppHeader v-if="activeTab === '管理'" :user="user" @logout="logout" @update-profile="updateProfile" />
    <AppTabs v-if="activeTab === '管理'" :tabs="tabs" :active="activeTab" @change="switchTab" />

    <AnalysisOsPage
      v-if="visitedTabs.has('研究终端')"
      v-show="activeTab === '研究终端'"
      :active="activeTab === '研究终端'"
      :requested-module-key="archiveModuleKey"
      @focus-change="syncArchiveModule"
      @navigate="navigateWorkspace"
    />

    <ArchiveWorkspaceShell
      v-if="activeModule"
      :module="activeModule"
      :modules="JARVIS_MODULES"
      :user="user"
      :context="researchContext"
      :night-mode="nightMode"
      @return="returnToArchive"
      @navigate-module="navigateWorkspace"
      @legacy-admin="openLegacyAdmin"
      @logout="logout"
      @update-profile="updateProfile"
      @toggle-night-mode="toggleNightMode"
    >
      <MarketPage v-if="activeTab === '行情'" :active="true" @context-change="setResearchContext" />

      <section v-else-if="activeTab === '多市场'" class="panel-wrap">
        <CrossMarketView :user="user" @context-change="setResearchContext" />
      </section>

      <BacktestPage
        v-else-if="activeTab === '回测'"
        :active="true"
        :handoff="backtestHandoff"
        @clear-handoff="clearBacktestHandoff"
      />

      <section v-else-if="activeTab === '模拟盘'">
        <SimTradeView :user="user" @context-change="setResearchContext" />
      </section>

      <section v-else-if="activeTab === '研究助手'" class="panel-wrap">
        <AiCenter :research-context="researchContext" />
      </section>

      <QuotePage v-else-if="activeTab === '智能报价'" />
      <SentimentPage v-else-if="activeTab === '多空研报'" />
      <FinancialReportPage v-else-if="activeTab === '财报解析'" :research-context="researchContext" />
      <ChainPage v-else-if="activeTab === '产业链图谱'" :research-context="researchContext" />
      <RiskPage v-else-if="activeTab === '风险预警'" :research-context="researchContext" />
      <StrategyPage v-else-if="activeTab === '策略生成'" @send-backtest="sendStrategyToBacktest" />
      <TrendPage v-else-if="activeTab === '市场趋势预测'" />

      <section v-else-if="activeTab === '运维'" class="panel-wrap">
        <OpsView />
      </section>
    </ArchiveWorkspaceShell>

    <section v-if="user?.role === 'ADMIN' && activeTab === '管理'" class="panel-wrap">
      <AdminView />
    </section>

    <footer v-if="activeTab === '管理'" class="foot">
      <span>贾维斯金融投研平台 · 仅供研究参考，不构成投资建议</span>
    </footer>
  </div>
</template>

<style scoped>
.container { max-width: 1580px; margin: 0 auto; padding: 0 20px 32px; }
.container--trading { max-width: none; padding-left: 12px; padding-right: 12px; padding-bottom: 12px; }
.container--analysis { max-width: none; padding: 0; }
.container--workspace { max-width: none; padding: 0; }
.panel-wrap { margin-top: 4px; }
.foot { color: var(--subtle); font-size: 11px; margin-top: 16px; }
.auth-shell { position: relative; min-height: 100vh; background: var(--bg); }
.home-back {
  position: fixed; top: 18px; left: 20px; z-index: 20;
  border: 1px solid var(--line); border-radius: 999px;
  color: var(--muted); background: rgba(18, 20, 22, .86);
  padding: 8px 13px; cursor: pointer; font-size: 12px;
  backdrop-filter: blur(10px);
  transition: color .15s ease, border-color .15s ease, background .15s ease;
}
.home-back:hover { color: var(--text); border-color: var(--line-strong); background: #1c1f22; }
@media (max-width: 620px) {
  .container { padding-left: 12px; padding-right: 12px; }
  .home-back { top: 12px; left: 12px; }
}
</style>
