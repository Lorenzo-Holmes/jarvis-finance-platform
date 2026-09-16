import { expect, type FrameLocator } from '@playwright/test'
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

  /** 点击官网内的「进入 JARVIS」入口，切到登录视图。 */
  async enterLogin(): Promise<void> {
    const link = this.frame.getByRole('link', { name: new RegExp(LANDING_ENTER_TEXT) })
    await expect(link, `官网应存在「${LANDING_ENTER_TEXT}」入口`).toBeVisible()
    await link.click()
  }
}
