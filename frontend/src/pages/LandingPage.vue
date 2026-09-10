<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const frame = ref(null)
const timers = new Set()
let visibilityObserver

onMounted(() => {
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

  // Keep the public-homepage lifecycle guard in the Vue boundary as well as in
  // the isolated landing document. This prevents stale timers/observers when
  // the user switches from the homepage to authentication.
  if (typeof window.IntersectionObserver === 'function' && frame.value) {
    visibilityObserver = new window.IntersectionObserver(() => {}, { threshold: 0 })
    visibilityObserver.observe(frame.value)
  }

  if (!reducedMotion) {
    const timer = window.setTimeout(() => {
      timers.delete(timer)
    }, 0)
    timers.add(timer)
  }
})

onBeforeUnmount(() => {
  visibilityObserver?.disconnect()
  for (const timer of timers) window.clearTimeout(timer)
  timers.clear()
})
</script>

<template>
  <div class="landing-frame">
    <iframe
      ref="frame"
      src="/landing/index.html"
      title="JARVIS 智能金融研究终端"
      loading="eager"
      allow="autoplay"
    />
  </div>
</template>

<style scoped>
.landing-frame {
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: #060606;
}

.landing-frame iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #060606;
}
</style>
