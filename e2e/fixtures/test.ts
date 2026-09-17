import { test as base } from '@playwright/test'
import { LandingPage } from '../pages/LandingPage'
import { LoginPage } from '../pages/LoginPage'
import { WorkspacePage } from '../pages/WorkspacePage'

/**
 * 自定义 test：把各页面对象注入用例，避免每个用例自己 `new`。
 *
 * 用例写法：
 * ```ts
 * import { test, expect } from '../fixtures/test'
 *
 * test('登录后能看到工作台', async ({ loginPage, workspacePage }) => {
 *   await loginPage.openLogin()
 *   await loginPage.login(email, password)
 *   await workspacePage.expectShellVisible()
 * })
 * ```
 */
export type JarvisFixtures = {
  landingPage: LandingPage
  loginPage: LoginPage
  workspacePage: WorkspacePage
}

export const test = base.extend<JarvisFixtures>({
  landingPage: async ({ page }, use) => {
    await use(new LandingPage(page))
  },
  loginPage: async ({ page }, use) => {
    await use(new LoginPage(page))
  },
  workspacePage: async ({ page }, use) => {
    await use(new WorkspacePage(page))
  },
})

export { expect } from '@playwright/test'
