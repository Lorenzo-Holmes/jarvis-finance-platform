import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')
const shell = read('analysis-os/components/ArchiveWorkspaceShell.vue')

function getRule(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(rule, `missing CSS rule: ${selector}`)
  return rule[1]
}

function getColor(rule, token) {
  const value = rule.match(new RegExp(`${token}:\\s*(#[0-9a-f]{6})`, 'i'))?.[1]
  assert.ok(value, `missing ${token} hex color`)
  return value
}

function luminance(hex) {
  const channels = hex.slice(1).match(/../g).map(channel => parseInt(channel, 16) / 255)
  const linear = channels.map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}

function contrast(first, second) {
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

test('Markdown and OPS table headers keep readable contrast in day and night themes', () => {
  const light = getRule(shell, '.workspace-shell')
  const night = getRule(shell, '.workspace-shell.is-night')
  const markdown = read('components/common/MarkdownContent.vue')
  const ops = read('components/OpsView.vue')

  for (const theme of [light, night]) {
    assert.ok(contrast(getColor(theme, '--surface-2'), getColor(theme, '--text')) >= 4.5)
  }
  assert.match(markdown, /:deep\(th\)[\s\S]*?background:\s*var\(--surface-2/)
  assert.match(ops, /\.health-table th\s*\{[^}]*background:\s*var\(--surface-2/)
  assert.match(ops, /\.health-table th\s*\{[^}]*color:\s*var\(--text/)
})

test('industry graph, instrument list and market rail use workspace theme surfaces', () => {
  const chain = read('pages/ChainPage.vue')
  const instruments = read('components/market/InstrumentList.vue')
  const market = read('pages/MarketPage.vue')
  const night = getRule(shell, '.workspace-shell.is-night')

  assert.match(chain, /\.industry-graph\s*\{[^}]*background:\s*var\(--workspace-panel-soft/)
  assert.match(chain, /\.graph-node\s*\{[^}]*background:\s*var\(--workspace-node-bg/)
  assert.match(chain, /\.node-file\s*\{[^}]*background:\s*var\(--workspace-panel-wash/)
  assert.match(instruments, /\.instrument-panel\s*\{[^}]*background:\s*var\(--workspace-panel-wash/)
  assert.match(instruments, /\.list-head span\s*\{[^}]*color:\s*var\(--muted/)
  assert.match(market, /\.chart-shell\s*\{[^}]*background:\s*var\(--workspace-chart-bg/)
  assert.match(market, /\.market-rail\s*\{[^}]*background:\s*var\(--workspace-panel-rail/)
  assert.match(market, /\.rail-panel\s*\{[^}]*background:\s*var\(--workspace-panel-rail/)
  for (const token of ['--workspace-panel-soft', '--workspace-panel-rail', '--workspace-node-bg', '--workspace-node-focus-bg']) {
    assert.match(night, new RegExp(`${token}:\\s*#[0-9a-f]{6}`, 'i'))
  }
})
