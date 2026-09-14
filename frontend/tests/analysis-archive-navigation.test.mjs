import test from 'node:test'
import assert from 'node:assert/strict'
import {
  JARVIS_MODULES,
  cellForModule,
  moduleAtCell,
  modulesForLane,
} from '../src/analysis-os/data/modules.js'
import {
  ArchiveDrag,
  ArchiveMomentum,
} from '../src/analysis-os/motion/archiveMomentum.js'

test('module archive defines the eleven stable workspace modules', () => {
  assert.equal(JARVIS_MODULES.length, 11)
  assert.deepEqual(JARVIS_MODULES.map(module => module.no), [1,2,3,4,5,6,7,8,9,10,11])
  assert.equal(new Set(JARVIS_MODULES.map(module => module.routeKey)).size, 11)
  assert.equal(modulesForLane(1).length, 3)
  assert.equal(moduleAtCell(1, 2).key, 'financial')
  assert.equal(moduleAtCell(1, 5).key, 'financial')
})

test('module targeting chooses a nearby periodic occurrence instead of resetting the archive', () => {
  const target = cellForModule('market', { lane: 4, row: 13 })
  assert.equal(target.lane, 5)
  assert.ok(Math.abs(target.row - 13) <= 1)
  assert.equal(moduleAtCell(target.lane, target.row).key, 'market')
})

test('free-plane drag inverts both projected archive tracks', () => {
  const drag = new ArchiveDrag()
  drag.start(100, 100, {
    lane: { x: 50, y: 0 },
    row: { x: 0, y: 25 },
  }, 0)
  drag.move(150, 150, 50)
  assert.equal(drag.active, true)
  assert.ok(Math.abs(drag.value.lane - 1) < 1e-9)
  assert.ok(Math.abs(drag.value.row - 2) < 1e-9)
  const velocity = drag.releaseVelocity(50)
  assert.ok(velocity.lane > 0)
  assert.ok(velocity.row > velocity.lane)
})

test('released archive momentum coasts before snapping to a stable cell', () => {
  const fast = new ArchiveMomentum(0, 8)
  const slow = new ArchiveMomentum(0, 1)
  for (let i = 0; i < 600 && fast.phase !== 'idle'; i += 1) fast.step(1 / 60)
  for (let i = 0; i < 600 && slow.phase !== 'idle'; i += 1) slow.step(1 / 60)
  assert.equal(fast.phase, 'idle')
  assert.equal(slow.phase, 'idle')
  assert.ok(Math.abs(fast.value) > Math.abs(slow.value))
  assert.equal(fast.value, Math.round(fast.value))
  assert.equal(slow.value, Math.round(slow.value))
})
