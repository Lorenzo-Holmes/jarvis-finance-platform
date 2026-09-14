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
    body: new RoundedBoxGeometry(3.16, 4.62, 0.19, 3, 0.075),
    inset: new RoundedBoxGeometry(2.92, 4.32, 0.05, 3, 0.065),
    glass: new RoundedBoxGeometry(2.86, 4.24, 0.045, 3, 0.07),
    sideBar: new BoxGeometry(0.072, 4.23, 0.062),
    topBar: new BoxGeometry(2.80, 0.072, 0.062),
    rail: new BoxGeometry(0.064, 3.03, 0.038),
    bridge: new BoxGeometry(0.05, 2.32, 0.05),
    latch: new RoundedBoxGeometry(0.66, 0.16, 0.075, 2, 0.032),
    marker: new RoundedBoxGeometry(0.13, 0.50, 0.07, 2, 0.022),
    labelCarrier: new RoundedBoxGeometry(2.66, 0.76, 0.04, 2, 0.032),
    label: new PlaneGeometry(2.52, 0.62),
    ring: new TorusGeometry(0.43, 0.03, 8, 40),
    fastener: new CylinderGeometry(0.055, 0.055, 0.04, 12),
    decrypt: new BoxGeometry(1.16, 0.022, 0.018),
    glassSide: new BoxGeometry(0.028, 4.02, 0.022),
    glassTop: new BoxGeometry(2.70, 0.028, 0.022),
  }

  const materials = {
    body: new MeshStandardMaterial({ color: new Color('#d7d0c4'), roughness: 0.44, metalness: 0.08 }),
    inset: new MeshStandardMaterial({ color: new Color('#ece7de'), roughness: 0.52, metalness: 0.03 }),
    frame: new MeshStandardMaterial({ color: new Color('#a7a39b'), roughness: 0.4, metalness: 0.2 }),
    rail: new MeshStandardMaterial({ color: new Color('#bbb4aa'), roughness: 0.38, metalness: 0.22 }),
    inner: new MeshStandardMaterial({ color: new Color('#666860'), roughness: 0.34, metalness: 0.16 }),
    accent: new MeshStandardMaterial({ color: new Color('#92764d'), roughness: 0.28, metalness: 0.34 }),
    labelCarrier: new MeshStandardMaterial({ color: new Color('#eee9e0'), roughness: 0.56, metalness: 0.02 }),
    glass: new MeshStandardMaterial({
      color: new Color('#e9e6df'),
      roughness: 0.22,
      metalness: 0.03,
      transparent: true,
      opacity: 0.34,
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
  inset.position.z = 0.145
  const marker = shadow(new Mesh(g.marker, m.accent), false, true)
  marker.position.set(1.32, -1.69, 0.185)
  const labelCarrier = shadow(new Mesh(g.labelCarrier, m.labelCarrier), false, true)
  labelCarrier.position.set(0, 1.53, 0.205)
  const label = new Mesh(g.label, labelMaterial)
  label.position.set(0, 1.53, 0.232)
  label.userData.moduleKey = module.key
  baseGroup.add(body, inset)
  identityGroup.add(marker, labelCarrier, label)

  const left = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const right = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const top = shadow(new Mesh(g.topBar, m.frame), false, true)
  const bottom = shadow(new Mesh(g.topBar, m.frame), false, true)
  left.position.set(-1.40, 0, 0.18)
  right.position.set(1.40, 0, 0.18)
  top.position.set(0, 2.08, 0.18)
  bottom.position.set(0, -2.08, 0.18)

  const railL = shadow(new Mesh(g.rail, m.rail), false, true)
  const railR = shadow(new Mesh(g.rail, m.rail), false, true)
  railL.position.set(-0.69, -0.2, 0.186)
  railR.position.set(0.69, -0.2, 0.186)

  const ringTop = shadow(new Mesh(g.ring, m.inner), false, true)
  const ringBottom = shadow(new Mesh(g.ring, m.inner), false, true)
  ringTop.position.set(0, 0.46, 0.212)
  ringBottom.position.set(0, -0.62, 0.212)

  const glass = new Mesh(g.glass, m.glass)
  glass.position.z = 0.258
  glass.renderOrder = 2
  nearGroup.add(left, right, top, bottom, railL, railR, ringTop, ringBottom, glass)

  const bridge = shadow(new Mesh(g.bridge, m.inner), false, true)
  bridge.position.set(0, -0.09, 0.228)
  const latch = shadow(new Mesh(g.latch, m.accent), false, true)
  latch.position.set(0, 1.89, 0.246)

  const fasteners = [
    [-1.24, 1.93], [1.24, 1.93], [-1.24, -1.93], [1.24, -1.93],
  ].map(([x, y]) => {
    const bolt = shadow(new Mesh(g.fastener, m.frame), false, true)
    bolt.rotation.x = Math.PI / 2
    bolt.position.set(x, y, 0.27)
    return bolt
  })

  const glassEdges = [
    new Mesh(g.glassSide, m.gasket), new Mesh(g.glassSide, m.gasket),
    new Mesh(g.glassTop, m.gasket), new Mesh(g.glassTop, m.gasket),
  ]
  glassEdges[0].position.set(-1.36, 0, 0.29)
  glassEdges[1].position.set(1.36, 0, 0.29)
  glassEdges[2].position.set(0, 2.01, 0.29)
  glassEdges[3].position.set(0, -2.01, 0.29)

  const decryptA = new Mesh(g.decrypt, m.decrypt)
  const decryptB = new Mesh(g.decrypt, m.decrypt)
  decryptA.position.set(-0.66, 0.1, 0.34)
  decryptB.position.set(0.66, 0.1, 0.34)
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

