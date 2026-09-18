const KIND_ORDER = Object.freeze({
  entity: 0,
  workspace: 1,
  action: 2,
})

export const COMMAND_GROUP_LABELS = Object.freeze({
  entity: '研究对象',
  workspace: '工作区',
  action: '操作',
})

export function searchCommandItems(items, query) {
  const normalizedQuery = normalize(query)
  const normalizedItems = (Array.isArray(items) ? items : [])
    .map((item, index) => ({ item, index, score: scoreCommandItem(item, normalizedQuery) }))
    .filter(entry => !normalizedQuery || entry.score > 0)

  normalizedItems.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    const kindDelta = kindOrder(a.item.kind) - kindOrder(b.item.kind)
    if (kindDelta !== 0) return kindDelta
    return a.index - b.index
  })

  return normalizedItems.map(entry => entry.item)
}

export function groupCommandItems(items) {
  const groups = new Map()
  for (const item of Array.isArray(items) ? items : []) {
    const key = item?.kind || 'workspace'
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        label: COMMAND_GROUP_LABELS[key] || '结果',
        items: [],
      })
    }
    groups.get(key).items.push(item)
  }
  return [...groups.values()].sort((a, b) => kindOrder(a.key) - kindOrder(b.key))
}

function scoreCommandItem(item, normalizedQuery) {
  if (!normalizedQuery) return 1

  const label = normalize(item?.label)
  const summary = normalize(item?.summary)
  const meta = normalize(item?.meta)
  const keywords = (Array.isArray(item?.keywords) ? item.keywords : [])
    .map(normalize)
    .filter(Boolean)

  if (label === normalizedQuery) return 120
  if (keywords.includes(normalizedQuery)) return 110
  if (label.startsWith(normalizedQuery)) return 96
  if (keywords.some(value => value.startsWith(normalizedQuery))) return 84
  if (label.includes(normalizedQuery)) return 72
  if (keywords.some(value => value.includes(normalizedQuery))) return 62
  if (meta.includes(normalizedQuery)) return 48
  if (summary.includes(normalizedQuery)) return 36
  return 0
}

function normalize(value) {
  return String(value || '').trim().toLowerCase()
}

function kindOrder(kind) {
  return KIND_ORDER[kind] ?? 99
}
