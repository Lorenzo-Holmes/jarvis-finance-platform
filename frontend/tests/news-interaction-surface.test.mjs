import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('news center exposes smart/latest ranking switch through API client', () => {
  const page = read('pages/NewsCenterPage.vue')
  assert.match(page, /const rankingMode = ref\('smart'\)/)
  assert.match(page, /api\.newsDaily\(24, force, rankingMode\.value\)/)
  assert.match(page, /智能精选/)
  assert.match(page, /最新发布/)
  assert.match(page, /aria-pressed/)
})

test('market news board keeps ranking mode aligned with paged feed', () => {
  const board = read('components/market/MarketNewsBoard.vue')
  assert.match(board, /const rankingMode = ref\('smart'\)/)
  assert.match(board, /api\.newsDaily\(NEWS_FETCH_LIMIT, force, rankingMode\.value\)/)
  assert.match(board, /async function changeRanking/)
  assert.match(board, /page\.value = 1/)
  assert.match(board, /aria-label="市场要闻排序方式"/)
})
