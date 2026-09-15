import {
  BoxGeometry,
  Color,
  CylinderGeometry,
  Group,
  Mesh,
  MeshPhysicalMaterial,
  MeshStandardMaterial,
  PlaneGeometry,
  TorusGeometry,
} from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

function shadow(mesh, cast = true, receive = true) {
  mesh.castShadow = cast
  mesh.receiveShadow = receive
  return mesh
}

export function createArchiveAssetLibrary() {
  const geometries = {
    body: new RoundedBoxGeometry(5.00, 3.70, 0.31, 3, 0.075),
    inset: new RoundedBoxGeometry(4.72, 3.42, 0.06, 3, 0.065),
    glass: new RoundedBoxGeometry(4.64, 3.34, 0.05, 3, 0.07),
    sideBar: new BoxGeometry(0.08, 3.34, 0.07),
    topBar: new BoxGeometry(4.56, 0.08, 0.07),
    rail: new BoxGeometry(0.07, 2.40, 0.04),
    bridge: new BoxGeometry(0.05, 1.95, 0.05),
    latch: new RoundedBoxGeometry(0.78, 0.16, 0.08, 2, 0.032),
    marker: new RoundedBoxGeometry(0.14, 0.44, 0.07, 2, 0.022),
    labelCarrier: new RoundedBoxGeometry(4.28, 0.70, 0.04, 2, 0.032),
    label: new PlaneGeometry(4.10, 0.56),
    ring: new TorusGeometry(0.43, 0.03, 8, 40),
    fastener: new CylinderGeometry(0.055, 0.055, 0.04, 12),
    decrypt: new BoxGeometry(1.16, 0.022, 0.018),
    glassSide: new BoxGeometry(0.03, 3.10, 0.022),
    glassTop: new BoxGeometry(4.42, 0.03, 0.022),
  }

  const materials = {
    body: new MeshStandardMaterial({ color: new Color('#f7f2eb'), roughness: 0.36, metalness: 0.04 }),
    inset: new MeshStandardMaterial({ color: new Color('#fcf8f2'), roughness: 0.46, metalness: 0.02 }),
    frame: new MeshStandardMaterial({ color: new Color('#c9bcad'), roughness: 0.42, metalness: 0.14 }),
    rail: new MeshStandardMaterial({ color: new Color('#d2c5b6'), roughness: 0.4, metalness: 0.16 }),
    inner: new MeshStandardMaterial({ color: new Color('#666860'), roughness: 0.34, metalness: 0.16 }),
    accent: new MeshStandardMaterial({ color: new Color('#92764d'), roughness: 0.28, metalness: 0.34 }),
    labelCarrier: new MeshStandardMaterial({ color: new Color('#f8f3ec'), roughness: 0.54, metalness: 0.02 }),
    glass: new MeshPhysicalMaterial({
      color: new Color('#fffdfa'),
      roughness: 0.21,
      metalness: 0,
      transmission: 0.68,
      thickness: 0.10,
      ior: 1.46,
      transparent: true,
      opacity: 0.92,
      depthWrite: false,
    }),
    decrypt: new MeshStandardMaterial({
      color: new Color('#9b7d52'),
      emissive: new Color('#6c5434'),
      emissiveIntensity: 0.18,
      roughness: 0.32,
      metalness: 0.2,
      transparent: true,
      opacity: 0.72,
    }),
    gasket: new MeshStandardMaterial({
      color: new Color('#4c4c47'),
      roughness: 0.42,
      metalness: 0.18,
      transparent: true,
      opacity: 0.72,
    }),
  }

  return {
    geometries,
    materials,
    dispose() {
      Object.values(geometries).forEach(item => item?.dispose?.())
      Object.values(materials).forEach(item => item?.dispose?.())
    },
  }
}

export function createFocusedGlassMaterial(library) {
  return new MeshPhysicalMaterial({
    color: new Color('#ece9e2'),
    roughness: 0.42,
    metalness: 0,
    transmission: 0.36,
    thickness: 0.13,
    ior: 1.46,
    transparent: true,
    opacity: 0.98,
    depthWrite: false,
  })
}

export function createArchiveAssembly(module, labelMaterial, library) {
  const { geometries: g, materials: m } = library
  const group = new Group()
  const baseGroup = new Group()
  const identityGroup = new Group()
  const nearGroup = new Group()
  const focusGroup = new Group()

  const body = shadow(new Mesh(g.body, m.body), false, true)
  body.userData.moduleKey = module.key
  const inset = shadow(new Mesh(g.inset, m.inset), false, true)
  inset.position.z = 0.205
  const marker = shadow(new Mesh(g.marker, m.accent), false, true)
  marker.position.set(2.12, -1.26, 0.245)
  const archiveMark = shadow(new Mesh(g.marker, m.inner), false, true)
  archiveMark.scale.set(0.70, 0.58, 0.70)
  archiveMark.position.set(2.16, -0.72, 0.37)
  const labelCarrier = shadow(new Mesh(g.labelCarrier, m.labelCarrier), false, true)
  labelCarrier.position.set(0, 1.18, 0.275)
  const label = new Mesh(g.label, labelMaterial)
  label.position.set(0, 1.18, 0.305)
  label.userData.moduleKey = module.key
  baseGroup.add(body, inset, archiveMark)
  identityGroup.add(marker, labelCarrier, label)

  const left = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const right = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const top = shadow(new Mesh(g.topBar, m.frame), false, true)
  const bottom = shadow(new Mesh(g.topBar, m.frame), false, true)
  left.position.set(-2.31, 0, 0.245)
  right.position.set(2.31, 0, 0.245)
  top.position.set(0, 1.65, 0.245)
  bottom.position.set(0, -1.65, 0.245)

  const railL = shadow(new Mesh(g.rail, m.rail), false, true)
  const railR = shadow(new Mesh(g.rail, m.rail), false, true)
  railL.position.set(-1.06, -0.18, 0.252)
  railR.position.set(1.06, -0.18, 0.252)

  const ringTop = shadow(new Mesh(g.ring, m.inner), false, true)
  const ringBottom = shadow(new Mesh(g.ring, m.inner), false, true)
  ringTop.position.set(0, 0.36, 0.278)
  ringBottom.position.set(0, -0.48, 0.278)

  const glass = new Mesh(g.glass, m.glass)
  glass.position.z = 0.33
  glass.renderOrder = 2
  nearGroup.add(left, right, top, bottom, railL, railR, ringTop, ringBottom, glass)

  const bridge = shadow(new Mesh(g.bridge, m.inner), false, true)
  bridge.position.set(0, -0.06, 0.292)
  const latch = shadow(new Mesh(g.latch, m.accent), false, true)
  latch.position.set(0, 1.48, 0.318)

  const fasteners = [
    [-2.12, 1.50], [2.12, 1.50], [-2.12, -1.50], [2.12, -1.50],
  ].map(([x, y]) => {
    const bolt = shadow(new Mesh(g.fastener, m.frame), false, true)
    bolt.rotation.x = Math.PI / 2
    bolt.position.set(x, y, 0.342)
    return bolt
  })

  const glassEdges = [
    new Mesh(g.glassSide, m.gasket), new Mesh(g.glassSide, m.gasket),
    new Mesh(g.glassTop, m.gasket), new Mesh(g.glassTop, m.gasket),
  ]
  glassEdges[0].position.set(-2.24, 0, 0.365)
  glassEdges[1].position.set(2.24, 0, 0.365)
  glassEdges[2].position.set(0, 1.58, 0.365)
  glassEdges[3].position.set(0, -1.58, 0.365)

  const decryptA = new Mesh(g.decrypt, m.decrypt)
  const decryptB = new Mesh(g.decrypt, m.decrypt)
  decryptA.position.set(-1.10, 0.08, 0.415)
  decryptB.position.set(1.10, 0.08, 0.415)
  decryptA.rotation.z = 0.22
  decryptB.rotation.z = -0.22
  decryptA.visible = false
  decryptB.visible = false

  focusGroup.add(bridge, latch, ...fasteners, ...glassEdges, decryptA, decryptB)
  nearGroup.visible = false
  focusGroup.visible = false
  group.add(baseGroup, identityGroup, nearGroup, focusGroup)

  return {
    group,
    body,
    marker,
    labelCarrier,
    label,
    baseGroup,
    identityGroup,
    nearGroup,
    focusGroup,
    glass,
    decryptA,
    decryptB,
    hitTargets: [body, label],
  }
}

