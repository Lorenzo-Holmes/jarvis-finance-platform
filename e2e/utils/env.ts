/**
 * E2E 运行环境参数。
 *
 * 全部可通过环境变量覆盖，默认值对齐本地开发三栈：
 *   frontend :5173 / java-backend :8200 / python-ai :8100
 *
 * 注意：浏览器侧一律经前端 `/api` 代理访问 Java，不直连 Python
 * （与 SRS「版本实施要求」的三栈边界一致）。
 */

/**
 * 默认用 `localhost` 而非 `127.0.0.1`：两者都满足前端 `?preview=1` 预览模式对 host 的判定
 * （`App.vue` 的 `localPreview` 同时接受这两个 host），用 `localhost` 兼容性更好
 * ——部分环境下 Vite dev server 只监听 `localhost`，此时 `127.0.0.1` 会连不上。
 */
export const FRONTEND_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'

/** 仅用于将来做测试数据准备/清理时直连 Java；不要在用例里绕过前端访问它。 */
export const API_URL = process.env.E2E_API_URL ?? 'http://127.0.0.1:8200'

export type Role = 'user' | 'admin'

export const CREDENTIALS: Record<Role, { email: string; password: string }> = {
  user: {
    email: process.env.E2E_USER_EMAIL ?? '',
    password: process.env.E2E_USER_PASSWORD ?? '',
  },
  admin: {
    email: process.env.E2E_ADMIN_EMAIL ?? '',
    password: process.env.E2E_ADMIN_PASSWORD ?? '',
  },
}

/** 未配置凭据时，相关用例应 `test.skip()` 而不是失败——保证 CI 在无账号时仍为绿。 */
export function hasCredentials(role: Role): boolean {
  const c = CREDENTIALS[role]
  return Boolean(c.email && c.password)
}

/** 由 Playwright 自己拉起 Vite dev server（默认关闭，服务由调用方或 CI job 预先启动）。 */
export const MANAGED_WEB_SERVER = process.env.E2E_MANAGED_SERVER === '1'

/** 登录态复用文件的落点（已在 .gitignore 中排除，含 Cookie，绝不可入库）。 */
export const STORAGE_STATE: Record<Role, string> = {
  user: '.auth/user.json',
  admin: '.auth/admin.json',
}

/**
 * 本地预览模式开关：前端在 DEV + localhost 下支持 `?preview=1` 免登录进入工作台
 * （见 frontend/src/App.vue 的 localPreview 分支）。
 * 用于不依赖后端账号即可验证工作台外壳渲染的用例。
 *
 * `PREVIEW_PARAMS` 可直接交给 `BasePage.goto(query)`；`PREVIEW_QUERY` 用于拼裸 URL。
 * 两者由同一份常量派生，避免参数名在多处手写漂移。
 */
export const PREVIEW_PARAMS = { preview: '1' } as const

export const PREVIEW_QUERY = new URLSearchParams(PREVIEW_PARAMS).toString()
