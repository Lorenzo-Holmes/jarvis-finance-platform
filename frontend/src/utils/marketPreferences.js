const MARKET_VALUES = new Set(['a_share', 'us_stock', 'crypto'])
const SYMBOL_PATTERN = /^[A-Za-z0-9._-]{1,20}$/
const INSTRUMENT_KEY_PATTERN = /^(a_share|us_stock|crypto):[A-Za-z0-9._-]{1,20}$/
const MAX_WATCHLIST_ITEMS = 100
const MAX_HIDDEN_DEFAULTS = 100

export const MARKET_PREFERENCES_VERSION = 1

export function marketPreferencesKey(user) {
  const identity = String(user?.id ?? user?.email ?? 'anonymous').trim().toLowerCase() || 'anonymous'
  return `jarvis.market.preferences.v${MARKET_PREFERENCES_VERSION}:${encodeURIComponent(identity)}`
}

export function normalizeSavedInstrument(value) {
  if (!value || !MARKET_VALUES.has(String(value.market))) return null
  const symbol = String(value.symbol || '').trim()
  if (!SYMBOL_PATTERN.test(symbol)) return null
  const name = String(value.name || symbol).trim().slice(0, 80) || symbol
  return {
    market: String(value.market),
    symbol,
    name,
    currency: String(value.currency || '').trim().slice(0, 12),
    source: String(value.source || '').trim().slice(0, 40),
  }
}

export function normalizeMarketPreferences(raw) {
  const seen = new Set()
  const watchlist = (Array.isArray(raw?.watchlist) ? raw.watchlist : [])
    .map(normalizeSavedInstrument)
    .filter(item => {
      if (!item) return false
      const itemKey = `${item.market}:${item.symbol}`
      if (seen.has(itemKey)) return false
      seen.add(itemKey)
      return true
    })
    .slice(0, MAX_WATCHLIST_ITEMS)
  const hiddenDefaultKeys = (Array.isArray(raw?.hiddenDefaultKeys) ? raw.hiddenDefaultKeys : [])
    .map(item => String(item).trim())
    .filter(item => INSTRUMENT_KEY_PATTERN.test(item))
    .slice(0, MAX_HIDDEN_DEFAULTS)
  return { watchlist, hiddenDefaultKeys: [...new Set(hiddenDefaultKeys)] }
}

export function hasMarketPreferences(preferences) {
  return Boolean(preferences?.watchlist?.length || preferences?.hiddenDefaultKeys?.length)
}

export function readMarketPreferences(storage, key) {
  if (!storage?.getItem) return { watchlist: [], hiddenDefaultKeys: [] }
  try {
    return normalizeMarketPreferences(JSON.parse(storage.getItem(key) || '{}'))
  } catch (_) {
    return { watchlist: [], hiddenDefaultKeys: [] }
  }
}

export function writeMarketPreferences(storage, key, preferences) {
  if (!storage?.setItem) return false
  try {
    storage.setItem(key, JSON.stringify({ version: MARKET_PREFERENCES_VERSION, ...normalizeMarketPreferences({
      ...preferences,
    })
    }))
    return true
  } catch (_) {
    return false
  }
}
