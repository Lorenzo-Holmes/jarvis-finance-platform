import test from 'node:test'
import assert from 'node:assert/strict'

import { JARVIS_MODULES } from '../src/analysis-os/data/modules.js'
import {
  buildModuleNavGroups,
  groupIsCurrent,
  groupKeyOfModule,
  keepOrRefollow,
  modulesInGroup,
  navCoversAllModules,
  openGroupForModule,
  toggleGroupKey,
} from '../src/analysis-os/data/moduleNav.js'

const groups = buildModuleNavGroups(JARVIS_MODULES)
const groupKeys = groups.map(group => group.key)

test('分组覆盖全部模块，且不重复不丢失', () => {
  // 导航少一个模块就是功能消失，这种错误在界面上看不出来，必须钉住
  assert.equal(navCoversAllModules(JARVIS_MODULES, groups), true)
  assert.equal(groups.flatMap(group => group.modules).length, JARVIS_MODULES.length)
  assert.equal(JARVIS_MODULES.length, 13)
})

test('分组顺序按工作流，未知分类落到 OTHER 且排在最后', () => {
  assert.deepEqual(groupKeys, ['MARKET', 'RESEARCH', 'INTELLIGENCE', 'STRATEGY', 'EXECUTION', 'SYSTEM'])

  const withUnknown = buildModuleNavGroups([
    ...JARVIS_MODULES,
    { key: 'mystery', no: 99, labelEn: 'MYSTERY', labelZh: '未知', category: 'NOT_A_CATEGORY' },
  ])
  assert.deepEqual(withUnknown.map(group => group.key).at(-1), 'OTHER')
  assert.equal(withUnknown.at(-1).modules[0].key, 'mystery')
})

test('各分组的模块数量与模块表一致', () => {
  assert.equal(modulesInGroup(groups, 'MARKET').length, 2)
  assert.equal(modulesInGroup(groups, 'RESEARCH').length, 3)
  assert.equal(modulesInGroup(groups, 'INTELLIGENCE').length, 4)
  assert.equal(modulesInGroup(groups, 'STRATEGY').length, 2)
  assert.equal(modulesInGroup(groups, 'EXECUTION').length, 1)
  assert.equal(modulesInGroup(groups, 'SYSTEM').length, 1)
  assert.deepEqual(modulesInGroup(groups, 'NOPE'), [])
})

test('切换模块会展开该模块所在分组', () => {
  assert.equal(openGroupForModule(groups, 'market'), 'MARKET')
  assert.equal(openGroupForModule(groups, 'market-trend'), 'INTELLIGENCE')
  assert.equal(openGroupForModule(groups, 'ops'), 'SYSTEM')
  // 未知模块不展开任何一组，而不是糊在第一个组上
  assert.equal(openGroupForModule(groups, 'not-a-module'), '')
  assert.equal(groupKeyOfModule(groups, 'not-a-module'), '')
})

test('点击一级分组是展开/收起，且一次只展开一组', () => {
  assert.equal(toggleGroupKey('', 'RESEARCH'), 'RESEARCH')
  assert.equal(toggleGroupKey('RESEARCH', 'MARKET'), 'MARKET')
  // 再点同一个分组就是收起
  assert.equal(toggleGroupKey('RESEARCH', 'RESEARCH'), '')
})

test('模块列表变化时：仍在的组保持展开，消失的组重新跟随当前模块', () => {
  const market = JARVIS_MODULES.find(module => module.key === 'market')

  assert.equal(keepOrRefollow(groups, 'SYSTEM', 'market'), 'SYSTEM')
  // 登录态变化导致 SYSTEM 组消失时，不能停在空面板上
  const withoutSystem = buildModuleNavGroups(JARVIS_MODULES.filter(module => module.category !== 'SYSTEM'))
  assert.equal(keepOrRefollow(withoutSystem, 'SYSTEM', 'market'), 'MARKET')
  assert.equal(keepOrRefollow(withoutSystem, 'SYSTEM', market.key), 'MARKET')
})

test('当前所在分组可被标出', () => {
  const marketGroup = groups.find(group => group.key === 'MARKET')
  const researchGroup = groups.find(group => group.key === 'RESEARCH')

  assert.equal(groupIsCurrent(marketGroup, 'cross-market'), true)
  assert.equal(groupIsCurrent(researchGroup, 'cross-market'), false)
})

test('分组只改呈现，不改动模块数据本身', () => {
  const before = JSON.stringify(JARVIS_MODULES)
  buildModuleNavGroups(JARVIS_MODULES)
  assert.equal(JSON.stringify(JARVIS_MODULES), before)
})