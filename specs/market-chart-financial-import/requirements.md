# Requirements: dark chart and financial document import

## Problem and scope

The multi-market chart keeps a pale gray plot background in night mode, and the financial-report source textarea is also hard-coded to a pale surface. The report workflow accepts pasted text only. This change covers those two frontend experiences and local extraction of PDF, DOCX, Markdown/text, and raster-image files. The existing financial-report backend contract remains text-only.

## User stories

- As a researcher using night mode, I want the multi-market chart canvas and its surrounding controls to use the night palette so the plot remains legible and visually consistent.
- As a researcher, I want to import a filing instead of manually copying it, while retaining the ability to review and edit extracted text before analysis.
- As a researcher handling sensitive filings, I want the source file parsed locally and only the text I explicitly submit sent to the existing analysis API.

## Acceptance criteria (EARS)

1. When the multi-market page is displayed in night mode, the chart plot surface shall use a dark theme surface rather than a fixed light/gray background, while preserving candles, overlays, axes, and zoom behavior.
2. When the financial-report page is displayed in night mode, its textarea, placeholder, file controls, progress, and feedback shall maintain readable contrast against the surrounding panels.
3. When a user selects a supported PDF, DOCX, Markdown, or plain-text file, the application shall extract readable text in the browser and place it into the editable financial-report input only after successful extraction.
4. When a selected PDF contains image-only pages or the user selects a supported raster image, the application shall offer Chinese/English OCR in the browser and show extraction progress and a recoverable error when recognition fails.
5. When extraction completes, the application shall let the user review and edit the text before analysis; it shall not submit a file automatically.
6. When the user explicitly starts analysis, the application shall send only the current text through the existing financial-report API and shall enforce the existing 50,000-character limit without silently truncating content.
7. When a file exceeds the documented size/page limits or uses an unsupported format, the application shall explain the constraint and keep the current text unchanged.
8. The browser shall not upload the source file for parsing or OCR; source-file bytes shall remain client-side, while the existing analysis action may send the user-approved extracted text to the backend.

## Constraints and non-goals

- No backend/API or database changes are in scope; `/api/ai/financial/report` continues to receive `{ content: string }`.
- Do not introduce server-side storage or retain uploaded filings.
- Keep pasted-text analysis working.
- Initial import is a single file at a time; batch filing management, OCR correction UI, and extracting tables into structured financial fields are out of scope.
- Proposed safety limits for design review: 20 MB per file, at most 50 PDF pages for text extraction, and at most 20 pages requiring OCR. Content over 50,000 characters must be surfaced for user editing rather than silently truncated.
