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
    maxDpr: 1.65,
    shadows: true,
    post: false,
    postCandidate: true,
    aoKernel: 16,
    aoRadius: 5,
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
  if (!profile.post) return null
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

