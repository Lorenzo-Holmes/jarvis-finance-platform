import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('workspace module switching keeps fixed navigation chrome out of transition snapshots', () => {
  const shell = read('analysis-os/components/ArchiveWorkspaceShell.vue')
  const system = read('analysis-os/styles/WorkspaceSystem.css')

  assert.doesNotMatch(shell, /document\.startViewTransition/)
  assert.doesNotMatch(system, /view-transition-name:\s*jarvis-context-capsule/)
  assert.doesNotMatch(system, /workspace-shell\.switching \.entity-context-display/)
  assert.doesNotMatch(system, /view-transition-(?:old|new|group)\(jarvis-context-capsule\)/)
  assert.doesNotMatch(system, /workspace-shell\.ambient-[^{]+\{[^}]*--context-accent/)
  assert.match(system, /workspace-shell\.switching \.workspace-body/)
})
