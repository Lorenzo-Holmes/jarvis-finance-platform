import { expect, test } from '../fixtures/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const filingFixture = path.resolve(here, '../../frontend/tests/fixtures/browser-filing.md')
const unsupportedFixture = path.resolve(here, '../../frontend/tests/fixtures/browser-unsupported.xlsx')

test.describe('财报导入 · 浏览器验收', () => {
  test('替换/追加与失败保留均在本地完成，选择文件不调用分析接口', async ({ visualWorkspacePage, page }) => {
    test.setTimeout(45_000)
    await visualWorkspacePage.openStablePreview()
    await visualWorkspacePage.openWorkspaceModule('财报')

    const textarea = page.getByRole('textbox', { name: '财报内容' })
    const fileInput = page.getByLabel('选择财报文件')
    const reportRequests: string[] = []
    page.on('request', request => {
      if (request.url().includes('/api/ai/financial/report')) reportRequests.push(request.url())
    })

    await textarea.fill('已有原文')
    await fileInput.setInputFiles(filingFixture)
    await expect(page.getByText(/已从 browser-filing\.md 提取/)).toBeVisible()
    await page.getByRole('button', { name: '替换输入区' }).click()
    await expect(textarea).toHaveValue(/营业收入同比增长 18%/)

    await textarea.fill('保留原文')
    await fileInput.setInputFiles(filingFixture)
    await expect(page.getByRole('button', { name: '追加到末尾' })).toBeVisible()
    await page.getByRole('button', { name: '追加到末尾' }).click()
    await expect(textarea).toHaveValue(/保留原文[\s\S]*营业收入同比增长 18%/)

    await textarea.fill('失败前的内容')
    await fileInput.setInputFiles(unsupportedFixture)
    await expect(page.getByText('支持 PDF、DOCX、Markdown、TXT、PNG、JPG 和 WebP 文件')).toBeVisible()
    await expect(textarea).toHaveValue('失败前的内容')
    expect(reportRequests, '文件选择和提取期间不得提前提交财报分析').toEqual([])
  })
})
