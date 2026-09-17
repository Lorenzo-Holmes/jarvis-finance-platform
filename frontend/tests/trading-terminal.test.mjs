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
  assert.match(terminal, /execution-rail/)
  assert.doesNotMatch(terminal, /chart-toolbar/)
  assert.match(terminal, /委托类型/)
  assert.match(terminal, /止损市价单/)
  assert.match(terminal, /预计卖出金额/)
  assert.match(terminal, /当日有效/)
  assert.match(terminal, /modal-layer/)
})

test('terminal keeps server-side stop orders wired through the authenticated API client', () => {
  assert.match(apiClient, /simOpenOrders/)
  assert.match(apiClient, /simUpdateOrder/)
  assert.match(apiClient, /simCancelOrder/)
  assert.match(apiClient, /orderType: options\.orderType \|\| 'MARKET'/)
  assert.match(simView, /orderType: current\.orderType/)
})

test('simulation terminal reuses the multi-market chart configuration', () => {
  assert.match(chart, /riseColor = PRICE_UP/)
  assert.match(chart, /fallColor = PRICE_DOWN/)
  assert.match(chart, /showLegend = true/)
  assert.match(chart, /showSlider = true/)
  assert.match(chart, /xLabelsOnVolume = false/)
  assert.match(chart, /getMultiMarketChartOptions/)
  assert.match(terminal, /getMultiMarketChartOptions\(\{[\s\S]*visibleCount: activeRange\.value\.limit/)
  assert.doesNotMatch(terminal, /riseColor: '#b9ff22'/)
  assert.doesNotMatch(terminal, /withVolume: true/)
})

test('trade terminal and order modal use the multi-market semantic text palette', () => {
  assert.match(terminal, /--trade-text: var\(--text\)/)
  assert.match(terminal, /--trade-muted: var\(--muted\)/)
  assert.match(terminal, /--trade-subtle: var\(--subtle\)/)
  assert.match(terminal, /--trade-buy: var\(--ok\)/)
  assert.match(terminal, /--trade-sell: var\(--bad\)/)
  assert.match(terminal, /\.order-grid > label, \.field-label > label \{ color: var\(--trade-muted\)/)
  assert.match(terminal, /\.order-summary p \{[^}]*color: var\(--trade-subtle\)/)
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

test('reference terminal keeps chart primary, adds an execution rail, and uses a side order sheet', () => {
  assert.match(terminal, /grid-template-columns: minmax\(0, 1fr\) clamp\(196px, 14vw, 216px\)/)
  assert.match(terminal, /height: clamp\(360px, calc\(100dvh - 420px\), 620px\)/)
  assert.match(terminal, /@media \(max-width: 1120px\)[\s\S]*?\.terminal-workspace \{ grid-template-columns: 1fr; \}/)
  assert.match(terminal, /execution-segmented/)
  assert.match(terminal, /place-items: stretch end/)
  assert.match(terminal, /width: min\(420px, 100%\)/)
  assert.match(terminal, /border-right: 0/)
  assert.match(terminal, /aria-labelledby="order-modal-title"/)
  assert.match(terminal, /right: 150px/)
  assert.match(terminal, /right: 4px/)
})
