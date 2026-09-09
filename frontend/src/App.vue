<script setup>
import { defineAsyncComponent, onMounted } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'
import AppHeader from './components/common/AppHeader.vue'
import AppTabs from './components/common/AppTabs.vue'
import { useAuthSession } from './composables/useAuthSession'
import { useWorkspaceTabs } from './composables/useWorkspaceTabs'

const MarketPage = defineAsyncComponent(() => import('./pages/MarketPage.vue'))
const BacktestPage = defineAsyncComponent(() => import('./pages/BacktestPage.vue'))
const CrossMarketView = defineAsyncComponent(() => import('./components/CrossMarketView.vue'))
const SimTradeView = defineAsyncComponent(() => import('./components/SimTradeView.vue'))
const AiCenter = defineAsyncComponent(() => import('./components/AiCenter.vue'))
const SentimentPage = defineAsyncComponent(() => import('./pages/SentimentPage.vue'))
const OpsView = defineAsyncComponent(() => import('./components/OpsView.vue'))
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))

const session = useAuthSession()
const { user, isLoggedIn } = session
const workspace = useWorkspaceTabs(user)
const { activeTab, visitedTabs, tabs, switchTab } = workspace

function handleLoggedIn(value) {
  session.acceptLogin(value)
  workspace.reset()
}

async function logout() {
  await session.logout()
  workspace.reset()
}

async function updateProfile(displayName) {
  const response = await api.updateProfile(displayName)
  if (response.code === 200 && response.data) session.acceptLogin(response.data)
}

onMounted(session.restore)
</script>

<template>
  <LoginView v-if="!isLoggedIn" @logged-in="handleLoggedIn" />

  <div v-else class="container">
    <AppHeader :user="user" @logout="logout" @update-profile="updateProfile" />

    <div class="workspace-shell">
      <AppTabs :tabs="tabs" :active="activeTab" @change="switchTab" />

      <div class="workspace-main">
        <MarketPage v-if="visitedTabs.has('行情')" v-show="activeTab === '行情'" :active="activeTab === '行情'" />

        <section v-if="activeTab === '多市场'" class="panel-wrap">
          <CrossMarketView :user="user" />
        </section>

        <BacktestPage v-if="visitedTabs.has('回测')" v-show="activeTab === '回测'" :active="activeTab === '回测'" />

        <section v-if="activeTab === '模拟盘'">
          <SimTradeView />
        </section>

        <section v-if="activeTab === '研究助手'" class="panel-wrap">
          <AiCenter />
        </section>

        <SentimentPage v-if="visitedTabs.has('多空研报')" v-show="activeTab === '多空研报'" />

        <section v-if="activeTab === '运维'" class="panel-wrap">
          <OpsView />
        </section>

        <section v-if="user?.role === 'ADMIN' && activeTab === '管理'" class="panel-wrap">
          <AdminView />
        </section>

        <footer class="foot">
          <span>JARVIS Research Terminal · 仅供研究参考，不构成投资建议</span>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.container { max-width: 1680px; margin: 0 auto; padding: 0 22px 32px; }
.workspace-shell { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 18px; align-items: start; }
.workspace-main { min-width: 0; }
.panel-wrap { margin-top: 4px; }
.foot { color: var(--subtle); font-size: 10px; margin-top: 20px; padding-top: 12px; border-top: 1px solid var(--line); letter-spacing: .02em; }
@media (max-width: 1080px) { .workspace-shell { grid-template-columns: minmax(0, 1fr); gap: 0; } }
@media (max-width: 620px) { .container { padding-left: 12px; padding-right: 12px; } }
</style>
