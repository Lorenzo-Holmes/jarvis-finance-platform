import { onBeforeUnmount, ref } from 'vue'

const STORAGE_KEY = 'jarvis-analysis-os-sound'

function storedPreference() {
  try { return window.localStorage?.getItem(STORAGE_KEY) === 'on' } catch (_) { return false }
}

export function useArchiveAudio() {
  const enabled = ref(typeof window !== 'undefined' ? storedPreference() : false)
  let context = null
  let master = null

  function ensureContext() {
    if (context) return context
    const AudioContext = window.AudioContext || window.webkitAudioContext
    if (!AudioContext) return null
    context = new AudioContext()
    master = context.createGain()
    master.gain.value = 0.11
    master.connect(context.destination)
    return context
  }

  async function resume() {
    const ctx = ensureContext()
    if (!ctx) return false
    if (ctx.state === 'suspended') await ctx.resume()
    return ctx.state === 'running'
  }

  function tone({ frequency = 440, endFrequency = frequency, duration = 0.08, gain = 0.07, type = 'sine', delay = 0 }) {
    if (!enabled.value) return
    const ctx = ensureContext()
    if (!ctx || !master) return
    const now = ctx.currentTime + delay
    const oscillator = ctx.createOscillator()
    const envelope = ctx.createGain()
    oscillator.type = type
    oscillator.frequency.setValueAtTime(frequency, now)
    oscillator.frequency.exponentialRampToValueAtTime(Math.max(20, endFrequency), now + duration)
    envelope.gain.setValueAtTime(0.0001, now)
    envelope.gain.exponentialRampToValueAtTime(gain, now + Math.min(0.018, duration * 0.25))
    envelope.gain.exponentialRampToValueAtTime(0.0001, now + duration)
    oscillator.connect(envelope)
    envelope.connect(master)
    oscillator.start(now)
    oscillator.stop(now + duration + 0.02)
  }

  function noise({ duration = 0.06, gain = 0.025, delay = 0 }) {
    if (!enabled.value) return
    const ctx = ensureContext()
    if (!ctx || !master) return
    const frames = Math.max(64, Math.floor(ctx.sampleRate * duration))
    const buffer = ctx.createBuffer(1, frames, ctx.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < data.length; i += 1) data[i] = (Math.random() * 2 - 1) * (1 - i / data.length)
    const source = ctx.createBufferSource()
    const filter = ctx.createBiquadFilter()
    const envelope = ctx.createGain()
    filter.type = 'bandpass'
    filter.frequency.value = 1900
    filter.Q.value = 0.8
    envelope.gain.value = gain
    source.buffer = buffer
    source.connect(filter)
    filter.connect(envelope)
    envelope.connect(master)
    source.start(ctx.currentTime + delay)
  }

  function play(cue) {
    if (!enabled.value) return
    switch (cue) {
      case 'wake':
        tone({ frequency: 170, endFrequency: 330, duration: 0.16, gain: 0.05, type: 'sine' })
        tone({ frequency: 420, endFrequency: 620, duration: 0.12, gain: 0.025, delay: 0.08 })
        break
      case 'focus':
        tone({ frequency: 720, endFrequency: 610, duration: 0.055, gain: 0.025, type: 'triangle' })
        break
      case 'extract':
        noise({ duration: 0.11, gain: 0.02 })
        tone({ frequency: 145, endFrequency: 260, duration: 0.34, gain: 0.055, type: 'sine' })
        break
      case 'decrypt':
        tone({ frequency: 920, endFrequency: 1180, duration: 0.08, gain: 0.028, type: 'triangle' })
        tone({ frequency: 1240, endFrequency: 860, duration: 0.11, gain: 0.02, delay: 0.07, type: 'triangle' })
        break
      case 'reveal':
        tone({ frequency: 310, endFrequency: 465, duration: 0.18, gain: 0.035 })
        tone({ frequency: 620, endFrequency: 740, duration: 0.15, gain: 0.018, delay: 0.05 })
        break
      case 'return':
        tone({ frequency: 430, endFrequency: 220, duration: 0.16, gain: 0.035, type: 'triangle' })
        break
      default:
        tone({ frequency: 520, endFrequency: 540, duration: 0.05, gain: 0.02, type: 'triangle' })
    }
  }

  async function toggle() {
    enabled.value = !enabled.value
    try { window.localStorage?.setItem(STORAGE_KEY, enabled.value ? 'on' : 'off') } catch (_) { /* optional preference */ }
    if (enabled.value) {
      await resume()
      play('wake')
    }
  }

  function dispose() {
    context?.close?.()
    context = null
    master = null
  }

  onBeforeUnmount(dispose)

  return { enabled, toggle, resume, play, dispose }
}
