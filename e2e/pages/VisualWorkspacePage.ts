import { expect, type Locator, type Route } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { WorkspacePage } from './WorkspacePage'

const here = path.dirname(fileURLToPath(import.meta.url))
const STABILITY_STYLE = path.resolve(here, '../visual/stability.css')

const FIXED_MARKET = {
  gold_etf: {
    name: '黄金ETF华夏',
    price: 8.95,
    change: -0.05,
    change_pct: -0.57,
    prev_close: 9.00,
    low: 8.90,
    high: 9.05,
  },
  london_gold: {
    name: '伦敦金（现货黄金）',
    price: 4318.82,
    change: 1.29,
    change_pct: 0.03,
    prev_close: 4317.53,
    low: 4298.12,
    high: 4332.44,
  },
}

const FIXED_JD = {
  zheshang: { label: '浙商', price: 932.56, change: -7.01, change_pct: -0.75 },
  minsheng: { label: '民生', price: 932.95, change: -6.89, change_pct: -0.73 },
}

export class VisualWorkspacePage extends WorkspacePage {
  get workspaceShell(): Locator {
    return this.byTestId('common-workspace-shell', '.workspace-shell')
  }

  get accessFileButton(): Locator {
    return this.byRole('button', /^ACCESS FILE/)
  }

  async openStablePreview(): Promise<void> {
    await this.raw.emulateMedia({ reducedMotion: 'reduce' })
    await this.raw.route('**/*', route => this.fulfillVisualApi(route))
    await this.openPreview()
    await this.clickAndWait(this.accessFileButton, '预览模式应能从档案进入金融工作区')
    await expect(
      this.workspaceShell,
      '档案过场完成后应渲染金融工作区外壳',
    ).toBeVisible({ timeout: 24_000 })
    await this.raw.waitForTimeout(250)
  }

  async openWorkspaceModule(label: '行情' | '研究' | '产业链' | '策略' | '交易'): Promise<void> {
    const button = this.byRole('button', new RegExp('^' + label + '$')).first()
    await this.clickAndWait(button, '视觉回归应能进入' + label + '工作区')
    await this.raw.waitForTimeout(260)
  }

  async expectWorkspaceScreenshot(name: string): Promise<void> {
    await expect(
      this.raw,
      name + ' 在当前 viewport 下不应产生非预期视觉变化',
    ).toHaveScreenshot(name, {
      animations: 'disabled',
      caret: 'hide',
      scale: 'css',
      stylePath: STABILITY_STYLE,
      maxDiffPixelRatio: 0.008,
    })
  }

  async expectCommandPaletteWorkflow(): Promise<void> {
    await this.raw.keyboard.press('Control+K')
    const palette = this.raw.getByRole('dialog', { name: '快速导航' })
    await expect(palette, 'Ctrl+K 应打开 Command Palette 2.0').toBeVisible()
    await expect(palette.getByText('研究对象', { exact: true }), 'Palette 应包含研究对象分组').toBeVisible()
    await expect(palette.getByText('工作区', { exact: true }), 'Palette 应包含工作区分组').toBeVisible()
    await expect(palette.getByText('操作', { exact: true }), 'Palette 应包含操作分组').toBeVisible()

    const search = palette.getByRole('searchbox', { name: '搜索工作区' })
    await search.fill('夜间')
    const action = palette.locator('[data-command-kind="action"]').filter({ hasText: '夜间模式' }).first()
    await expect(action, '输入夜间应命中主题操作而不是只搜索页面').toBeVisible()

    await search.fill('研究')
    const workspace = palette.locator('[data-command-kind="workspace"]').filter({ hasText: '研究助手' }).first()
    await expect(workspace, '输入研究应命中研究工作区').toBeVisible()
    await this.raw.keyboard.press('Escape')
    await expect(palette, 'Escape 应关闭 Command Palette').toBeHidden()
  }

  private async fulfillVisualApi(route: Route): Promise<void> {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname

    if (!pathname.startsWith('/api/')) {
      await route.continue()
      return
    }

    if (pathname.includes('stream')) {
      await route.abort('failed')
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(visualFixture(url)),
    })
  }
}

function visualFixture(url: URL): unknown {
  const pathname = url.pathname
  if (pathname === '/api/market/prices') return { code: 200, data: FIXED_MARKET }
  if (pathname === '/api/jd/prices') return { code: 200, data: FIXED_JD }
  if (pathname === '/api/market/kline') return { code: 200, data: [] }
  if (pathname === '/api/market/extended/quote') {
    const symbol = url.searchParams.get('symbol') || ''
    const indexQuotes: Record<string, { name: string; price: number; change_pct: number }> = {
      sh000001: { name: '上证指数', price: 3911.87, change_pct: 0.94 },
      sz399001: { name: '深证成指', price: 13640.87, change_pct: 1.72 },
      sz399006: { name: '创业板指', price: 3372.68, change_pct: 2.25 },
    }
    const quote = indexQuotes[symbol]
    return quote
      ? { code: 200, data: { symbol, ...quote, source: 'Visual Fixture' } }
      : { code: 404, message: 'visual fixture symbol not found', data: null }
  }
  if (pathname === '/api/market/instruments') return { code: 200, data: [] }
  if (pathname === '/api/market/preferences') {
    return { code: 200, data: { persisted: false, watchlist: [], hiddenDefaultKeys: [] } }
  }
  if (pathname === '/api/market/session') return { code: 200, data: { is_open: true } }
  if (pathname === '/api/sim/account') {
    return {
      code: 200,
      data: {
        cash: 100000,
        netEquity: 100000,
        totalAssets: 100000,
        marketValue: 0,
        totalReturnPct: 0,
        positions: {},
      },
    }
  }
  if (pathname === '/api/sim/orders/open') return { code: 200, data: [] }
  if (pathname === '/api/ai/status') return { code: 200, data: { available: false } }
  if (pathname.includes('/api/news')) return { code: 200, data: [] }
  return { code: 503, message: 'visual fixture', data: null }
}
