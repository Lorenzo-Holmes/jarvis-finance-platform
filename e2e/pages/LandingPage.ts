import { expect, type FrameLocator, type Locator } from '@playwright/test'
import { BasePage } from './BasePage'
import { FALLBACK, LANDING_ENTER_TEXT } from '../utils/selectors'

/**
 * 官网首页。
 *
 * ⚠️ 关键事实：官网**由 iframe 承载**——`frontend/src/pages/LandingPage.vue` 内嵌
 * `/landing/index.html`，并以「点击文案含『进入 JARVIS』的链接」作为进入登录页的判定。
 * 因此断言与点击都必须走 `frameLocator`，直接在顶层 page 上找元素会全部落空。
 */
export class LandingPage extends BasePage {
  readonly path = '/'

  get frame(): FrameLocator {
    return this.raw.frameLocator(FALLBACK.landingFrame)
  }

  /** iframe 元素本身（用于断言首页骨架已渲染，无需进入 frame 内部）。 */
  get frameElement(): Locator {
    return this.raw.locator(FALLBACK.landingFrame)
  }

  /**
   * 官网内所有「进入 JARVIS」入口。
   *
   * ⚠️ `/landing/index.html` 里该文案有 **3 处**（顶部导航、首屏 hero、底部 CTA），
   * 全部命中同一套点击拦截逻辑。因此这里必须收敛为 `.first()`——
   * 直接对多元素 locator 断言会触发 Playwright 的 strict mode violation。
   */
  get enterLinks(): Locator {
    return this.frame.getByRole('link', { name: new RegExp(LANDING_ENTER_TEXT) })
  }

  /** 点击官网内的「进入 JARVIS」入口，切到登录视图。 */
  async enterLogin(): Promise<void> {
    const link = this.enterLinks.first()
    await expect(link, `官网应存在「${LANDING_ENTER_TEXT}」入口`).toBeVisible()
    await link.click()
  }
}
