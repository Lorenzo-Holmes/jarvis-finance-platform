import { computed, onBeforeUnmount, ref } from 'vue'

export function useFreshness(staleAfterMs = 90000, tickMs = 15000) {
  const lastUpdated = ref(null)
  const now = ref(Date.now())
  const timer = setInterval(() => { now.value = Date.now() }, tickMs)

  function touch(value = Date.now()) {
    lastUpdated.value = value instanceof Date ? value.getTime() : Number(value)
    now.value = Date.now()
  }

  const ageMs = computed(() => lastUpdated.value == null ? null : Math.max(0, now.value - lastUpdated.value))
  const stale = computed(() => ageMs.value != null && ageMs.value > staleAfterMs)
  const label = computed(() => {
    if (lastUpdated.value == null) return '尚未同步'
    const seconds = Math.floor((ageMs.value || 0) / 1000)
    if (seconds < 10) return '刚刚同步'
    if (seconds < 60) return `${seconds} 秒前`
    return `${Math.floor(seconds / 60)} 分钟前`
  })

  onBeforeUnmount(() => clearInterval(timer))

  return { lastUpdated, ageMs, stale, label, touch }
}
