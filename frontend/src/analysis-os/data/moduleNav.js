// 显式带扩展名：这个模块被 node --test 直接 import（Vite 能解析无扩展名，
// Node 的 ESM 加载器不能），少一个 .js 就会让测试整份报 Cannot find module。
import { groupModulesByCategory } from './modules.js'

/**
 * 二级导航（一级分组 → 点击展开 → 二级模块）的状态计算。
 *
 * 这些逻辑原本内联在 ArchiveWorkspaceShell.vue 里，而组件测试只能读源码做正则断言，
 * 测不出"该展开哪一组、再点一下会变成谁"。抽成纯函数后可以在
 * frontend/tests/module-nav.test.mjs 里用真实模块数据直接断言。
 */

/** 模块所属的一级分组键；模块不在任何分组里时返回空串（此时不展开任何组）。 */
export function groupKeyOfModule(groups, moduleKey) {
  const group = groups.find(item => item.modules.some(module => module.key === moduleKey))
  return group ? group.key : ''
}

/** 切换模块时用：展开该模块所在的分组。 */
export function openGroupForModule(groups, moduleKey) {
  return groupKeyOfModule(groups, moduleKey)
}

/**
 * 分组列表变化时用（例如登录态切换导致分组增减）：
 * 当前展开的组还在就保持不变，否则重新跟随当前模块——
 * 这样既不会因为列表重建就把用户正看着的菜单收起来，也不会停留在一个已经不存在
 * 的组上导致二级面板空白。
 */
export function keepOrRefollow(groups, current, moduleKey) {
  if (groups.some(group => group.key === current)) return current
  return groupKeyOfModule(groups, moduleKey)
}

/** 点击一级分组：已展开则收起（一次只展开一组）。 */
export function toggleGroupKey(current, key) {
  return current === key ? '' : key
}

/** 该分组是否包含当前模块（用于在一级标签上标出"当前所在组"）。 */
export function groupIsCurrent(group, moduleKey) {
  return group.modules.some(module => module.key === moduleKey)
}

/** 展开的组对应的二级模块列表。 */
export function modulesInGroup(groups, key) {
  return groups.find(group => group.key === key)?.modules || []
}

/** 供组件一次性拿到分组结构。 */
export function buildModuleNavGroups(modules) {
  return groupModulesByCategory(modules)
}

/**
 * 不变量：分组只影响呈现，**一个模块都不能丢、也不能重复**。
 * 导航少一个模块就是功能消失，而这种错误在界面上一眼看不出来。
 */
export function navCoversAllModules(modules, groups = buildModuleNavGroups(modules)) {
  const seen = groups.flatMap(group => group.modules.map(module => module.key))
  const expected = modules.map(module => module.key)
  return seen.length === expected.length
    && expected.every(key => seen.filter(item => item === key).length === 1)
}