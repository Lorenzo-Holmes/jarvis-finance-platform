# JARVIS Analysis OS · RhineLabUI integration note

## Goal

Add a spatial research entry to JARVIS without replacing the existing authenticated business pages or changing Java/Python APIs. The interaction direction is inspired by the archive-array, glass-panel, selection/decryption, and viewer hierarchy demonstrated by `LBEILC/RhineLabUI`.

## Brand and license boundary

`RhineLabUI` is MIT licensed, but its README also states that the original work is an unofficial recreation of an Arknights / Rhine Lab visual reference and that the related names, marks, and setting belong to their respective rightsholders.

For JARVIS:

- Do not copy Rhine Lab / Arknights logos, names, illustrations, audio, narrative text, or branded archive assets.
- Keep JARVIS naming, typography hierarchy, financial semantics, and market content original.
- Interaction ideas such as spatial archive browsing, glass-like panels, scan/decrypt transitions, object focus, and layered research drill-down may be reinterpreted for financial research.
- If substantial source code is copied from the MIT-licensed upstream in a later implementation, preserve its copyright and MIT permission notice with the copied/substantial portions.

The current JARVIS implementation is original code and does not copy Rhine Lab / Arknights branded assets or source modules.

## Phase 1: information-architecture PoC

Phase 1 established the product flow with Vue + CSS 3D:

1. Authenticated users land on `研究终端`.
2. The terminal presents a spatial market archive array.
3. Selecting an object reveals a compact research context panel.
4. Actions route into the existing JARVIS pages: market, AI research, financial report, industry chain, risk warning, and simulation trading.
5. Existing backend, authentication, CSRF, trading, and API behavior remain untouched.

That PoC passed the repository CI before the renderer was replaced.

## Phase 2: Three.js + existing market APIs

Phase 2 is implemented in this draft branch and is awaiting local visual/lifecycle acceptance:

- The CSS-only archive layer has been replaced by an isolated Three.js WebGL scene.
- Archive cards use original JARVIS glass/terminal styling, CanvasTexture labels, depth, lighting, subtle idle motion, pointer parallax, and selected-object focus.
- Raycasting selects 3D objects; an equivalent DOM asset list remains available for keyboard/accessibility paths.
- The page reads the existing `marketInstruments`, `marketPreferences`, and `marketAssetQuote` APIs. Server watchlist objects are prioritized before default market instruments.
- Quotes are marked as live, stale/catalog, or fallback. If the API cannot provide data, fallback objects display no fabricated price and explicitly state that they are navigation-only placeholders.
- K-line charts, order entry, tables, AI chat, and other dense financial controls remain DOM/ECharts views instead of being rendered into WebGL.
- Scene teardown cancels RAF, disconnects `ResizeObserver`, removes listeners, disposes textures/materials/geometries, disposes the renderer, and releases the WebGL context.
- Three.js is lazy-loaded with the `AnalysisOsPage` async route, so it is not part of the initial unauthenticated landing bundle.

## CI evidence

Repository CI has already verified the Phase 2 source compiles and the existing frontend behavior remains covered. The dependency lock is synchronized to Three.js `0.183.2`; final CI should use a clean `npm ci` rather than the earlier `npm install` fallback.

## Remaining acceptance boundary

Keep this PR in Draft until all of the following are complete:

- `npm ci` succeeds directly with the synchronized lockfile.
- `npm run test:p0` passes.
- `npm run build` passes.
- Desktop widths around 1440/1280/1024 and mobile widths around 430/390 are visually checked in the real local application.
- Reduced-motion behavior is visually verified.
- Entering/leaving the research terminal repeatedly is checked for render-loop, listener, and WebGL resource leaks.
- Real authenticated market/watchlist data is verified in the browser, including fallback behavior when quote calls fail.
- Existing simulation-trading and authentication flows remain unchanged.

The local visual/lifecycle checks require the DevSpace/Browser execution channel and must not be inferred from GitHub CI alone.
