import { expect, test } from '../fixtures/test'
import type { APIRequestContext } from '@playwright/test'
import { API_URL, CREDENTIALS, hasCredentials } from '../utils/env'

async function backendReady(request: APIRequestContext): Promise<boolean> {
  try {
    const response = await request.get(`${API_URL}/api/health/ready`, { timeout: 3_000 })
    return response.ok()
  } catch {
    return false
  }
}

function parseSse(body: Buffer): Array<Record<string, unknown>> {
  return body.toString('utf8')
    .split(/\r?\n/)
    .filter(line => line.startsWith('data:'))
    .map(line => {
      try { return JSON.parse(line.slice(5).trim()) as Record<string, unknown> } catch { return null }
    })
    .filter((event): event is Record<string, unknown> => Boolean(event))
}

async function openAgentWorkspace(page: import('@playwright/test').Page): Promise<void> {
  await expect(page.locator('.boot-layer'), 'Analysis OS 启动层应结束后再接受模块交互').toHaveCount(0, { timeout: 10_000 })
  const access = page.getByRole('button', { name: /ACCESS FILE/ })
  await expect(access, '默认市场档案稳定后应允许进入工作台').toBeEnabled({ timeout: 30_000 })
  await access.click()
  const researchMenu = page.getByLabel('研究功能菜单')
  await expect(researchMenu, '进入工作台后应显示研究功能菜单').toBeVisible({ timeout: 30_000 })
  await researchMenu.click()
  await page.getByRole('button', { name: /^研究助手(?:\s|$)/ }).click()
  await expect(page.getByRole('textbox', { name: '研究问题' }), '打开研究助手后应显示研究问题输入').toBeVisible({ timeout: 30_000 })
}

test.describe('金融 Agent · 真实浏览器验收', () => {
  test('登录后可观察工具生命周期、Markdown 结论与历史运行', async ({ loginPage, page, request }) => {
    test.setTimeout(150_000)
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过真实 Agent 浏览器验收`)
    test.skip(!hasCredentials('user'), '未配置 E2E_USER_EMAIL / E2E_USER_PASSWORD，跳过真实 Agent 浏览器验收')

    const streamBodies: Promise<Array<Record<string, unknown>>>[] = []
    page.on('response', response => {
      if (response.url().includes('/api/agent/research/stream')) {
        streamBodies.push(response.body().then(parseSse).catch(() => []))
      }
    })

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await expect(page.locator('.container')).toBeVisible()

    await openAgentWorkspace(page)
    const question = '请执行完整金融研究工作流，调用资讯、财报、行情、K线、技术指标和风险工具后，用 Markdown 输出一段简短结论；不要执行交易。'
    await page.getByRole('textbox', { name: '研究问题' }).fill(question)
    await page.getByRole('button', { name: '提交研究问题' }).click()

    const trace = page.getByTestId('agent-trace')
    await expect(trace, '真实登录后应显示 Agent Trace').toBeVisible()
    await expect(trace.getByText('STEP', { exact: true }), 'Trace 应展示已完成步骤').toBeVisible({ timeout: 120_000 })
    await expect(trace.getByText('DONE', { exact: true }), 'Trace 应展示运行终态').toBeVisible({ timeout: 120_000 })
    await expect(page.locator('.message-row.assistant').last().locator('.message-content'), 'Agent 应渲染 Markdown 结论').toContainText(/.+/)

    const events = (await Promise.all(streamBodies)).flat()
    const runningCalls = events.filter(event => event.type === 'tool_call' && event.status === 'running')
    expect(runningCalls.length, '真实 SSE 至少应包含一个工具调用').toBeGreaterThan(0)
    for (const call of runningCalls) {
      const sameStep = events.filter(event => event.stepId === call.stepId).map(event => event.type)
      expect(sameStep, `工具 ${String(call.tool)} 应有完整步骤生命周期`).toEqual(expect.arrayContaining([
        'step_started', 'tool_call', 'tool_result', 'step_completed',
      ]))
    }

    await page.getByRole('button', { name: '运行历史' }).click()
    await expect(page.getByRole('complementary', { name: 'Agent 运行历史' }), '历史运行抽屉应可打开').toBeVisible()
  })

  test('运行中点击停止后，Trace 应显示已停止', async ({ loginPage, page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过真实 Agent 停止验收`)
    test.skip(!hasCredentials('user'), '未配置 E2E_USER_EMAIL / E2E_USER_PASSWORD，跳过真实 Agent 停止验收')

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await expect(page.locator('.container')).toBeVisible()
    await openAgentWorkspace(page)
    await page.getByRole('textbox', { name: '研究问题' }).fill(
      `请执行完整金融研究工作流并持续输出可观察步骤；不要执行交易。${'请保留完整工具调用上下文。'.repeat(30)}`,
    )
    await page.getByRole('button', { name: '提交研究问题' }).click()

    const trace = page.getByTestId('agent-trace')
    const stop = trace.getByRole('button', { name: '停止' })
    await expect(stop, '运行中 Trace 应提供停止按钮').toBeVisible({ timeout: 15_000 })
    // Trace 位于内部滚动面板中，Playwright 的几何点击可能被同一滚动容器抢焦点；
    // 强制派发真实 DOM click，仍由组件的停止处理器调用后端取消接口。
    await stop.click({ force: true })
    await expect(trace.getByText('STOPPED', { exact: true }), '停止后 Trace 应显示已停止').toBeVisible({ timeout: 30_000 })
  })
})
