<template>
  <div class="terminal-preview" aria-label="JARVIS 研究终端界面预览">
    <div class="preview-topbar">
      <div class="preview-brand">
        <img src="/favicon.svg" alt="" />
        <span>JARVIS</span>
        <span class="preview-mode">DEMO</span>
      </div>
      <div class="window-dots" aria-hidden="true"><i></i><i></i><i></i></div>
    </div>

    <div class="preview-body">
      <aside class="preview-nav" aria-hidden="true">
        <span class="nav-line active"></span>
        <span class="nav-line"></span>
        <span class="nav-line short"></span>
        <span class="nav-line"></span>
        <span class="nav-line short"></span>
      </aside>

      <section class="market-stage">
        <div class="asset-head">
          <div>
            <div class="asset-code">518880 · 黄金 ETF</div>
            <div class="asset-price">5.842 <span>+0.67%</span></div>
          </div>
          <div class="periods"><b>1D</b><span>5D</span><span>1M</span><span>1Y</span></div>
        </div>

        <div class="chart-shell" aria-hidden="true">
          <svg viewBox="0 0 720 250" preserveAspectRatio="none">
            <defs>
              <linearGradient id="landing-chart-fill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" stop-color="#d7b56d" stop-opacity=".18" />
                <stop offset="1" stop-color="#d7b56d" stop-opacity="0" />
              </linearGradient>
            </defs>
            <g class="grid-lines">
              <line x1="0" y1="50" x2="720" y2="50" />
              <line x1="0" y1="125" x2="720" y2="125" />
              <line x1="0" y1="200" x2="720" y2="200" />
              <line x1="180" y1="0" x2="180" y2="250" />
              <line x1="360" y1="0" x2="360" y2="250" />
              <line x1="540" y1="0" x2="540" y2="250" />
            </g>
            <path class="chart-area" d="M0 212 C38 204 54 218 84 196 S140 182 176 190 S230 152 270 160 S322 126 360 136 S420 118 456 102 S506 120 548 84 S610 62 648 74 S690 45 720 34 L720 250 L0 250 Z" />
            <path class="chart-line" pathLength="1" d="M0 212 C38 204 54 218 84 196 S140 182 176 190 S230 152 270 160 S322 126 360 136 S420 118 456 102 S506 120 548 84 S610 62 648 74 S690 45 720 34" />
            <circle class="chart-dot" cx="720" cy="34" r="4" />
          </svg>
        </div>

        <div class="metric-row">
          <div><span>今开</span><strong>5.808</strong></div>
          <div><span>最高</span><strong>5.861</strong></div>
          <div><span>最低</span><strong>5.792</strong></div>
          <div><span>成交额</span><strong>18.6 亿</strong></div>
        </div>
      </section>

      <aside class="insight-rail">
        <div class="rail-label">RESEARCH PULSE</div>
        <div class="insight-title">趋势保持强势</div>
        <p>价格运行于短期均线上方，波动率仍处可控区间。</p>
        <div class="rail-rule"></div>
        <div class="watch-row"><span>NASDAQ</span><b class="positive">+0.82%</b></div>
        <div class="watch-row"><span>BTC/USD</span><b class="positive">+2.31%</b></div>
        <div class="watch-row"><span>XAU/USD</span><b class="negative">-0.18%</b></div>
      </aside>
    </div>

    <div class="scan-line" aria-hidden="true"></div>
  </div>
</template>

<style scoped>
.terminal-preview {
  position: relative;
  width: min(100%, 900px);
  aspect-ratio: 1.62 / 1;
  overflow: hidden;
  border: 1px solid rgba(218, 190, 126, .26);
  border-radius: 9px;
  background: #0d1011;
  box-shadow: 0 36px 90px rgba(0,0,0,.44), 0 0 0 1px rgba(255,255,255,.02) inset;
  transform: perspective(1600px) rotateY(-3deg) rotateX(1deg);
  transform-origin: center center;
  animation: terminal-float 8s ease-in-out infinite;
}
.terminal-preview::before {
  content: '';
  position: absolute;
  inset: -45% -10% 28% 30%;
  background: radial-gradient(circle, rgba(215,181,109,.12), transparent 62%);
  pointer-events: none;
}
.preview-topbar { height: 42px; display: flex; align-items: center; justify-content: space-between; padding: 0 14px; border-bottom: 1px solid #24282a; background: #101314; }
.preview-brand { display: flex; align-items: center; gap: 8px; color: #e9e5da; font-size: 10px; font-weight: 700; letter-spacing: .08em; }
.preview-brand img { width: 18px; height: 18px; }
.preview-mode { color: #8f9497; font-size: 7px; letter-spacing: .15em; border-left: 1px solid #303436; padding-left: 8px; }
.window-dots { display: flex; gap: 5px; }
.window-dots i { display: block; width: 5px; height: 5px; border-radius: 50%; background: #383d40; }
.preview-body { position: relative; display: grid; grid-template-columns: 66px minmax(0, 1fr) 190px; height: calc(100% - 42px); }
.preview-nav { display: flex; flex-direction: column; gap: 16px; padding: 24px 14px; border-right: 1px solid #222628; background: #0b0e0f; }
.nav-line { display: block; width: 34px; height: 5px; border-radius: 1px; background: #34393b; }
.nav-line.short { width: 23px; }
.nav-line.active { background: #c9a65f; box-shadow: -14px 0 0 #c9a65f; }
.market-stage { min-width: 0; padding: 22px 22px 16px; }
.asset-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.asset-code { color: #9ba0a2; font-size: 9px; letter-spacing: .03em; }
.asset-price { margin-top: 4px; color: #f2efe7; font-size: clamp(20px, 2.15vw, 34px); font-weight: 620; font-variant-numeric: tabular-nums; letter-spacing: -.035em; }
.asset-price span { color: #4ac889; font-size: .36em; letter-spacing: 0; }
.periods { display: flex; gap: 12px; color: #5f6568; font-size: 8px; }
.periods b { color: #d9bb76; font-weight: 650; }
.chart-shell { height: 58%; margin-top: 16px; }
.chart-shell svg { width: 100%; height: 100%; overflow: visible; }
.grid-lines line { stroke: #202527; stroke-width: 1; vector-effect: non-scaling-stroke; }
.chart-area { fill: url(#landing-chart-fill); }
.chart-line { fill: none; stroke: #d7b56d; stroke-width: 2; vector-effect: non-scaling-stroke; stroke-linecap: round; stroke-dasharray: 1; stroke-dashoffset: 1; animation: chart-draw 2.2s cubic-bezier(.2,.7,.2,1) .25s forwards; }
.chart-dot { fill: #f0d58c; filter: drop-shadow(0 0 6px rgba(240,213,140,.55)); animation: dot-pulse 2.4s ease-in-out infinite; }
.metric-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; border-top: 1px solid #222628; }
.metric-row div { display: flex; flex-direction: column; gap: 3px; padding: 10px 9px 0 0; }
.metric-row span { color: #62686b; font-size: 7px; }
.metric-row strong { color: #c5c8c7; font-size: 9px; font-weight: 600; font-variant-numeric: tabular-nums; }
.insight-rail { padding: 22px 16px; border-left: 1px solid #222628; background: rgba(17,20,21,.72); }
.rail-label { color: #b99a58; font-size: 7px; letter-spacing: .16em; }
.insight-title { margin-top: 18px; color: #e9e5dc; font-size: 12px; font-weight: 620; }
.insight-rail p { color: #7f8587; font-size: 8px; line-height: 1.7; margin: 7px 0 0; }
.rail-rule { height: 1px; background: #292d2f; margin: 20px 0 14px; }
.watch-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 0; color: #9ba0a2; font-size: 8px; border-bottom: 1px solid #202426; }
.watch-row b { font-weight: 650; font-variant-numeric: tabular-nums; }
.positive { color: #4ac889; }
.negative { color: #e36a69; }
.scan-line { position: absolute; top: 42px; bottom: 0; width: 1px; left: 18%; background: linear-gradient(to bottom, transparent, rgba(231,199,128,.26), transparent); opacity: 0; animation: terminal-scan 9s ease-in-out infinite; pointer-events: none; }
@keyframes chart-draw { to { stroke-dashoffset: 0; } }
@keyframes dot-pulse { 50% { opacity: .45; transform: scale(.75); transform-origin: 720px 34px; } }
@keyframes terminal-scan { 0%, 18% { left: 18%; opacity: 0; } 25% { opacity: .5; } 55% { left: 82%; opacity: .25; } 65%, 100% { left: 82%; opacity: 0; } }
@keyframes terminal-float { 0%, 100% { transform: perspective(1600px) rotateY(-3deg) rotateX(1deg) translateY(0); } 50% { transform: perspective(1600px) rotateY(-2deg) rotateX(.4deg) translateY(-6px); } }
@media (max-width: 1180px) { .preview-body { grid-template-columns: 58px minmax(0, 1fr) 160px; } }
@media (max-width: 760px) {
  .terminal-preview { transform: none; animation: none; aspect-ratio: 1.32 / 1; }
  .preview-body { grid-template-columns: 50px minmax(0, 1fr); }
  .insight-rail { display: none; }
}
</style>
