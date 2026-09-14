import { readonly, ref } from 'vue'

const backtestHandoff = ref(null)

export function useWorkflowHandoff() {
  function setBacktestHandoff(value) {
    if (!value || typeof value !== 'object') return
    backtestHandoff.value = {
      ...value,
      createdAt: Number(value.createdAt) || Date.now(),
    }
  }

  function clearBacktestHandoff() {
    backtestHandoff.value = null
  }

  return {
    backtestHandoff: readonly(backtestHandoff),
    setBacktestHandoff,
    clearBacktestHandoff,
  }
}
