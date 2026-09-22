import fs from 'node:fs'
import path from 'node:path'

const port = Number(process.env.CDP_PORT || 9333)
const base = `http://127.0.0.1:${port}`
const outDir = path.resolve('artifacts/nav-flicker')
fs.mkdirSync(outDir, { recursive: true })

const targets = await fetch(`${base}/json/list`).then(r => r.json())
const target = targets.find(item => item.type === 'page' && item.url.includes('127.0.0.1:5173'))
if (!target) throw new Error('No Vite page target found')

const ws = new WebSocket(target.webSocketDebuggerUrl)
let seq = 0
const pending = new Map()
ws.addEventListener('message', event => {
  const msg = JSON.parse(event.data)
  if (!msg.id || !pending.has(msg.id)) return
  const { resolve, reject } = pending.get(msg.id)
  pending.delete(msg.id)
  msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
})
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})

function cdp(method, params = {}) {
  const id = ++seq
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }))
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const evaluate = async expression => {
  const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text || 'Runtime evaluation failed')
  return result.result?.value
}

async function clickSelector(selector, index = 0) {
  const rect = await evaluate(`(() => {
    const el = document.querySelectorAll(${JSON.stringify(selector)})[${index}]
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, text: el.textContent.trim() }
  })()`)
  if (!rect) throw new Error(`Selector not found: ${selector}[${index}]`)
  await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', x: rect.x, y: rect.y, button: 'left', clickCount: 1 })
  await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x: rect.x, y: rect.y, button: 'left', clickCount: 1 })
  return rect
}

async function clickPoint(rect) {
  await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', x: rect.x, y: rect.y, button: 'left', clickCount: 1 })
  await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x: rect.x, y: rect.y, button: 'left', clickCount: 1 })
}

async function capture(name) {
  const { data } = await cdp('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
    clip: { x: 0, y: 0, width: 420, height: 150, scale: 1 },
  })
  fs.writeFileSync(path.join(outDir, `${name}.png`), Buffer.from(data, 'base64'))
}

await cdp('Page.enable')
await cdp('Runtime.enable')
await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
await sleep(1800)

const initial = await evaluate(`({
  title: document.title,
  text: document.body.innerText.slice(0, 2500),
  workspace: Boolean(document.querySelector('.workspace-shell')),
  archive: Boolean(document.querySelector('.analysis-os')),
  groups: [...document.querySelectorAll('.workspace-function-menu > summary')].map(el => el.textContent.trim()),
})`)
console.log('INITIAL', JSON.stringify(initial))

if (!initial.workspace) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const ready = await evaluate(`Boolean(document.querySelector('.archive-callout button:not(:disabled)'))`)
    if (ready) break
    await sleep(150)
  }
  const ready = await evaluate(`Boolean(document.querySelector('.archive-callout button:not(:disabled)'))`)
  if (!ready) throw new Error('Archive ACCESS FILE button did not become ready')
  await evaluate(`document.querySelector('.archive-callout button:not(:disabled)').click()`)
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const workspace = await evaluate(`Boolean(document.querySelector('.workspace-shell:not(.preparing)'))`)
    if (workspace) break
    await sleep(150)
  }
}

const groupCount = await evaluate(`document.querySelectorAll('.workspace-function-menu > summary').length`)
if (!groupCount) throw new Error('Workspace navigation groups unavailable after preview load')

await capture('00-before-menu')
const menu = await clickSelector('.workspace-function-menu > summary', 0)
await sleep(80)
const candidateCount = await evaluate(`document.querySelectorAll('.workspace-function-menu[open] .function-menu-popover > button').length`)
if (!candidateCount) throw new Error('No navigation candidate in opened function menu')
const candidateIndex = await evaluate(`(() => {
  const buttons = [...document.querySelectorAll('.workspace-function-menu[open] .function-menu-popover > button')]
  const index = buttons.findIndex(el => !el.classList.contains('active'))
  return index >= 0 ? index : 0
})()`)
const candidate = await evaluate(`(() => {
  const el = document.querySelectorAll('.workspace-function-menu[open] .function-menu-popover > button')[${candidateIndex}]
  return el ? el.textContent.trim() : ''
})()`)

await capture('01-menu-open')
const clicked = await clickSelector('.workspace-function-menu[open] .function-menu-popover > button', candidateIndex)
console.log('CLICK', JSON.stringify({ menu: menu.text, candidate, clicked }))

for (const [delay, name] of [[0,'02-click-0'], [16,'03-click-16'], [34,'04-click-50'], [70,'05-click-120'], [100,'06-click-220'], [180,'07-click-400']]) {
  if (delay) await sleep(delay)
  await capture(name)
}

const post = await evaluate(`({
  shellClass: document.querySelector('.workspace-shell')?.className || '',
  railBg: getComputedStyle(document.querySelector('.global-rail')).backgroundColor,
  headerBg: getComputedStyle(document.querySelector('.workspace-header')).backgroundColor,
  entityBg: getComputedStyle(document.querySelector('.entity-bar')).backgroundColor,
  historyRect: (() => { const r = document.querySelector('.entity-history')?.getBoundingClientRect(); return r && {x:r.x,y:r.y,width:r.width,height:r.height} })(),
  contextRect: (() => { const r = document.querySelector('.entity-context-display')?.getBoundingClientRect(); return r && {x:r.x,y:r.y,width:r.width,height:r.height} })(),
  lensRect: (() => { const r = document.querySelector('.entity-selection-lens')?.getBoundingClientRect(); return r && {x:r.x,y:r.y,width:r.width,height:r.height} })(),
  rootViewOld: getComputedStyle(document.documentElement, '::view-transition-old(root)').animationName,
  rootViewNew: getComputedStyle(document.documentElement, '::view-transition-new(root)').animationName,
})`)
console.log('POST', JSON.stringify(post))

if (process.env.SMOKE_ALL === '1') {
  const results = []
  const totalGroups = await evaluate(`document.querySelectorAll('.workspace-function-menu > summary').length`)
  for (let groupIndex = 0; groupIndex < totalGroups; groupIndex += 1) {
    await clickSelector('.workspace-function-menu > summary', groupIndex)
    await sleep(60)
    const targetRect = await evaluate(`(() => {
      const group = document.querySelectorAll('.workspace-function-menu')[${groupIndex}]
      if (!group?.open) return null
      const button = [...group.querySelectorAll('.function-menu-popover > button')].find(el => !el.classList.contains('active'))
      if (!button) return null
      const r = button.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2, text: button.textContent.trim() }
    })()`)
    if (!targetRect) {
      results.push({ groupIndex, skipped: true })
      continue
    }
    await clickPoint(targetRect)
    const samples = []
    for (const delay of [16, 34, 70]) {
      await sleep(delay)
      samples.push(await evaluate(`(() => {
        const context = getComputedStyle(document.querySelector('.entity-context-display'))
        const rail = getComputedStyle(document.querySelector('.global-rail'))
        const header = getComputedStyle(document.querySelector('.workspace-header'))
        const entity = getComputedStyle(document.querySelector('.entity-bar'))
        return {
          switching: document.querySelector('.workspace-shell')?.classList.contains('switching') || false,
          contextOpacity: context.opacity,
          contextTransform: context.transform,
          contextBackground: context.backgroundColor,
          railBackground: rail.backgroundColor,
          headerBackground: header.backgroundColor,
          entityBackground: entity.backgroundColor,
        }
      })()`))
    }
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const switching = await evaluate(`document.querySelector('.workspace-shell')?.classList.contains('switching') || false`)
      if (!switching) break
      await sleep(30)
    }
    const moduleLabel = await evaluate(`document.querySelector('.workspace-module')?.textContent?.trim() || ''`)
    results.push({ groupIndex, target: targetRect.text, moduleLabel, samples })
  }
  console.log('SMOKE_ALL', JSON.stringify(results))
}
ws.close()
