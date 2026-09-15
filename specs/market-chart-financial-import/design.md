# Design: dark chart and financial document import

## UI design specification

1. **Purpose:** Keep multi-market charting legible in night mode and let researchers bring a filing into the existing analysis flow without manually copying every document. Users remain in control of the extracted source and the final analysis request.
2. **Aesthetic direction:** Industrial/utilitarian research terminal. Preserve the product's existing asymmetrical research layout: a fixed-width source rail beside the flexible analysis dossier; no page redesign.
3. **Color palette:** Night background `#0B0F13`, panel `#141A20`, input/chart surface `#11171D`, primary text `#EEE9DE`, accent `#D6B36A`; status colors continue to use the workspace's existing semantic tokens.
4. **Typography:** Preserve the workspace's existing IBM Plex Sans / Noto Sans SC body pairing and monospace terminal labels. This is a narrow brand/design-system override; do not introduce a new font dependency.
5. **Layout strategy:** Keep the source panel as the left rail and analysis output on the right. Add a compact import/control row above the existing textarea, with a small progress/source-status strip and explicit replace/append choice after extraction. The chart remains full-width in its existing center column.

## Architecture and module boundaries

```text
FinancialReportPage.vue
  ├─ local file picker + progress/error/source metadata
  ├─ editable textarea (same source of truth as paste flow)
  └─ explicit Analyze action
       └─ existing POST /api/ai/financial/report { content }

financialDocumentImport.js (new browser-only utility)
  ├─ .md / .markdown / .txt → File.text()
  ├─ .docx → Mammoth extractRawText(ArrayBuffer)
  ├─ text-native PDF → PDF.js getTextContent per page
  ├─ sparse/image-only PDF pages → PDF.js canvas render → Tesseract OCR
  └─ PNG / JPEG / WebP → Tesseract OCR
```

No backend, database, or endpoint change is needed. `FinancialReportPage.vue` continues to call the existing `api.aiFinancialReport()` only after the user clicks Analyze.

## Technology choices and trade-offs

- **PDF:** `pdfjs-dist` parses local PDF bytes, extracts text page by page, and renders only sparse pages for OCR. PDF.js publishes a separate worker entry; use the Vite worker asset URL so it is served from the app's own origin and works with the existing static build. Pin the exact package version in `package-lock.json`.
- **DOCX:** `mammoth.extractRawText()` consumes the browser `ArrayBuffer`. This intentionally discards visual formatting and avoids injecting untrusted document HTML into the page; tables may be flattened and still need user review.
- **Markdown/text:** use `File.text()` and preserve source text; no parser is required for plain source submission.
- **Image/scanned-page OCR:** lazy-load `tesseract.js` and use `chi_sim+eng` in its Web Worker. The OCR engine and language data are fetched lazily from version-pinned public CDNs on first OCR, not at initial page load. The document/image bytes are processed in the browser and are not sent to those CDN requests. Network restrictions can prevent OCR; the UI must surface that error and leave paste/other import paths available. Self-hosting all OCR assets would avoid this dependency but adds roughly tens of MB of static assets, so it is not the initial default.
- **Lazy loading:** PDF, DOCX and OCR libraries load only when their corresponding file type is selected, avoiding a large initial application bundle.

## Import interaction and safety

1. The file picker accepts `.pdf`, `.docx`, `.md`, `.markdown`, `.txt`, `.png`, `.jpg`, `.jpeg`, and `.webp`; actual parsing errors are still caught because extensions/MIME types are not trusted.
2. Validate a 20 MB file limit before parsing. Reject PDFs over 50 pages. For image-only/sparse PDF pages, OCR at most 20 pages; if the limit is exceeded, show which pages were not OCR'd instead of implying complete extraction. Downscale raster inputs to a bounded canvas before OCR.
3. Show extraction/OCR progress, current page where applicable, source filename, and actionable errors. Terminate OCR workers after each import and on component teardown.
4. Keep existing textarea text unchanged until extraction succeeds. If text already exists, show explicit **Replace** and **Append** actions; otherwise insert the extracted text. Users can edit the textarea after either action.
5. Do not silently truncate extracted text. If it exceeds the existing 50,000-character API limit, insert the result and explain that the user must edit it before Analyze becomes available.
6. Selecting a file never makes an analysis request. The raw source remains in the browser; only the reviewed textarea value is sent after the explicit Analyze action.

## Theme implementation

- In `CrossMarketView.vue`, replace the fixed light `.chart` background with `var(--workspace-chart-bg, transparent)`. The ECharts option already uses a transparent canvas; the workspace theme supplies a transparent daytime surface and `#0B0F13` at night.
- In `FinancialReportPage.vue`, replace fixed pale backgrounds on the textarea and main output area with existing `--workspace-panel-*`/`--surface` theme tokens. Define placeholder, focus, file-control, progress, and error colors using `--text`, `--muted`, `--subtle`, `--line-strong`, and `--accent`.
- Keep candle colors, indicator colors, chart data, and analysis output semantics unchanged.

## Validation strategy

- Unit tests for accepted/rejected extensions, byte/page limits, Markdown/text extraction, PDF page text collection, DOCX raw-text extraction, OCR fallback selection, and extraction failure behavior.
- Component-flow tests for replacement/append, unchanged input on a failed import, no API call on file selection, and API submission only after Analyze.
- Theme regression tests asserting that chart and report input surfaces use workspace theme variables rather than fixed pale `rgba()` backgrounds.
- Run the existing P0 suite and production Vite build.
- Browser smoke tests: switch to night mode, inspect the multi-market chart surface and financial-report textarea/placeholder; import one Markdown file and one image/PDF fixture, verify progress and editable output, then submit only the edited text against a mocked API. No production account or real AI request is required.

## References

- [Mozilla PDF.js getting started](https://mozilla.github.io/pdf.js/getting_started/) — separate PDF worker asset and browser deployment.
- [Mammoth.js documentation](https://github.com/mwilliamson/mammoth.js/) — browser `ArrayBuffer` input and raw-text extraction; its docs also warn that generated HTML is not sanitized.
- [Tesseract.js local installation](https://github.com/naptha/tesseract.js/blob/master/docs/local-installation.md) — worker, core, and language paths can be configured, including local assets.
- [Tesseract.js FAQ](https://github.com/naptha/tesseract.js/blob/master/docs/faq.md) — PDF needs a separate PDF-to-image renderer before Tesseract OCR.
