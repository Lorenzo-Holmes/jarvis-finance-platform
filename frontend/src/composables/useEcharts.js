import { nextTick, onBeforeUnmount, ref } from 'vue'

let echartsModulePromise = null

function loadEcharts() {
  if (!echartsModulePromise) echartsModulePromise = import('../charts/echarts')
  return echartsModulePromise
}

export function useEcharts() {
  const elementRef = ref(null)
  let chart = null
  let resizeObserver = null

  async function ensureChart() {
    await nextTick()
    if (!elementRef.value) return null
    if (!chart) {
      const echarts = await loadEcharts()
      if (!elementRef.value) return null
      chart = echarts.init(elementRef.value)
      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => chart?.resize())
        resizeObserver.observe(elementRef.value)
      }
    }
    return chart
  }

  async function setOption(option, notMerge = true) {
    const instance = await ensureChart()
    if (!instance) return null
    instance.setOption(option, notMerge)
    instance.resize()
    return instance
  }

  function resize() {
    chart?.resize()
  }

  function clear() {
    chart?.clear()
  }

  function dispose() {
    resizeObserver?.disconnect()
    resizeObserver = null
    chart?.dispose()
    chart = null
  }

  function getChart() {
    return chart
  }

  onBeforeUnmount(dispose)

  return {
    elementRef,
    ensureChart,
    setOption,
    resize,
    clear,
    dispose,
    getChart,
  }
}
