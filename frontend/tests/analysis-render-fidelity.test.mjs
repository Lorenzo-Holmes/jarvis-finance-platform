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

test('optional post processing probes the GPU and falls back to direct rendering on WebGL errors', () => {
  const quality = read('analysis-os/render/renderQuality.js')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')

  assert.match(quality, /probeArchiveComposer/)
  assert.match(quality, /gl\.getError\(\)/)
  assert.match(quality, /fallback-webgl/)
  assert.doesNotMatch(quality, /BokehPass/)
  assert.match(quality, /maxDpr: 1\.5/)
  assert.match(quality, /renderer\.render\(scene, camera\)/)
  assert.match(scene, /postProcessingStatus/)
  assert.match(scene, /skipped-automation/)
  assert.match(scene, /schedulePostProcessingProbe/)
  assert.match(scene, /IDLE_RENDER_INTERVAL_MS = 1000 \/ 30/)
  assert.match(scene, /ssaoPass\.enabled = extractionDetail > 0\.72/)
  assert.match(scene, /browseDetailDensity/)
  assert.doesNotMatch(scene, /focusArchiveComposer/)
})

test('boot and sound systems are original local runtime effects without third-party media assets', () => {
  const page = read('pages/AnalysisOsPage.vue')
  const audio = read('analysis-os/audio/useArchiveAudio.js')

  for (const stage of ['SYSTEM WAKE', 'IDENTITY RESOLVED', 'PERMISSION SCAN', 'MODULE ARCHIVE ONLINE']) {
    assert.match(page, new RegExp(stage))
  }
  assert.match(page, /bootPhase/)
  assert.match(page, /SOUND/)
  assert.match(audio, /AudioContext/)
  assert.match(audio, /createOscillator/)
  assert.match(audio, /createBuffer/)
  assert.doesNotMatch(audio, /fetch\(|\.mp3|\.wav|\.ogg/i)
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
  assert.match(scene, /0\.40 \+ extraction \* \(4\.05 - 0\.40\)/)
  assert.match(scene, /camera\.position\.copy\(cameraBase\)\.lerp\(cameraDetailBase, detail\)/)
  assert.match(transition, /options\.enterDuration \|\| 1080/)
})

test('render fidelity V3-G uses an original focused GLB with procedural fallback', () => {
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const assetReadme = fs.readFileSync(path.resolve(here, '../public/assets/analysis-os/README.md'), 'utf8')

  assert.match(scene, /GLTFLoader/)
  assert.match(scene, /jarvis-archive-v1\.glb/)
  assert.match(scene, /detailAssetStatus = 'fallback'/)
  assert.match(scene, /qualityProfile\.name !== 'MOBILE'/)
  assert.match(scene, /disposeFocusedArchiveAsset/)
  assert.match(assetReadme, /original JARVIS archive model/)
})

test('archive field composition V5 matches the wide-card long-lens archive reference while keeping anonymous backgrounds', () => {
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(scene, /const LANE_SPACING = 5\.20/)
  assert.match(scene, /const ROW_SPACING = 0\.62/)
  assert.match(scene, /const elevation = 7\.5 \* Math\.PI \/ 180/)
  assert.match(scene, /const LANE_ROW_SKEW = 0/)
  assert.match(scene, /const targetTilt = 0/)
  assert.match(scene, /function setDesktopBrowseCamera/)
  assert.match(scene, /const baseSpan = 7\.33/)
  assert.match(scene, /const distance = 100/)
  assert.match(scene, /const referenceDistance = 140/)
  assert.match(scene, /const yaw = 76\.75 \* Math\.PI \/ 180/)
  assert.match(scene, /cameraAimBase\.set\(-5\.13, -2\.16, 0\.481\)/)
  assert.match(scene, /0\.965 \+ extraction \* 0\.091/)
  assert.doesNotMatch(scene, /identified \? 1\.028/)
  assert.match(scene, /scene\.fog = new Fog\('#eae5e1', 145, 165\)/)
  assert.match(scene, /const renderedDistance = camera\.position\.distanceTo\(cameraAim\)/)
  assert.match(scene, /archiveShoulderField/)
  assert.match(scene, /ANONYMOUS_ARCHIVE/)
  assert.match(scene, /showIdentity = Boolean\(isFocused && identified\)/)
  assert.match(scene, /retrievalState/)
  assert.match(scene, /const shoulder = archiveShoulderField\(rowRelative, laneRelative\)/)
  assert.match(scene, /radial-gradient\(ellipse at 48% 48%/)
  assert.doesNotMatch(scene, /moduleAtCell|cellForModule/)
  assert.match(page, /moduleIndexExpanded/)
  assert.match(page, /module-index-shell\.expanded/)
  assert.match(page, /FILE NUMBER:/)
})

test('archive sea V5 uses bidirectional cyclic flow and a lightweight retrieval illusion', () => {
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(scene, /emit\('step', delta\)/)
  assert.match(scene, /emit\('settled'\)/)
  assert.match(scene, /function shiftRows/)
  assert.match(page, /wrap\(focusedIndex\.value \+ delta, modules\.length\)/)
  assert.match(page, /retrievalState\.value = 'FLOW'/)
  assert.match(page, /retrievalState\.value = 'QUERY'/)
  assert.match(page, /retrievalState\.value = 'MATCH'/)
  assert.match(page, /archive-callout/)
  assert.match(page, /is-settled/)
  assert.doesNotMatch(page, /class="retrieval-hud"/)
  assert.doesNotMatch(page, /PseudoRandomArchiveResolver|candidateScore|randomCandidate/i)
})

test('archive browse keeps visual density restrained and avoids stop-state detail pops', () => {
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(scene, /let motionAmount = 0/)
  assert.match(scene, /let detailDensity = 0\.42/)
  assert.match(scene, /let settleProgress = 1/)
  assert.match(scene, /function detailBudgetForState/)
  assert.match(scene, /densityBudget = Math\.max\(2, Math\.min\(6, Math\.round\(1 \+ detailDensity \* 12\)\)\)/)
  assert.match(scene, /motionAmount > 0\.52 \? 2 : 3/)
  assert.match(scene, /Math\.min\(4 \+ Math\.round\(settleProgress \* 2\), densityBudget\)/)
  assert.match(scene, /new InstancedMesh/)
  assert.match(scene, /baseInstances\.body\.setMatrixAt/)
  assert.match(scene, /entry\.focusGroup\.visible = \(isFocused && extraction > 0\.14\)/)
  assert.doesNotMatch(scene, /keyLight\.castShadow = Boolean\(qualityProfile\?\.shadows && !interactiveMotion\)/)
  assert.match(page, /const settleProgress = ref\(1\)/)
  assert.match(page, /calloutFileOpacity/)
  assert.match(page, /calloutNameOpacity/)
  assert.match(page, /calloutCtaOpacity/)
  assert.match(page, /:disabled="!calloutCtaReady"/)
  assert.match(page, /@motion="handleSceneMotion"/)
  assert.doesNotMatch(page, /module-summary/)
  assert.doesNotMatch(page, /capability-line/)
})

test('archive sea V5 has no visible physical edge because the finite pool is periodically remapped', () => {
  const scene = read('components/analysis/AnalysisArchiveScene.vue')
  const loop = read('analysis-os/motion/archiveLoop.js')

  assert.match(scene, /LOOP_POOL/)
  assert.match(loop, /laneCount: 11/)
  assert.match(loop, /rowCount: 35/)
  assert.match(scene, /updateWrappedArchivePositions/)
  assert.match(scene, /entry\.virtualLane/)
  assert.match(scene, /entry\.virtualRow/)
  assert.match(scene, /poolKeyForCell/)
  assert.match(loop, /nearestPeriodicCoordinate/)
  assert.match(loop, /canonicalPoolCoordinate/)
  assert.doesNotMatch(scene, /root\.position\.x = -\(laneTrack/)
  assert.doesNotMatch(scene, /root\.position\.z = -\(rowTrack/)
})

