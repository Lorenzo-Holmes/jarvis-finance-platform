import { onBeforeUnmount, ref } from 'vue'

function smoothstep(value) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * t * (10 + t * (-15 + 6 * t))
}

export function useArchiveTransition(options = {}) {
  const transitionState = ref('ARCHIVE')
  const extractionProgress = ref(0)
  let frame = 0
  let revision = 0

  function cancel() {
    revision += 1
    if (frame) cancelAnimationFrame(frame)
    frame = 0
  }

  function animateTo(target, duration, state) {
    cancel()
    const runRevision = revision
    transitionState.value = state
    const start = performance.now()
    const from = extractionProgress.value
    const delta = target - from
    return new Promise(resolve => {
      const tick = now => {
        if (runRevision !== revision) {
          resolve(false)
          return
        }
        const elapsed = now - start
        const progress = duration <= 0 ? 1 : Math.min(1, elapsed / duration)
        extractionProgress.value = from + delta * smoothstep(progress)
        if (progress >= 1) {
          extractionProgress.value = target
          frame = 0
          resolve(true)
          return
        }
        frame = requestAnimationFrame(tick)
      }
      frame = requestAnimationFrame(tick)
    })
  }

  async function enter(reduced = false) {
    if (!['ARCHIVE', 'FOCUSED', 'RETURN_DESCEND'].includes(transitionState.value)) return false
    const ok = await animateTo(1, reduced ? 80 : (options.enterDuration || 820), 'EXTRACTING')
    if (ok) transitionState.value = 'ENTERING_WORKSPACE'
    return ok
  }

  function workspaceActive() {
    extractionProgress.value = 1
    transitionState.value = 'WORKSPACE_ACTIVE'
  }

  async function returnToArchive(reduced = false) {
    cancel()
    extractionProgress.value = 1
    transitionState.value = 'RETURN_ALIGN'
    if (!reduced) await new Promise(resolve => setTimeout(resolve, options.alignDuration || 150))
    const ok = await animateTo(0, reduced ? 80 : (options.returnDuration || 720), 'RETURN_DESCEND')
    if (ok) transitionState.value = 'FOCUSED'
    return ok
  }

  function reset() {
    cancel()
    extractionProgress.value = 0
    transitionState.value = 'ARCHIVE'
  }

  onBeforeUnmount(cancel)

  return {
    transitionState,
    extractionProgress,
    enter,
    workspaceActive,
    returnToArchive,
    reset,
    cancel,
  }
}
