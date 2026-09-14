<script setup>
import {
  ACESFilmicToneMapping,
  BoxGeometry,
  CanvasTexture,
  DirectionalLight,
  EdgesGeometry,
  FogExp2,
  GridHelper,
  Group,
  HemisphereLight,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  PerspectiveCamera,
  PlaneGeometry,
  PointLight,
  Raycaster,
  Scene,
  SRGBColorSpace,
  Vector2,
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
const entries = []
const sharedGeometries = []
const disposables = []
const sceneDisposables = []

function matchesQuery(asset) {
  const query = String(props.query || '').trim().toLowerCase()
  if (!query) return true
  return [asset.symbol, asset.name, asset.marketLabel, asset.sector]
    .filter(Boolean)
    .some(value => String(value).toLowerCase().includes(query))
}

function makeLabelTexture(asset) {
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 640
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = 'rgba(7, 13, 13, 0.92)'
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  ctx.strokeStyle = 'rgba(182, 255, 226, 0.5)'
  ctx.lineWidth = 3
  ctx.strokeRect(22, 22, 468, 596)

  ctx.fillStyle = '#9fb4ae'
  ctx.font = '600 23px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.marketLabel || asset.market || 'MARKET').toUpperCase(), 44, 76)

  ctx.fillStyle = '#f0fff9'
  ctx.font = '700 76px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.symbol || '--').slice(0, 10), 44, 188)

  ctx.fillStyle = '#c6d8d2'
  ctx.font = '500 30px system-ui, sans-serif'
  ctx.fillText(String(asset.name || '').slice(0, 18), 44, 242)

  ctx.fillStyle = '#778e87'
  ctx.font = '500 22px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.sector || 'RESEARCH OBJECT').slice(0, 26).toUpperCase(), 44, 306)

  ctx.fillStyle = '#ecfff8'
  ctx.font = '700 46px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.price || '--'), 44, 418)

  ctx.fillStyle = asset.direction === 'down' ? '#ff8f92' : '#91ffc8'
  ctx.font = '700 31px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(String(asset.change || '--'), 44, 470)

  ctx.fillStyle = '#61736d'
  ctx.font = '500 19px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(`JARVIS / ${String(asset.dataState || 'catalog').toUpperCase()}`, 44, 574)

  const texture = new CanvasTexture(canvas)
  texture.colorSpace = SRGBColorSpace
  texture.anisotropy = Math.min(renderer?.capabilities?.getMaxAnisotropy?.() || 1, 8)
  disposables.push(texture)
  return texture
}

function clearSceneObjects() {
  while (root?.children?.length) root.remove(root.children[0])
  entries.splice(0, entries.length)
  for (const item of disposables.splice(0)) item?.dispose?.()
  for (const geometry of sharedGeometries.splice(0)) geometry?.dispose?.()
}

function buildArchiveArray() {
  if (!root || !renderer) return
  clearSceneObjects()

  const cardGeometry = new BoxGeometry(2.34, 3.18, 0.16)
  const labelGeometry = new PlaneGeometry(2.12, 2.66)
  const edgeGeometry = new EdgesGeometry(cardGeometry)
  sharedGeometries.push(cardGeometry, labelGeometry, edgeGeometry)

  const cards = props.assets.slice(0, 15)
  cards.forEach((asset, index) => {
    const group = new Group()
    const col = index % 5
    const row = Math.floor(index / 5)
    const baseX = (col - 2) * 2.95
    const baseY = (1 - row) * 3.72
    const baseZ = -Math.abs(col - 2) * 0.35 - Math.abs(row - 1) * 0.16
    group.position.set(baseX, baseY, baseZ)
    group.rotation.y = (2 - col) * 0.035

    const bodyMaterial = new MeshPhysicalMaterial({
      color: 0x101b19,
      metalness: 0.14,
      roughness: 0.28,
      transmission: 0.18,
      transparent: true,
      opacity: 0.82,
      clearcoat: 0.9,
      clearcoatRoughness: 0.22,
      thickness: 0.34,
    })
    disposables.push(bodyMaterial)
    const body = new Mesh(cardGeometry, bodyMaterial)
    body.userData.assetId = asset.id
    group.add(body)

    const edgesMaterial = new LineBasicMaterial({
      color: 0x8fffc8,
      transparent: true,
      opacity: 0.34,
    })
    disposables.push(edgesMaterial)
    group.add(new LineSegments(edgeGeometry, edgesMaterial))

    const labelMaterial = new MeshBasicMaterial({
      map: makeLabelTexture(asset),
      transparent: true,
      opacity: 0.96,
      toneMapped: false,
    })
    disposables.push(labelMaterial)
    const label = new Mesh(labelGeometry, labelMaterial)
    label.position.z = 0.088
    label.userData.assetId = asset.id
    group.add(label)

    const accentGeometry = new BoxGeometry(0.12, 0.6, 0.06)
    sharedGeometries.push(accentGeometry)
    const accentMaterial = new MeshBasicMaterial({
      color: asset.direction === 'down' ? 0xff7378 : 0x7fffc1,
      transparent: true,
      opacity: 0.85,
    })
    disposables.push(accentMaterial)
    const accent = new Mesh(accentGeometry, accentMaterial)
    accent.position.set(1.01, -1.12, 0.13)
    group.add(accent)

    group.userData = {
      assetId: asset.id,
      baseX,
      baseY,
      baseZ,
      targetZ: baseZ,
      targetScale: 1,
      bodyMaterial,
      labelMaterial,
      edgesMaterial,
    }
    root.add(group)
    entries.push({ asset, group, hitTargets: [body, label] })
  })
  updateVisualState()
}

function updateVisualState() {
  for (const entry of entries) {
    const selected = props.selectedId && entry.asset.id === props.selectedId
    const dimmedBySelection = Boolean(props.selectedId) && !selected
    const visible = matchesQuery(entry.asset)
    const userData = entry.group.userData
    userData.targetZ = userData.baseZ + (selected ? 2.5 : 0)
    userData.targetScale = selected ? 1.1 : 1
    userData.bodyMaterial.opacity = visible ? (dimmedBySelection ? 0.2 : 0.82) : 0.08
    userData.labelMaterial.opacity = visible ? (dimmedBySelection ? 0.2 : 0.96) : 0.06
    userData.edgesMaterial.opacity = visible ? (selected ? 0.92 : dimmedBySelection ? 0.08 : 0.34) : 0.03
  }
}

function resize() {
  const element = mountRef.value
  if (!element || !renderer || !camera) return
  const width = Math.max(1, element.clientWidth)
  const height = Math.max(1, element.clientHeight)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.7))
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function pointerFromEvent(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
}

function pick(event) {
  if (!renderer || !camera || !raycaster) return null
  pointerFromEvent(event)
  raycaster.setFromCamera(pointer, camera)
  const objects = entries.flatMap(entry => entry.hitTargets)
  const hit = raycaster.intersectObjects(objects, false)[0]
  if (!hit) return null
  const id = hit.object.userData.assetId
  return entries.find(entry => entry.asset.id === id)?.asset || null
}

function onPointerMove(event) {
  const asset = pick(event)
  renderer.domElement.style.cursor = asset ? 'pointer' : 'default'
  if (reducedMotion || !root) return
  const rect = renderer.domElement.getBoundingClientRect()
  const nx = (event.clientX - rect.left) / rect.width - 0.5
  const ny = (event.clientY - rect.top) / rect.height - 0.5
  root.userData.targetRotationY = nx * 0.08
  root.userData.targetRotationX = -ny * 0.05
}

function onPointerLeave() {
  if (!root) return
  root.userData.targetRotationX = 0
  root.userData.targetRotationY = 0
  if (renderer) renderer.domElement.style.cursor = 'default'
}

function onClick(event) {
  const asset = pick(event)
  if (asset) emit('select', asset.id)
}

function renderFrame(time) {
  if (disposed || !renderer || !scene || !camera || !root || !props.active) {
    rendering = false
    animationFrame = 0
    return
  }
  const easing = reducedMotion ? 1 : 0.12
  root.rotation.x += ((root.userData.targetRotationX || 0) - root.rotation.x) * easing
  root.rotation.y += ((root.userData.targetRotationY || 0) - root.rotation.y) * easing

  entries.forEach((entry, index) => {
    const data = entry.group.userData
    entry.group.position.z += (data.targetZ - entry.group.position.z) * easing
    const nextScale = entry.group.scale.x + (data.targetScale - entry.group.scale.x) * easing
    entry.group.scale.setScalar(nextScale)
    if (!reducedMotion && !props.selectedId) {
      entry.group.position.y = data.baseY + Math.sin(time * 0.00045 + index * 0.47) * 0.045
    } else {
      entry.group.position.y += (data.baseY - entry.group.position.y) * easing
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
    scene.fog = new FogExp2(0x060908, 0.018)

    camera = new PerspectiveCamera(38, 1, 0.1, 100)
    camera.position.set(0, 0.15, 19.4)

    renderer = new WebGLRenderer({
      antialias: true,
      alpha: true,
      powerPreference: 'high-performance',
    })
    renderer.outputColorSpace = SRGBColorSpace
    renderer.toneMapping = ACESFilmicToneMapping
    renderer.toneMappingExposure = 1.06
    element.appendChild(renderer.domElement)

    root = new Group()
    root.userData.targetRotationX = 0
    root.userData.targetRotationY = 0
    scene.add(root)

    scene.add(new HemisphereLight(0xcfffee, 0x07100d, 1.45))
    const key = new DirectionalLight(0xffffff, 2.2)
    key.position.set(-4, 7, 10)
    scene.add(key)
    const rim = new PointLight(0x79ffc0, 18, 30)
    rim.position.set(7, -3, 8)
    scene.add(rim)

    const grid = new GridHelper(34, 34, 0x24443a, 0x12221d)
    grid.rotation.x = Math.PI / 2
    grid.position.z = -5.5
    const gridMaterials = Array.isArray(grid.material) ? grid.material : [grid.material]
    gridMaterials.forEach(material => {
      material.transparent = true
      material.opacity = 0.18
      sceneDisposables.push(material)
    })
    sceneDisposables.push(grid.geometry)
    scene.add(grid)

    raycaster = new Raycaster()
    pointer = new Vector2()
    buildArchiveArray()
    resize()

    renderer.domElement.addEventListener('pointermove', onPointerMove)
    renderer.domElement.addEventListener('pointerleave', onPointerLeave)
    renderer.domElement.addEventListener('click', onClick)
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
watch(() => props.active, async (active) => {
  if (active) {
    await nextTick()
    if (props.active) {
      resize()
      startRendering()
    }
  } else {
    stopRendering()
  }
})

onMounted(init)
onBeforeUnmount(dispose)
</script>

<template>
  <div ref="mountRef" class="analysis-scene" aria-hidden="true">
    <div v-if="failed" class="scene-fallback">
      <strong>WEBGL FALLBACK</strong>
      <span>三维渲染不可用，仍可通过下方资产列表进入研究详情。</span>
    </div>
  </div>
</template>

<style scoped>
.analysis-scene {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 50% 34%, rgba(112, 255, 195, .075), transparent 34%),
    linear-gradient(180deg, rgba(8, 13, 12, .82), rgba(5, 8, 8, .96));
}
.analysis-scene :deep(canvas) { width: 100%; height: 100%; display: block; }
.scene-fallback {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  gap: 8px;
  text-align: center;
  color: #a7bbb4;
  font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.scene-fallback strong { color: #dfffee; letter-spacing: .12em; }
</style>
