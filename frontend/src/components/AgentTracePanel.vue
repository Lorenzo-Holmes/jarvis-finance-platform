<script setup>
import { computed } from 'vue'

const props = defineProps({
  steps: { type: Array, default: () => [] },
  running: { type: Boolean, default: false },
  recoverable: { type: Boolean, default: false },
})

const emit = defineEmits(['stop', 'recover'])
const title = computed(() => props.running ? 'JARVIS Agent 执行中' : 'Agent 执行轨迹')

const typeLabel = {
  run_started: 'RUN',
  plan_created: 'PLAN',
  step_started: 'START',
  tool_call: 'CALL',
  tool_result: 'RESULT',
  step_completed: 'STEP',
  safety_review: 'SAFETY',
  assistant_delta: 'ANSWER',
  assistant_retracted: 'RETRACTED',
  run_completed: 'DONE',
  run_failed: 'FAILED',
  run_cancelled: 'STOPPED',
}

function formatDuration(value) {
  if (value === null || value === undefined) return ''
  if (value < 1000) return `${value}ms`
  return `${(value / 1000).toFixed(1)}s`
}

function summary(step) {
  return step.content || step.message || step.outputSummary || step.inputSummary || ''
}
</script>

<template>
  <section class="agent-trace" data-testid="agent-trace" aria-live="polite">
    <header class="trace-head">
      <div>
        <span class="trace-kicker">OBSERVABLE WORKFLOW</span>
        <b>{{ title }}</b>
      </div>
      <div class="trace-head-actions">
        <span class="trace-count">{{ steps.length }} EVENTS</span>
        <button v-if="running" type="button" class="trace-stop" @click="emit('stop')">停止</button>
        <button v-else-if="recoverable" type="button" class="trace-recover" @click="emit('recover')">恢复轨迹</button>
      </div>
    </header>
    <div v-if="!steps.length" class="trace-empty">提交研究问题后，这里会显示计划、工具调用和结果。</div>
    <ol v-else class="trace-list">
      <li v-for="step in steps" :key="step.stepId || `${step.type}-${step.sequence}`" class="trace-step" :class="[`is-${step.status || 'running'}`]">
        <span class="trace-rail" aria-hidden="true"><i></i></span>
        <div class="trace-body">
          <div class="trace-row">
            <strong>{{ step.title || step.tool || step.type }}</strong>
            <span class="trace-status">{{ typeLabel[step.type] || step.status }}</span>
          </div>
          <div v-if="step.tool" class="trace-tool">{{ step.tool }}</div>
          <p v-if="summary(step)">{{ summary(step) }}</p>
          <div class="trace-meta">
            <span v-if="step.status">{{ step.status }}</span>
            <span v-if="formatDuration(step.durationMs)">{{ formatDuration(step.durationMs) }}</span>
            <span v-if="step.errorCode" class="trace-error-code">{{ step.errorCode }}</span>
          </div>
          <details v-if="step.payload && Object.keys(step.payload).length" class="trace-details">
            <summary>查看脱敏结果</summary>
            <pre>{{ JSON.stringify(step.payload, null, 2) }}</pre>
          </details>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.agent-trace {
  border: 1px solid var(--material-border, var(--line));
  border-radius: 10px;
  background: color-mix(in srgb, var(--surface) 76%, transparent);
  color: var(--text);
  overflow: hidden;
}
.trace-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 13px; border-bottom: 1px solid var(--line); }
.trace-head > div:first-child { display: grid; gap: 4px; }
.trace-kicker, .trace-status, .trace-count, .trace-meta, .trace-tool { color: var(--muted); font: 600 8px/1.2 "IBM Plex Mono", ui-monospace, monospace; letter-spacing: .08em; text-transform: uppercase; }
.trace-head b { font-size: 11px; font-weight: 650; }
.trace-head-actions { display: flex; align-items: center; gap: 10px; }
.trace-stop, .trace-recover { border: 1px solid color-mix(in srgb, var(--negative, #e45d5d) 55%, var(--line)); border-radius: 6px; background: transparent; color: var(--negative, #e45d5d); padding: 4px 8px; cursor: pointer; font-size: 9px; }
.trace-recover { border-color: color-mix(in srgb, var(--accent-strong) 60%, var(--line)); color: var(--accent-strong); }
.trace-list { list-style: none; margin: 0; padding: 7px 13px 10px; }
.trace-step { position: relative; display: grid; grid-template-columns: 16px 1fr; gap: 8px; min-height: 49px; padding: 8px 0; }
.trace-rail { position: relative; display: flex; justify-content: center; }
.trace-rail::after { content: ''; position: absolute; top: 11px; bottom: -9px; width: 1px; background: var(--line); }
.trace-step:last-child .trace-rail::after { display: none; }
.trace-rail i { z-index: 1; width: 7px; height: 7px; margin-top: 2px; border: 1px solid var(--muted); border-radius: 50%; background: var(--surface); }
.is-running .trace-rail i { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 16%, transparent); }
.is-completed .trace-rail i { border-color: var(--positive, #3fc98a); background: var(--positive, #3fc98a); }
.is-failed .trace-rail i, .is-cancelled .trace-rail i, .is-retracted .trace-rail i { border-color: var(--negative, #e45d5d); background: var(--negative, #e45d5d); }
.trace-body { min-width: 0; }
.trace-row { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.trace-row strong { color: var(--text); font-size: 10px; font-weight: 650; }
.trace-step p { margin: 4px 0 0; color: var(--muted); font-size: 9px; line-height: 1.55; overflow-wrap: anywhere; }
.trace-tool { margin-top: 4px; color: var(--accent-strong); letter-spacing: .02em; text-transform: none; }
.trace-meta { display: flex; gap: 9px; margin-top: 5px; letter-spacing: .02em; text-transform: none; }
.trace-error-code { color: var(--negative, #e45d5d); }
.trace-details { margin-top: 6px; color: var(--muted); font-size: 9px; }
.trace-details summary { cursor: pointer; color: var(--accent-strong); }
.trace-details pre { max-height: 150px; overflow: auto; margin: 6px 0 0; padding: 7px; border: 1px solid var(--line); background: var(--workspace-control-bg, var(--surface)); color: var(--muted); font: 8px/1.45 "IBM Plex Mono", ui-monospace, monospace; white-space: pre-wrap; }
.trace-empty { padding: 14px; color: var(--muted); font-size: 9px; }
</style>
