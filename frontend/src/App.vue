<script setup>
import { defineAsyncComponent, onMounted } from 'vue'
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

onMounted(session.restore)
</script>

<template>
  <div class="container">
    <AppHeader :user="user" @logout="logout" />

    <LoginView v-if="!isLoggedIn" @logged-in="handleLoggedIn" />

    <template v-else>
      <AppTabs :tabs="tabs" :active="activeTab" @change="switchTab" />

      <MarketPage v-if="visitedTabs.has('行情')" v-show="activeTab === '行情'" :active="activeTab === '行情'" />

      <section v-if="activeTab === '多市场'" class="panel-wrap">
        <CrossMarketView />
      </section>

      <BacktestPage v-if="visitedTabs.has('回测')" v-show="activeTab === '回测'" :active="activeTab === '回测'" />

      <section v-if="activeTab === '模拟盘'">
        <SimTradeView />
      </section>

      <section v-if="activeTab === '研究助手'" class="panel-wrap">
        <AiCenter />
      </section>

      <section v-if="activeTab === '运维'" class="panel-wrap">
        <OpsView />
      </section>

      <section v-if="user?.role === 'ADMIN' && activeTab === '管理'" class="panel-wrap">
        <AdminView />
      </section>

      <footer class="foot">
        <span>贾维斯金融投研平台 · 仅供研究参考，不构成投资建议</span>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.container { max-width: 1580px; margin: 0 auto; padding: 0 20px 32px; }
.panel-wrap { margin-top: 4px; }
.foot { color: var(--subtle); font-size: 11px; margin-top: 16px; }
@media (max-width: 620px) { .container { padding-left: 12px; padding-right: 12px; } }
</style>
