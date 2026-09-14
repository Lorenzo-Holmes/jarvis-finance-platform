<script setup>
import {
  AmbientLight,
  BoxGeometry,
  CanvasTexture,
  Color,
  DirectionalLight,
  Fog,
  Group,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  PerspectiveCamera,
  PlaneGeometry,
  Raycaster,
  Scene,
  SRGBColorSpace,
  TorusGeometry,
  Vector2,
  Vector3,
  WebGLRenderer,
} from 'three'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  assets: { type: Array, default: () => [] },
  selectedId: { type: String, default: '' },
  query: { type: String, default: '' },
  active: { type: Boolean, default: true },
})

const emit = defineEmits(['select'])
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
const entries = []
const sharedGeometries = []
const disposables = []
const sceneDisposables = []
const textureCache = new Map()
const cameraAim = new Vector3(0, -1.4, -0.2)

function matchesQuery(asset) {
  const query = String(props.query || '').trim().toLowerCase()
  if (!query) return true
  return [asset.symbol, asset.name, asset.marketLabel, asset.sector]
    .filter(Boolean)
    .some(value => String(value).toLowerCase().includes(query))
}

function makeLabelTexture(asset) {
  if (textureCache.has(asset.id)) return textureCache.get(asset.id)
  const canvas = document.createElement('canvas')
  canvas.width = 1024
  canvas.height = 220
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, canvas.width, canvas.height)

  ctx.fillStyle = 'rgba(244, 241, 234, .72)'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = 'rgba(112, 108, 99, .74)'
  ctx.lineWidth = 3
  ctx.strokeRect(8, 8, canvas.width - 16, canvas.height - 16)

  ctx.fillStyle = '#77736a'
  ctx.font = '600 22px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.marketLabel || asset.market || 'MARKET').toUpperCase(), 38, 45)

  ctx.fillStyle = '#191b17'
  ctx.font = '700 50px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.symbol || '--').slice(0, 12), 38, 111)

  ctx.fillStyle = '#3f423a'
  ctx.font = '500 27px system-ui, sans-serif'
  ctx.fillText(String(asset.name || '').slice(0, 20), 38, 158)

  ctx.fillStyle = '#77736a'
  ctx.font = '500 18px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.sector || 'RESEARCH OBJECT').slice(0, 30).toUpperCase(), 38, 196)
  ctx.fillText(`JARVIS / ${String(asset.dataState || 'catalog').toUpperCase()}`, 730, 43)

  ctx.fillStyle = '#4c5046'
  ctx.font = '650 24px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.textAlign = 'right'
  ctx.fillText(String(asset.price || '—'), 965, 116)
  ctx.fillStyle = asset.direction === 'down' ? '#98594f' : '#65745f'
  ctx.font = '650 18px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.change || '—'), 965, 154)
  ctx.textAlign = 'left'

  const texture = new CanvasTexture(canvas)
  texture.colorSpace = SRGBColorSpace
  texture.anisotropy = Math.min(renderer?.capabilities?.getMaxAnisotropy?.() || 1, 8)
  textureCache.set(asset.id, texture)
  disposables.push(texture)
  return texture
}

function clearSceneObjects() {
  while (root?.children?.length) root.remove(root.children[0])
  entries.splice(0, entries.length)
  hoveredEntry = null
  textureCache.clear()
  for (const item of disposables.splice(0)) item?.dispose?.()
  for (const geometry of sharedGeometries.splice(0)) geometry?.dispose?.()
}

function addFrame(group, geometry, material, x, y, z = 0.15) {
  const mesh = new Mesh(geometry, material)
  mesh.position.set(x, y, z)
  group.add(mesh)
  return mesh
}

function createArchiveCard(asset, lane, row) {
  const group = new Group()
  const cardGeometry = sharedGeometries[0]
  const verticalSpine = sharedGeometries[1]
  const ringGeometry = sharedGeometries[2]
  const ringSmallGeometry = sharedGeometries[3]
  const connectorGeometry = sharedGeometries[4]
  const markerGeometry = sharedGeometries[5]
  const labelGeometry = sharedGeometries[6]

  const shellMaterial = new MeshStandardMaterial({
    color: new Color('#e9e3d8'),
    metalness: 0.02,
    roughness: 0.52,
    transparent: true,
    opacity: 0.74,
  })
  const frameMaterial = new MeshStandardMaterial({
    color: '#858178', roughness: 0.5, metalness: 0.08,
    transparent: true, opacity: 0.43,
  })
  const opticMaterial = new MeshStandardMaterial({
    color: '#5d6155', roughness: 0.36, metalness: 0.2,
    transparent: true, opacity: 0.52,
  })
  const accentMaterial = new MeshStandardMaterial({
    color: asset.direction === 'down' ? '#b36b5e' : '#a98d5f',
    roughness: 0.32, metalness: 0.16,
    transparent: true, opacity: 0.58,
  })
  const labelMaterial = new MeshBasicMaterial({
    map: makeLabelTexture(asset), transparent: true, opacity: 0.92, toneMapped: false,
  })
  disposables.push(shellMaterial, frameMaterial, opticMaterial, accentMaterial, labelMaterial)

  const shell = new Mesh(cardGeometry, shellMaterial)
  shell.userData.assetId = asset.id
  group.add(shell)

  const spine = addFrame(group, verticalSpine, frameMaterial, 1.57, 0)
  spine.userData.assetId = asset.id

  const ringA = new Mesh(ringGeometry, opticMaterial)
  ringA.position.set(-0.42, -0.45, 0.19)
  ringA.visible = false
  group.add(ringA)
  const ringB = new Mesh(ringSmallGeometry, opticMaterial)
  ringB.position.set(0.47, -0.34, 0.19)
  ringB.visible = false
  group.add(ringB)
  const bridge = new Mesh(connectorGeometry, accentMaterial)
  bridge.position.set(0.05, -0.4, 0.2)
  bridge.rotation.z = -0.22
  bridge.visible = false
  group.add(bridge)

  let marker = null
  let label = null
  const showFrontDetails = row >= 18 || row % 5 === 0
  if (showFrontDetails) {
    marker = new Mesh(markerGeometry, accentMaterial)
    marker.position.set(1.42, -1.82, 0.22)
    group.add(marker)

    label = new Mesh(labelGeometry, labelMaterial)
    label.position.set(0, 1.72, 0.205)
    label.userData.assetId = asset.id
    group.add(label)
  }

  const baseX = (lane - 2) * 4.75
  const baseY = -2.18
  const baseZ = (row - 12.5) * 0.58
  group.position.set(baseX, baseY, baseZ)
  group.rotation.y = (2 - lane) * 0.014
  group.userData = {
    assetId: asset.id,
    lane,
    row,
    baseX,
    baseY,
    baseZ,
    targetY: baseY,
    targetZ: baseZ,
    targetScale: 1,
    shellMaterial,
    frameMaterial,
    opticMaterial,
    accentMaterial,
    labelMaterial,
    internals: [ringA, ringB, bridge],
    marker,
  }
  root.add(group)
  entries.push({ asset, group, hitTargets: label ? [shell, label] : [shell], lane, row })
}

function buildArchiveArray() {
  if (!root || !renderer) return
  clearSceneObjects()
  if (!props.assets.length) return

  const cardGeometry = new BoxGeometry(3.38, 4.95, 0.24)
  const verticalSpine = new BoxGeometry(0.10, 4.68, 0.09)
  const ringGeometry = new TorusGeometry(0.67, 0.08, 12, 42)
  const ringSmallGeometry = new TorusGeometry(0.42, 0.055, 10, 36)
  const connectorGeometry = new BoxGeometry(0.9, 0.13, 0.08)
  const markerGeometry = new BoxGeometry(0.14, 0.52, 0.08)
  const labelGeometry = new PlaneGeometry(2.86, 0.62)
  sharedGeometries.push(
    cardGeometry, verticalSpine, ringGeometry, ringSmallGeometry,
    connectorGeometry, markerGeometry, labelGeometry,
  )

  const rows = 26
  for (let row = 0; row < rows; row += 1) {
    for (let lane = 0; lane < 5; lane += 1) {
      const asset = props.assets[(row * 5 + lane) % props.assets.length]
      createArchiveCard(asset, lane, row)
    }
  }
  updateVisualState()
}

function selectedVisualEntry() {
  if (!props.selectedId) return null
  return entries
    .filter(entry => entry.asset.id === props.selectedId)
    .sort((a, b) => (Math.abs(a.row - 13) + Math.abs(a.lane - 2)) - (Math.abs(b.row - 13) + Math.abs(b.lane - 2)))[0] || null
}

function updateVisualState() {
  const selectedEntry = selectedVisualEntry()
  for (const entry of entries) {
    const data = entry.group.userData
    const selected = entry === selectedEntry
    const hovered = entry === hoveredEntry
    const visible = matchesQuery(entry.asset)
    entry.group.visible = visible
    if (!visible) continue

    data.targetY = data.baseY + (selected ? 3.75 : hovered ? 0.34 : 0)
    data.targetZ = data.baseZ + (selected ? 1.65 : 0)
    data.targetScale = selected ? 1.04 : 1

    const dim = selectedEntry && !selected ? 0.66 : 1
    data.shellMaterial.opacity = (selected ? 0.90 : 0.74) * dim
    data.frameMaterial.opacity = (selected ? 0.70 : 0.42) * dim
    data.accentMaterial.opacity = (selected ? 0.88 : 0.58) * dim
    data.labelMaterial.opacity = (selected ? 1 : 0.92) * dim
    data.internals.forEach(mesh => { mesh.visible = selected })
    if (data.marker) data.marker.visible = selected || entry.row >= 18
  }
}

function resize() {
  const element = mountRef.value
  if (!element || !renderer || !camera) return
  const width = Math.max(1, element.clientWidth)
  const height = Math.max(1, element.clientHeight)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.6))
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  if (width < 700) {
    camera.position.set(-14.5, 10.5, 35)
    camera.fov = 22
    cameraAim.set(0, -1.05, -0.3)
  } else if (width < 1100) {
    camera.position.set(-19, 11.5, 31)
    camera.fov = 18
    cameraAim.set(0, -1.25, -0.4)
  } else {
    camera.position.set(-25.5, 14.5, 34)
    camera.fov = 15
    cameraAim.set(-0.4, -1.45, -0.4)
  }
  camera.lookAt(cameraAim)
  camera.updateProjectionMatrix()
}

function pointerFromEvent(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
}

function pickEntry(event) {
  if (!renderer || !camera || !raycaster) return null
  pointerFromEvent(event)
  raycaster.setFromCamera(pointer, camera)
  const objects = entries.filter(entry => entry.group.visible).flatMap(entry => entry.hitTargets)
  const hit = raycaster.intersectObjects(objects, false)[0]
  if (!hit) return null
  return entries.find(entry => entry.hitTargets.includes(hit.object)) || null
}

function onPointerMove(event) {
  const entry = pickEntry(event)
  if (entry !== hoveredEntry) {
    hoveredEntry = entry
    updateVisualState()
  }
  renderer.domElement.style.cursor = entry ? 'pointer' : 'grab'
  if (reducedMotion || !root) return
  const rect = renderer.domElement.getBoundingClientRect()
  const nx = (event.clientX - rect.left) / rect.width - 0.5
  const ny = (event.clientY - rect.top) / rect.height - 0.5
  root.userData.targetRotationY = nx * 0.025
  root.userData.targetRotationX = -ny * 0.012
}

function onPointerLeave() {
  hoveredEntry = null
  updateVisualState()
  if (!root) return
  root.userData.targetRotationX = 0
  root.userData.targetRotationY = 0
  if (renderer) renderer.domElement.style.cursor = 'grab'
}

function onClick(event) {
  const entry = pickEntry(event)
  if (entry) emit('select', entry.asset.id)
}

function onWheel(event) {
  if (!root || props.selectedId) return
  event.preventDefault()
  const next = (root.userData.targetZOffset || 0) - event.deltaY * 0.0014
  root.userData.targetZOffset = Math.max(-2.4, Math.min(2.4, next))
}

function renderFrame(time) {
  if (disposed || !renderer || !scene || !camera || !root || !props.active) {
    rendering = false
    animationFrame = 0
    return
  }
  const easing = reducedMotion ? 1 : 0.1
  root.rotation.x += ((root.userData.targetRotationX || 0) - root.rotation.x) * easing
  root.rotation.y += ((root.userData.targetRotationY || 0) - root.rotation.y) * easing
  root.position.z += ((root.userData.targetZOffset || 0) - root.position.z) * easing

  const selectedEntry = selectedVisualEntry()
  entries.forEach((entry, index) => {
    if (!entry.group.visible) return
    const data = entry.group.userData
    const idleWave = !reducedMotion && !selectedEntry
      ? Math.sin(time * 0.00042 + entry.row * 0.54 + entry.lane * 0.71) * 0.105
      : 0
    const targetY = data.targetY + idleWave
    entry.group.position.y += (targetY - entry.group.position.y) * easing
    entry.group.position.z += (data.targetZ - entry.group.position.z) * easing
    const nextScale = entry.group.scale.x + (data.targetScale - entry.group.scale.x) * easing
    entry.group.scale.setScalar(nextScale)
    if (!reducedMotion && !selectedEntry) {
      entry.group.rotation.x = Math.sin(time * 0.00031 + index * 0.24) * 0.006
    } else {
      entry.group.rotation.x *= (1 - easing)
    }
  })

  renderer.render(scene, camera)
  animationFrame = requestAnimationFrame(renderFrame)
}

function stopRendering() {
  if (animationFrame) cancelAnimationFrame(animationFrame)
  animationFrame = 0
  rendering = false
}

function startRendering() {
  if (disposed || !renderer || rendering || !props.active) return
  rendering = true
  animationFrame = requestAnimationFrame(renderFrame)
}

function init() {
  const element = mountRef.value
  if (!element) return
  try {
    reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false
    scene = new Scene()
    scene.background = new Color('#e8e5e1')
    scene.fog = new Fog('#e8e5e1', 31, 54)

    camera = new PerspectiveCamera(15, 1, 0.1, 140)
    renderer = new WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
    renderer.outputColorSpace = SRGBColorSpace
    renderer.setClearColor('#e8e5e1', 1)
    element.appendChild(renderer.domElement)

    root = new Group()
    root.userData.targetRotationX = 0
    root.userData.targetRotationY = 0
    root.userData.targetZOffset = 0
    scene.add(root)

    scene.add(new AmbientLight(0xffffff, 1.25))
    const key = new DirectionalLight(0xfffcf5, 1.9)
    key.position.set(-9, 15, 12)
    scene.add(key)
    const fill = new DirectionalLight(0xc6bca8, 0.7)
    fill.position.set(12, 7, -8)
    scene.add(fill)

    const floorGeometry = new PlaneGeometry(120, 120)
    const floorMaterial = new MeshStandardMaterial({ color: '#e6e1d8', roughness: 0.94, metalness: 0 })
    const floor = new Mesh(floorGeometry, floorMaterial)
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -2.52
    scene.add(floor)
    sceneDisposables.push(floorGeometry, floorMaterial)

    raycaster = new Raycaster()
    pointer = new Vector2()
    buildArchiveArray()
    resize()

    renderer.domElement.addEventListener('pointermove', onPointerMove)
    renderer.domElement.addEventListener('pointerleave', onPointerLeave)
    renderer.domElement.addEventListener('click', onClick)
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
  stopRendering()
  resizeObserver?.disconnect()
  if (renderer?.domElement) {
    renderer.domElement.removeEventListener('pointermove', onPointerMove)
    renderer.domElement.removeEventListener('pointerleave', onPointerLeave)
    renderer.domElement.removeEventListener('click', onClick)
    renderer.domElement.removeEventListener('wheel', onWheel)
  }
  clearSceneObjects()
  for (const item of sceneDisposables.splice(0)) item?.dispose?.()
  renderer?.dispose()
  renderer?.forceContextLoss?.()
  renderer?.domElement?.remove()
  renderer = null
  scene = null
  camera = null
  root = null
}

watch(() => props.assets, buildArchiveArray, { deep: true })
watch(() => [props.selectedId, props.query], updateVisualState)
watch(() => props.active, async active => {
  if (active) {
    await nextTick()
    if (props.active) {
      resize()
      startRendering()
    }
  } else stopRendering()
})

onMounted(init)
onBeforeUnmount(dispose)
</script>

<template>
  <div ref="mountRef" class="analysis-scene" aria-hidden="true">
    <div v-if="failed" class="scene-fallback">
      <strong>WEBGL FALLBACK</strong>
      <span>三维渲染不可用，仍可通过资产索引进入研究详情。</span>
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
    linear-gradient(180deg, rgba(248,246,240,.38), transparent 30%),
    radial-gradient(ellipse at 43% 48%, transparent 30%, rgba(224,218,207,.2) 88%);
  mix-blend-mode: multiply;
}
.analysis-scene :deep(canvas) { width: 100%; height: 100%; display: block; }
.scene-fallback {
  position: absolute; inset: 0; z-index: 2;
  display: grid; place-content: center; gap: 8px; text-align: center;
  color: #6f6b63; font: 12px/1.5 ui-monospace, monospace;
}
.scene-fallback strong { color: #20221d; letter-spacing: .12em; }
</style>
