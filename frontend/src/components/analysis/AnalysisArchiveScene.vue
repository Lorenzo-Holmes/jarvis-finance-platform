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
import { cellForModule, moduleAtCell, moduleByKey, wrap } from '../../analysis-os/data/modules'
import { ArchiveDrag, ArchivePlaneMomentum, dampSpring } from '../../analysis-os/motion/archiveMomentum'
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
  resizeArchiveComposer,
} from '../../analysis-os/render/renderQuality'

const LANE_SPACING = 4.52
const ROW_SPACING = 0.56
const CENTER_LANE = 2
const CENTER_ROW = 12
const ROW_PERIOD = 6

const props = defineProps({
  modules: { type: Array, default: () => [] },
  focusedKey: { type: String, default: '' },
  sleepAmount: { type: Number, default: 0 },
  extractionProgress: { type: Number, default: 0 },
  active: { type: Boolean, default: true },
})

const emit = defineEmits(['focus', 'activate', 'interaction'])
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
let internalFocusKey = ''
let initializedTrack = false
let suppressSleepMotion = false
let archiveLibrary = null
let focusedGlassMaterial = null
let composerBundle = null
let qualityProfile = null
let keyLight = null
let renderedFrames = 0
let composerRevision = 0

const drag = new ArchiveDrag()
const laneTrack = { value: CENTER_LANE, velocity: 0, target: CENTER_LANE }
const rowTrack = { value: CENTER_ROW, velocity: 0, target: CENTER_ROW }
const entries = []
const textureCache = new Map()
const materialCache = new Map()
const sceneDisposables = []
const cameraAim = new Vector3(-0.2, -1.22, -0.6)
const cameraBase = new Vector3(-23.8, 11.9, 31.5)
const cameraAimBase = new Vector3(-0.2, -1.22, -0.6)
const cameraDetailBase = new Vector3(-14.8, 7.5, 22.3)
const cameraDetailAim = new Vector3(3.25, 0.3, -0.2)
let cameraBaseFov = 16.4
let cameraDetailFov = 13.6

function smoothstep(value) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
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
  hoveredEntry = null
  for (const material of materialCache.values()) material.dispose()
  for (const texture of textureCache.values()) texture.dispose()
  materialCache.clear()
  textureCache.clear()
}

function createCard(module, physicalLane, physicalRow) {
  const assembly = createArchiveAssembly(module, labelMaterial(module), archiveLibrary)
  const group = assembly.group

  const baseX = (physicalLane - CENTER_LANE) * LANE_SPACING
  const baseY = -2.12
  const baseZ = (physicalRow - CENTER_ROW) * ROW_SPACING
  group.position.set(baseX, baseY, baseZ)
  group.rotation.y = (CENTER_LANE - physicalLane) * 0.0105
  group.userData = { baseY, targetY: baseY, physicalLane, physicalRow, moduleKey: module.key }
  root.add(group)
  entries.push({ module, ...assembly, group, physicalLane, physicalRow })
}

function buildArchiveArray() {
  if (!root || !renderer || !archiveLibrary) return
  clearArchive()
  if (!props.modules.length) return

  for (let physicalRow = 0; physicalRow <= 24; physicalRow += 1) {
    for (let physicalLane = -2; physicalLane <= 6; physicalLane += 1) {
      const module = moduleAtCell(physicalLane, physicalRow, props.modules)
      if (module) createCard(module, physicalLane, physicalRow)
    }
  }

  if (!initializedTrack) {
    const initial = cellForModule(props.focusedKey || props.modules[0]?.key, { lane: CENTER_LANE, row: CENTER_ROW }, props.modules)
    laneTrack.value = laneTrack.target = initial.lane
    rowTrack.value = rowTrack.target = initial.row
    initializedTrack = true
  }
  updateFocusVisuals()
}

function currentCell() {
  return { lane: Math.round(laneTrack.value), row: Math.round(rowTrack.value) }
}

function focusedEntry() {
  const cell = currentCell()
  return entries.find(entry => entry.physicalLane === cell.lane && entry.physicalRow === cell.row) || null
}

function updateFocusVisuals() {
  const focused = focusedEntry()
  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  for (const entry of entries) {
    const isFocused = entry === focused
    const isHovered = entry === hoveredEntry
    const focusedLift = 0.42 + extraction * (4.05 - 0.42)
    entry.group.userData.targetY = entry.group.userData.baseY + (isFocused ? focusedLift : isHovered ? 0.28 : 0)
    entry.group.userData.targetScale = isFocused ? 1.018 + extraction * 0.035 : 1
    if (entry.glass) {
      entry.glass.material = isFocused && extraction > 0.32
        ? focusedGlassMaterial
        : archiveLibrary.materials.glass
    }
  }
}

function emitFocusFromTrack() {
  const cell = currentCell()
  const module = moduleAtCell(cell.lane, cell.row, props.modules)
  if (!module || module.key === internalFocusKey) {
    updateFocusVisuals()
    return
  }
  internalFocusKey = module.key
  emit('focus', module.key)
  updateFocusVisuals()
}

function moveToModule(key) {
  const current = currentCell()
  const target = cellForModule(key, current, props.modules)
  momentum = null
  laneTrack.target = target.lane
  rowTrack.target = target.row
  laneTrack.velocity = 0
  rowTrack.velocity = 0
  emit('interaction', 'index')
}

function rebaseTracksIfNeeded() {
  const laneRounded = Math.round(laneTrack.value)
  const canonicalLane = wrap(laneRounded, 5)
  const laneShift = laneRounded - canonicalLane
  if (Math.abs(laneShift) >= 5) {
    laneTrack.value -= laneShift
    laneTrack.target -= laneShift
    if (momentum) {
      momentum.lane.value -= laneShift
      momentum.lane.target -= laneShift
    }
  }
  const rowRounded = Math.round(rowTrack.value)
  const rowShift = Math.round((rowRounded - CENTER_ROW) / ROW_PERIOD) * ROW_PERIOD
  if (Math.abs(rowShift) >= ROW_PERIOD) {
    rowTrack.value -= rowShift
    rowTrack.target -= rowShift
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
  const center = new Vector3(0, -2.12, 0)
  const base = screenPoint(center)
  const lane = screenPoint(center.clone().add(new Vector3(-LANE_SPACING, 0, 0)))
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
  emitFocusFromTrack()
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
  hoveredEntry = null
  laneTrack.value = laneTrack.target = dragStart.lane + drag.value.lane
  rowTrack.value = rowTrack.target = dragStart.row + drag.value.row
  laneTrack.velocity = rowTrack.velocity = 0
  renderer.domElement.style.cursor = 'grabbing'
  emitFocusFromTrack()
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
      if (current?.module.key === entry.module.key && current.physicalLane === entry.physicalLane && current.physicalRow === entry.physicalRow) {
        emit('activate', entry.module.key)
      } else {
        laneTrack.target = entry.physicalLane
        rowTrack.target = entry.physicalRow
        internalFocusKey = entry.module.key
        emit('focus', entry.module.key)
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
  rowTrack.target = Math.round(rowTrack.target) + direction * steps
}

function resize() {
  const element = mountRef.value
  if (!element || !renderer || !camera) return
  const width = Math.max(1, element.clientWidth)
  const height = Math.max(1, element.clientHeight)
  const nextQuality = archiveQualityProfile(width, window.devicePixelRatio || 1)
  const qualityChanged = !qualityProfile || qualityProfile.name !== nextQuality.name
  qualityProfile = nextQuality
  configureArchiveRenderer(renderer, qualityProfile)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, qualityProfile.maxDpr))
  renderer.setSize(width, height, false)
  if (qualityChanged || (qualityProfile.post && !composerBundle)) rebuildComposer(width, height, qualityProfile)
  resizeArchiveComposer(composerBundle, width, height)
  if (keyLight) {
    const shadowSize = qualityProfile.name === 'HIGH' ? 2048 : 1024
    keyLight.castShadow = Boolean(qualityProfile.shadows)
    keyLight.shadow.mapSize.set(shadowSize, shadowSize)
  }
  camera.aspect = width / height
  if (width < 700) {
    cameraBase.set(-13.8, 9.5, 33)
    cameraBaseFov = 22.5
    cameraDetailBase.set(-8.9, 6.8, 25.7)
    cameraDetailAim.set(1.2, 0.4, 0)
    cameraDetailFov = 18
    cameraAimBase.set(0, -0.92, -0.5)
  } else if (width < 1100) {
    cameraBase.set(-18.8, 10.4, 29.8)
    cameraBaseFov = 18.4
    cameraDetailBase.set(-12.5, 7.2, 23.2)
    cameraDetailAim.set(2.5, 0.25, 0)
    cameraDetailFov = 15
    cameraAimBase.set(0, -1.08, -0.55)
  } else {
    cameraBase.set(-23.8, 11.9, 31.5)
    cameraBaseFov = 16.4
    cameraDetailBase.set(-14.8, 7.5, 22.3)
    cameraDetailAim.set(3.25, 0.3, -0.2)
    cameraDetailFov = 13.6
    cameraAimBase.set(-0.2, -1.22, -0.6)
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

function renderFrame(time) {
  if (disposed || !renderer || !scene || !camera || !root || !props.active) {
    rendering = false
    animationFrame = 0
    return
  }
  const dt = Math.min(Math.max((time - lastFrameTime) / 1000, 0.001), 0.05)
  lastFrameTime = time
  renderedFrames += 1

  if (momentum) {
    momentum.step(dt)
    laneTrack.value = laneTrack.target = momentum.value.lane
    rowTrack.value = rowTrack.target = momentum.value.row
    rebaseTracksIfNeeded()
    emitFocusFromTrack()
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
    if (nextCell.lane !== previousCell.lane || nextCell.row !== previousCell.row) emitFocusFromTrack()
    if (Math.abs(laneTrack.value - laneTrack.target) < 0.0004 && Math.abs(rowTrack.value - rowTrack.target) < 0.0004) rebaseTracksIfNeeded()
  }

  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  const detail = smoothstep(Math.max(0, (extraction - 0.08) / 0.92))
  const sleep = sleepOffsets(time)
  sleep.amount *= (1 - detail)
  sleep.lane *= (1 - detail)
  sleep.row *= (1 - detail)
  root.position.x = -(laneTrack.value + sleep.lane - CENTER_LANE) * LANE_SPACING
  root.position.z = -(rowTrack.value + sleep.row - CENTER_ROW) * ROW_SPACING

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

  const easing = reducedMotion ? 1 : 1 - Math.exp(-dt * 10)
  const focused = focusedEntry()
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
      ? Math.sin(time * 0.00034 + entry.physicalRow * 0.32 + entry.physicalLane * 0.41) * 0.045
      : 0
    const sleepWave = sleep.amount
      ? (Math.sin(time * 0.00055 + entry.physicalRow * 0.22 - entry.physicalLane * 0.35) * 0.11
        + Math.sin(time * 0.00029 - entry.physicalRow * 0.11 + entry.physicalLane * 0.27) * 0.05) * sleep.amount
      : 0
    entry.group.position.y += (data.targetY + idle + sleepWave - entry.group.position.y) * easing
    const targetScale = data.targetScale || 1
    const scale = entry.group.scale.x + (targetScale - entry.group.scale.x) * easing
    entry.group.scale.setScalar(scale)
    entry.group.rotation.x = focused === entry ? 0 : Math.sin(time * 0.00019 + index * 0.17) * 0.0025

    const laneDistance = Math.abs(entry.physicalLane - (laneTrack.value + sleep.lane))
    const rowDistance = Math.abs(entry.physicalRow - (rowTrack.value + sleep.row))
    const isFocused = entry === focused
    const isNear = isFocused || (laneDistance <= 2.2 && rowDistance <= 4.6)
    const showIdentity = isFocused || (laneDistance <= 2.6 && rowDistance <= 5.4)
    if (entry.identityGroup) entry.identityGroup.visible = showIdentity
    entry.nearGroup.visible = isNear
    entry.focusGroup.visible = isFocused || entry === hoveredEntry
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

  if (composerBundle) composerBundle.composer.render()
  else renderer.render(scene, camera)
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
    scene.background = new Color('#dfdbd3')
    scene.fog = new Fog('#dfdbd3', 42, 76)
    camera = new PerspectiveCamera(16.4, 1, 0.1, 150)
    renderer = new WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
    qualityProfile = archiveQualityProfile(element.clientWidth || 1440, window.devicePixelRatio || 1)
    configureArchiveRenderer(renderer, qualityProfile)
    renderer.setClearColor('#dfdbd3', 1)
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

    const floorGeometry = new PlaneGeometry(120, 120)
    const floorMaterial = new MeshStandardMaterial({ color: '#cfc8bd', roughness: 0.9, metalness: 0.02 })
    const floor = new Mesh(floorGeometry, floorMaterial)
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -2.55
    floor.receiveShadow = true
    scene.add(floor)
    sceneDisposables.push(floorGeometry, floorMaterial)

    raycaster = new Raycaster()
    pointer = new Vector2()
    buildArchiveArray()
    resize()
    emitFocusFromTrack()

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
    focusedKey: focusedEntry()?.module?.key || props.focusedKey,
    extractionProgress: Number(props.extractionProgress) || 0,
    sleepAmount: Number(props.sleepAmount) || 0,
    quality: qualityProfile?.name || 'UNKNOWN',
    postProcessing: Boolean(composerBundle),
    postCandidate: Boolean(qualityProfile?.postCandidate),
    visibleNear,
    visibleFocus,
    drawCalls: renderer?.info?.render?.calls ?? 0,
    triangles: renderer?.info?.render?.triangles ?? 0,
    renderedFrames,
    canvasCount: renderer?.domElement?.isConnected ? 1 : 0,
  }
}

watch(() => props.modules, buildArchiveArray, { deep: true })
watch(() => props.focusedKey, key => {
  if (!key) return
  if (key === internalFocusKey) return
  moveToModule(key)
})
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

defineExpose({ getDebugState, moveToModule })
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
    linear-gradient(180deg, rgba(248,246,240,.4), transparent 30%),
    radial-gradient(ellipse at 43% 48%, transparent 30%, rgba(224,218,207,.2) 88%);
}
.analysis-scene :deep(canvas) { width: 100%; height: 100%; display: block; }
.scene-fallback {
  position: absolute; inset: 0; display: grid; place-content: center; gap: 8px;
  color: #625f57; text-align: center; font-size: 12px;
}
.scene-fallback strong { color: #292b25; letter-spacing: .12em; }
</style>
