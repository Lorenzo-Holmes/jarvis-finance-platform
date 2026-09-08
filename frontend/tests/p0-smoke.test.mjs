import test from 'node:test'
import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = fileURLToPath(new URL('..', import.meta.url))

globalThis.window = { location: { hostname: 'localhost' } }

class FakeEventSource {
  static instances = []

  constructor(url, options) {
    this.url = url
    this.options = options
    this.listeners = new Map()
    this.closed = false
    this.onerror = null
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, callback) {
    this.listeners.set(name, callback)
  }

  emit(name, data) {
    this.listeners.get(name)?.({ data })
  }

  close() {
    this.closed = true
  }
}

globalThis.EventSource = FakeEventSource

const { API_BASE, api } = await import('../src/api/client.js')

test('local development keeps browser on the Java/Vite same-origin API', () => {
  assert.equal(API_BASE, '')
})

test('market price stream parses JSON events and closes cleanly', () => {
  let received = null
  let errors = 0
  const close = api.marketPriceStream(
    payload => { received = payload },
    () => { errors += 1 },
  )

  const source = FakeEventSource.instances.at(-1)
  assert.ok(source)
  assert.equal(source.url, '/api/market/prices/stream')
  assert.deepEqual(source.options, { withCredentials: true })

  const payload = {
    market: { gold_etf: { price: 7.88 } },
    jd: { zheshang: { price: 812.3 } },
    server_time: '2026-09-08T10:00:00Z',
  }
  source.emit('prices', JSON.stringify(payload))
  assert.deepEqual(received, payload)

  source.onerror?.(new Error('network'))
  assert.equal(errors, 1)

  close()
  assert.equal(source.closed, true)
})

async function sourceFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const output = []
  for (const entry of entries) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) output.push(...await sourceFiles(path))
    else if (['.js', '.vue'].includes(extname(entry.name))) output.push(path)
  }
  return output
}

test('frontend source cannot bypass the Java security boundary', async () => {
  const forbidden = [
    ['direct Python port', /(?:localhost|127\.0\.0\.1):8100/i],
    ['public Python route', /["'`]\/py(?:\/|["'`])/i],
    ['internal service token header', /X-Internal-Service-Token/i],
    ['Python service secret', /PYTHON_SERVICE_TOKEN/i],
    ['AI provider secret', /(?:AI_API_KEY|DEEPSEEK_API_KEY|OLLAMA_API_KEY)/i],
  ]

  const violations = []
  for (const file of await sourceFiles(join(frontendRoot, 'src'))) {
    const text = await readFile(file, 'utf8')
    for (const [label, pattern] of forbidden) {
      if (pattern.test(text)) violations.push(`${relative(frontendRoot, file)}: ${label}`)
    }
  }
  assert.deepEqual(violations, [])
})
