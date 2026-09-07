export function formatNumber(value, minDigits = 2, maxDigits = 2) {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '-'
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: minDigits,
    maximumFractionDigits: maxDigits,
  })
}

export function formatPercent(value, digits = 2) {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '-'
  return `${Number(value).toFixed(digits)}%`
}

export function formatDateTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}
