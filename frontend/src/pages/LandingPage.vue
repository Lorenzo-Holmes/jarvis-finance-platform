<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['enter'])
const root = ref(null)
let revealObserver
let demoStopped = false
const timers = []

const demoStates = {
  XAUUSD: { name: 'Gold Spot · USD', price: '3,635.20', change: '+24.63 · +0.68%', tone: 'pos', signal: 'Moderately Bullish', line: 'M0 280 C55 270 75 286 118 247 C163 207 197 226 238 210 C280 193 303 232 344 187 C383 148 405 170 447 126 C487 87 520 116 563 96 C606 78 620 122 667 87 C718 49 757 71 800 32' },
  NVDA: { name: 'NVIDIA · NASDAQ', price: '183.42', change: '+4.20 · +2.36%', tone: 'pos', signal: 'Momentum Strong', line: 'M0 302 C54 287 97 295 135 258 C177 217 211 235 251 190 C295 139 337 166 374 134 C417 96 456 110 500 86 C548 56 586 80 633 62 C690 39 733 58 800 19' },
  BTCUSD: { name: 'Bitcoin · USD', price: '112,840', change: '-472 · -0.42%', tone: 'neg', signal: 'Volatility Elevated', line: 'M0 213 C40 199 87 190 129 207 C171 225 208 220 252 242 C297 264 336 242 380 259 C425 276 465 251 510 265 C556 280 600 266 645 283 C700 303 744 300 800 292' },
}

onMounted(() => {
  const revealElements = root.value?.querySelectorAll('.reveal') || []
  if (typeof window.IntersectionObserver === 'function') {
    revealObserver = new window.IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) entry.target.classList.add('visible')
      })
    }, { threshold: .12 })
    revealElements.forEach((el) => revealObserver.observe(el))
  } else {
    // 旧版浏览器没有 IntersectionObserver 时仍显示全部内容。
    revealElements.forEach((el) => el.classList.add('visible'))
  }

  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return

  const terminal = root.value?.querySelector('[data-terminal]')
  if (!terminal) return
  const cursor = terminal.querySelector('[data-demo-cursor]')
  const path = terminal.querySelector('[data-demo-line]')
  const area = terminal.querySelector('[data-demo-area]')
  const symbolEl = terminal.querySelector('[data-symbol-title]')
  const nameEl = terminal.querySelector('[data-symbol-sub]')
  const priceEl = terminal.querySelector('[data-price]')
  const changeEl = terminal.querySelector('[data-change]')
  const signalEl = terminal.querySelector('[data-signal]')
  if (!cursor || !path || !area || !symbolEl || !nameEl || !priceEl || !changeEl || !signalEl) return
  const assets = [...terminal.querySelectorAll('[data-asset]')]
  const ranges = [...terminal.querySelectorAll('[data-range]')]

  const sleep = (ms) => new Promise((resolve) => {
    const id = window.setTimeout(resolve, ms)
    timers.push(id)
  })

  const moveTo = async (el) => {
    if (!cursor || !el || demoStopped) return
    const host = terminal.getBoundingClientRect()
    const rect = el.getBoundingClientRect()
    const x = rect.left - host.left + rect.width * .7
    const y = rect.top - host.top + rect.height * .52
    cursor.style.opacity = '1'
    cursor.style.translate = `${x}px ${y}px`
    await sleep(680)
    el.classList.add('demo-click')
    const t = window.setTimeout(() => el.classList.remove('demo-click'), 520)
    timers.push(t)
    await sleep(160)
  }

  const applyState = (key) => {
    const state = demoStates[key]
    if (!state) return
    symbolEl.textContent = key
    nameEl.textContent = state.name
    priceEl.textContent = state.price
    changeEl.textContent = state.change
    changeEl.className = `price-change ${state.tone}`
    signalEl.textContent = state.signal
    path.setAttribute('d', state.line)
    area.setAttribute('d', `${state.line} L800 360 L0 360Z`)
    assets.forEach((el) => el.classList.toggle('active', el.dataset.asset === key))
  }

  const sequence = async () => {
    const steps = [
      ['NVDA', '[data-asset="NVDA"]'],
      ['NVDA', '[data-range="1M"]'],
      ['BTCUSD', '[data-asset="BTCUSD"]'],
      ['BTCUSD', '[data-range="1Y"]'],
      ['XAUUSD', '[data-asset="XAUUSD"]'],
      ['XAUUSD', '[data-range="1D"]'],
    ]
    await sleep(1200)
    while (!demoStopped) {
      for (const [key, selector] of steps) {
        if (demoStopped) break
        const target = terminal.querySelector(selector)
        await moveTo(target)
        if (target?.dataset.range) {
          ranges.forEach((el) => el.classList.toggle('active', el === target))
        }
        applyState(key)
        await sleep(1000)
      }
      await sleep(900)
    }
  }
  sequence()
})

onBeforeUnmount(() => {
  demoStopped = true
  revealObserver?.disconnect()
  timers.forEach((id) => window.clearTimeout(id))
})
</script>

<template>
  <div ref="root" class="landing">
    <header class="nav">
      <a href="#top" class="brand"><span class="brand-mark"></span><b>JARVIS</b></a>
      <nav><a href="#markets">市场</a><a href="#research">AI 研究</a><a href="#backtest">策略回测</a><a href="#workspace">工作空间</a></nav>
      <div class="nav-actions"><a href="#workspace" class="pill">探索平台</a><button class="pill gold" @click="emit('enter')">进入 JARVIS</button></div>
    </header>

    <main id="top">
      <section class="hero">
        <div class="hero-copy">
          <div class="eyebrow">AI-POWERED FINANCIAL RESEARCH</div>
          <h1>看懂市场</h1>
          <h2>比价格，更接近<span>判断</span>。</h2>
          <p>把实时行情、跨市场数据、AI 研究、策略回测与模拟交易，组织成一条连续的研究路径。</p>
          <div class="hero-actions"><button class="pill gold" @click="emit('enter')">进入 JARVIS</button><a class="pill" href="#research">探索平台 ↓</a></div>
        </div>

        <div class="terminal-wrap reveal">
          <div class="terminal" data-terminal>
            <div class="terminal-top"><span class="dots">● ● ●</span><span>JARVIS RESEARCH TERMINAL</span><span class="live">● MARKET LIVE</span></div>
            <div class="terminal-grid">
              <aside class="rail"><i>⌁</i><i>◇</i><i>⌘</i><i>↗</i><i>◎</i></aside>
              <aside class="watch">
                <div class="watch-title"><b>Watchlist</b><small>LIVE</small></div>
                <div class="asset active" data-asset="XAUUSD"><b>XAUUSD</b><span>3,635.20</span><small>Gold Spot</small><em class="pos">+0.68%</em></div>
                <div class="asset" data-asset="NVDA"><b>NVDA</b><span>183.42</span><small>NVIDIA</small><em class="pos">+2.36%</em></div>
                <div class="asset" data-asset="BTCUSD"><b>BTCUSD</b><span>112,840</span><small>Bitcoin</small><em class="neg">-0.42%</em></div>
                <div class="asset"><b>NASDAQ</b><span>23,402</span><small>US Tech</small><em class="pos">+1.21%</em></div>
              </aside>
              <section class="chart-pane">
                <div class="chart-head"><div><b data-symbol-title>XAUUSD</b><small data-symbol-sub>Gold Spot · USD</small></div><div class="quote"><strong data-price>3,635.20</strong><span class="price-change pos" data-change>+24.63 · +0.68%</span></div></div>
                <div class="range"><button class="active" data-range="1D">1D</button><button data-range="5D">5D</button><button data-range="1M">1M</button><button data-range="6M">6M</button><button data-range="YTD">YTD</button><button data-range="1Y">1Y</button></div>
                <svg viewBox="0 0 800 380" preserveAspectRatio="none" class="chart-svg"><defs><linearGradient id="heroFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c9ab70" stop-opacity=".22"/><stop offset="1" stop-color="#c9ab70" stop-opacity="0"/></linearGradient></defs><g class="gridlines"><line x1="0" x2="800" y1="70" y2="70"/><line x1="0" x2="800" y1="150" y2="150"/><line x1="0" x2="800" y1="230" y2="230"/><line x1="0" x2="800" y1="310" y2="310"/></g><path data-demo-area fill="url(#heroFill)" d="M0 280 C55 270 75 286 118 247 C163 207 197 226 238 210 C280 193 303 232 344 187 C383 148 405 170 447 126 C487 87 520 116 563 96 C606 78 620 122 667 87 C718 49 757 71 800 32 L800 360 L0 360Z"/><path data-demo-line class="main-line" d="M0 280 C55 270 75 286 118 247 C163 207 197 226 238 210 C280 193 303 232 344 187 C383 148 405 170 447 126 C487 87 520 116 563 96 C606 78 620 122 667 87 C718 49 757 71 800 32"/></svg>
              </section>
              <aside class="side"><small>JARVIS SIGNAL</small><div class="signal"><b data-signal>Moderately Bullish</b><p>实时市场上下文与研究信号保持联动，帮助快速形成判断。</p></div></aside>
              <span class="demo-cursor" data-demo-cursor></span>
            </div>
          </div>
        </div>
      </section>

      <section class="pulse-section">
        <div class="pulse-sticky">
          <div class="metal-stage" aria-hidden="true">
            <div class="candle" style="--x:12%;--body:170px;--top:92px;--bottom:0px;--delay:-1s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:27%;--body:190px;--top:18px;--bottom:122px;--delay:-4s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:39%;--body:176px;--top:0px;--bottom:42px;--delay:-2.4s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:51%;--body:214px;--top:178px;--bottom:0px;--delay:-5.3s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:63%;--body:180px;--top:150px;--bottom:0px;--delay:-3s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:75%;--body:162px;--top:18px;--bottom:12px;--delay:-6s"><i></i><b></b><em></em></div>
            <div class="candle" style="--x:87%;--body:226px;--top:0px;--bottom:0px;--delay:-1.6s"><i></i><b></b><em></em></div>
          </div>
          <div class="pulse-copy"><span class="kicker">LIVE MARKET PULSE</span><h3>市场，在持续变化。</h3><p>原创金属 K 线动效用于产品叙事，真实行情与研究信息保持独立。</p></div>
        </div>
      </section>

      <section id="research" class="section"><div class="section-inner"><div class="section-head reveal"><span class="kicker">JARVIS INTELLIGENCE</span><h3>研究，从问题开始。</h3><p>JARVIS 把行情、市场数据和研究上下文放在同一个分析工作流里，让 AI 输出更接近真正的研究结论。</p></div><div class="showcase reveal"><div class="copy"><h4>AI 研究助手</h4><p>围绕价格变化、宏观驱动和风险因素提问；资产上下文自动关联，无需反复复制数据。</p><ul><li>资产上下文自动关联</li><li>多空因素结构化呈现</li><li>研究结论与置信度</li></ul></div><div class="ai-card"><div class="ai-head"><b>JARVIS VIEW</b><span>Confidence 82%</span></div><div class="factor"><span>美元走弱</span><em class="pos">Bullish</em><i style="--w:84%"></i></div><div class="factor"><span>实际利率回落</span><em class="pos">Bullish</em><i style="--w:74%"></i></div><div class="factor"><span>避险需求</span><em class="pos">Bullish</em><i style="--w:64%"></i></div><div class="factor"><span>短线拥挤度</span><em class="neg">Risk</em><i style="--w:47%"></i></div><div class="thesis"><small>RESEARCH THESIS</small><p>金价的主要驱动仍来自美元与实际利率方向。短期趋势保持正向，但价格已接近前高密集区。</p></div></div></div></div></section>

      <section id="markets" class="section muted-section"><div class="section-inner"><div class="section-head reveal"><span class="kicker">CROSS MARKET</span><h3>一个工作空间，观察多个市场。</h3><p>黄金、A 股、美股、Crypto 与宏观指标集中在同一套研究界面。</p></div><div class="market-grid reveal"><article><small>GOLD · XAUUSD</small><b>3,635.20</b><em class="pos">+0.68%</em><svg viewBox="0 0 300 70"><path d="M0 55 C40 59 40 38 72 42 S115 26 150 33 S190 17 220 24 S255 7 300 10"/></svg></article><article><small>US · NASDAQ</small><b>23,402.18</b><em class="pos">+1.21%</em><svg viewBox="0 0 300 70"><path d="M0 58 C25 50 48 54 70 43 S112 48 140 33 S184 35 205 19 S244 27 300 8"/></svg></article><article><small>CRYPTO · BTCUSD</small><b>112,840</b><em class="neg">-0.42%</em><svg viewBox="0 0 300 70"><path d="M0 18 C30 12 48 30 74 26 S105 46 136 38 S173 56 202 42 S250 51 300 48"/></svg></article><article><small>CHINA · CSI 300</small><b>4,083.61</b><em class="pos">+0.31%</em><svg viewBox="0 0 300 70"><path d="M0 49 C25 44 51 51 80 38 S118 41 143 28 S189 38 215 25 S263 30 300 17"/></svg></article></div></div></section>

      <section id="backtest" class="section"><div class="section-inner"><div class="section-head reveal"><span class="kicker">BACKTEST</span><h3>从想法，到验证。</h3><p>把假设变成可复现的回测结果，再理解收益、风险与回撤。</p></div><div class="backtest reveal"><div class="eq-title"><b>Strategy Equity</b><span>MA 20 / 60 · XAUUSD</span></div><svg viewBox="0 0 900 340" preserveAspectRatio="none"><defs><linearGradient id="eqFillLanding" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c9ab70" stop-opacity=".16"/><stop offset="1" stop-color="#c9ab70" stop-opacity="0"/></linearGradient></defs><path fill="url(#eqFillLanding)" d="M0 280 C70 270 80 300 145 252 C205 207 240 234 290 185 C335 143 384 180 430 138 C480 94 510 124 565 82 C615 45 650 92 703 56 C760 17 820 55 900 20 L900 330 L0 330Z"/><path class="eq-line" d="M0 280 C70 270 80 300 145 252 C205 207 240 234 290 185 C335 143 384 180 430 138 C480 94 510 124 565 82 C615 45 650 92 703 56 C760 17 820 55 900 20"/></svg><div class="stats"><div><small>ANNUAL RETURN</small><b>18.42%</b></div><div><small>SHARPE</small><b>1.72</b></div><div><small>MAX DRAWDOWN</small><b>-8.31%</b></div><div><small>WIN RATE</small><b>61.2%</b></div></div></div></div></section>

      <section id="workspace" class="workspace-section"><div class="section-inner"><div class="section-head reveal"><span class="kicker">CONNECTED WORKSPACE</span><h3>行情、研究、策略，在同一条思路里联动。</h3><p>选择一个资产，行情、AI 分析、回测与模拟交易围绕同一个上下文切换。</p></div><div class="network reveal"><svg class="network-lines" viewBox="0 0 1000 520" preserveAspectRatio="none"><path d="M240 150 C350 180 400 220 470 246"/><path d="M760 160 C650 190 600 215 530 246"/><path d="M340 400 C400 345 430 315 475 278"/></svg><div class="node market-node"><small>MARKET</small><b>XAUUSD · 3,635.20</b><span>实时行情 / 技术指标 / 价格结构</span></div><div class="node ai-node"><small>JARVIS AI</small><b>Bullish · 82%</b><span>宏观驱动 / 多空因素 / 风险解释</span></div><div class="node backtest-node"><small>BACKTEST</small><b>Sharpe · 1.72</b><span>参数验证 / 回撤 / 策略收益</span></div><div class="node center-node"><small>SELECTED CONTEXT</small><b>GOLD</b><span>一个资产上下文，连接整个工作空间。</span></div></div></div></section>

      <section class="cta"><span class="kicker">JARVIS FINANCIAL RESEARCH PLATFORM</span><h3>下一次研究，从这里开始。</h3><p>进入 JARVIS，使用完整的行情、AI、回测与模拟交易能力。</p><button class="pill gold" @click="emit('enter')">进入 JARVIS →</button></section>
    </main>

    <footer><span>© 2026 JARVIS · 仅供研究参考，不构成投资建议</span><a href="https://github.com/panda-lsy/jarvis-finance-platform" target="_blank" rel="noreferrer">GitHub</a></footer>
  </div>
</template>

<style scoped>
.landing{--bg:#060606;--panel:#0d0d0e;--line:rgba(255,255,255,.09);--text:#f3f1ea;--muted:#98958d;--gold:#c9ab70;--gold2:#e4c989;--green:#38ce92;--red:#e26764;background:radial-gradient(900px 500px at 50% 0,rgba(201,171,112,.1),transparent 65%),var(--bg);color:var(--text);min-height:100vh;font-family:Inter,ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}.landing *{box-sizing:border-box}.landing a{color:inherit;text-decoration:none}.nav{height:72px;position:fixed;inset:0 0 auto;z-index:40;display:flex;align-items:center;justify-content:space-between;padding:0 clamp(20px,4vw,56px);background:rgba(6,6,6,.76);backdrop-filter:blur(18px);border-bottom:1px solid rgba(255,255,255,.05)}.brand{display:flex;align-items:center;gap:11px;letter-spacing:.12em}.brand-mark{width:28px;height:28px;border:1px solid var(--gold);border-radius:50%;position:relative}.brand-mark:after{content:"";position:absolute;width:9px;height:9px;border-top:1px solid var(--gold2);border-right:1px solid var(--gold2);rotate:45deg;left:7px;top:8px}.nav nav{display:flex;gap:30px;color:#aaa79f;font-size:13px}.nav nav a:hover{color:#fff}.nav-actions,.hero-actions{display:flex;gap:10px}.pill{border:1px solid var(--line);border-radius:999px;padding:10px 17px;background:rgba(255,255,255,.03);color:var(--text);font:inherit;cursor:pointer}.pill.gold{background:var(--gold2);border-color:var(--gold2);color:#15120c;font-weight:700}.hero{padding:150px 26px 95px;min-height:1040px;overflow:hidden}.hero-copy{max-width:850px;margin:auto;text-align:center}.eyebrow,.kicker{font-size:11px;letter-spacing:.22em;color:var(--gold2)}.hero h1{font-size:clamp(72px,8vw,118px);line-height:.94;letter-spacing:-.07em;margin:28px 0 0;font-weight:570}.hero h2{font-size:clamp(30px,4vw,48px);line-height:1.15;letter-spacing:-.045em;color:#9d998f;margin:20px 0 0;font-weight:480}.hero h2 span{color:#c9b88f}.hero-copy>p{max-width:700px;margin:28px auto 0;color:#aaa79f;font-size:18px;line-height:1.75}.hero-actions{justify-content:center;margin-top:31px}.terminal-wrap{max-width:1460px;margin:76px auto 0}.terminal{border:1px solid rgba(255,255,255,.12);border-radius:16px 16px 9px 9px;background:#09090a;overflow:hidden;box-shadow:0 45px 120px rgba(0,0,0,.62)}.terminal-top{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 17px;background:#0d0d0e;border-bottom:1px solid rgba(255,255,255,.06);font-size:9px;letter-spacing:.13em;color:#66635d}.dots{color:#474747;letter-spacing:.3em}.live{color:#777}.live:first-letter{color:var(--green)}.terminal-grid{display:grid;grid-template-columns:56px 1fr 3fr 1.05fr;min-height:610px;position:relative}.rail{border-right:1px solid rgba(255,255,255,.06);padding:12px 8px;display:flex;flex-direction:column;gap:10px}.rail i{height:31px;display:grid;place-items:center;color:#666;font-style:normal}.rail i:first-child{color:var(--gold2);border:1px solid rgba(201,171,112,.16);background:rgba(201,171,112,.07);border-radius:8px}.watch{padding:15px;border-right:1px solid rgba(255,255,255,.06)}.watch-title{display:flex;justify-content:space-between;color:#bbb7ad;font-size:12px;margin:4px 2px 13px}.watch-title small{font-size:9px;color:#5f5c55}.asset{display:grid;grid-template-columns:1fr auto;gap:4px 12px;padding:11px 8px;border-radius:7px;margin-bottom:3px;font-size:11px;position:relative}.asset.active{background:#151516;box-shadow:0 0 0 1px rgba(255,255,255,.05) inset}.asset small{color:#66635d;font-size:9px}.asset em{font-size:10px;font-style:normal}.demo-click:after{content:"";position:absolute;inset:-2px;border:1px solid rgba(225,196,136,.38);border-radius:8px;animation:clickPulse .55s ease}.chart-pane{padding:19px 21px;position:relative}.chart-head{display:flex;justify-content:space-between}.chart-head small{display:block;color:#5f5c56;font-size:9px;margin-top:4px}.quote{text-align:right}.quote strong{font-size:27px;font-weight:520;letter-spacing:-.03em}.price-change{display:block;font-size:10px;margin-top:3px}.range{display:flex;gap:15px;margin:24px 0 8px}.range button{border:0;background:none;color:#55524c;font-size:9px;padding:4px 0;cursor:pointer;position:relative}.range button.active{color:#cfcabf}.range button.active:after{content:"";position:absolute;left:0;right:0;bottom:0;height:1px;background:var(--gold2)}.chart-svg{width:100%;height:370px}.gridlines line{stroke:rgba(255,255,255,.045)}.main-line{fill:none;stroke:#d6b97f;stroke-width:2.2;filter:drop-shadow(0 0 6px rgba(214,185,127,.18));transition:d .3s ease}.side{border-left:1px solid rgba(255,255,255,.06);padding:18px;background:#0b0b0c}.side>small{font-size:9px;color:#5e5b55;letter-spacing:.12em}.signal{margin-top:14px;padding:14px;border:1px solid rgba(201,171,112,.14);border-radius:9px;background:linear-gradient(145deg,rgba(201,171,112,.08),rgba(255,255,255,.018))}.signal b{font-size:11px;color:#d8c293}.signal p{font-size:9px;color:#77736b;line-height:1.55}.demo-cursor{position:absolute;left:0;top:0;width:18px;height:18px;border:1px solid rgba(236,211,156,.95);border-radius:50%;background:rgba(10,10,10,.55);z-index:12;opacity:0;translate:0 0;transition:translate .72s cubic-bezier(.22,1,.36,1),opacity .2s}.demo-cursor:after{content:"";position:absolute;width:4px;height:4px;border-radius:50%;background:#f0d89f;left:50%;top:50%;translate:-50% -50%;box-shadow:0 0 12px rgba(240,216,159,.7)}@keyframes clickPulse{from{opacity:1;scale:.99}to{opacity:0;scale:1.04}}.pulse-section{height:116vh;min-height:820px;background:#000}.pulse-sticky{height:100vh;min-height:700px;position:sticky;top:0;overflow:hidden}.metal-stage{position:absolute;inset:0;animation:camera 13s ease-in-out infinite alternate}.candle{position:absolute;left:var(--x);top:50%;width:clamp(54px,6vw,102px);height:calc(var(--body) + var(--top) + var(--bottom));translate:-50% -50%;animation:float 7.5s ease-in-out var(--delay) infinite alternate;filter:drop-shadow(0 28px 42px rgba(0,0,0,.5))}.candle b{position:absolute;left:0;top:var(--top);width:100%;height:var(--body);border-radius:5px;border:1px solid rgba(255,255,255,.22);background:linear-gradient(90deg,#070707,#242424 15%,#969696 30%,#f2f2f2 44%,#575757 58%,#d0d0d0 70%,#171717 88%,#050505);background-size:220% 100%;animation:sheen 10s linear var(--delay) infinite alternate}.candle i,.candle em{position:absolute;left:50%;translate:-50% 0;width:9px;border-radius:3px;border:1px solid rgba(255,255,255,.22);background:linear-gradient(90deg,#111,#e4e4e4 45%,#444 70%,#0b0b0b)}.candle i{top:0;height:var(--top)}.candle em{top:calc(var(--top) + var(--body));height:var(--bottom)}@keyframes float{from{transform:translateY(-14px)}to{transform:translateY(16px)}}@keyframes sheen{from{background-position:0 0}to{background-position:100% 0}}@keyframes camera{from{scale:1;translate:-1% 0}to{scale:1.04;translate:1% -1%}}.pulse-copy{position:absolute;left:clamp(24px,4.5vw,80px);bottom:clamp(28px,5vw,68px);z-index:3;max-width:520px;text-shadow:0 2px 22px #000}.pulse-copy h3{font-size:clamp(34px,4.4vw,64px);margin:12px 0 0;letter-spacing:-.05em;font-weight:520}.pulse-copy p{color:#8d8980;font-size:12px;line-height:1.65}.section{padding:145px 26px}.muted-section{background:#090909;border-block:1px solid rgba(255,255,255,.05)}.section-inner{max-width:1450px;margin:auto}.section-head{max-width:950px;margin-bottom:68px}.section-head h3,.cta h3{font-size:clamp(46px,6vw,92px);line-height:.99;letter-spacing:-.055em;margin:18px 0 0;font-weight:550}.section-head>p,.cta>p{max-width:720px;color:#949087;font-size:17px;line-height:1.75}.showcase{display:grid;grid-template-columns:.8fr 1.7fr;gap:70px;align-items:center}.copy h4{font-size:22px}.copy p,.copy li{color:#918d85;line-height:1.7}.copy ul{padding-left:18px}.ai-card,.backtest{border:1px solid var(--line);border-radius:14px;background:#0d0d0e;padding:28px}.ai-head{display:flex;justify-content:space-between;margin-bottom:22px}.ai-head span{font-size:11px;color:#7c786f}.factor{display:grid;grid-template-columns:1fr auto;gap:8px;margin:18px 0;color:#97938a;font-size:12px}.factor em{font-style:normal}.factor i{grid-column:1/3;height:4px;background:linear-gradient(90deg,var(--gold),var(--gold2));width:var(--w);border-radius:999px}.thesis{border-top:1px solid var(--line);margin-top:28px;padding-top:22px;color:#aaa69c}.thesis small{color:#706d65;letter-spacing:.13em}.market-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}.market-grid article{border:1px solid var(--line);border-radius:12px;background:#0d0d0e;padding:28px}.market-grid small{color:#8b7853;letter-spacing:.1em}.market-grid b{display:block;font-size:38px;font-weight:500;margin-top:20px}.market-grid em{font-style:normal;font-size:12px}.market-grid svg{width:100%;height:85px;margin-top:32px}.market-grid path,.eq-line{fill:none;stroke:#b79657;stroke-width:2}.backtest>svg{width:100%;height:390px}.eq-title{display:flex;justify-content:space-between}.eq-title span{color:#6c6962;font-size:11px}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.stats div{border:1px solid var(--line);padding:18px;border-radius:10px}.stats small{color:#80735c}.stats b{display:block;font-size:25px;margin-top:13px}.workspace-section{padding:145px 26px;background:#080808}.network{height:600px;position:relative;border:1px solid var(--line);border-radius:18px;background:radial-gradient(circle at 50% 50%,rgba(201,171,112,.055),transparent 35%),#0b0b0c;overflow:hidden}.network-lines{position:absolute;inset:0;width:100%;height:100%;z-index:0}.network-lines path{fill:none;stroke:rgba(201,171,112,.28);stroke-width:1}.node{position:absolute;z-index:2;width:270px;border:1px solid var(--line);border-radius:11px;background:#0d0d0e;padding:18px;box-shadow:0 22px 55px rgba(0,0,0,.3)}.node small{color:#68655e;letter-spacing:.1em}.node b{display:block;font-size:20px;margin-top:10px}.node span{display:block;color:#736f67;font-size:10px;margin-top:8px}.market-node{left:9%;top:16%}.ai-node{right:8%;top:18%}.backtest-node{left:29%;bottom:13%}.center-node{left:50%;top:50%;translate:-50% -50%;width:220px;background:linear-gradient(145deg,rgba(201,171,112,.15),#101010);border-color:rgba(201,171,112,.22)}.cta{text-align:center;padding:150px 26px}.cta p{margin:24px auto 32px}footer{display:flex;justify-content:space-between;padding:28px clamp(22px,4vw,60px);border-top:1px solid var(--line);color:#6f6b63;font-size:11px}.reveal{opacity:0;transform:translateY(20px);transition:opacity .75s ease,transform .75s cubic-bezier(.22,1,.36,1)}.reveal.visible{opacity:1;transform:none}.pos{color:var(--green)!important}.neg{color:var(--red)!important}@media(max-width:1000px){.nav nav{display:none}.terminal-grid{grid-template-columns:50px 1fr 2.4fr}.side{display:none}.showcase{grid-template-columns:1fr;gap:34px}.network{height:540px}.market-grid{grid-template-columns:1fr 1fr}}@media(max-width:700px){.nav{height:62px;padding:0 14px}.nav-actions .pill:first-child{display:none}.hero{padding:118px 14px 70px;min-height:auto}.hero h1{font-size:clamp(58px,17vw,78px)}.hero h2{font-size:clamp(26px,8vw,36px)}.hero-copy>p{font-size:15px}.terminal-wrap{margin-top:50px}.terminal-grid{grid-template-columns:44px 1fr;min-height:510px}.watch,.side{display:none}.chart-pane{padding:15px}.chart-svg{height:320px}.pulse-section{height:105vh;min-height:700px}.candle{width:clamp(42px,11vw,70px)}.candle:nth-child(2),.candle:nth-child(6){display:none}.section,.workspace-section{padding:105px 16px}.section-head h3,.cta h3{font-size:clamp(42px,12vw,64px)}.market-grid{grid-template-columns:1fr}.backtest>svg{height:260px}.stats{grid-template-columns:1fr 1fr}.network{height:560px}.node{width:205px}.market-node{left:4%;top:8%}.ai-node{right:4%;top:31%}.backtest-node{left:6%;bottom:7%}.center-node{left:64%;width:165px}.pulse-copy p{display:none}}@media(prefers-reduced-motion:reduce){.reveal{opacity:1;transform:none;transition:none}.metal-stage,.candle,.candle b{animation:none!important}.demo-cursor{display:none}}
</style>
