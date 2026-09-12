import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const terminal = fs.readFileSync(new URL('../src/components/trading/TradingTerminal.vue', import.meta.url), 'utf8')
const simView = fs.readFileSync(new URL('../src/components/SimTradeView.vue', import.meta.url), 'utf8')
const apiClient = fs.readFileSync(new URL('../src/api/client.js', import.meta.url), 'utf8')
const chart = fs.readFileSync(new URL('../src/composables/useMarketChart.js', import.meta.url), 'utf8')

test('simulated trading uses the chart-first terminal layout', () => {
  assert.match(simView, /TradingTerminal/)
  assert.match(terminal, /terminal-chart/)
  assert.match(terminal, /Order type/)
  assert.match(terminal, /Stop market/)
  assert.match(terminal, /Estimated credit/)
  assert.match(terminal, /Good for day/)
  assert.match(terminal, /modal-layer/)
})

test('terminal keeps server-side stop orders wired through the authenticated API client', () => {
  assert.match(apiClient, /simOpenOrders/)
  assert.match(apiClient, /simUpdateOrder/)
  assert.match(apiClient, /simCancelOrder/)
  assert.match(apiClient, /orderType: options\.orderType \|\| 'MARKET'/)
  assert.match(simView, /orderType: current\.orderType/)
})

test('market chart supports reference-style colors and volume axis layout without changing defaults', () => {
  assert.match(chart, /riseColor = PRICE_UP/)
  assert.match(chart, /fallColor = PRICE_DOWN/)
  assert.match(chart, /showLegend = true/)
  assert.match(chart, /showSlider = true/)
  assert.match(chart, /xLabelsOnVolume = false/)
  assert.match(terminal, /riseColor: '#b9ff22'/)
  assert.match(terminal, /fallColor: '#ff4d52'/)
})

test('reference terminal renders separate position and stop labels on the chart', () => {
  assert.match(terminal, /position-marker/)
  assert.match(terminal, /position-price-tag/)
  assert.match(terminal, /stop-marker/)
  assert.match(terminal, /stop-price-tag/)
  assert.match(terminal, /markArea/)
  assert.match(terminal, /convertToPixel/)
})

test('stop-line click handling uses ECharts markLine data events and validates stop direction', () => {
  assert.match(terminal, /params\?\.dataType === 'markLine'/)
  assert.match(terminal, /卖出止损价需低于当前价/)
  assert.match(terminal, /买入止损价需高于当前价/)
})
