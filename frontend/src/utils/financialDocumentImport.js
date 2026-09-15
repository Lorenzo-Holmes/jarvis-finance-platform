const MAX_FILE_BYTES = 20 * 1024 * 1024
const MAX_PDF_PAGES = 50
const MAX_OCR_PAGES = 20
const MAX_IMAGE_EDGE = 2400
const MAX_IMAGE_PIXELS = 12_000_000
const SPARSE_PDF_PAGE_CHARS = 45
const SUPPORTED_EXTENSIONS = new Set([
  'pdf', 'docx', 'md', 'markdown', 'txt', 'png', 'jpg', 'jpeg', 'webp',
])

export class DocumentImportError extends Error {
  constructor(message, code = 'IMPORT_FAILED') {
    super(message)
    this.name = 'DocumentImportError'
    this.code = code
  }
}

export function validateFinancialDocument(file) {
  if (!file || typeof file.name !== 'string') {
    throw new DocumentImportError('未读取到有效文件', 'INVALID_FILE')
  }
  if (file.size > MAX_FILE_BYTES) {
    throw new DocumentImportError('文件不能超过 20 MB', 'FILE_TOO_LARGE')
  }
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  if (!SUPPORTED_EXTENSIONS.has(extension)) {
    throw new DocumentImportError('支持 PDF、DOCX、Markdown、TXT、PNG、JPG 和 WebP 文件', 'UNSUPPORTED_FORMAT')
  }
  return extension
}

function reportProgress(onProgress, update) {
  if (typeof onProgress === 'function') onProgress(update)
}

function throwIfAborted(signal) {
  if (!signal?.aborted) return
  const error = new Error('文件识别已取消')
  error.name = 'AbortError'
  throw error
}

function normalizeText(value) {
  return String(value || '').replace(/\r\n?/g, '\n').trim()
}

function getPdfPageText(items) {
  return normalizeText((Array.isArray(items) ? items : [])
    .map(item => typeof item?.str === 'string' ? item.str : '')
    .filter(Boolean)
    .join(' ')
    .replace(/[ \t]+/g, ' '))
}

async function loadPdfJs(loaders) {
  const pdfjs = loaders.pdfjs ? await loaders.pdfjs() : await import('pdfjs-dist')
  if (!pdfjs.GlobalWorkerOptions.workerSrc) {
    pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString()
  }
  return pdfjs
}

async function loadMammoth(loaders) {
  return loaders.mammoth ? loaders.mammoth() : import('mammoth')
}

async function loadTesseract(loaders) {
  return loaders.tesseract ? loaders.tesseract() : import('tesseract.js')
}

async function terminateOcrWorker(context) {
  const worker = context.worker
  if (!worker) return
  context.worker = null
  if (context.abortListener) {
    context.signal?.removeEventListener('abort', context.abortListener)
    context.abortListener = null
  }
  await worker.terminate?.()
}

async function ensureOcrWorker(context) {
  if (context.worker) return context.worker
  throwIfAborted(context.signal)
  reportProgress(context.onProgress, { phase: 'ocr-engine', status: '正在加载中文/英文 OCR 引擎', progress: 0 })
  const tesseract = await loadTesseract(context.loaders)
  throwIfAborted(context.signal)
  const createWorker = tesseract.createWorker || tesseract.default?.createWorker
  if (typeof createWorker !== 'function') {
    throw new DocumentImportError('OCR 引擎加载失败，请改用粘贴文本或稍后重试', 'OCR_UNAVAILABLE')
  }
  context.worker = await createWorker('chi_sim+eng', 1, {
    logger: message => {
      reportProgress(context.onProgress, {
        phase: 'ocr-engine',
        status: message.status || '正在识别',
        progress: Number(message.progress) || 0,
        currentPage: context.currentPage,
        totalPages: context.totalPages,
      })
    },
  })
  if (context.signal) {
    context.abortListener = () => {
      void terminateOcrWorker(context).catch(error => {
        reportProgress(context.onProgress, {
          phase: 'error',
          status: `OCR 清理未完成：${error?.message || '未知错误'}`,
          progress: 0,
        })
      })
    }
    context.signal.addEventListener('abort', context.abortListener, { once: true })
  }
  throwIfAborted(context.signal)
  return context.worker
}

function boundedCanvas(width, height) {
  const edgeScale = MAX_IMAGE_EDGE / Math.max(width, height, 1)
  const pixelScale = Math.sqrt(MAX_IMAGE_PIXELS / Math.max(width * height, 1))
  return Math.min(1, edgeScale, pixelScale)
}

async function makeImageCanvas(file) {
  if (typeof createImageBitmap !== 'function') {
    throw new DocumentImportError('当前浏览器不支持图片本地识别，请尝试粘贴文字或使用最新版浏览器', 'IMAGE_UNSUPPORTED')
  }
  const bitmap = await createImageBitmap(file)
  const scale = boundedCanvas(bitmap.width, bitmap.height)
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bitmap.width * scale))
  canvas.height = Math.max(1, Math.round(bitmap.height * scale))
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) {
    bitmap.close()
    throw new DocumentImportError('无法读取图片像素，请尝试转换图片格式后重试', 'IMAGE_DECODE_FAILED')
  }
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close()
  return canvas
}

async function makePdfPageCanvas(page) {
  const original = page.getViewport({ scale: 1 })
  const scale = Math.min(1.6, MAX_IMAGE_EDGE / Math.max(original.width, original.height, 1))
  const viewport = page.getViewport({ scale })
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.floor(viewport.width))
  canvas.height = Math.max(1, Math.floor(viewport.height))
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) throw new DocumentImportError('无法渲染 PDF 页面供 OCR 识别', 'PDF_RENDER_FAILED')
  await page.render({ canvasContext: context, viewport }).promise
  return canvas
}

async function extractPdf(file, context) {
  const pdfjs = await loadPdfJs(context.loaders)
  throwIfAborted(context.signal)
  const loadingTask = pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) })
  let pdf
  try {
    pdf = await loadingTask.promise
    if (pdf.numPages > MAX_PDF_PAGES) {
      throw new DocumentImportError(`PDF 共 ${pdf.numPages} 页，单次最多识别 ${MAX_PDF_PAGES} 页`, 'TOO_MANY_PAGES')
    }

    const pages = []
    const sparsePages = []
    context.totalPages = pdf.numPages
    for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber += 1) {
      throwIfAborted(context.signal)
      const page = await pdf.getPage(pageNumber)
      const textContent = await page.getTextContent()
      const text = getPdfPageText(textContent.items)
      pages.push({ pageNumber, text })
      if (text.replace(/\s/g, '').length < SPARSE_PDF_PAGE_CHARS) sparsePages.push({ pageNumber, page })
      reportProgress(context.onProgress, {
        phase: 'pdf-text',
        status: `正在提取 PDF 文本（${pageNumber}/${pdf.numPages} 页）`,
        progress: pageNumber / pdf.numPages,
        currentPage: pageNumber,
        totalPages: pdf.numPages,
      })
    }

    const ocrPages = sparsePages.slice(0, MAX_OCR_PAGES)
    for (let index = 0; index < ocrPages.length; index += 1) {
      throwIfAborted(context.signal)
      const { pageNumber, page } = ocrPages[index]
      context.currentPage = pageNumber
      let canvas
      try {
        canvas = await makePdfPageCanvas(page)
        const worker = await ensureOcrWorker(context)
        const result = await worker.recognize(canvas)
        pages[pageNumber - 1].text = normalizeText(result?.data?.text)
      } finally {
        if (canvas) {
          canvas.width = 0
          canvas.height = 0
        }
        page.cleanup?.()
      }
      reportProgress(context.onProgress, {
        phase: 'pdf-ocr',
        status: `正在识别扫描页（${index + 1}/${ocrPages.length}）`,
        progress: (index + 1) / Math.max(ocrPages.length, 1),
        currentPage: pageNumber,
        totalPages: pdf.numPages,
      })
    }

    const warnings = sparsePages.length > MAX_OCR_PAGES
      ? [`已识别前 ${MAX_OCR_PAGES} 个扫描/稀疏页面；未进行 OCR 的页码：${sparsePages.slice(MAX_OCR_PAGES).map(item => item.pageNumber).join('、')}`]
      : []
    return {
      text: pages.map(({ pageNumber, text }) => `【第 ${pageNumber} 页】\n${text}`).join('\n\n').trim(),
      warnings,
      pageCount: pdf.numPages,
    }
  } finally {
    await terminateOcrWorker(context)
    await loadingTask.destroy?.()
  }
}

async function extractImage(file, context) {
  const canvas = await makeImageCanvas(file)
  try {
    const worker = await ensureOcrWorker(context)
    const result = await worker.recognize(canvas)
    return { text: normalizeText(result?.data?.text), warnings: [], pageCount: 1 }
  } finally {
    canvas.width = 0
    canvas.height = 0
    await terminateOcrWorker(context)
  }
}

export async function extractFinancialDocument(file, options = {}) {
  const extension = validateFinancialDocument(file)
  const context = {
    signal: options.signal,
    onProgress: options.onProgress,
    loaders: options.loaders || {},
    worker: null,
    currentPage: null,
    totalPages: null,
    abortListener: null,
  }
  throwIfAborted(context.signal)

  if (extension === 'md' || extension === 'markdown' || extension === 'txt') {
    reportProgress(context.onProgress, { phase: 'text', status: '正在读取文本文件', progress: 0.5 })
    const text = normalizeText(await file.text())
    throwIfAborted(context.signal)
    reportProgress(context.onProgress, { phase: 'complete', status: '文本提取完成', progress: 1 })
    return { text, warnings: [], pageCount: null }
  }

  if (extension === 'docx') {
    reportProgress(context.onProgress, { phase: 'docx', status: '正在读取 DOCX 文本', progress: 0.1 })
    const mammoth = await loadMammoth(context.loaders)
    const result = await mammoth.extractRawText({ arrayBuffer: await file.arrayBuffer() })
    throwIfAborted(context.signal)
    reportProgress(context.onProgress, { phase: 'complete', status: 'DOCX 文本提取完成', progress: 1 })
    return {
      text: normalizeText(result?.value),
      warnings: Array.isArray(result?.messages)
        ? result.messages.filter(message => message.type === 'warning').map(message => message.message)
        : [],
      pageCount: null,
    }
  }

  if (extension === 'pdf') return extractPdf(file, context)
  return extractImage(file, context)
}

export const financialImportLimits = Object.freeze({
  maxFileBytes: MAX_FILE_BYTES,
  maxPdfPages: MAX_PDF_PAGES,
  maxOcrPages: MAX_OCR_PAGES,
  maxImageEdge: MAX_IMAGE_EDGE,
  maxImagePixels: MAX_IMAGE_PIXELS,
})
