import { computed, ref, watch } from 'vue'

const DEFAULT_TAB = '研究终端'
const BASE_TABS = [DEFAULT_TAB, '行情', '多市场', '回测', '模拟盘', '研究助手', '智能报价', '多空研报', '财报解析', '产业链图谱', '风险预警', '策略生成', '市场趋势预测', 'RSS资讯', '社区', '个人中心', '运维', '定时任务']
const ADMIN_TAB = '管理后台'

export function useWorkspaceTabs(userRef) {
  const activeTab = ref(DEFAULT_TAB)
  const visitedTabs = ref(new Set([DEFAULT_TAB]))
  const workspaceTabs = ref([])
  const tabs = computed(() => userRef.value?.role === 'ADMIN' ? [...BASE_TABS, ADMIN_TAB] : BASE_TABS)
  let restoringSession = false

  function sessionKey() {
    const user = userRef.value
    const identity = user?.id || user?.email || user?.username
    return identity ? `jarvis-workspace-session:${identity}` : ''
  }

  function persistSession() {
    if (restoringSession || typeof window === 'undefined') return
    const key = sessionKey()
    if (!key) return
    try {
      window.sessionStorage.setItem(key, JSON.stringify({
        activeTab: activeTab.value,
        workspaceTabs: workspaceTabs.value,
      }))
    } catch (_) {
      // Session restore is progressive enhancement; storage failure must not block navigation.
    }
  }

  function restoreSession() {
    if (typeof window === 'undefined') return
    const key = sessionKey()
    if (!key) return
    let saved = null
    try {
      saved = JSON.parse(window.sessionStorage.getItem(key) || 'null')
    } catch (_) {
      return
    }
    if (!saved || typeof saved !== 'object') return
    const allowed = new Set(tabs.value)
    const restoredTabs = Array.isArray(saved.workspaceTabs)
      ? saved.workspaceTabs.filter(name => allowed.has(name) && name !== DEFAULT_TAB)
      : []
    const restoredActive = allowed.has(saved.activeTab) ? saved.activeTab : DEFAULT_TAB

    restoringSession = true
    workspaceTabs.value = [...new Set(restoredTabs)]
    activeTab.value = restoredActive
    visitedTabs.value = new Set([DEFAULT_TAB, restoredActive, ...workspaceTabs.value])
    restoringSession = false
  }

  function reset() {
    activeTab.value = DEFAULT_TAB
    visitedTabs.value = new Set([DEFAULT_TAB])
    workspaceTabs.value = []
  }

  function switchTab(name) {
    if (!tabs.value.includes(name)) return
    activeTab.value = name
    if (!visitedTabs.value.has(name)) {
      const next = new Set(visitedTabs.value)
      next.add(name)
      visitedTabs.value = next
    }
    if (name !== DEFAULT_TAB && !workspaceTabs.value.includes(name)) {
      workspaceTabs.value = [...workspaceTabs.value, name]
    }
  }

  function closeWorkspaceTab(name) {
    const index = workspaceTabs.value.indexOf(name)
    if (index < 0) return
    const remaining = workspaceTabs.value.filter(item => item !== name)
    workspaceTabs.value = remaining
    if (activeTab.value !== name) return

    const fallback = remaining[index - 1]
      || remaining[index]
      || remaining[remaining.length - 1]
      || DEFAULT_TAB
    activeTab.value = fallback
    if (!visitedTabs.value.has(fallback)) {
      const next = new Set(visitedTabs.value)
      next.add(fallback)
      visitedTabs.value = next
    }
  }

  watch(tabs, available => {
    if (!available.includes(activeTab.value)) reset()
  })

  watch(() => userRef.value?.id || userRef.value?.email || userRef.value?.username || '', identity => {
    if (!identity) return
    restoreSession()
  }, { immediate: true })

  watch([activeTab, workspaceTabs], persistSession, { deep: true })

  return {
    activeTab,
    visitedTabs,
    workspaceTabs,
    tabs,
    switchTab,
    closeWorkspaceTab,
    reset,
  }
}
