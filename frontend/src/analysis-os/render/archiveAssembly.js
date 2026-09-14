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
    body: new RoundedBoxGeometry(3.38, 4.96, 0.24, 3, 0.08),
    inset: new RoundedBoxGeometry(3.08, 4.64, 0.055, 3, 0.07),
    glass: new RoundedBoxGeometry(3.02, 4.56, 0.052, 3, 0.08),
    sideBar: new BoxGeometry(0.085, 4.58, 0.07),
    topBar: new BoxGeometry(2.98, 0.085, 0.07),
    rail: new BoxGeometry(0.075, 3.34, 0.045),
    bridge: new BoxGeometry(0.055, 2.58, 0.055),
    latch: new RoundedBoxGeometry(0.72, 0.18, 0.085, 2, 0.035),
    marker: new RoundedBoxGeometry(0.14, 0.54, 0.08, 2, 0.025),
    labelCarrier: new RoundedBoxGeometry(2.86, 0.82, 0.045, 2, 0.035),
    label: new PlaneGeometry(2.72, 0.68),
    ring: new TorusGeometry(0.49, 0.035, 8, 40),
    fastener: new CylinderGeometry(0.055, 0.055, 0.04, 12),
    decrypt: new BoxGeometry(1.16, 0.022, 0.018),
  }

  const materials = {
    body: new MeshStandardMaterial({ color: new Color('#d7d0c4'), roughness: 0.44, metalness: 0.08 }),
    inset: new MeshStandardMaterial({ color: new Color('#ece7de'), roughness: 0.52, metalness: 0.03 }),
    frame: new MeshStandardMaterial({ color: new Color('#706e68'), roughness: 0.32, metalness: 0.34 }),
    rail: new MeshStandardMaterial({ color: new Color('#a39b8f'), roughness: 0.28, metalness: 0.38 }),
    inner: new MeshStandardMaterial({ color: new Color('#3b3e38'), roughness: 0.29, metalness: 0.22 }),
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
  marker.position.set(1.43, -1.82, 0.205)
  const labelCarrier = shadow(new Mesh(g.labelCarrier, m.labelCarrier), false, true)
  labelCarrier.position.set(0, 1.66, 0.23)
  const label = new Mesh(g.label, labelMaterial)
  label.position.set(0, 1.66, 0.258)
  label.userData.moduleKey = module.key
  baseGroup.add(body, inset)
  identityGroup.add(marker, labelCarrier, label)

  const left = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const right = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const top = shadow(new Mesh(g.topBar, m.frame), false, true)
  const bottom = shadow(new Mesh(g.topBar, m.frame), false, true)
  left.position.set(-1.5, 0, 0.205)
  right.position.set(1.5, 0, 0.205)
  top.position.set(0, 2.25, 0.205)
  bottom.position.set(0, -2.25, 0.205)

  const railL = shadow(new Mesh(g.rail, m.rail), false, true)
  const railR = shadow(new Mesh(g.rail, m.rail), false, true)
  railL.position.set(-0.76, -0.18, 0.206)
  railR.position.set(0.76, -0.18, 0.206)

  const ringTop = shadow(new Mesh(g.ring, m.inner), false, true)
  const ringBottom = shadow(new Mesh(g.ring, m.inner), false, true)
  ringTop.position.set(0, 0.52, 0.235)
  ringBottom.position.set(0, -0.68, 0.235)

  const glass = new Mesh(g.glass, m.glass)
  glass.position.z = 0.29
  glass.renderOrder = 2
  nearGroup.add(left, right, top, bottom, railL, railR, ringTop, ringBottom, glass)

  const bridge = shadow(new Mesh(g.bridge, m.inner), false, true)
  bridge.position.set(0, -0.1, 0.252)
  const latch = shadow(new Mesh(g.latch, m.accent), false, true)
  latch.position.set(0, 2.04, 0.27)

  const fasteners = [
    [-1.34, 2.08], [1.34, 2.08], [-1.34, -2.08], [1.34, -2.08],
  ].map(([x, y]) => {
    const bolt = shadow(new Mesh(g.fastener, m.frame), false, true)
    bolt.rotation.x = Math.PI / 2
    bolt.position.set(x, y, 0.30)
    return bolt
  })

  const decryptA = new Mesh(g.decrypt, m.decrypt)
  const decryptB = new Mesh(g.decrypt, m.decrypt)
  decryptA.position.set(-0.66, 0.1, 0.34)
  decryptB.position.set(0.66, 0.1, 0.34)
  decryptA.rotation.z = 0.22
  decryptB.rotation.z = -0.22
  decryptA.visible = false
  decryptB.visible = false

  focusGroup.add(bridge, latch, ...fasteners, decryptA, decryptB)
  nearGroup.visible = false
  focusGroup.visible = false
  group.add(baseGroup, identityGroup, nearGroup, focusGroup)

  return {
    group,
    body,
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

