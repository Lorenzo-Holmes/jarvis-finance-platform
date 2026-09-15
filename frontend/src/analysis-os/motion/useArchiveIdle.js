import { computed, onBeforeUnmount, ref } from 'vue'

export const ARCHIVE_IDLE_TIMING = {
  dimStartMs: 8_000,
  motionStartMs: 12_000,
  sleepStartMs: 18_000,
  wakeSettleMs: 280,
}

function smoothstep(value) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}

export function useArchiveIdle(options = {}) {
  const environmentState = ref('AWAKE')
  const sleepAmount = ref(0)
  const lastInteractionAt = ref(performance.now())
  const lastReason = ref('init')
  const enabled = ref(true)
  const reduced = ref(Boolean(options.reduced))
  let frame = 0
  let lastTick = performance.now()
  let lastCoarseTick = 0

  const hudDim = computed(() => {
    if (environmentState.value === 'AWAKE') return 0
    return Math.min(1, Math.max(0, sleepAmount.value * 0.75 + (environmentState.value === 'IDLE_ARMED' ? 0.18 : 0)))
  })

  function activity(reason = 'input') {
    lastReason.value = reason
    lastInteractionAt.value = performance.now()
    if (environmentState.value !== 'AWAKE' || sleepAmount.value > 0) {
      environmentState.value = 'WAKE_SETTLE'
    }
  }

  function tick(now = performance.now()) {
    frame = window.requestAnimationFrame(tick)

    const idleMs = now - lastInteractionAt.value
    const needsFrameRateUpdates = environmentState.value === 'WAKE_SETTLE'
      || idleMs >= ARCHIVE_IDLE_TIMING.motionStartMs
    if (!needsFrameRateUpdates && now - lastCoarseTick < 80) return
    lastCoarseTick = now

    const dt = Math.min(100, Math.max(0, now - lastTick))
    lastTick = now

    if (!enabled.value || document.hidden) {
      lastInteractionAt.value = now
      environmentState.value = 'AWAKE'
      sleepAmount.value = 0
      return
    }

    if (environmentState.value === 'WAKE_SETTLE') {
      const step = dt / ARCHIVE_IDLE_TIMING.wakeSettleMs
      sleepAmount.value = Math.max(0, sleepAmount.value - step)
      if (sleepAmount.value <= 0.0001) {
        sleepAmount.value = 0
        environmentState.value = 'AWAKE'
      }
      return
    }

    if (idleMs < ARCHIVE_IDLE_TIMING.dimStartMs) {
      environmentState.value = 'AWAKE'
      sleepAmount.value = 0
      return
    }
    if (idleMs < ARCHIVE_IDLE_TIMING.motionStartMs) {
      environmentState.value = 'IDLE_ARMED'
      sleepAmount.value = 0
      return
    }

    const motionSpan = ARCHIVE_IDLE_TIMING.sleepStartMs - ARCHIVE_IDLE_TIMING.motionStartMs
    const progress = smoothstep((idleMs - ARCHIVE_IDLE_TIMING.motionStartMs) / motionSpan)
    if (reduced.value) {
      environmentState.value = 'IDLE_ARMED'
      sleepAmount.value = Math.min(0.08, progress * 0.08)
      return
    }
    environmentState.value = idleMs >= ARCHIVE_IDLE_TIMING.sleepStartMs ? 'SLEEP_DRIFT' : 'SLEEP_ENTER'
    sleepAmount.value = progress
  }

  function start() {
    if (frame) return
    lastTick = performance.now()
    lastCoarseTick = 0
    lastInteractionAt.value = lastTick
    frame = window.requestAnimationFrame(tick)
  }

  function stop() {
    if (frame) window.cancelAnimationFrame(frame)
    frame = 0
    environmentState.value = 'AWAKE'
    sleepAmount.value = 0
  }

  function setEnabled(value) {
    enabled.value = Boolean(value)
    if (!enabled.value) stop()
    else start()
  }

  function setReduced(value) {
    reduced.value = Boolean(value)
    activity('reduced-motion')
  }

  onBeforeUnmount(stop)

  return {
    environmentState,
    sleepAmount,
    hudDim,
    lastInteractionAt,
    lastReason,
    activity,
    start,
    stop,
    setEnabled,
    setReduced,
  }
}
