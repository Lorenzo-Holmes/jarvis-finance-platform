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
  await access.click({ force: true })
  const researchMenu = page.getByLabel('研究功能菜单')
  await expect(researchMenu, '进入工作台后应显示研究功能菜单').toBeVisible({ timeout: 30_000 })
  await researchMenu.click()
  const agentMenuItem = page.getByRole('button', { name: /^研究助手(?:\s|$)/ })
  await expect(agentMenuItem, '研究功能菜单应列出研究助手').toBeVisible({ timeout: 10_000 })
  await agentMenuItem.click()
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
    await expect(page.locator('.container')).toBeVisible({ timeout: 30_000 })

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

    // 用受控的长连接覆盖前端取消状态；第一条用例已经覆盖真实生产 SSE。
    await page.addInitScript(() => {
      const nativeFetch = window.fetch.bind(window)
      window.fetch = async (input, init) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
        const method = String(init?.method || (input instanceof Request ? input.method : 'GET') || 'GET').toUpperCase()
        if (method === 'POST' && url.includes('/api/agent/research/stream')) {
          const encoder = new TextEncoder()
          const frame = `event: agent_step\ndata: ${JSON.stringify({
            runId: 'e2e-cancel-run', stepId: 'e2e-cancel-step', sequence: 1,
            type: 'run_started', status: 'running', title: '研究工作流已开始',
          })}\n\n`
          const body = new ReadableStream({
            start(controller) {
              controller.enqueue(encoder.encode(frame))
              init?.signal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true })
            },
          })
          return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
        }
        if (method === 'DELETE' && url.includes('/api/agent/runs/')) {
          return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        return nativeFetch(input, init)
      }
    })

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await expect(page.locator('.container')).toBeVisible({ timeout: 30_000 })
    await openAgentWorkspace(page)
    await page.getByRole('textbox', { name: '研究问题' }).fill(
      `请执行完整金融研究工作流并持续输出可观察步骤；不要执行交易。${'请保留完整工具调用上下文。'.repeat(30)}`,
    )
    await page.getByRole('button', { name: '提交研究问题' }).click()

    const trace = page.getByTestId('agent-trace')
    const stop = trace.getByRole('button', { name: '停止' })
    await expect(stop, '运行中 Trace 应提供停止按钮').toBeVisible({ timeout: 15_000 })
    // 发送区与 Trace 共用同一个 stopChat 处理器，使用发送区按钮避免滚动容器的几何点击干扰。
    await page.getByRole('button', { name: '停止生成' }).click({ force: true })
    await expect(trace.getByText('STOPPED', { exact: true }), '停止后 Trace 应显示已停止').toBeVisible({ timeout: 30_000 })
  })

  test('工具失败时，Trace 应显示可理解的失败提示', async ({ loginPage, page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过真实 Agent 失败验收`)
    test.skip(!hasCredentials('user'), '未配置 E2E_USER_EMAIL / E2E_USER_PASSWORD，跳过真实 Agent 失败验收')

    await page.addInitScript(() => {
      const nativeFetch = window.fetch.bind(window)
      window.fetch = async (input, init) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
        const method = String(init?.method || (input instanceof Request ? input.method : 'GET') || 'GET').toUpperCase()
        if (method === 'POST' && url.includes('/api/agent/research/stream')) {
          const events = [
            { runId: 'e2e-failed-run', stepId: 'e2e-failed-run', sequence: 1, type: 'run_started', status: 'running', title: '研究工作流已开始' },
            { runId: 'e2e-failed-run', stepId: 'terminal:e2e-failed-run', sequence: 2, type: 'run_failed', status: 'failed', title: '研究工作流失败', outputSummary: '行情工具暂时不可用，请稍后重试', errorCode: 'TOOL_UNAVAILABLE' },
          ]
          const body = events.map(event => `event: agent_step\ndata: ${JSON.stringify(event)}\n\n`).join('')
          return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
        }
        return nativeFetch(input, init)
      }
    })

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await expect(page.locator('.container')).toBeVisible({ timeout: 30_000 })
    await openAgentWorkspace(page)
    await page.getByRole('textbox', { name: '研究问题' }).fill('请验证行情工具失败时的研究提示。')
    await page.getByRole('button', { name: '提交研究问题' }).click()

    const trace = page.getByTestId('agent-trace')
    await expect(trace.getByText('FAILED', { exact: true }), '失败运行应显示 FAILED').toBeVisible({ timeout: 30_000 })
    await expect(trace, '失败运行应给出可理解的错误原因').toContainText('行情工具暂时不可用，请稍后重试')
  })
})
