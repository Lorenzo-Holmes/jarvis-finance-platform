import { expect, test } from '../fixtures/test'
import { PREVIEW_PARAMS } from '../utils/env'

/**
 * 冒烟用例：只验证前端自身可渲染，**不依赖 Java / Python 任一后端**。
 *
 * 这样即使本地只起了 `npm run dev`，也能确认「页面没白屏、路由没坏、登录表单还在」——
 * 正是二级菜单改造期最容易踩坏的部分。
 */
test.describe('冒烟 · 基础渲染', () => {
  test('官网首页可打开并渲染 iframe', async ({ landingPage }) => {
    await landingPage.goto()
    await landingPage.expectVisible(landingPage.frameElement.first(), '官网 iframe 应渲染')
  })

  test('官网「进入 JARVIS」入口可切到登录视图', async ({ loginPage, landingPage }) => {
    await landingPage.goto()
    await landingPage.enterLogin()
    await loginPage.expectFormVisible()
  })

  test('登录视图可由 ?view=login 直达', async ({ loginPage }) => {
    await loginPage.openLogin()
    await loginPage.expectFormVisible()
  })

  test('空表单提交应被前端校验拦下', async ({ loginPage }) => {
    await loginPage.openLogin()
    await loginPage.submitEmptyAndExpectValidation()
  })

  test('DEV 预览模式可免登录进入工作台外壳', async ({ workspacePage }) => {
    await workspacePage.goto(PREVIEW_PARAMS)

    // ?preview=1 只在 dev + localhost 生效；生产构建下拿不到工作台，此时跳过而非判失败。
    const available = await workspacePage.shell
      .first()
      .waitFor({ state: 'visible', timeout: 8_000 })
      .then(() => true)
      .catch(() => false)

    test.skip(!available, '当前环境未启用 DEV 预览模式（?preview=1 仅在 dev + localhost 生效）')

    await workspacePage.expectShellVisible()
  })

  test('分类菜单与新版行情首页可在无后端 fixture 下完整导航', async ({ visualWorkspacePage, page }) => {
    test.setTimeout(45_000)
    await visualWorkspacePage.openStablePreview()

    await expect(page.getByRole('heading', { name: '全球市场脉搏' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '多市场自选' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '市场要闻' })).toBeVisible()

    for (const label of ['研究', '产业链', '策略', '交易'] as const) {
      await visualWorkspacePage.openWorkspaceModule(label)
      await expect(visualWorkspacePage.workspaceShell).toBeVisible()
    }
  })
})
