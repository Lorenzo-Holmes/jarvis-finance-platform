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

## Phase 1: information-architecture PoC

The first PoC is intentionally dependency-free and uses Vue + CSS 3D only. This proves the product flow before adding a heavyweight scene renderer:

1. Authenticated users land on `研究终端`.
2. The terminal presents a spatial market archive array.
3. Selecting an object reveals a compact research context panel.
4. Actions route into the existing JARVIS pages: market, AI research, financial report, industry chain, risk warning, and simulation trading.
5. Existing backend, authentication, CSRF, trading, and API behavior remain untouched.

The displayed quote values in the PoC are placeholders and are explicitly labeled as non-real-time.

## Phase 2: real scene renderer

After local DevSpace execution is available, replace the CSS scene layer with a Three.js implementation while keeping the same Vue page contract and business routing. Recommended work:

- Isolate renderer lifecycle from Vue DOM state.
- Build reusable archive-card meshes and instanced background elements.
- Use raycasting for object selection and keyboard-accessible DOM mirrors for accessibility.
- Add camera focus/return motion, glass materials, subtle depth, scan/decryption transitions, and quality presets.
- Feed objects from existing JARVIS market/watchlist APIs instead of static PoC data.
- Keep K-line charts, tables, order entry, and AI chat as DOM/ECharts UI rather than rendering dense financial text into WebGL.
- Dispose geometries, materials, textures, listeners, RAF loops, and observers when leaving the tab.

## Acceptance boundary

This integration should be considered ready for wider rollout only after:

- `npm run test:p0` passes.
- `npm run build` passes.
- Desktop widths around 1440/1280/1024 and mobile widths around 430/390 are visually checked.
- Reduced-motion behavior is verified.
- Entering/leaving the research terminal repeatedly does not leak render loops or event listeners.
- Existing simulation-trading and authentication flows remain unchanged.
