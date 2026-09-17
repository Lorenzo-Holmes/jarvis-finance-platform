import { expect, type Locator } from '@playwright/test'
import { BasePage } from './BasePage'
import { FALLBACK } from '../utils/selectors'

/**
 * 登录 / 注册 / 重置密码页（`frontend/src/components/LoginView.vue`）。
 *
 * 该组件同时承载三种模式，本类只覆盖登录路径；注册与重置涉及邮箱验证码，
 * 不适合放进自动化用例（需要外部邮件通道），若确需覆盖应改由后端测试承接。
 *
 * 登录视图由 query 参数切换：`/?view=login`（见 `App.vue` 的 `publicView` 判定）。
 */
export class LoginPage extends BasePage {
  readonly path = '/'

  async openLogin(): Promise<void> {
    await this.goto({ view: 'login' })
  }

  get emailInput(): Locator {
    return this.byTestId('auth-login-email', FALLBACK.loginEmail)
  }

  get passwordInput(): Locator {
    return this.byTestId('auth-login-password', FALLBACK.loginPassword)
  }

  get submitButton(): Locator {
    return this.byTestId('auth-login-submit', FALLBACK.loginSubmit)
  }

  get errorMessage(): Locator {
    return this.byTestId('auth-login-error', FALLBACK.authError)
  }

  get noticeMessage(): Locator {
    return this.byTestId('auth-login-notice', FALLBACK.authNotice)
  }

  /** 断言登录表单已渲染（不依赖后端）。 */
  async expectFormVisible(): Promise<void> {
    await this.expectVisible(this.emailInput.first(), '登录邮箱输入框应可见')
    await this.expectVisible(this.passwordInput.first(), '登录密码输入框应可见')
    await this.expectVisible(this.submitButton.first(), '登录按钮应可见')
  }

  async fillCredentials(email: string, password: string): Promise<void> {
    await this.fillField(this.emailInput.first(), email, '登录邮箱输入框')
    await this.fillField(this.passwordInput.first(), password, '登录密码输入框')
  }

  async submit(): Promise<void> {
    await this.clickAndWait(this.submitButton.first(), '登录提交按钮')
  }

  /** 完整登录动作：填表 + 提交。登录成功的判定交给调用方（不同用例断言点不同）。 */
  async login(email: string, password: string): Promise<void> {
    await this.fillCredentials(email, password)
    await this.submit()
  }

  /** 断言出现错误提示（如密码错误、越权）。 */
  async expectError(): Promise<void> {
    await expect(this.errorMessage.first(), '应出现登录错误提示').toBeVisible()
  }

  /** 断言空表单提交时的前端校验提示。 */
  async submitEmptyAndExpectValidation(): Promise<void> {
    await this.submit()
    await expect(
      this.errorMessage.first(),
      '空表单提交应被前端校验拦下并给出提示',
    ).toContainText('请输入邮箱和密码')
  }
}
