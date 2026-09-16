import { expect, type Locator } from '@playwright/test'
import { BasePage } from './BasePage'
import { FALLBACK } from '../utils/selectors'

/**
 * 登录后的工作台外壳（`frontend/src/App.vue` 的 `container` 分支）。
 *
 * 免登录入口：`?preview=1`。
 * ⚠️ 该预览模式**仅在 `import.meta.env.DEV` 且 host 为 localhost/127.0.0.1 时生效**
 * （见 `App.vue` 的 `localPreview` 判定）。因此它对本地与「vite dev 起服的 CI job」可用，
 * 对 `vite preview` / GitHub Pages 产物**不生效**——那类环境下应改用真实账号登录。
 */
export class WorkspacePage extends BasePage {
  readonly path = '/'

  /** 免登录进入工作台（预览用户，role=USER）。 */
  async openPreview(): Promise<void> {
    await this.goto({ preview: '1' })
  }

  get shell(): Locator {
    return this.byTestId('common-workspace-shell', FALLBACK.workspaceContainer)
  }

  /** 断言工作台外壳已渲染。 */
  async expectShellVisible(): Promise<void> {
    await this.expectVisible(this.shell.first(), '工作台外壳应渲染（登录或预览模式生效）')
  }

  /** 断言未进入工作台（仍在官网或登录视图），用于未登录/越权场景。 */
  async expectShellHidden(): Promise<void> {
    await expect(
      this.shell.first(),
      '未登录时不应渲染工作台外壳',
    ).toBeHidden()
  }
}
