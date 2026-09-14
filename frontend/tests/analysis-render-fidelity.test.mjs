import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('render fidelity V3 uses an original layered archive assembly instead of a box-and-label card', () => {
  const assembly = read('analysis-os/render/archiveAssembly.js')

  assert.match(assembly, /RoundedBoxGeometry/)
  assert.match(assembly, /glassCover|const glass/)
  assert.match(assembly, /ringTop/)
  assert.match(assembly, /ringBottom/)
  assert.match(assembly, /centerBridge|const bridge/)
  assert.match(assembly, /fasteners/)
  assert.match(assembly, /decryptA/)
  assert.match(assembly, /decryptB/)
  assert.match(assembly, /MeshPhysicalMaterial/)
})

test('render fidelity V3 defines quality profiles, tone mapping and soft-shadow capability', () => {
  const quality = read('analysis-os/render/renderQuality.js')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')

  assert.match(quality, /ACESFilmicToneMapping/)
  assert.match(quality, /PCFShadowMap/)
  assert.match(quality, /HIGH/)
  assert.match(quality, /BALANCED/)
  assert.match(quality, /MOBILE/)
  assert.match(scene, /HemisphereLight/)
  assert.match(scene, /focusedGlassMaterial\.transmission/)
  assert.match(scene, /qualityProfile\?\.shadows/)
})

test('render fidelity V3 extraction exposes glass-decrypt stages without moving the card toward the camera', () => {
  const page = read('pages/AnalysisOsPage.vue')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const transition = read('analysis-os/motion/useArchiveTransition.js')

  assert.match(page, /RELEASE LOCK/)
  assert.match(page, /VERTICAL EXTRACTION/)
  assert.match(page, /CAMERA APPROACH/)
  assert.match(page, /GLASS DECRYPT/)
  assert.match(page, /DOCUMENT REVEAL/)
  assert.match(page, /WORKSPACE_REVEAL_HOLD_MS = 180/)
  assert.match(page, /documentRevealAmount \* 1\.55/)
  assert.match(scene, /focusedLift = 0\.42 \+ extraction \* \(4\.05 - 0\.42\)/)
  assert.match(scene, /camera\.position\.copy\(cameraBase\)\.lerp\(cameraDetailBase, detail\)/)
  assert.match(transition, /options\.enterDuration \|\| 1080/)
})

