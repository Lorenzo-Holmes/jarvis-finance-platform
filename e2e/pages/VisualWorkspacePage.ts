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

    // Archive Sea 偶尔会在 WebGL settle 与 CTA 渲染之间直接进入 extraction。
    // 视觉测试只关心最终工作区，因此等待「CTA 或工作区」任一出现，避免把正常的
    // 自动过场误判成 ACCESS FILE 丢失；若仍停在档案页，再显式点击 CTA。
    await expect(
      this.accessFileButton.or(this.workspaceShell).first(),
      '预览模式应能从档案进入金融工作区',
    ).toBeVisible({ timeout: 24_000 })
    if (!(await this.workspaceShell.isVisible())) {
      await this.clickAndWait(this.accessFileButton, '预览模式应能从档案进入金融工作区')
    }
    await expect(
      this.workspaceShell,
      '档案过场完成后应渲染金融工作区外壳',
    ).toBeVisible({ timeout: 24_000 })
    await this.raw.waitForTimeout(250)
  }

  async openWorkspaceModule(label: '行情' | '研究' | '产业链' | '策略' | '交易'): Promise<void> {
    const target = {
      行情: { group: '市场', module: '行情' },
      研究: { group: '研究', module: '研究助手' },
      产业链: { group: '情报', module: '产业链图谱' },
      策略: { group: '策略', module: '策略生成' },
      交易: { group: '执行', module: '模拟盘' },
    }[label]
    const menu = this.raw.getByLabel(target.group + '功能菜单')
    await this.clickAndWait(menu, '视觉回归应能展开' + target.group + '功能菜单')
    const button = this.byRole('button', new RegExp('^' + target.module + '(?:\\s|$)')).first()
    await this.clickAndWait(button, '视觉回归应能进入' + target.module + '工作区')
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
  if (pathname === '/api/market/overview') {
    const rows = [
      ['sse', '上证指数', 'a_share', 'sh000001', 3911.87, 0.94, 'CNY', 'CN'],
      ['chinext', '创业板指', 'a_share', 'sz399006', 3372.68, 2.25, 'CNY', 'CN'],
      ['star50', '科创50', 'a_share', 'sh000688', 1298.42, 1.36, 'CNY', 'CN'],
      ['szse', '深证成指', 'a_share', 'sz399001', 13640.87, 1.72, 'CNY', 'CN'],
      ['bse50', '北证50', 'a_share', 'bj899050', 1468.21, 1.08, 'CNY', 'CN'],
      ['sse50', '上证50', 'a_share', 'sh000016', 3036.44, 0.61, 'CNY', 'CN'],
      ['dow', '道琼斯', 'global_index', '^DJI', 46788.12, 0.31, 'USD', 'US'],
      ['nasdaq', '纳斯达克', 'global_index', '^IXIC', 23175.35, 0.67, 'USD', 'US'],
      ['sp500', '标普500', 'global_index', '^GSPC', 6712.18, 0.43, 'USD', 'US'],
      ['nasdaq100', '纳斯达克100', 'global_index', '^NDX', 24781.20, 0.58, 'USD', 'US'],
      ['au9999', '黄金9999', 'sge_gold', 'Au99.99', 947.09, 1.31, 'CNY/g', 'CN'],
      ['hsi', '恒生指数', 'global_index', '^HSI', 26710.11, -0.12, 'HKD', 'HK'],
      ['hscei', '恒生国企指数', 'global_index', '^HSCE', 9518.66, -0.20, 'HKD', 'HK'],
      ['hstech', '恒生科技指数', 'global_index', 'HSTECH.HK', 6284.41, 0.38, 'HKD', 'HK'],
    ]
    return { code: 200, data: rows.map(([key, name, market, symbol, price, change_pct, currency, region]) => ({ key, name, market, symbol, price, change_pct, currency, region, source: 'Visual Fixture', available: true })) }
  }
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
  if (pathname === '/api/notifications/unread-count') return { code: 200, data: { unread: 0 } }
  if (pathname.includes('/api/news')) return { code: 200, data: [] }
  return { code: 503, message: 'visual fixture', data: null }
}
