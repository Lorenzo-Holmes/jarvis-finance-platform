import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  DocumentImportError,
  extractFinancialDocument,
  financialImportLimits,
} from '../src/utils/financialDocumentImport.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

function makeFile(name, content = '') {
  return new File([content], name)
}

test('Markdown and plain text are read locally and report progress', async () => {
  const updates = []
  const result = await extractFinancialDocument(makeFile('filing.md', '  # 业绩\r\n营收增长  '), {
    onProgress: update => updates.push(update),
  })

  assert.equal(result.text, '# 业绩\n营收增长')
  assert.deepEqual(result.warnings, [])
  assert.equal(updates.at(-1).phase, 'complete')
})

test('unsupported and oversized files are rejected before reading bytes', async () => {
  await assert.rejects(
    extractFinancialDocument(makeFile('filing.xlsx')),
    error => error instanceof DocumentImportError && error.code === 'UNSUPPORTED_FORMAT',
  )
  await assert.rejects(
    extractFinancialDocument({ name: 'large.pdf', size: financialImportLimits.maxFileBytes + 1 }),
    error => error instanceof DocumentImportError && error.code === 'FILE_TOO_LARGE',
  )
})

test('DOCX extraction uses raw text only and surfaces parser warnings', async () => {
  let received
  const result = await extractFinancialDocument(makeFile('filing.docx', 'docx-bytes'), {
    loaders: {
      mammoth: async () => ({
        extractRawText: async input => {
          received = input
          return { value: '  第一段\r\n第二段  ', messages: [{ type: 'warning', message: '表格格式已扁平化' }] }
        },
      }),
    },
  })

  assert.ok(received.arrayBuffer instanceof ArrayBuffer)
  assert.equal(result.text, '第一段\n第二段')
  assert.deepEqual(result.warnings, ['表格格式已扁平化'])
})

test('PDF text is extracted page by page without OCR for text-native pages', async () => {
  const pages = [
    Array.from({ length: 12 }, (_, index) => ({ str: `年度财报文本${index}` })),
    [
      { str: '净利润表' },
      { str: '单位：亿元' },
      { str: '经营现金流持续改善' },
      { str: '资产负债表与利润表保持稳健' },
      { str: '现金及现金等价物同比增加' },
      { str: '财务费用控制有效' },
    ],
  ]
  let destroyed = false
  const result = await extractFinancialDocument(makeFile('annual.pdf', 'pdf-bytes'), {
    loaders: {
      pdfjs: async () => ({
        GlobalWorkerOptions: {},
        getDocument: () => ({
          promise: Promise.resolve({
            numPages: pages.length,
            getPage: async pageNumber => ({
              getTextContent: async () => ({ items: pages[pageNumber - 1] }),
              cleanup() {},
            }),
          }),
          destroy: async () => { destroyed = true },
        }),
      }),
    },
  })

  assert.match(result.text, /【第 1 页】/)
  assert.match(result.text, /年度财报文本11/)
  assert.match(result.text, /经营现金流持续改善/)
  assert.deepEqual(result.warnings, [])
  assert.equal(destroyed, true)
})

test('PDF page and OCR limits are explicit', async () => {
  await assert.rejects(
    extractFinancialDocument(makeFile('long.pdf', 'pdf-bytes'), {
      loaders: {
        pdfjs: async () => ({
          GlobalWorkerOptions: {},
          getDocument: () => ({
            promise: Promise.resolve({ numPages: financialImportLimits.maxPdfPages + 1 }),
            destroy: async () => {},
          }),
        }),
      },
    }),
    error => error instanceof DocumentImportError && error.code === 'TOO_MANY_PAGES',
  )

  assert.equal(financialImportLimits.maxOcrPages, 20)
})

test('sparse PDF pages are rendered for OCR and pages beyond the OCR cap are identified', async () => {
  const previousDocument = globalThis.document
  let recognizedPages = 0
  let workerTerminated = false
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => ({ drawImage() {} }),
  }
  globalThis.document = { createElement: () => canvas }

  try {
    const result = await extractFinancialDocument(makeFile('scanned.pdf', 'pdf-bytes'), {
      loaders: {
        pdfjs: async () => ({
          GlobalWorkerOptions: {},
          getDocument: () => ({
            promise: Promise.resolve({
              numPages: financialImportLimits.maxOcrPages + 1,
              getPage: async pageNumber => ({
                getTextContent: async () => ({ items: [] }),
                getViewport: ({ scale }) => ({ width: 120 * scale, height: 160 * scale }),
                render: () => ({ promise: Promise.resolve() }),
                cleanup() {},
                pageNumber,
              }),
            }),
            destroy: async () => {},
          }),
        }),
        tesseract: async () => ({
          createWorker: async () => ({
            recognize: async () => {
              recognizedPages += 1
              return { data: { text: '扫描页识别文字' } }
            },
            terminate: async () => { workerTerminated = true },
          }),
        }),
      },
    })

    assert.equal(recognizedPages, financialImportLimits.maxOcrPages)
    assert.match(result.warnings[0], /未进行 OCR 的页码：21/)
    assert.equal(workerTerminated, true)
  } finally {
    globalThis.document = previousDocument
  }
})

test('image OCR uses Chinese and English, bounds canvas size, and terminates the worker', async () => {
  const previousDocument = globalThis.document
  const previousCreateImageBitmap = globalThis.createImageBitmap
  let workerTerminated = false
  let recognizedCanvas
  let loadedLanguages
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => ({ drawImage() {} }),
  }
  globalThis.document = { createElement: () => canvas }
  globalThis.createImageBitmap = async () => ({ width: 4200, height: 3000, close() {} })

  try {
    const result = await extractFinancialDocument(makeFile('statement.png', 'image-bytes'), {
      loaders: {
        tesseract: async () => ({
          createWorker: async (languages, _oem, options) => {
            loadedLanguages = languages
            options.logger({ status: 'recognizing text', progress: 0.5 })
            return {
              recognize: async image => {
                recognizedCanvas = image
                return { data: { text: '营业收入 120 亿元' } }
              },
              terminate: async () => { workerTerminated = true },
            }
          },
        }),
      },
    })

    assert.equal(result.text, '营业收入 120 亿元')
    assert.equal(loadedLanguages, 'chi_sim+eng')
    assert.ok(recognizedCanvas.width <= financialImportLimits.maxImageEdge)
    assert.ok(recognizedCanvas.width * recognizedCanvas.height <= financialImportLimits.maxImagePixels)
    assert.equal(workerTerminated, true)
  } finally {
    globalThis.document = previousDocument
    globalThis.createImageBitmap = previousCreateImageBitmap
  }
})

test('report import is review-first and theme surfaces use workspace tokens', () => {
  const report = read('pages/FinancialReportPage.vue')
  const crossMarket = read('components/CrossMarketView.vue')

  assert.match(report, /type="file"[\s\S]*?\.pdf[\s\S]*?\.docx[\s\S]*?\.md[\s\S]*?\.png/)
  assert.match(report, /pendingImport\.value = \{/)
  assert.match(report, /替换输入区/)
  assert.match(report, /追加到末尾/)
  assert.match(report, /api\.aiFinancialReport\(content\.value\.trim\(\)\)/)
  assert.doesNotMatch(report, /api\.aiFinancialReport\([^)]*file/i)
  assert.match(report, /background:\s*var\(--workspace-panel-soft/)
  assert.match(report, /textarea::placeholder\s*\{[^}]*color:\s*var\(--muted/)
  assert.match(crossMarket, /\.chart\s*\{[^}]*background:\s*var\(--workspace-chart-bg/)
  assert.doesNotMatch(crossMarket, /\.chart\s*\{[^}]*background:\s*rgba\(232,229,225/i)
})
