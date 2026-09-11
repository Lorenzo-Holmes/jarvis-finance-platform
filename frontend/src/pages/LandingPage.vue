<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['login'])
const landingUrl = `${import.meta.env.BASE_URL}landing/index.html`

const frame = ref(null)
const timers = new Set()
let visibilityObserver
let frameDocument

function handleLandingClick(event) {
  const link = event.target?.closest?.('a')
  if (!link || !link.textContent?.includes('进入 JARVIS')) return
  event.preventDefault()
  emit('login')
}

function bindLandingDocument() {
  try {
    const doc = frame.value?.contentDocument
    if (!doc || doc === frameDocument) return
    frameDocument?.removeEventListener('click', handleLandingClick)
    frameDocument = doc
    frameDocument.addEventListener('click', handleLandingClick)
  } catch {
    // /landing/index.html is expected to be same-origin. If hosting changes that,
    // keep the iframe functional rather than breaking the public homepage.
  }
}

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
  frameDocument?.removeEventListener('click', handleLandingClick)
  frameDocument = undefined
  visibilityObserver?.disconnect()
  for (const timer of timers) window.clearTimeout(timer)
  timers.clear()
})
</script>

<template>
  <div class="landing-frame">
    <iframe
      ref="frame"
      :src="landingUrl"
      title="JARVIS 智能金融研究终端"
      loading="eager"
      allow="autoplay"
      @load="bindLandingDocument"
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
