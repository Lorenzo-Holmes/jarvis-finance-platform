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

Phase 2 is implemented in this draft branch. Automated local browser acceptance was completed on 2026-09-14; the only remaining gate is a direct pixel-level visual review through the host Computer Use channel:

- The CSS-only archive layer has been replaced by an isolated Three.js WebGL scene.
- Archive cards use original JARVIS glass/terminal styling, CanvasTexture labels, depth, lighting, subtle idle motion, pointer parallax, and selected-object focus.
- Raycasting selects 3D objects; an equivalent DOM asset list remains available for keyboard/accessibility paths.
- The page reads the existing `marketInstruments`, `marketPreferences`, and `marketAssetQuote` APIs. Server watchlist objects are prioritized before default market instruments.
- Quotes are marked as live, stale/catalog, or fallback. If the API cannot provide data, fallback objects display no fabricated price and explicitly state that they are navigation-only placeholders.
- K-line charts, order entry, tables, AI chat, and other dense financial controls remain DOM/ECharts views instead of being rendered into WebGL.
- Scene teardown cancels RAF, disconnects `ResizeObserver`, removes listeners, disposes textures/materials/geometries, disposes the renderer, and releases the WebGL context.
- While `研究终端` is cached with `v-show`, the scene now receives an explicit active state. Its RAF pauses while another workspace tab is active and resumes when the user returns, avoiding hidden-tab GPU work without discarding the selected object or loaded research data.
- Three.js is lazy-loaded with the `AnalysisOsPage` async route, so it is not part of the initial unauthenticated landing bundle.

## CI evidence

Repository CI has already verified the Phase 2 source compiles and the existing frontend behavior remains covered. The dependency lock is synchronized to Three.js `0.183.2`; final CI should use a clean `npm ci` rather than the earlier `npm install` fallback.

## Local acceptance evidence · 2026-09-14

Completed against the real local Vue/Vite frontend and Java/H2 backend through DevSpace + a headed Playwright browser:

- `npm ci` succeeded directly with the synchronized lockfile.
- `npm run test:p0` passed: 27/27 tests, 0 failures.
- `npm run build` passed. The Analysis OS async chunk is about 520.9 kB / 134.4 kB gzip; the existing ECharts chunk is about 537.4 kB / 179.9 kB gzip.
- Responsive layout was exercised at 1440×900, 1280×800, 1024×768, 430×844 and 390×844. No horizontal document overflow was observed. 1440/1280 retain the scene + 340px research rail; 1024 and below stack the research panel below the scene; the WebGL canvas follows the responsive stage dimensions.
- `prefers-reduced-motion: reduce` was emulated in the real browser. The boot overlay completed through the reduced 80ms path, the canvas remained available, and CSS animation/transition durations resolved to the reduced-motion override.
- A real authenticated temporary H2 user loaded the Analysis OS as the default workspace and received live market catalog/quote data from the local Java API.
- Server-side watchlist precedence was verified by persisting `SOLUSDT` for the temporary user and resyncing; it moved to the first archive object while remaining backed by live quote data.
- Fallback behavior was verified by intentionally failing `/api/market/instruments`: the terminal switched to `FALLBACK`, rendered 15 labeled navigation objects, and showed `—` rather than fabricated prices. Removing the injected failure restored `LIVE API` data.
- Lifecycle instrumentation exposed one issue during acceptance: the cached `v-show` page kept the Three.js RAF alive while hidden. The branch now passes an explicit active state to the scene. Runtime instrumentation measured active rendering, zero Analysis OS RAF callbacks while the user was on `行情`, and rendering resuming after returning to `研究终端`. Four additional tab round-trips retained exactly one Three.js canvas and produced no new WebGL/RAF warnings.
- The existing authenticated simulation workspace loaded successfully with the temporary user, including market selection, chart controls, and Buy/Sell entry points. No backend authentication, CSRF, API, or order logic was changed by the Analysis OS work.

The local npm audit currently reports 3 dependency advisories (2 moderate, 1 high). They pre-exist the acceptance path and did not block install/test/build; do not apply `npm audit fix --force` as part of this integration without a separate dependency-upgrade review.

## Remaining acceptance boundary

Keep this PR in Draft until one final direct visual pass is completed in the host-controlled Chrome/Computer Use channel for the five responsive widths above. The headed browser already generated and exercised those layouts, but the host app-approval card timed out before the assistant could inspect the rendered pixels directly. Do not infer that final pixel-level review from DOM metrics alone.
