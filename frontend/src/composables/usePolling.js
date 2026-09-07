import { onBeforeUnmount } from 'vue'

export function usePolling(task, intervalMs, options = {}) {
  const { pauseWhenHidden = true, refreshOnVisible = true } = options
  let timer = null
  let running = false
  let listeningVisibility = false

  async function tick() {
    if (running) return
    if (pauseWhenHidden && typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    running = true
    try {
      await task()
    } finally {
      running = false
    }
  }

  function handleVisibilityChange() {
    if (!refreshOnVisible || typeof document === 'undefined') return
    if (document.visibilityState === 'visible') tick()
  }

  function start() {
    if (timer) return
    timer = setInterval(tick, intervalMs)
    if (pauseWhenHidden && typeof document !== 'undefined' && !listeningVisibility) {
      document.addEventListener('visibilitychange', handleVisibilityChange)
      listeningVisibility = true
    }
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    if (listeningVisibility && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      listeningVisibility = false
    }
  }

  onBeforeUnmount(stop)

  return { start, stop, tick }
}
