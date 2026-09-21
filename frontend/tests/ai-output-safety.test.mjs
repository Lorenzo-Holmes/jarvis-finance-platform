import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const center = fs.readFileSync(path.resolve(here, '../src/components/AiCenter.vue'), 'utf8')
const trace = fs.readFileSync(path.resolve(here, '../src/components/AgentTracePanel.vue'), 'utf8')

test('research chat renders output-safety review and retraction events', () => {
  assert.match(center, /event\.type === 'safety_review'/)
  assert.match(center, /event\.type === 'assistant_retracted'/)
  assert.match(center, /safetyStatusLabel/)
  assert.match(center, /输出已撤回/)
  assert.match(center, /safety-retraction/)
  assert.match(trace, /safety_review: 'SAFETY'/)
  assert.match(trace, /assistant_retracted: 'RETRACTED'/)
})

test('retracted assistant output is excluded from future conversation context', () => {
  assert.match(center, /message\.role === 'assistant' && message\.retracted/)
  assert.match(center, /filter\(message => !\(message\.role === 'assistant' && message\.retracted\)\)/)
})

test('historical replay applies the same safety events before restoring answer text', () => {
  assert.match(center, /applySafetyEventToMessage\(assistantMessage, event\)/)
  assert.match(center, /content && !assistantMessage\.retracted/)
})
