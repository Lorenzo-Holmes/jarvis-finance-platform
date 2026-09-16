import { type APIRequestContext } from '@playwright/test'
import { test } from '../fixtures/test'
import { API_URL, CREDENTIALS, hasCredentials } from '../utils/env'

/**
 * 后端可用性探测。
 *
 * 需要真实接口的用例必须先过这一关：Java 未起时，前端会把网络异常也渲染成
 * `[role="alert"]`，导致「错误凭据」用例**假通过**——那验证不了任何东西。
 */
async function backendReady(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${API_URL}/api/health/ready`, { timeout: 3_000 })
    return res.ok()
  } catch {
    return false
  }
}

/**
 * 登录与权限流程（对应 SRS V1.1「覆盖登录权限流程」）。
 *
 * 需要真实账号的用例在未配置环境变量时**跳过而非失败**，保证 CI 无账号也能为绿；
 * 需要凭据时应通过 CI secret 注入：
 *   E2E_USER_EMAIL / E2E_USER_PASSWORD
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD
 */
test.describe('登录与权限', () => {
  test('未登录时工作台外壳不应渲染', async ({ page, workspacePage }) => {
    // 纯前端判定，不依赖后端：即使 `/me` 请求失败，未登录也不该进入工作台。
    await page.goto('/')
    await workspacePage.expectShellHidden()
  })

  test('错误凭据应给出错误提示', async ({ loginPage, request }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)

    await loginPage.openLogin()
    await loginPage.login('e2e-nonexistent@example.com', 'not-a-real-password-1234')
    await loginPage.expectError()
  })

  test('正确凭据登录后可进入工作台', async ({ loginPage, workspacePage, request }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)
    test.skip(!hasCredentials('user'), '未配置 E2E_USER_EMAIL / E2E_USER_PASSWORD，跳过真实登录用例')

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await workspacePage.expectShellVisible()
  })

  test('管理员登录后可进入工作台', async ({ loginPage, workspacePage, request }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)
    test.skip(!hasCredentials('admin'), '未配置 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD，跳过管理员用例')

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.admin.email, CREDENTIALS.admin.password)
    await workspacePage.expectShellVisible()

    // TODO(nav-ia-v11)：二级菜单（A2/A4）落地后，在此补「系统管理」域对 ADMIN 可见、
    // 对普通 USER 不可见的断言。当前「管理」入口挂在旧 AppTabs 上，断言不稳定，暂不写。
  })
})
