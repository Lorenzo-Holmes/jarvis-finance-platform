import { expect, type Locator, type Route } from '@playwright/test'
import { WorkspacePage } from './WorkspacePage'

/**
 * 系统管理域 · **定时任务的权限置灰契约**（PR 中「按 TASK_MANAGE 在 UI 层置灰」那部分）。
 *
 * 为什么单独一个页面对象而不是复用整个任务页 POM：
 * 本类只服务于「按权限灰置写操作」这一条契约，与逐步补齐的功能用例（列表/编辑/历史）
 * 关注点不同。`path`、免登录预览入口与外壳断言继承自 `WorkspacePage`；
 * 「档案过场 + 系统菜单导航」在本类内实现 —— 手法与 `VisualWorkspacePage.openStablePreview`
 * 一致，但刻意不继承视觉页对象：视觉页属回归专用，混进来会让两条线耦合。
 *
 * ⚠️ 本页目前**没有任何 `data-testid`**，所以这里按 role 与文案定位。
 * 语义化 role 比 class 稳定：队友改样式不会红，改文案才会——那时断言信息会直接指出是哪个按钮。
 */

/** 后端 `/api/scheduled-tasks` 返回的任务条目（字段名对齐 `ScheduledTasksPage.vue` 的读取路径）。 */
export type StubTask = {
  id: number
  name: string
  task_type: string
  status: 'ACTIVE' | 'PAUSED'
  cron_expr: string
  next_run_at: string
  last_run_status: string | null
  last_error: string | null
}

/** 运行中的任务 —— 有「暂停」按钮，没有「恢复」。 */
export const TASK_ACTIVE: StubTask = {
  id: 101,
  name: '工作日风险检查',
  task_type: 'RISK_CHECK',
  status: 'ACTIVE',
  cron_expr: '0 0 9 * * MON-FRI',
  next_run_at: '2026-09-22T09:00:00+08:00',
  last_run_status: 'SUCCESS',
  last_error: null,
}

/** 已暂停的任务 —— 有「恢复」按钮，没有「暂停」。 */
export const TASK_PAUSED: StubTask = {
  id: 102,
  name: '每半小时行情扫描',
  task_type: 'MARKET_SCAN',
  status: 'PAUSED',
  cron_expr: '0 */30 * * * *',
  next_run_at: '2026-09-22T10:00:00+08:00',
  last_run_status: 'FAILED',
  last_error: '上游行情源超时',
}

/** 后端声明的类型目录（`/api/scheduled-tasks/types`）。 */
const TYPE_CATALOG = [
  { type: 'MARKET_SCAN', label: '行情扫描', supported: true },
  { type: 'RISK_CHECK', label: '风险检测', supported: true },
  { type: 'BACKTEST', label: '策略回测', supported: true },
  { type: 'DAILY_DIGEST', label: '资讯日报', supported: true },
]

/** 能力探测的三种结局：明确有权限 / 明确无权限 / 探测失败（后端不可用）。 */
export type CanManage = boolean | 'probe-failed'

export type PermissionStubOptions = {
  /** 影响「新建 / 编辑 / 立即执行 / 恢复」四个写操作的可用性。 */
  canManage: CanManage
  /** 列表内容，默认一条运行中 + 一条已暂停，刚好覆盖「暂停」与「恢复」两种按钮。 */
  tasks?: StubTask[]
}

export class TaskPermissionPage extends WorkspacePage {
  /**
   * 拦**所有** `/api/` 请求并本地应答，完全不依赖 Java / Python 三栈。
   *
   * ⚠️ 只拦 `/api/` 前缀（用 URL 谓词），**不要**用「拦全部请求」的通配路由再 `route.continue()`：
   * 那会把 Vite 的 HMR WebSocket 一起拦下，与浏览器关闭互相等待 → 用例**无输出地卡死**，
   * 连超时都不触发、也拿不到失败原因。
   *
   * ⚠️ 必须在导航**之前**调用：能力探测发生在任务页首次加载（`load()` 里三个请求并发）。
   */
  async stubPermission(options: PermissionStubOptions): Promise<void> {
    const tasks = options.tasks ?? [TASK_ACTIVE, TASK_PAUSED]
    await this.raw.route(
      (url) => url.pathname.startsWith('/api/'),
      async (route: Route) => {
        const pathname = new URL(route.request().url()).pathname
        const json = (payload: unknown) =>
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(payload),
          })

        if (pathname.startsWith('/api/scheduled-tasks')) {
          if (pathname.endsWith('/types')) return json({ code: 200, data: TYPE_CATALOG })
          if (pathname.endsWith('/capabilities')) {
            // 探测失败刻意用「HTTP 200 + 业务非 200」表达：前端只认 data.can_manage 是否为 false，
            // 用 HTTP 500 会让整个 Promise.all 提前 reject，列表一起消失，测不出「默认放行」。
            if (options.canManage === 'probe-failed') {
              return json({ code: 503, message: '能力探测失败', data: null })
            }
            return json({
              code: 200,
              data: { feature_key: 'TASK_MANAGE', can_manage: options.canManage },
            })
          }
          if (route.request().method() === 'GET' && /\/api\/scheduled-tasks$/.test(pathname)) {
            return json({ code: 200, data: { items: tasks, total: tasks.length } })
          }
          return json({ code: 200, data: {} })
        }

        // 工作台外壳自己会拉的接口，给最小可用应答，避免回落到未启动的后端。
        if (pathname === '/api/auth/csrf') return json({ code: 200, data: { token: 'stub-csrf' } })
        if (pathname === '/api/notifications/unread-count') return json({ code: 200, data: { unread: 0 } })
        if (pathname === '/api/market/session') return json({ code: 200, data: { is_open: true } })
        if (pathname === '/api/market/preferences') {
          return json({ code: 200, data: { persisted: false, watchlist: [], hiddenDefaultKeys: [] } })
        }
        if (pathname === '/api/ai/status') return json({ code: 200, data: { available: false } })
        return json({ code: 200, data: {} })
      },
    )
  }

  /**
   * 从免登录预览进入「系统 > 定时任务」。
   *
   * 预览模式首屏是 Archive Sea 过场（WebGL），工作台外壳要等它结束或点 `ACCESS FILE →` 才出现，
   * 直接断言菜单会失败。此逻辑与 `VisualWorkspacePage.openStablePreview` 一致，
   * 但不继承该页对象 —— 视觉页属回归专用，混进功能用例会让两条线耦合。
   */
  async openTaskWorkspace(): Promise<void> {
    await this.openPreview()

    const enter = this.byRole('button', /^ACCESS FILE/)
    const shell = this.byTestId('common-workspace-shell', '.workspace-shell')

    await enter
      .or(shell)
      .first()
      .waitFor({ state: 'visible', timeout: 24_000 })
    if (!(await shell.first().isVisible())) {
      await this.clickAndWait(enter, '预览模式应能从档案进入金融工作区')
    }
    await shell.first().waitFor({ state: 'visible', timeout: 24_000 })

    const menu = this.raw.getByLabel('系统功能菜单')
    await this.clickAndWait(menu, '工作台应能展开「系统」功能菜单')
    const entry = this.byRole('button', /^定时任务(?:\s|$)/).first()
    await this.clickAndWait(entry, '「系统」菜单里应有「定时任务」入口')
    await this.waitForTaskPage()
  }

  // ── 定位 ──────────────────────────────────────────────────────────────

  get heading(): Locator {
    return this.byRole('heading', /^定时任务$/)
  }

  /** 无权限时才出现的说明条。 */
  get permissionNotice(): Locator {
    return this.raw.getByText(/未开通「定时任务管理」权限/)
  }

  get refreshButton(): Locator {
    return this.byRole('button', '刷新')
  }

  get createButton(): Locator {
    return this.byRole('button', '新建任务')
  }

  /** 任务表里某一行的行内操作按钮（`编辑 / 历史 / 立即执行 / 暂停 / 恢复 / 删除`）。 */
  rowAction(taskName: string, action: string): Locator {
    return this.row(taskName).getByRole('button', { name: action, exact: true })
  }

  /** 任务表里包含指定文案的整行，供「列表本身可见」这类断言使用。 */
  row(text: string): Locator {
    return this.raw.getByRole('row').filter({ hasText: text })
  }

  /** 任务编辑器（新建/编辑弹窗）。 */
  get editor(): Locator {
    return this.raw.getByRole('dialog', { name: '定时任务编辑器' })
  }

  // ── 断言 ──────────────────────────────────────────────────────────────

  /** 等页面真正渲染出列表，避免与异步组件的首次请求抢时序。 */
  async waitForTaskPage(): Promise<void> {
    await expect(this.heading, '应进入「定时任务」页').toBeVisible({ timeout: 20_000 })
    await expect(
      this.row(TASK_ACTIVE.name),
      '任务列表应渲染出第一条任务',
    ).toBeVisible({ timeout: 20_000 })
  }

  /** 钉住「服务端只拦四类消耗资源的动作」这条设计：这四个必须被置灰。 */
  async expectWriteActionsGated(): Promise<void> {
    await expect(this.createButton, '「新建任务」应因无权限而置灰').toBeDisabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '编辑'), '「编辑」应因无权限而置灰').toBeDisabled()
    await expect(
      this.rowAction(TASK_ACTIVE.name, '立即执行'),
      '「立即执行」应因无权限而置灰',
    ).toBeDisabled()
    await expect(this.rowAction(TASK_PAUSED.name, '恢复'), '「恢复」应因无权限而置灰').toBeDisabled()
  }

  /**
   * 钉住「其余动作刻意不受权限限制」这条设计 ——
   * 否则用户被收回权限后，连关掉自己还在跑的任务都做不到。
   */
  async expectNonWriteActionsUsable(): Promise<void> {
    await expect(this.refreshButton, '「刷新」不应受权限限制').toBeEnabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '历史'), '「历史」不应受权限限制').toBeEnabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '暂停'), '「暂停」不应受权限限制').toBeEnabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '删除'), '「删除」不应受权限限制').toBeEnabled()
  }

  /** 四个写操作全部可用（有权限时，以及探测失败默认放行时）。 */
  async expectWriteActionsUsable(): Promise<void> {
    await expect(this.createButton, '有权限时「新建任务」应可用').toBeEnabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '编辑'), '有权限时「编辑」应可用').toBeEnabled()
    await expect(this.rowAction(TASK_ACTIVE.name, '立即执行'), '有权限时「立即执行」应可用').toBeEnabled()
    await expect(this.rowAction(TASK_PAUSED.name, '恢复'), '有权限时「恢复」应可用').toBeEnabled()
  }

  /**
   * 证明按钮是**真禁用**而不是只调了透明度：强制点击后编辑器不应打开。
   * HTML 的 disabled 按钮不会派发 click，所以这里既能通过动作性检查，又不会触发处理函数。
   */
  async expectDisabledClickDoesNothing(taskName: string, action: string): Promise<void> {
    await this.rowAction(taskName, action).click({ force: true })
    await expect(this.editor, `置灰的「${action}」被点击后不应打开编辑器`).toBeHidden()
  }
}
