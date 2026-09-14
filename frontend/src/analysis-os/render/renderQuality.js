import {
  ACESFilmicToneMapping,
  PCFShadowMap,
  SRGBColorSpace,
} from 'three'

export function archiveQualityProfile(width, dpr = 1) {
  if (width < 700) {
    return {
      name: 'MOBILE',
      maxDpr: 1.15,
      shadows: false,
      post: false,
      aoKernel: 8,
      aoRadius: 4,
      aoMinDistance: 0.004,
      aoMaxDistance: 0.08,
    }
  }
  if (width < 1180 || dpr > 1.8) {
    return {
      name: 'BALANCED',
      maxDpr: 1.35,
      shadows: true,
      post: false,
      postCandidate: true,
      aoKernel: 12,
      aoRadius: 4,
      aoMinDistance: 0.004,
      aoMaxDistance: 0.09,
    }
  }
  return {
    name: 'HIGH',
    maxDpr: 1.5,
    shadows: true,
    post: false,
    postCandidate: true,
    aoKernel: 12,
    aoRadius: 4.5,
    aoMinDistance: 0.003,
    aoMaxDistance: 0.1,
  }
}

export function configureArchiveRenderer(renderer, profile) {
  renderer.outputColorSpace = SRGBColorSpace
  renderer.toneMapping = ACESFilmicToneMapping
  renderer.toneMappingExposure = 0.94
  renderer.shadowMap.enabled = Boolean(profile.shadows)
  renderer.shadowMap.type = PCFShadowMap
}

export async function createArchiveComposer({ renderer, scene, camera, width, height, profile }) {
  if (!profile.post && !profile.postCandidate) return null
  const [
    { EffectComposer },
    { OutputPass },
    { RenderPass },
    { SSAOPass },
  ] = await Promise.all([
    import('three/addons/postprocessing/EffectComposer.js'),
    import('three/addons/postprocessing/OutputPass.js'),
    import('three/addons/postprocessing/RenderPass.js'),
    import('three/addons/postprocessing/SSAOPass.js'),
  ])
  const composer = new EffectComposer(renderer)
  composer.addPass(new RenderPass(scene, camera))
  const ssaoPass = new SSAOPass(scene, camera, width, height, profile.aoKernel)
  ssaoPass.kernelRadius = profile.aoRadius
  ssaoPass.minDistance = profile.aoMinDistance
  ssaoPass.maxDistance = profile.aoMaxDistance
  composer.addPass(ssaoPass)
  composer.addPass(new OutputPass())
  return { composer, ssaoPass }
}

export async function probeArchiveComposer({ renderer, scene, camera, width, height, profile }) {
  if (!renderer || !profile?.postCandidate) return { bundle: null, status: 'disabled' }
  if (typeof navigator !== 'undefined' && navigator.webdriver) {
    return { bundle: null, status: 'skipped-automation' }
  }

  const gl = renderer.getContext?.()
  if (!gl) return { bundle: null, status: 'no-context' }

  // Drain prior WebGL errors so the probe only judges the optional composer path.
  while (gl.getError() !== gl.NO_ERROR) {
    // no-op
  }

  let bundle = null
  try {
    bundle = await createArchiveComposer({
      renderer,
      scene,
      camera,
      width,
      height,
      profile: { ...profile, post: true },
    })
    if (!bundle) return { bundle: null, status: 'unavailable' }
    bundle.composer.render()
    gl.finish?.()
    const error = gl.getError()
    if (error !== gl.NO_ERROR) {
      disposeArchiveComposer(bundle)
      renderer.render(scene, camera)
      return { bundle: null, status: `fallback-webgl-${error}` }
    }
    return { bundle, status: 'enabled' }
  } catch (_) {
    disposeArchiveComposer(bundle)
    try { renderer.render(scene, camera) } catch (_) { /* keep direct renderer best-effort */ }
    return { bundle: null, status: 'fallback-exception' }
  }
}

export function resizeArchiveComposer(bundle, width, height) {
  if (!bundle) return
  bundle.composer.setSize(width, height)
  bundle.ssaoPass.setSize(width, height)
}

export function disposeArchiveComposer(bundle) {
  if (!bundle) return
  bundle.ssaoPass?.dispose?.()
  bundle.composer?.dispose?.()
}

