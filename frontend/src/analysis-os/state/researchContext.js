import { readonly, ref } from 'vue'

const context = ref(null)

function normalize(value) {
  if (!value || typeof value !== 'object') return null
  const symbol = String(value.symbol || '').trim()
  const name = String(value.name || '').trim()
  const market = String(value.market || '').trim()
  if (!symbol && !name) return null
  return {
    market: market || undefined,
    symbol: symbol || undefined,
    name: name || symbol || undefined,
    entityId: value.entityId || undefined,
    sourceModule: value.sourceModule || undefined,
    updatedAt: Number(value.updatedAt) || Date.now(),
  }
}

export function useResearchContext() {
  function setContext(value) {
    const next = normalize(value)
    if (!next) return
    context.value = next
  }

  function clearContext() {
    context.value = null
  }

  return {
    context: readonly(context),
    setContext,
    clearContext,
  }
}
