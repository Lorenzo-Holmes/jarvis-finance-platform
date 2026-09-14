import test from 'node:test'
import assert from 'node:assert/strict'

import {
  LOOP_POOL,
  canonicalPoolCoordinate,
  nearestPeriodicCoordinate,
  poolKeyForCell,
} from '../src/analysis-os/motion/archiveLoop.js'

test('archive loop maps arbitrarily distant coordinates back into the finite render pool', () => {
  assert.equal(canonicalPoolCoordinate(10_000, LOOP_POOL.laneMin, LOOP_POOL.laneCount), 1)
  assert.equal(canonicalPoolCoordinate(-10_000, LOOP_POOL.laneMin, LOOP_POOL.laneCount), -1)
  assert.equal(canonicalPoolCoordinate(10_000, LOOP_POOL.rowMin, LOOP_POOL.rowCount), 25)
  assert.equal(canonicalPoolCoordinate(-10_000, LOOP_POOL.rowMin, LOOP_POOL.rowCount), 10)
})

test('nearest periodic archive copies remain centered around the live camera track', () => {
  for (const center of [-10_000.4, -123.7, 12, 258.25, 10_000.8]) {
    const lane = nearestPeriodicCoordinate(LOOP_POOL.laneMin, center, LOOP_POOL.laneCount)
    const row = nearestPeriodicCoordinate(LOOP_POOL.rowMin, center, LOOP_POOL.rowCount)
    assert.ok(Math.abs(lane - center) <= LOOP_POOL.laneCount / 2)
    assert.ok(Math.abs(row - center) <= LOOP_POOL.rowCount / 2)
  }
})

test('logical cells separated by full pool periods reuse the same physical archive slot', () => {
  const base = poolKeyForCell(2, 12, LOOP_POOL)
  assert.equal(poolKeyForCell(2 + LOOP_POOL.laneCount * 37, 12 + LOOP_POOL.rowCount * 19, LOOP_POOL), base)
  assert.equal(poolKeyForCell(2 - LOOP_POOL.laneCount * 41, 12 - LOOP_POOL.rowCount * 23, LOOP_POOL), base)
})

test('periodic remapping always produces gap-free lane and row coverage around the live center', () => {
  const centers = [-10_000.5, -117.25, 0, 12.4, 999.9, 10_000.5]
  for (const center of centers) {
    const lanes = Array.from({ length: LOOP_POOL.laneCount }, (_, index) => LOOP_POOL.laneMin + index)
      .map(seed => nearestPeriodicCoordinate(seed, center, LOOP_POOL.laneCount))
      .sort((a, b) => a - b)
    const rows = Array.from({ length: LOOP_POOL.rowCount }, (_, index) => LOOP_POOL.rowMin + index)
      .map(seed => nearestPeriodicCoordinate(seed, center, LOOP_POOL.rowCount))
      .sort((a, b) => a - b)

    assert.equal(new Set(lanes).size, LOOP_POOL.laneCount)
    assert.equal(new Set(rows).size, LOOP_POOL.rowCount)
    for (let index = 1; index < lanes.length; index += 1) assert.equal(lanes[index] - lanes[index - 1], 1)
    for (let index = 1; index < rows.length; index += 1) assert.equal(rows[index] - rows[index - 1], 1)
  }
})

test('overscan pool expands the infinite Archive Sea beyond the original visible bounds', () => {
  assert.equal(LOOP_POOL.laneMin, -3)
  assert.equal(LOOP_POOL.laneMax, 7)
  assert.equal(LOOP_POOL.laneCount, 11)
  assert.equal(LOOP_POOL.rowMin, -4)
  assert.equal(LOOP_POOL.rowMax, 30)
  assert.equal(LOOP_POOL.rowCount, 35)
})
