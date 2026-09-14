function positiveModulo(value, period) {
  const size = Math.max(1, Math.abs(Number(period) || 1))
  return ((value % size) + size) % size
}

export function canonicalPoolCoordinate(value, minimum, count) {
  const rounded = Math.round(Number(value) || 0)
  const size = Math.max(1, Math.round(Number(count) || 1))
  return minimum + positiveModulo(rounded - minimum, size)
}

export function nearestPeriodicCoordinate(seed, center, period) {
  const size = Math.max(1, Math.abs(Number(period) || 1))
  const origin = Number(seed) || 0
  const focus = Number(center) || 0
  return origin + Math.round((focus - origin) / size) * size
}

export function poolKeyForCell(lane, row, options) {
  const laneSeed = canonicalPoolCoordinate(lane, options.laneMin, options.laneCount)
  const rowSeed = canonicalPoolCoordinate(row, options.rowMin, options.rowCount)
  return `${laneSeed}:${rowSeed}`
}
