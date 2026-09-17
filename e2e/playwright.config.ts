import { defineConfig, devices } from '@playwright/test'
import { FRONTEND_URL, MANAGED_WEB_SERVER } from './utils/env'

const isCI = Boolean(process.env.CI)

/**
 * JARVIS E2E 配置。
 *
 * 起服约定（默认：由调用方预先起服）
 *   ① 前端   `cd frontend && npm run dev`            → http://localhost:5173
 *   ② Java   `java-backend`（:8200，前端 /api 代理目标）
 *   ③ Python `backend`（:8100，仅 Java 内部调用）
 *   只要用例不触达后端接口，仅起 ①即可跑通 smoke 组；
 *   若设置 `E2E_MANAGED_SERVER=1`，则由 Playwright 自动拉起 Vite dev server。
 *
 * 注意：**不要**为跑 E2E 去改 `frontend/` 的依赖或配置——本目录自带 package.json，
 * Playwright 不进前端构建产物。
 */
const webServer = MANAGED_WEB_SERVER
  ? {
      command: 'npm --prefix ../frontend run dev',
      url: FRONTEND_URL,
      reuseExistingServer: true,
      timeout: 120_000,
    }
  : undefined

export default defineConfig({
  testDir: './tests',
  outputDir: 'test-results',
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  workers: isCI ? 1 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },

  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],

  use: {
    baseURL: FRONTEND_URL,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'smoke',
      testMatch: /smoke\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'auth',
      testMatch: /auth\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer,
})
