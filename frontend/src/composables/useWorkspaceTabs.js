import { computed, ref, watch } from 'vue'

const DEFAULT_TAB = '研究终端'
const BASE_TABS = [DEFAULT_TAB, '行情', '多市场', '回测', '模拟盘', '研究助手', '智能报价', '多空研报', '财报解析', '产业链图谱', '风险预警', '策略生成', '市场趋势预测', '运维']

export function useWorkspaceTabs(userRef) {
  const activeTab = ref(DEFAULT_TAB)
  const visitedTabs = ref(new Set([DEFAULT_TAB]))
  const tabs = computed(() => userRef.value?.role === 'ADMIN' ? [...BASE_TABS, '管理'] : BASE_TABS)

  function reset() {
    activeTab.value = DEFAULT_TAB
    visitedTabs.value = new Set([DEFAULT_TAB])
  }

  function switchTab(name) {
    if (!tabs.value.includes(name)) return
    activeTab.value = name
    if (!visitedTabs.value.has(name)) {
      const next = new Set(visitedTabs.value)
      next.add(name)
      visitedTabs.value = next
    }
  }

  watch(tabs, available => {
    if (!available.includes(activeTab.value)) reset()
  })

  return {
    activeTab,
    visitedTabs,
    tabs,
    switchTab,
    reset,
  }
}
