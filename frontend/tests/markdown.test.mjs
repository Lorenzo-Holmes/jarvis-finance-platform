import test from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from '../src/utils/markdown.js'

test('renders common research Markdown structures', () => {
  const html = renderMarkdown('## 结论\n\n- **偏多**\n- `RSI(14)`\n\n| 指标 | 数值 |\n| --- | --- |\n| RSI | 52 |')

  assert.match(html, /<h2>结论<\/h2>/)
  assert.match(html, /<strong>偏多<\/strong>/)
  assert.match(html, /<code>RSI\(14\)<\/code>/)
  assert.match(html, /<table>/)
})

test('escapes raw HTML instead of executing it', () => {
  const html = renderMarkdown('<script>alert(1)</script><img src=x onerror=alert(1)>')

  assert.doesNotMatch(html, /<script|<img\b/i)
  assert.match(html, /&lt;script&gt;/)
})

test('rejects executable URL schemes and hardens external links', () => {
  const html = renderMarkdown('[unsafe](javascript:alert(1))\n\n[market](https://example.com)\n\n![tracking](https://example.com/pixel.png)')

  assert.doesNotMatch(html, /href="javascript:/i)
  assert.doesNotMatch(html, /<img\b/i)
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.match(html, /\[图片：tracking\]/)
})
