import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const app = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const shell = fs.readFileSync(new URL('../src/analysis-os/components/ArchiveWorkspaceShell.vue', import.meta.url), 'utf8')

test('night theme can be toggled from the workspace and persists across reloads', () => {
  assert.match(app, /jarvis-ui-night-mode/)
  assert.match(app, /localStorage\.setItem\(NIGHT_MODE_KEY/)
  assert.match(app, /:night-mode="nightMode"/)
  assert.match(shell, /@click="emit\('toggle-night-mode'\)"/)
  assert.match(shell, /:aria-pressed="props\.nightMode"/)
  assert.match(shell, /\.workspace-shell\.is-night\s*\{/)
  assert.match(shell, /--bg: #0b0f13/)
})
