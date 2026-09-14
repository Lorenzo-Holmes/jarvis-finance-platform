import test from 'node:test'
import assert from 'node:assert/strict'

import {
  canonicalPoolCoordinate,
  nearestPeriodicCoordinate,
  poolKeyForCell,
} from '../src/analysis-os/motion/archiveLoop.js'

test('archive loop maps arbitrarily distant coordinates back into the finite render pool', () => {
  assert.equal(canonicalPoolCoordinate(10_000, -2, 9), 1)
  assert.equal(canonicalPoolCoordinate(-10_000, -2, 9), -1)
  assert.equal(canonicalPoolCoordinate(10_000, 0, 25), 0)
  assert.equal(canonicalPoolCoordinate(-10_000, 0, 25), 0)
})

test('nearest periodic archive copies remain centered around the live camera track', () => {
  for (const center of [-10_000.4, -123.7, 12, 258.25, 10_000.8]) {
    const lane = nearestPeriodicCoordinate(-2, center, 9)
    const row = nearestPeriodicCoordinate(0, center, 25)
    assert.ok(Math.abs(lane - center) <= 4.5)
    assert.ok(Math.abs(row - center) <= 12.5)
  }
})

test('logical cells separated by full pool periods reuse the same physical archive slot', () => {
  const options = { laneMin: -2, laneCount: 9, rowMin: 0, rowCount: 25 }
  const base = poolKeyForCell(2, 12, options)
  assert.equal(poolKeyForCell(2 + 9 * 37, 12 + 25 * 19, options), base)
  assert.equal(poolKeyForCell(2 - 9 * 41, 12 - 25 * 23, options), base)
})

test('periodic remapping always produces gap-free lane and row coverage around the live center', () => {
  const centers = [-10_000.5, -117.25, 0, 12.4, 999.9, 10_000.5]
  for (const center of centers) {
    const lanes = Array.from({ length: 9 }, (_, index) => -2 + index)
      .map(seed => nearestPeriodicCoordinate(seed, center, 9))
      .sort((a, b) => a - b)
    const rows = Array.from({ length: 25 }, (_, index) => index)
      .map(seed => nearestPeriodicCoordinate(seed, center, 25))
      .sort((a, b) => a - b)

    assert.equal(new Set(lanes).size, 9)
    assert.equal(new Set(rows).size, 25)
    for (let index = 1; index < lanes.length; index += 1) assert.equal(lanes[index] - lanes[index - 1], 1)
    for (let index = 1; index < rows.length; index += 1) assert.equal(rows[index] - rows[index - 1], 1)
  }
})
