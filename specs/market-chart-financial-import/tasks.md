# Implementation Plan

- [x] 1. Correct the multi-market chart night surface
  - Replace the fixed pale `.chart` background in `CrossMarketView.vue` with the workspace chart theme token and retain the transparent ECharts canvas.
  - Add a regression assertion for day/night chart surface tokens.
  - _Requirement: 1_

- [x] 2. Make the financial-report source panel theme-aware
  - Replace hard-coded pale backgrounds on the input and output surfaces with workspace theme tokens.
  - Add readable placeholder, focus, file picker, progress, success, and error states for both themes.
  - Preserve the current pasted-text analysis flow and existing panel layout.
  - _Requirement: 2_

- [x] 3. Implement local extraction for supported source files
  - Add a browser-only extraction utility with lazy-loaded PDF.js, Mammoth, and Tesseract.js dependencies.
  - Support Markdown/plain text, DOCX raw text, PDF page text, sparse PDF page rendering/OCR, and PNG/JPEG/WebP OCR.
  - Enforce 20 MB, 50 PDF pages, 20 OCR pages, and bounded image dimensions; return explicit warnings/errors without silently truncating text.
  - Terminate OCR workers after completion, failure, cancellation, and teardown.
  - _Requirement: 3, 4, 7_

- [x] 4. Add a review-first import interaction
  - Add a single-file picker and source filename/progress/error status to `FinancialReportPage.vue`.
  - Preserve the current textarea until extraction succeeds; offer replace/append when it already contains text.
  - Insert extracted text into the editable textarea, show the existing 50,000-character limit, and disable Analyze until the user trims over-limit content.
  - Ensure file selection/extraction makes no analysis API request; submit only the current textarea value after explicit Analyze.
  - _Requirement: 5, 6, 8_

- [x] 5. Add focused automated regression coverage
  - Test extension/size/page validation, Markdown/text extraction, PDF text/OCR fallback selection, DOCX raw text, image OCR handoff, and error behavior.
  - Test import replace/append, failed-import preservation, no API call during selection, and explicit Analyze submission.
  - Test night-mode surface styles do not use fixed pale chart/textarea backgrounds.
  - Completed extraction utility tests and source-level safeguards; Playwright covers replace/append, failed-import preservation, and API-call timing in a managed Vite browser run.
  - _Requirement: 1–8_

- [x] 6. Verify the frontend change
  - Run the frontend P0 test suite and production build.
  - Run local browser smoke tests for night-mode multi-market chart and report input surfaces, Markdown import, and one OCR fixture using a mocked analysis API.
  - Record exact browser route/actions/results and any remaining limitation; do not use production credentials or send real filing content.
  - Frontend P0 tests (128), production build and Playwright `financial-import` passed. The browser test uses local fixtures and a mocked backend; it does not claim real financial-report analysis or production credentials.
  - _Requirement: 1–8_
