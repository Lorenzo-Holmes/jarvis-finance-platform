import { expect, type Locator, type Page, type Response } from '@playwright/test'
import { cssTestId } from '../utils/selectors'

/** `getByRole` 的 role 参数类型（避免依赖 playwright 未公开的类型导出）。 */
type AriaRole = Parameters<Page['getByRole']>[0]

/**
 * POM（Page Object Model）基类 —— 对应跨域契约 **S3**，是对外提供给队友复用的稳定接口。
 *
 * 冻结的方法名（队友写 RSS 用例时可直接依赖，不要改名）：
 *   goto / byTestId / byRole / expectVisible / expectHidden / expectText /
 *   expectUrlContains / fillField / clickAndWait / waitForApi / screenshot
 *
 * 设计约束：
 * - 断言一律带描述，失败信息直接说明「哪个功能坏了」，便于测试智能体自动产出缺陷报告；
 * - 定位一律走「优先 data-testid、回退既有选择器」的双轨，属性补齐前后都能跑；
 * - 不在基类里写死任何业务路径，路径由各页面子类的 `path` 决定。
 */
export abstract class BasePage {
  constructor(protected readonly page: Page) {}

  /** 相对 `baseURL` 的路径，不含 query。子类必须提供。 */
  abstract readonly path: string

  async goto(query?: Record<string, string>): Promise<void> {
    const search = query && Object.keys(query).length > 0
      ? `?${new URLSearchParams(query).toString()}`
      : ''
    await this.page.goto(`${this.path}${search}`)
    await this.waitForReady()
  }

  /** 子类可覆写以等待本页标志性元素出现。默认只等 DOM 就绪。 */
  protected async waitForReady(): Promise<void> {
    await this.page.waitForLoadState('domcontentloaded')
  }

  // ── 定位 ──────────────────────────────────────────────────────────────

  /**
   * 双轨定位：优先 `[data-testid]`，不存在时回退到既有选择器。
   *
   * 前端补齐 `data-testid` 后，应逐步去掉 `fallbackSelector` 参数收敛为单轨。
   */
  protected byTestId(id: string, fallbackSelector?: string): Locator {
    const primary = this.page.locator(cssTestId(id))
    if (!fallbackSelector) return primary
    return primary.or(this.page.locator(fallbackSelector))
  }

  protected byRole(role: AriaRole, name: string | RegExp): Locator {
    return this.page.getByRole(role, { name })
  }

  protected get raw(): Page {
    return this.page
  }

  // ── 断言 ──────────────────────────────────────────────────────────────

  async expectVisible(locator: Locator, description: string): Promise<void> {
    await expect(locator, description).toBeVisible()
  }

  async expectHidden(locator: Locator, description: string): Promise<void> {
    await expect(locator, description).toBeHidden()
  }

  async expectText(locator: Locator, expected: string | RegExp, description: string): Promise<void> {
    await expect(locator, description).toHaveText(expected)
  }

  async expectUrlContains(fragment: string): Promise<void> {
    await expect(this.page, `地址栏应包含 ${fragment}`).toHaveURL(new RegExp(escapeRegExp(fragment)))
  }

  // ── 动作 ──────────────────────────────────────────────────────────────

  async fillField(locator: Locator, value: string, description: string): Promise<void> {
    await expect(locator, `${description}（填写前应可见）`).toBeVisible()
    await locator.fill(value)
  }

  async clickAndWait(locator: Locator, description: string): Promise<void> {
    await expect(locator, `${description}（点击前应可见）`).toBeVisible()
    await locator.click()
  }

  // ── 网络 ──────────────────────────────────────────────────────────────

  /** 等待 URL 里包含指定片段的响应，用于确认请求确实发出并返回。 */
  async waitForApi(pathFragment: string): Promise<Response> {
    return this.page.waitForResponse((r) => r.url().includes(pathFragment))
  }

  // ── 取证 ──────────────────────────────────────────────────────────────

  /** 由测试智能体在生成缺陷报告时调用，产出可附在报告里的截图。 */
  async screenshot(name: string): Promise<Buffer> {
    return this.page.screenshot({ path: `test-results/screenshots/${name}.png`, fullPage: true })
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
