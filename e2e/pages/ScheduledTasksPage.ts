import { type Locator } from '@playwright/test'
import { BasePage } from './BasePage'

/**
 * 系统域 · 定时任务（`frontend/src/pages/ScheduledTasksPage.vue`）。
 *
 * 对应 SRS V1.1「覆盖任务管理核心流程」。本页面由队友交付，E2E 侧**只加属性不碰结构**：
 * 随本次改动在页面上注入了 30 个 `data-testid`（命名遵循 README §4 的 `<域>-<模块>-<元素>`），
 * 因此本 POM 走**单轨定位**，不再需要 `byTestId(id, fallback)` 的回退分支。
 *
 * 进入路径：工作台 shell 的「系统」功能菜单 → 「定时任务」。
 * 与 `VisualWorkspacePage.openWorkspaceModule` 路径一致，但此处**不 import 视觉页对象**
 * —— 视觉页属于回归专用，混进功能用例会让两条线耦合。
 *
 * ⚠️ 免登录预览模式（`?preview=1`）仅在 dev + localhost 生效；生产构建下需真实账号。
 */
export class ScheduledTasksPage extends BasePage {
  readonly path = '/'

  /** 工作台导航里「系统」域下的模块名，同时也是路由 key。 */
  static readonly MODULE_LABEL = '定时任务'
  static readonly GROUP_LABEL = '系统'

  /** 页面上注入的 testid 前缀，集中在此避免用例里手写字符串漂移。 */
  private static readonly PREFIX = 'system-task'

  private id(element: string): string {
    return `${ScheduledTasksPage.PREFIX}-${element}`
  }

  // ── 导航 ──────────────────────────────────────────────────────────────

  /**
   * 真正的金融工作台外壳（`ArchiveWorkspaceShell` 的根节点）。
   *
   * ⚠️ 不能用 `.container`：`?preview=1` 首屏是**档案场景**（MODULE INDEX），
   * 它的容器也叫 `.container`，会误判「已进入工作台」。必须锚定 `.workspace-shell`，
   * 与 `VisualWorkspacePage` 保持一致。
   */
  get shell(): Locator {
    return this.byTestId('common-workspace-shell', '.workspace-shell')
  }

  /** 档案过场（Archive Sea）的进入按钮。 */
  get archiveEnterButton(): Locator {
    return this.byRole('button', /^ACCESS FILE/)
  }

  /**
   * 从工作台外壳展开「系统」功能菜单并进入定时任务。
   *
   * 调用前需已用 `goto(PREVIEW_PARAMS)` 打开免登录预览。本方法自带**档案过场**处理：
   * 预览进入后先渲染档案场景（MODULE INDEX），必须点 `ACCESS FILE →` 才进入
   * `ArchiveWorkspaceShell`；不点就直接找菜单会一直找不到。
   *
   * 菜单本身还没有 `data-testid`（属导航改造 A 线），因此按 ARIA 定位：
   * `<summary aria-label="系统功能菜单">` 由 `ArchiveWorkspaceShell.vue` 生成。
   */
  async openFromWorkspace(): Promise<void> {
    await this.passArchiveHandoff()

    const menu = this.raw.getByLabel(`${ScheduledTasksPage.GROUP_LABEL}功能菜单`)
    await this.clickAndWait(menu, '工作台应能展开「系统」功能菜单')

    const entry = this.byRole('button', new RegExp(`^${ScheduledTasksPage.MODULE_LABEL}(?:\\s|$)`)).first()
    await this.clickAndWait(entry, '「系统」菜单里应有「定时任务」入口')
  }

  /** 等待档案场景就绪；若停在那里则点 CTA 进入金融工作台。 */
  private async passArchiveHandoff(): Promise<void> {
    await this.archiveEnterButton
      .or(this.shell)
      .first()
      .waitFor({ state: 'visible', timeout: 24_000 })

    if (!(await this.shell.isVisible())) {
      await this.clickAndWait(this.archiveEnterButton, '预览模式应能从档案进入金融工作区')
    }
    await this.shell.waitFor({ state: 'visible', timeout: 24_000 })
  }

  // ── 页头 ──────────────────────────────────────────────────────────────

  get heading(): Locator {
    return this.byTestId(this.id('heading'))
  }

  get refreshButton(): Locator {
    return this.byTestId(this.id('refresh-btn'))
  }

  /** ⚠️ 该 id 由 README §4 作为命名示例给出，刻意保留 `task-list-` 前缀。 */
  get createButton(): Locator {
    return this.byTestId('task-list-create-btn')
  }

  get errorBanner(): Locator {
    return this.byTestId(this.id('error'))
  }

  // ── 汇总卡 ────────────────────────────────────────────────────────────

  get summary(): Locator {
    return this.byTestId(this.id('summary'))
  }

  get totalCount(): Locator {
    return this.byTestId(this.id('summary-total'))
  }

  get activeCount(): Locator {
    return this.byTestId(this.id('summary-active'))
  }

  get pausedCount(): Locator {
    return this.byTestId(this.id('summary-paused'))
  }

  get supportedTypeCount(): Locator {
    return this.byTestId(this.id('summary-types'))
  }

  // ── 任务列表 ──────────────────────────────────────────────────────────

  get table(): Locator {
    return this.byTestId(this.id('table'))
  }

  get rows(): Locator {
    return this.byTestId(this.id('row'))
  }

  get emptyState(): Locator {
    return this.byTestId(this.id('empty'))
  }

  get loadingState(): Locator {
    return this.byTestId(this.id('loading'))
  }

  /** 按任务名定位列表行（`filter` 作用在行内，避免跨行误命中）。 */
  rowByName(name: string): Locator {
    return this.rows.filter({ hasText: name })
  }

  /** 操作列按钮：先收窄到该行，再按按钮名匹配。 */
  rowAction(
    name: string,
    action: '编辑' | '历史' | '立即执行' | '暂停' | '恢复' | '删除',
  ): Locator {
    return this.rowByName(name).getByRole('button', { name: action, exact: true })
  }

  // ── 执行历史 ──────────────────────────────────────────────────────────

  get historyPanel(): Locator {
    return this.byTestId(this.id('history-panel'))
  }

  get historyItems(): Locator {
    return this.byTestId(this.id('history-item'))
  }

  get historyEmptyState(): Locator {
    return this.byTestId(this.id('history-empty'))
  }

  get historyCloseButton(): Locator {
    return this.byTestId(this.id('history-close'))
  }

  // ── 编辑器 ────────────────────────────────────────────────────────────

  get editor(): Locator {
    return this.byTestId(this.id('editor'))
  }

  get nameInput(): Locator {
    return this.byTestId(this.id('form-name'))
  }

  get typeSelect(): Locator {
    return this.byTestId(this.id('form-type'))
  }

  get cronInput(): Locator {
    return this.byTestId(this.id('form-cron'))
  }

  get cronPresets(): Locator {
    return this.byTestId(this.id('cron-presets'))
  }

  get typeParams(): Locator {
    return this.byTestId(this.id('form-params'))
  }

  // ── DAILY_DIGEST 专属参数（资讯日报）────────────────────────────────

  get digestLimitInput(): Locator {
    return this.byTestId(this.id('form-digest-limit'))
  }

  get digestHeadlineInput(): Locator {
    return this.byTestId(this.id('form-digest-headlines'))
  }

  get digestAnalyzeCheckbox(): Locator {
    return this.byTestId(this.id('form-digest-analyze'))
  }

  get saveButton(): Locator {
    return this.byTestId(this.id('form-save'))
  }

  get cancelButton(): Locator {
    return this.byTestId(this.id('form-cancel'))
  }

  // ── 组合动作 ──────────────────────────────────────────────────────────

  async openCreateEditor(): Promise<void> {
    await this.clickAndWait(this.createButton, '点击「新建任务」应打开编辑器')
    await this.expectVisible(this.editor, '定时任务编辑器应打开')
  }

  /** 选择 cron 预置按钮（文案即按钮名）。 */
  async applyCronPreset(label: '每30分钟' | '每小时' | '工作日09:00' | '工作日15:00'): Promise<void> {
    await this.clickAndWait(
      this.cronPresets.getByRole('button', { name: label, exact: true }),
      `应能应用 Cron 预置「${label}」`,
    )
  }

  /** 编辑器里的任务类型下拉（值为后端枚举，如 `MARKET_SCAN`）。 */
  async selectTaskType(value: string): Promise<void> {
    await this.typeSelect.selectOption(value)
  }
}
