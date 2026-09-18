import test from 'node:test'
import assert from 'node:assert/strict'
import { groupCommandItems, searchCommandItems } from '../src/analysis-os/data/commandPalette.js'

const ITEMS = [
  {
    id: 'entity:gold',
    kind: 'entity',
    label: '黄金ETF华夏',
    summary: '518850 · gold_etf',
    meta: '当前研究对象',
    keywords: ['518850', '黄金', 'gold'],
  },
  {
    id: 'workspace:market',
    kind: 'workspace',
    label: '行情',
    summary: '实时行情与价格结构',
    meta: '市场',
    keywords: ['market'],
  },
  {
    id: 'workspace:research',
    kind: 'workspace',
    label: '研究助手',
    summary: '研究记录与证据',
    meta: '研究',
    keywords: ['research'],
  },
  {
    id: 'action:theme',
    kind: 'action',
    label: '切换到夜间模式',
    summary: '调整视觉主题',
    meta: '主题',
    keywords: ['night', 'theme', '夜间'],
  },
]

test('command palette searches entity, workspace and action keywords with stable relevance', () => {
  assert.equal(searchCommandItems(ITEMS, '518850')[0].id, 'entity:gold')
  assert.equal(searchCommandItems(ITEMS, 'research')[0].id, 'workspace:research')
  assert.equal(searchCommandItems(ITEMS, '夜间')[0].id, 'action:theme')
  assert.deepEqual(searchCommandItems(ITEMS, '不存在'), [])
})

test('command palette groups results in entity, workspace, action order', () => {
  const groups = groupCommandItems(ITEMS)
  assert.deepEqual(groups.map(group => group.key), ['entity', 'workspace', 'action'])
  assert.deepEqual(groups.map(group => group.label), ['研究对象', '工作区', '操作'])
  assert.equal(groups[1].items.length, 2)
})
