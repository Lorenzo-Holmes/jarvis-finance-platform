<script setup>
import {
  AmbientLight,
  CanvasTexture,
  Color,
  DirectionalLight,
  Fog,
  Group,
  HemisphereLight,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  PerspectiveCamera,
  PlaneGeometry,
  Raycaster,
  Scene,
  SRGBColorSpace,
  Vector2,
  Vector3,
  WebGLRenderer,
} from 'three'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArchiveDrag, ArchivePlaneMomentum, dampSpring } from '../../analysis-os/motion/archiveMomentum'
import {
  LOOP_POOL,
  nearestPeriodicCoordinate,
  poolKeyForCell,
} from '../../analysis-os/motion/archiveLoop'
import {
  createArchiveAssembly,
  createArchiveAssetLibrary,
  createFocusedGlassMaterial,
} from '../../analysis-os/render/archiveAssembly'
import {
  archiveQualityProfile,
  configureArchiveRenderer,
  createArchiveComposer,
  disposeArchiveComposer,
  probeArchiveComposer,
  resizeArchiveComposer,
} from '../../analysis-os/render/renderQuality'

const LANE_SPACING = 5.20
const ROW_SPACING = 0.62
const LANE_ROW_SKEW = 0
const CENTER_LANE = 2
const CENTER_ROW = 12
const ARCHIVE_ORIGIN_ROW = 15.5
const FOCUS_Z = (CENTER_ROW - ARCHIVE_ORIGIN_ROW) * ROW_SPACING
const POOL_OPTIONS = LOOP_POOL
const IDLE_RENDER_INTERVAL_MS = 1000 / 30

const ANONYMOUS_ARCHIVE = {
  id: 'archive:anonymous',
  key: 'archive-anonymous',
  no: 0,
  code: 'ARCHIVE',
  labelEn: 'ARCHIVE',
  labelZh: '档案',
  category: 'ARCHIVE',
  capabilities: ['INDEX', 'RECORD', 'SEA'],
}

const props = defineProps({
  modules: { type: Array, default: () => [] },
  focusedKey: { type: String, default: '' },
  retrievalState: { type: String, default: 'FOCUSED' },
  sleepAmount: { type: Number, default: 0 },
  extractionProgress: { type: Number, default: 0 },
  active: { type: Boolean, default: true },
})

const emit = defineEmits(['step', 'settled', 'flow', 'activate', 'interaction'])
const mountRef = ref(null)
const failed = ref(false)

let renderer = null
let scene = null
let camera = null
let root = null
let raycaster = null
let pointer = null
let resizeObserver = null
let animationFrame = 0
let rendering = false
let reducedMotion = false
let disposed = false
let hoveredEntry = null
let activePointer = null
let dragStart = { lane: 0, row: 0 }
let momentum = null
let wheelTotal = 0
let wheelTime = 0
let lastFrameTime = 0
let lastRenderedAt = 0
let initializedTrack = false
let lastReportedRow = CENTER_ROW
let navigationActive = false
let suppressSleepMotion = false
let archiveLibrary = null
let focusedGlassMaterial = null
let composerBundle = null
let qualityProfile = null
let keyLight = null
let renderedFrames = 0
let composerRevision = 0
let postProbeTimer = 0
let postProbeAttempted = false
let postProbeFailed = false
let postProcessingStatus = 'direct'
let detailAsset = null
let detailAssetStatus = 'idle'

const drag = new ArchiveDrag()
const laneTrack = { value: CENTER_LANE, velocity: 0, target: CENTER_LANE }
const rowTrack = { value: CENTER_ROW, velocity: 0, target: CENTER_ROW }
const entries = []
const entriesByPoolKey = new Map()
const textureCache = new Map()
const materialCache = new Map()
const sceneDisposables = []
const cameraAim = new Vector3(-1.091, -0.045, 0.481)
const cameraBase = new Vector3(-114.556, 45.535, 68.658)
const cameraAimBase = new Vector3(-1.091, -0.045, 0.481)
const cameraDetailBase = new Vector3(-14.8, 7.5, 22.3)
const cameraDetailAim = new Vector3(3.25, 0.3, -0.2)
let cameraBaseFov = 3.0
let cameraDetailFov = 13.6

function smoothstep(value) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}

function gaussian(distance, width) {
  const scaled = distance / Math.max(0.0001, width)
  return Math.exp(-0.5 * scaled * scaled)
}

function archiveShoulderField(rowOffset, laneOffset) {
  const envelope = Math.max(-0.42, 2.15 - 0.17 * (Math.sqrt(rowOffset * rowOffset + 1) - 1))
  const column = 0.25 + 0.75 * gaussian(laneOffset, 0.55)
  return envelope * column
}

function setDesktopBrowseCamera(width, height) {
  const aspect = Math.max(0.25, width / Math.max(1, height))
  const baseSpan = 7.33
  const span = Math.max(baseSpan, baseSpan * (16 / 9) / aspect)
  const distance = 100
  const referenceDistance = 140
  const yaw = 59 * Math.PI / 180
  const elevation = 9.5 * Math.PI / 180
  const viewX = -Math.sin(yaw) * Math.cos(elevation)
  const viewY = Math.sin(elevation)
  const viewZ = Math.cos(yaw) * Math.cos(elevation)
  cameraAimBase.set(-1.091, -0.045, 0.481)
  cameraBase.set(
    cameraAimBase.x + viewX * distance,
    cameraAimBase.y + viewY * distance,
    cameraAimBase.z + viewZ * distance,
  )
  cameraBaseFov = 2 * Math.atan(span / (2 * referenceDistance)) * 180 / Math.PI
}

function makeLabelTexture(module) {
  if (textureCache.has(module.id)) return textureCache.get(module.id)
  const canvas = document.createElement('canvas')
  canvas.width = 1024
  canvas.height = 260
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = 'rgba(244, 241, 234, .78)'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = 'rgba(112, 108, 99, .72)'
  ctx.lineWidth = 3
  ctx.strokeRect(8, 8, canvas.width - 16, canvas.height - 16)

  ctx.fillStyle = '#817c72'
  ctx.font = '600 24px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(`MODULE / ${String(module.no).padStart(2, '0')}`, 40, 52)
  ctx.textAlign = 'right'
  ctx.fillText(module.code, 975, 52)
  ctx.textAlign = 'left'

  ctx.fillStyle = '#1e211c'
  ctx.font = '700 54px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(module.labelEn, 40, 126)

  ctx.fillStyle = '#4b4d46'
  ctx.font = '600 28px system-ui, sans-serif'
  ctx.fillText(module.labelZh, 40, 178)

  ctx.fillStyle = '#8c877d'
  ctx.font = '500 19px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(module.capabilities.slice(0, 3).join(' · '), 40, 224)

  const texture = new CanvasTexture(canvas)
  texture.colorSpace = SRGBColorSpace
  texture.anisotropy = Math.min(renderer?.capabilities?.getMaxAnisotropy?.() || 1, 8)
  textureCache.set(module.id, texture)
  return texture
}

function labelMaterial(module) {
  if (materialCache.has(module.id)) return materialCache.get(module.id)
  const material = new MeshBasicMaterial({
    map: makeLabelTexture(module), transparent: true, opacity: 0.94, toneMapped: false,
  })
  materialCache.set(module.id, material)
  return material
}

function clearArchive() {
  while (root?.children?.length) root.remove(root.children[0])
  entries.splice(0, entries.length)
  entriesByPoolKey.clear()
  hoveredEntry = null
  for (const material of materialCache.values()) material.dispose()
  for (const texture of textureCache.values()) texture.dispose()
  materialCache.clear()
  textureCache.clear()
}

function createCard(physicalLane, physicalRow) {
  const assembly = createArchiveAssembly(ANONYMOUS_ARCHIVE, labelMaterial(ANONYMOUS_ARCHIVE), archiveLibrary)
  const group = assembly.group

  const baseY = -4.60
  const baseX = (physicalLane - CENTER_LANE) * LANE_SPACING
  const baseZ = FOCUS_Z + (physicalRow - CENTER_ROW) * ROW_SPACING
  group.position.set(baseX, baseY, baseZ)
  group.rotation.y = 0
  group.userData = {
    baseY,
    targetY: baseY,
    physicalLane,
    physicalRow,
    slotKey: `slot:${physicalLane}:${physicalRow}`,
  }
  root.add(group)
  const entry = {
    ...assembly,
    group,
    physicalLane,
    physicalRow,
    virtualLane: physicalLane,
    virtualRow: physicalRow,
  }
  entries.push(entry)
  entriesByPoolKey.set(`${physicalLane}:${physicalRow}`, entry)
}

function buildArchiveArray() {
  if (!root || !renderer || !archiveLibrary) return
  clearArchive()

  for (let physicalRow = LOOP_POOL.rowMin; physicalRow <= LOOP_POOL.rowMax; physicalRow += 1) {
    for (let physicalLane = LOOP_POOL.laneMin; physicalLane <= LOOP_POOL.laneMax; physicalLane += 1) {
      createCard(physicalLane, physicalRow)
    }
  }

  if (!initializedTrack) {
    laneTrack.value = laneTrack.target = CENTER_LANE
    rowTrack.value = rowTrack.target = CENTER_ROW
    lastReportedRow = CENTER_ROW
    initializedTrack = true
  }
  updateFocusVisuals()
}

function focusedModuleData() {
  return props.modules.find(module => module.key === props.focusedKey) || props.modules[0] || ANONYMOUS_ARCHIVE
}

function currentCell() {
  return { lane: Math.round(laneTrack.value), row: Math.round(rowTrack.value) }
}

function focusedEntry() {
  const cell = currentCell()
  return entriesByPoolKey.get(poolKeyForCell(cell.lane, cell.row, POOL_OPTIONS)) || null
}

function updateWrappedArchivePositions(centerLane, centerRow) {
  for (const entry of entries) {
    const virtualLane = nearestPeriodicCoordinate(entry.physicalLane, centerLane, LOOP_POOL.laneCount)
    const virtualRow = nearestPeriodicCoordinate(entry.physicalRow, centerRow, LOOP_POOL.rowCount)
    entry.virtualLane = virtualLane
    entry.virtualRow = virtualRow
    entry.group.position.x = (virtualLane - centerLane) * LANE_SPACING
    entry.group.position.z = FOCUS_Z + (virtualRow - centerRow) * ROW_SPACING
  }
}

function updateFocusVisuals() {
  const focused = focusedEntry()
  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  const identified = extraction > 0.001 || props.retrievalState === 'MATCH' || props.retrievalState === 'FOCUSED'
  const detailVisible = extraction > 0.035
  const previewLift = props.retrievalState === 'QUERY'
    ? 0.12
    : props.retrievalState === 'MATCH'
      ? 0.28
      : props.retrievalState === 'FOCUSED'
        ? 0.40
        : 0.08
  const module = focusedModuleData()
  for (const entry of entries) {
    const isFocused = entry === focused
    const isHovered = entry === hoveredEntry
    const focusedLift = extraction > 0.001
      ? 0.40 + extraction * (4.05 - 0.40)
      : previewLift
    entry.group.userData.targetY = entry.group.userData.baseY + (isFocused ? focusedLift : isHovered ? 0.16 : 0)
    entry.group.userData.targetScale = isFocused && identified ? 1.028 + extraction * 0.028 : isHovered ? 0.985 : 0.965
    if (entry.glass) {
      entry.glass.material = isFocused && extraction > 0.32
        ? focusedGlassMaterial
        : archiveLibrary.materials.glass
    }
    if (entry.label) entry.label.material = isFocused && identified ? labelMaterial(module) : labelMaterial(ANONYMOUS_ARCHIVE)
    if (entry.baseGroup) entry.baseGroup.visible = !(isFocused && detailAsset && detailVisible)
    if (entry.identityGroup) {
      entry.identityGroup.position.z = isFocused && identified ? (detailVisible ? 0.22 : 0.09) : 0
      entry.identityGroup.visible = Boolean(isFocused && identified)
    }
    if (entry.nearGroup && isFocused && detailAsset && detailVisible) entry.nearGroup.visible = false
  }
  if (detailAsset) {
    if (focused && detailAsset.parent !== focused.group) {
      focused.group.add(detailAsset)
      detailAsset.position.set(0, 0, 0)
      detailAsset.rotation.set(0, 0, 0)
    }
    detailAsset.visible = Boolean(focused && detailVisible)
  }
}

async function loadFocusedArchiveAsset() {
  if (detailAsset || detailAssetStatus === 'loading' || detailAssetStatus === 'ready') return
  detailAssetStatus = 'loading'
  try {
    const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js')
    const loader = new GLTFLoader()
    const gltf = await loader.loadAsync('/assets/analysis-os/jarvis-archive-v1.glb')
    if (disposed) return
    detailAsset = gltf.scene
    detailAsset.name = 'JARVIS_FOCUSED_ARCHIVE_GLB'
    detailAsset.traverse(node => {
      if (!node.isMesh) return
      node.castShadow = true
      node.receiveShadow = true
    })
    detailAssetStatus = 'ready'
    updateFocusVisuals()
  } catch (error) {
    console.warn('Analysis OS focused GLB unavailable; using procedural fallback', error)
    detailAssetStatus = 'fallback'
  }
}

function disposeFocusedArchiveAsset() {
  if (!detailAsset) return
  detailAsset.traverse(node => {
    if (!node.isMesh) return
    node.geometry?.dispose?.()
    const materials = Array.isArray(node.material) ? node.material : [node.material]
    materials.forEach(material => material?.dispose?.())
  })
  detailAsset.removeFromParent?.()
  detailAsset = null
}

function emitStepFromTrack() {
  const row = Math.round(rowTrack.value)
  const delta = row - lastReportedRow
  if (delta) {
    lastReportedRow = row
    emit('step', delta)
  }
  updateFocusVisuals()
}

function beginFlow(direction = 0) {
  if (!navigationActive) emit('flow', Math.sign(direction || 0))
  navigationActive = true
}

function shiftRows(steps, source = 'index') {
  if (!Number.isFinite(steps) || !steps || props.extractionProgress > 0.001) return
  stopMomentum()
  beginFlow(steps)
  rowTrack.target = Math.round(rowTrack.target) + steps
  laneTrack.velocity = 0
  rowTrack.velocity = 0
  emit('interaction', source)
}

function rebaseTracksIfNeeded() {
  const laneShift = Math.round((laneTrack.value - CENTER_LANE) / LOOP_POOL.laneCount) * LOOP_POOL.laneCount
  if (Math.abs(laneShift) >= LOOP_POOL.laneCount) {
    laneTrack.value -= laneShift
    laneTrack.target -= laneShift
    if (momentum) {
      momentum.lane.value -= laneShift
      momentum.lane.target -= laneShift
    }
  }
  const rowShift = Math.round((rowTrack.value - CENTER_ROW) / LOOP_POOL.rowCount) * LOOP_POOL.rowCount
  if (Math.abs(rowShift) >= LOOP_POOL.rowCount) {
    rowTrack.value -= rowShift
    rowTrack.target -= rowShift
    lastReportedRow -= rowShift
    if (momentum) {
      momentum.row.value -= rowShift
      momentum.row.target -= rowShift
    }
  }
}

function screenPoint(world) {
  const rect = renderer.domElement.getBoundingClientRect()
  const point = world.clone().project(camera)
  return { x: (point.x + 1) * rect.width * 0.5, y: (1 - point.y) * rect.height * 0.5 }
}

function dragProjection() {
  const center = new Vector3(0, -2.75, -2.17)
  const base = screenPoint(center)
  const lane = screenPoint(center.clone().add(new Vector3(-LANE_SPACING, 0, -LANE_ROW_SKEW)))
  const row = screenPoint(center.clone().add(new Vector3(0, 0, -ROW_SPACING)))
  return {
    lane: { x: lane.x - base.x, y: lane.y - base.y },
    row: { x: row.x - base.x, y: row.y - base.y },
  }
}

function pointerFromEvent(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
}

function pickEntry(event) {
  pointerFromEvent(event)
  raycaster.setFromCamera(pointer, camera)
  const hit = raycaster.intersectObjects(entries.flatMap(entry => entry.hitTargets), false)[0]
  if (!hit) return null
  return entries.find(entry => entry.hitTargets.includes(hit.object)) || null
}

function stopMomentum() {
  momentum = null
  laneTrack.velocity = 0
  rowTrack.velocity = 0
}

function sleepOffsets(time = performance.now()) {
  const amount = suppressSleepMotion ? 0 : Math.max(0, Math.min(1, Number(props.sleepAmount) || 0))
  return {
    amount,
    lane: Math.sin(time / 11_000) * 0.35 * amount,
    row: (Math.sin(time / 7_300) * 1.4 + Math.sin(time / 17_000) * 0.55) * amount,
  }
}

function captureSleepPosition() {
  if (suppressSleepMotion || props.sleepAmount <= 0.001) return
  const offset = sleepOffsets()
  laneTrack.value += offset.lane
  laneTrack.target += offset.lane
  rowTrack.value += offset.row
  rowTrack.target += offset.row
  suppressSleepMotion = true
  emitStepFromTrack()
}

function onPointerDown(event) {
  if (props.extractionProgress > 0.001) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  captureSleepPosition()
  activePointer = event.pointerId
  stopMomentum()
  dragStart = { lane: laneTrack.value, row: rowTrack.value }
  drag.start(event.clientX, event.clientY, dragProjection(), event.timeStamp)
  renderer.domElement.setPointerCapture?.(event.pointerId)
  emit('interaction', 'pointer')
}

function onPointerMove(event) {
  if (props.extractionProgress > 0.001 && activePointer === null) return
  if (activePointer === null) {
    const entry = pickEntry(event)
    if (entry !== hoveredEntry) {
      hoveredEntry = entry
      updateFocusVisuals()
    }
    renderer.domElement.style.cursor = entry ? 'pointer' : 'grab'
    return
  }
  if (event.pointerId !== activePointer) return
  drag.move(event.clientX, event.clientY, event.timeStamp)
  if (!drag.active) return
  beginFlow(drag.value.row || drag.value.lane)
  hoveredEntry = null
  laneTrack.value = laneTrack.target = dragStart.lane + drag.value.lane
  rowTrack.value = rowTrack.target = dragStart.row + drag.value.row
  laneTrack.velocity = rowTrack.velocity = 0
  renderer.domElement.style.cursor = 'grabbing'
  emitStepFromTrack()
}

function finishPointer(event, cancelled = false) {
  if (event.pointerId !== activePointer) return
  activePointer = null
  if (renderer.domElement.hasPointerCapture?.(event.pointerId)) renderer.domElement.releasePointerCapture(event.pointerId)
  renderer.domElement.style.cursor = 'grab'

  if (!cancelled && drag.active) {
    if (reducedMotion) {
      laneTrack.target = Math.round(laneTrack.value)
      rowTrack.target = Math.round(rowTrack.value)
    } else {
      momentum = new ArchivePlaneMomentum(
        { lane: laneTrack.value, row: rowTrack.value },
        drag.releaseVelocity(event.timeStamp, false),
      )
    }
    return
  }

  if (!cancelled && !drag.moved) {
    const entry = pickEntry(event)
    if (entry) {
      const current = focusedEntry()
      if (current && current.physicalLane === entry.physicalLane && current.physicalRow === entry.physicalRow) {
        emit('activate', props.focusedKey)
      } else {
        const cell = currentCell()
        beginFlow(entry.virtualRow - cell.row)
        laneTrack.target = entry.virtualLane
        rowTrack.target = entry.virtualRow
      }
    }
  }
}

function onWheel(event) {
  if (props.extractionProgress > 0.001) return
  if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return
  event.preventDefault()
  captureSleepPosition()
  emit('interaction', 'wheel')
  stopMomentum()
  const now = performance.now()
  const normalized = Math.max(-300, Math.min(300, event.deltaY * (event.deltaMode === 1 ? 40 : event.deltaMode === 2 ? renderer.domElement.clientHeight : 1)))
  if (now - wheelTime > 180 || Math.sign(normalized) !== Math.sign(wheelTotal)) wheelTotal = 0
  wheelTime = now
  wheelTotal += normalized
  const steps = Math.min(3, Math.floor(Math.abs(wheelTotal) / 100))
  if (!steps) return
  const direction = Math.sign(wheelTotal)
  wheelTotal -= direction * steps * 100
  beginFlow(direction)
  rowTrack.target = Math.round(rowTrack.target) + direction * steps
}

function resize() {
  const element = mountRef.value
  if (!element || !renderer || !camera) return
  const width = Math.max(1, element.clientWidth)
  const height = Math.max(1, element.clientHeight)
  const previousQuality = qualityProfile
  const nextQuality = archiveQualityProfile(width, window.devicePixelRatio || 1)
  const qualityChanged = !qualityProfile || qualityProfile.name !== nextQuality.name
  qualityProfile = nextQuality
  configureArchiveRenderer(renderer, qualityProfile)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, qualityProfile.maxDpr))
  renderer.setSize(width, height, false)
  if (qualityProfile.name !== 'MOBILE' && detailAssetStatus === 'idle') loadFocusedArchiveAsset()
  if (qualityChanged && !qualityProfile.postCandidate && composerBundle) {
    composerRevision += 1
    disposeArchiveComposer(composerBundle)
    composerBundle = null
    postProcessingStatus = 'direct'
  } else if (qualityChanged && qualityProfile.post && composerBundle) {
    rebuildComposer(width, height, qualityProfile)
  } else if (qualityChanged && qualityProfile.postCandidate && !composerBundle && !postProbeFailed) {
    postProbeAttempted = false
    postProcessingStatus = previousQuality?.postCandidate ? postProcessingStatus : 'direct'
  }
  if (qualityProfile.post && !composerBundle) rebuildComposer(width, height, qualityProfile)
  resizeArchiveComposer(composerBundle, width, height)
  if (keyLight) {
    const shadowSize = qualityProfile.name === 'HIGH' ? 2048 : 1024
    keyLight.castShadow = Boolean(qualityProfile.shadows)
    keyLight.shadow.mapSize.set(shadowSize, shadowSize)
  }
  camera.aspect = width / height
  if (width < 700) {
    cameraBase.set(-23.5, 14.5, 30.5)
    cameraBaseFov = 14.5
    cameraDetailBase.set(-8.9, 6.8, 25.7)
    cameraDetailAim.set(1.2, 0.4, 0)
    cameraDetailFov = 18
    cameraAimBase.set(-0.2, -1.0, -1.1)
  } else if (width < 1100) {
    cameraBase.set(-44.0, 25.0, 35.0)
    cameraBaseFov = 8.6
    cameraDetailBase.set(-12.5, 7.2, 23.2)
    cameraDetailAim.set(2.5, 0.25, 0)
    cameraDetailFov = 15
    cameraAimBase.set(-0.4, 0.2, -0.2)
  } else {
    setDesktopBrowseCamera(width, height)
    cameraDetailBase.set(-14.8, 7.5, 22.3)
    cameraDetailAim.set(3.25, 0.3, -0.2)
    cameraDetailFov = 13.6
  }
  camera.fov = cameraBaseFov
  camera.position.copy(cameraBase)
  cameraAim.copy(cameraAimBase)
  camera.lookAt(cameraAim)
  camera.updateProjectionMatrix()
}

async function rebuildComposer(width, height, profile) {
  const revision = ++composerRevision
  disposeArchiveComposer(composerBundle)
  composerBundle = null
  if (!profile?.post || !renderer || !scene || !camera) return
  const next = await createArchiveComposer({ renderer, scene, camera, width, height, profile })
  if (revision !== composerRevision || disposed || !next) {
    disposeArchiveComposer(next)
    return
  }
  composerBundle = next
  resizeArchiveComposer(composerBundle, width, height)
}

function schedulePostProcessingProbe(width, height, profile) {
  if (postProbeAttempted || postProbeTimer || reducedMotion || !profile?.postCandidate) return
  if (typeof navigator !== 'undefined' && navigator.webdriver) {
    postProbeAttempted = true
    postProcessingStatus = 'skipped-automation'
    return
  }

  postProbeTimer = window.setTimeout(async () => {
    postProbeTimer = 0
    if (disposed || !renderer || !scene || !camera || !props.active || postProbeAttempted) return
    postProbeAttempted = true
    postProcessingStatus = 'probing'
    const revision = ++composerRevision
    const result = await probeArchiveComposer({ renderer, scene, camera, width, height, profile })
    if (revision !== composerRevision || disposed) {
      disposeArchiveComposer(result?.bundle)
      return
    }
    composerBundle = result?.bundle || null
    postProcessingStatus = result?.status || 'fallback'
    postProbeFailed = !composerBundle && String(postProcessingStatus).startsWith('fallback')
    resizeArchiveComposer(composerBundle, width, height)
  }, 700)
}

function maybeEmitSettled() {
  if (!navigationActive || momentum || activePointer !== null) return
  const laneSettled = Math.abs(laneTrack.value - laneTrack.target) < 0.002
  const rowSettled = Math.abs(rowTrack.value - rowTrack.target) < 0.002
  if (!laneSettled || !rowSettled) return
  navigationActive = false
  emit('settled')
}

function renderFrame(time) {
  if (disposed || !renderer || !scene || !camera || !root || !props.active) {
    rendering = false
    animationFrame = 0
    return
  }
  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  const staticBrowse = !momentum
    && activePointer === null
    && !navigationActive
    && props.retrievalState === 'FOCUSED'
    && extraction < 0.001
    && Number(props.sleepAmount || 0) <= 0.001
  if (staticBrowse && lastRenderedAt && time - lastRenderedAt < IDLE_RENDER_INTERVAL_MS) {
    animationFrame = requestAnimationFrame(renderFrame)
    return
  }
  lastRenderedAt = time

  const dt = Math.min(Math.max((time - lastFrameTime) / 1000, 0.001), 0.05)
  lastFrameTime = time
  renderedFrames += 1

  if (momentum) {
    momentum.step(dt)
    laneTrack.value = laneTrack.target = momentum.value.lane
    rowTrack.value = rowTrack.target = momentum.value.row
    emitStepFromTrack()
    rebaseTracksIfNeeded()
    if (momentum.phase === 'idle') {
      momentum = null
      laneTrack.target = Math.round(laneTrack.value)
      rowTrack.target = Math.round(rowTrack.value)
      rebaseTracksIfNeeded()
    }
  } else if (activePointer === null) {
    const previousCell = currentCell()
    dampSpring(laneTrack, laneTrack.target, reducedMotion ? 30 : 9, dt)
    dampSpring(rowTrack, rowTrack.target, reducedMotion ? 30 : 9, dt)
    const nextCell = currentCell()
    if (nextCell.row !== previousCell.row) emitStepFromTrack()
    else if (nextCell.lane !== previousCell.lane) updateFocusVisuals()
    if (Math.abs(laneTrack.value - laneTrack.target) < 0.0004 && Math.abs(rowTrack.value - rowTrack.target) < 0.0004) rebaseTracksIfNeeded()
  }
  maybeEmitSettled()

  const detail = smoothstep(Math.max(0, (extraction - 0.08) / 0.92))
  const sleep = sleepOffsets(time)
  sleep.amount *= (1 - detail)
  sleep.lane *= (1 - detail)
  sleep.row *= (1 - detail)
  root.position.set(0, 0, 0)
  const visualLane = laneTrack.value + sleep.lane
  const visualRow = rowTrack.value + sleep.row
  updateWrappedArchivePositions(visualLane, visualRow)

  camera.position.copy(cameraBase).lerp(cameraDetailBase, detail)
  cameraAim.copy(cameraAimBase).lerp(cameraDetailAim, detail)
  const nextFov = cameraBaseFov + (cameraDetailFov - cameraBaseFov) * detail
  if (Math.abs(camera.fov - nextFov) > 0.0001) {
    camera.fov = nextFov
    camera.updateProjectionMatrix()
  }
  if (sleep.amount > 0) {
    camera.position.x += Math.sin(time / 8_700) * 0.22 * sleep.amount
    camera.position.y += Math.sin(time / 12_500) * 0.11 * sleep.amount
    cameraAim.x += Math.sin(time / 10_700) * 0.09 * sleep.amount
    cameraAim.y += Math.sin(time / 14_300) * 0.05 * sleep.amount
  }
  camera.lookAt(cameraAim)

  const interactiveMotion = Boolean(
    momentum
    || activePointer !== null
    || navigationActive
    || props.retrievalState === 'FLOW'
    || props.retrievalState === 'QUERY'
    || sleep.amount > 0.01,
  )
  if (composerBundle?.ssaoPass) composerBundle.ssaoPass.enabled = !interactiveMotion
  if (keyLight) keyLight.castShadow = Boolean(qualityProfile?.shadows && !interactiveMotion)

  const fog = scene.fog
  if (fog instanceof Fog) {
    const renderedDistance = camera.position.distanceTo(cameraAim)
    fog.near = renderedDistance + (5 - 6 * detail)
    fog.far = renderedDistance + (25 - 13 * detail)
  }

  const easing = reducedMotion ? 1 : 1 - Math.exp(-dt * 10)
  const focused = focusedEntry()
  const identified = extraction > 0.001 || props.retrievalState === 'MATCH' || props.retrievalState === 'FOCUSED'
  if (focusedGlassMaterial) {
    const decrypt = smoothstep(Math.max(0, (extraction - 0.38) / 0.52))
    focusedGlassMaterial.roughness = 0.42 + (0.12 - 0.42) * decrypt
    focusedGlassMaterial.transmission = 0.36 + (0.88 - 0.36) * decrypt
    focusedGlassMaterial.thickness = 0.13 + (0.075 - 0.13) * decrypt
    focusedGlassMaterial.opacity = 0.98 + (0.9 - 0.98) * decrypt
  }
  entries.forEach((entry, index) => {
    const data = entry.group.userData
    const idle = !reducedMotion && !momentum && activePointer === null
      ? Math.sin(time * 0.00034 + entry.virtualRow * 0.32 + entry.virtualLane * 0.41) * 0.045
      : 0
    const sleepWave = sleep.amount
      ? (Math.sin(time * 0.00055 + entry.virtualRow * 0.22 - entry.virtualLane * 0.35) * 0.11
        + Math.sin(time * 0.00029 - entry.virtualRow * 0.11 + entry.virtualLane * 0.27) * 0.05) * sleep.amount
      : 0
    const laneRelative = entry.virtualLane - visualLane
    const laneDistance = Math.abs(laneRelative)
    const rowRelative = entry.virtualRow - visualRow
    const rowDistance = Math.abs(rowRelative)
    const isFocused = entry === focused
    const isNear = isFocused || (laneDistance <= 2.2 && rowDistance <= 4.6)
    const showIdentity = Boolean(isFocused && identified)
    const shoulder = archiveShoulderField(rowRelative, laneRelative) * (1 - detail)
    entry.group.position.y += (data.targetY + shoulder + idle + sleepWave - entry.group.position.y) * easing

    const targetScale = data.targetScale || 1
    const scale = entry.group.scale.x + (targetScale - entry.group.scale.x) * easing
    entry.group.scale.setScalar(scale)
    const targetTilt = 0
    const idleTilt = focused === entry ? 0 : Math.sin(time * 0.00019 + index * 0.17) * 0.003
    entry.group.rotation.x += (targetTilt + idleTilt - entry.group.rotation.x) * easing
    if (entry.identityGroup) entry.identityGroup.visible = showIdentity
    entry.nearGroup.visible = isNear && !(isFocused && detailAsset && extraction > 0.035)
    entry.focusGroup.visible = (isFocused && identified) || entry === hoveredEntry
    if (entry.body) entry.body.castShadow = Boolean(qualityProfile?.shadows && isNear)

    if (entry.decryptA && entry.decryptB) {
      const decryptProgress = isFocused ? smoothstep(Math.max(0, (extraction - 0.46) / 0.36)) : 0
      entry.decryptA.visible = decryptProgress > 0.01
      entry.decryptB.visible = decryptProgress > 0.01
      entry.decryptA.position.x = -0.82 + decryptProgress * 0.42
      entry.decryptB.position.x = 0.82 - decryptProgress * 0.42
      entry.decryptA.scale.x = 0.62 + decryptProgress * 0.58
      entry.decryptB.scale.x = 0.62 + decryptProgress * 0.58
    }
  })

  if (composerBundle && !interactiveMotion) composerBundle.composer.render()
  else renderer.render(scene, camera)
  if (!postProbeAttempted && qualityProfile?.postCandidate && renderedFrames > 24) {
    const rect = renderer.domElement.getBoundingClientRect()
    schedulePostProcessingProbe(Math.max(1, rect.width), Math.max(1, rect.height), qualityProfile)
  }
  animationFrame = requestAnimationFrame(renderFrame)
}

function startRendering() {
  if (disposed || !renderer || rendering || !props.active) return
  rendering = true
  lastFrameTime = performance.now()
  animationFrame = requestAnimationFrame(renderFrame)
}

function stopRendering() {
  if (animationFrame) cancelAnimationFrame(animationFrame)
  animationFrame = 0
  rendering = false
}

function init() {
  const element = mountRef.value
  if (!element) return
  try {
    reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false
    scene = new Scene()
    scene.background = new Color('#eae5e1')
    // The reference project uses custom archive shaders with a much shorter fog
    // range. Standard materials need a longer falloff to keep the foreground
    // files crisp while still dissolving the distant rows.
    scene.fog = new Fog('#eae5e1', 145, 165)
    camera = new PerspectiveCamera(3.0, 1, 0.1, 220)
    renderer = new WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
    qualityProfile = archiveQualityProfile(element.clientWidth || 1440, window.devicePixelRatio || 1)
    configureArchiveRenderer(renderer, qualityProfile)
    renderer.setClearColor('#eae5e1', 1)
    renderer.domElement.style.touchAction = 'none'
    element.appendChild(renderer.domElement)

    root = new Group()
    scene.add(root)
    archiveLibrary = createArchiveAssetLibrary()
    focusedGlassMaterial = createFocusedGlassMaterial(archiveLibrary)

    scene.add(new AmbientLight(0xfffbf5, 0.24))
    scene.add(new HemisphereLight(0xfffbf4, 0xa79f92, 0.82))
    keyLight = new DirectionalLight(0xfffbf1, 2.35)
    keyLight.position.set(-11, 18, 13)
    keyLight.castShadow = Boolean(qualityProfile.shadows)
    keyLight.shadow.mapSize.set(qualityProfile.name === 'HIGH' ? 2048 : 1024, qualityProfile.name === 'HIGH' ? 2048 : 1024)
    keyLight.shadow.camera.left = -28
    keyLight.shadow.camera.right = 28
    keyLight.shadow.camera.top = 26
    keyLight.shadow.camera.bottom = -18
    keyLight.shadow.camera.near = 1
    keyLight.shadow.camera.far = 85
    keyLight.shadow.bias = -0.0004
    keyLight.shadow.radius = 2.2
    scene.add(keyLight)
    const fill = new DirectionalLight(0xcabda9, 0.72)
    fill.position.set(14, 8, -10)
    scene.add(fill)
    const rim = new DirectionalLight(0xe6d8c5, 0.66)
    rim.position.set(7, 4, 18)
    scene.add(rim)

    const floorGeometry = new PlaneGeometry(200, 200)
    const floorMaterial = new MeshStandardMaterial({ color: '#d8c9b9', roughness: 0.95, metalness: 0.02 })
    const floor = new Mesh(floorGeometry, floorMaterial)
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -4.63
    floor.receiveShadow = true
    scene.add(floor)
    sceneDisposables.push(floorGeometry, floorMaterial)

    raycaster = new Raycaster()
    pointer = new Vector2()
    buildArchiveArray()
    resize()
    updateFocusVisuals()
    if (qualityProfile?.name !== 'MOBILE') loadFocusedArchiveAsset()

    renderer.domElement.addEventListener('pointerdown', onPointerDown)
    renderer.domElement.addEventListener('pointermove', onPointerMove)
    renderer.domElement.addEventListener('pointerup', event => finishPointer(event, false))
    renderer.domElement.addEventListener('pointercancel', event => finishPointer(event, true))
    renderer.domElement.addEventListener('lostpointercapture', event => finishPointer(event, true))
    renderer.domElement.addEventListener('pointerleave', () => {
      if (activePointer === null) {
        hoveredEntry = null
        updateFocusVisuals()
      }
    })
    renderer.domElement.addEventListener('wheel', onWheel, { passive: false })
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(element)
    startRendering()
  } catch (error) {
    console.error('Analysis OS WebGL scene failed to initialize', error)
    failed.value = true
  }
}

function dispose() {
  disposed = true
  composerRevision += 1
  if (postProbeTimer) window.clearTimeout(postProbeTimer)
  postProbeTimer = 0
  stopRendering()
  resizeObserver?.disconnect()
  clearArchive()
  for (const item of sceneDisposables.splice(0)) item?.dispose?.()
  disposeArchiveComposer(composerBundle)
  composerBundle = null
  focusedGlassMaterial?.dispose?.()
  focusedGlassMaterial = null
  archiveLibrary?.dispose?.()
  archiveLibrary = null
  disposeFocusedArchiveAsset()
  renderer?.dispose()
  renderer?.forceContextLoss?.()
  renderer?.domElement?.remove()
  renderer = scene = camera = root = raycaster = pointer = null
}

function getDebugState() {
  const visibleNear = entries.filter(entry => entry.nearGroup?.visible).length
  const visibleFocus = entries.filter(entry => entry.focusGroup?.visible).length
  return {
    cell: currentCell(),
    lane: laneTrack.value,
    row: rowTrack.value,
    laneTarget: laneTrack.target,
    rowTarget: rowTrack.target,
    laneVelocity: momentum?.velocity?.lane ?? laneTrack.velocity,
    rowVelocity: momentum?.velocity?.row ?? rowTrack.velocity,
    momentumPhase: momentum?.phase || 'idle',
    dragging: activePointer !== null && drag.active,
    focusedKey: props.focusedKey,
    retrievalState: props.retrievalState,
    focusedSlot: focusedEntry()?.group?.userData?.slotKey || null,
    extractionProgress: Number(props.extractionProgress) || 0,
    sleepAmount: Number(props.sleepAmount) || 0,
    quality: qualityProfile?.name || 'UNKNOWN',
    postProcessing: Boolean(composerBundle),
    postCandidate: Boolean(qualityProfile?.postCandidate),
    postProcessingStatus,
    detailAssetStatus,
    visibleNear,
    visibleFocus,
    drawCalls: renderer?.info?.render?.calls ?? 0,
    triangles: renderer?.info?.render?.triangles ?? 0,
    renderedFrames,
    canvasCount: renderer?.domElement?.isConnected ? 1 : 0,
  }
}

watch(() => props.modules, updateFocusVisuals, { deep: true })
watch(() => props.focusedKey, updateFocusVisuals)
watch(() => props.retrievalState, updateFocusVisuals)
watch(() => props.active, async active => {
  if (active) {
    await nextTick()
    if (props.active) {
      resize()
      startRendering()
    }
  } else {
    stopMomentum()
    stopRendering()
  }
})
watch(() => props.sleepAmount, amount => {
  if (amount <= 0.001) suppressSleepMotion = false
})
watch(() => props.extractionProgress, updateFocusVisuals)

onMounted(init)
onBeforeUnmount(dispose)

defineExpose({ getDebugState, shiftRows })
</script>

<template>
  <div ref="mountRef" class="analysis-scene" aria-hidden="true">
    <div v-if="failed" class="scene-fallback">
      <strong>WEBGL FALLBACK</strong>
      <span>三维档案场不可用，仍可使用顶部 MODULE INDEX 进入各业务模块。</span>
    </div>
  </div>
</template>

<style scoped>
.analysis-scene {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #e8e5e1;
}
.analysis-scene::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(180deg, rgba(248,246,240,.2), transparent 22%, transparent 78%, rgba(234,229,225,.1)),
    radial-gradient(ellipse at 48% 48%, transparent 56%, rgba(234,229,225,.08) 76%, rgba(234,229,225,.24) 100%);
}
.analysis-scene :deep(canvas) { width: 100%; height: 100%; display: block; }
.scene-fallback {
  position: absolute; inset: 0; display: grid; place-content: center; gap: 8px;
  color: #625f57; text-align: center; font-size: 12px;
}
.scene-fallback strong { color: #292b25; letter-spacing: .12em; }
</style>
