<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['navigate'])

const assets = [
  { id: 'XAU', symbol: 'XAU', name: '伦敦金', market: 'GLOBAL', sector: 'METALS', price: '2,614.28', change: '+0.84%', direction: 'up', confidence: '82', risk: 'MEDIUM', summary: '避险资产保持高位震荡，美元与实际利率仍是主要驱动。' },
  { id: '518850', symbol: '518850', name: '黄金 ETF', market: 'CN · SSE', sector: 'COMMODITY', price: '6.382', change: '+0.31%', direction: 'up', confidence: '77', risk: 'LOW', summary: '境内黄金资产延续跟随国际金价，流动性与跟踪误差处于可观察区间。' },
  { id: 'NVDA', symbol: 'NVDA', name: 'NVIDIA', market: 'US · NASDAQ', sector: 'AI COMPUTE', price: '184.62', change: '+1.92%', direction: 'up', confidence: '88', risk: 'HIGH', summary: 'AI 算力需求仍是核心变量，关注估值敏感度、供给扩张与大型客户资本开支。' },
  { id: 'AAPL', symbol: 'AAPL', name: 'Apple', market: 'US · NASDAQ', sector: 'CONSUMER TECH', price: '231.08', change: '-0.18%', direction: 'down', confidence: '69', risk: 'MEDIUM', summary: '硬件周期稳定，服务业务与端侧 AI 渗透率决定中期预期差。' },
  { id: 'BTC', symbol: 'BTC', name: 'Bitcoin', market: 'CRYPTO', sector: 'DIGITAL ASSET', price: '116,482', change: '+2.26%', direction: 'up', confidence: '71', risk: 'HIGH', summary: '高波动资产继续受流动性与机构资金流影响，短期趋势与风险偏好相关性偏高。' },
  { id: 'MSFT', symbol: 'MSFT', name: 'Microsoft', market: 'US · NASDAQ', sector: 'CLOUD / AI', price: '512.34', change: '+0.46%', direction: 'up', confidence: '84', risk: 'MEDIUM', summary: '云业务与 AI 商业化共同支撑基本面，资本开支效率是后续关键验证点。' },
  { id: '600519', symbol: '600519', name: '贵州茅台', market: 'CN · SSE', sector: 'CONSUMER', price: '1,476.20', change: '-0.42%', direction: 'down', confidence: '73', risk: 'MEDIUM', summary: '高端消费需求与渠道库存仍需持续跟踪，现金流质量维持较强韧性。' },
  { id: 'ETH', symbol: 'ETH', name: 'Ethereum', market: 'CRYPTO', sector: 'SMART CONTRACT', price: '4,638', change: '+1.14%', direction: 'up', confidence: '66', risk: 'HIGH', summary: '链上活跃度、资金费率与生态应用增长共同决定风险收益结构。' },
  { id: 'TSLA', symbol: 'TSLA', name: 'Tesla', market: 'US · NASDAQ', sector: 'MOBILITY / AI', price: '398.76', change: '-1.07%', direction: 'down', confidence: '63', risk: 'HIGH', summary: '车辆业务与自动驾驶预期交织，价格弹性和交付质量使波动保持较高水平。' },
  { id: '000001', symbol: '000001', name: '平安银行', market: 'CN · SZSE', sector: 'FINANCIALS', price: '12.44', change: '+0.08%', direction: 'up', confidence: '72', risk: 'LOW', summary: '净息差、资产质量和零售业务修复是银行板块的主要观察指标。' },
  { id: 'META', symbol: 'META', name: 'Meta', market: 'US · NASDAQ', sector: 'PLATFORM / AI', price: '742.55', change: '+0.67%', direction: 'up', confidence: '81', risk: 'MEDIUM', summary: '广告现金流为 AI 基础设施投入提供支撑，关注算力投资的边际回报。' },
  { id: 'JD_GOLD', symbol: '积存金', name: '银行积存金', market: 'CN · OTC', sector: 'GOLD SAVING', price: '836.40', change: '+0.24%', direction: 'up', confidence: '74', risk: 'LOW', summary: '适合作为黄金价格映射观察项，需同时关注点差、交易时间和流动性约束。' },
  { id: 'AMD', symbol: 'AMD', name: 'AMD', market: 'US · NASDAQ', sector: 'SEMICONDUCTOR', price: '211.36', change: '+0.95%', direction: 'up', confidence: '76', risk: 'HIGH', summary: '数据中心产品份额与 AI 加速卡放量决定增长斜率，竞争格局仍然激烈。' },
  { id: '0700', symbol: '0700.HK', name: '腾讯控股', market: 'HK · HKEX', sector: 'INTERNET', price: '641.50', change: '+0.39%', direction: 'up', confidence: '79', risk: 'MEDIUM', summary: '游戏、广告与金融科技形成多元现金流，AI 投入进入产品化验证阶段。' },
  { id: 'SPY', symbol: 'SPY', name: 'S&P 500 ETF', market: 'US · NYSE', sector: 'INDEX', price: '681.22', change: '+0.22%', direction: 'up', confidence: '80', risk: 'MEDIUM', summary: '宽基指数保持高位，盈利扩散与利率预期将决定后续风险溢价。' },
]

const selectedId = ref(null)
const query = ref('')
const booting = ref(true)
const tiltX = ref(0)
const tiltY = ref(0)
let bootTimer = null

const selectedAsset = computed(() => assets.find((item) => item.id === selectedId.value) || null)
const normalizedQuery = computed(() => query.value.trim().toLowerCase())
const stageStyle = computed(() => ({
  '--tilt-x': `${tiltX.value}deg`,
  '--tilt-y': `${tiltY.value}deg`,
}))

function matchesQuery(asset) {
  if (!normalizedQuery.value) return true
  return [asset.symbol, asset.name, asset.market, asset.sector]
    .some((value) => value.toLowerCase().includes(normalizedQuery.value))
}

function cardStyle(index) {
  const col = index % 5
  const row = Math.floor(index / 5)
  const x = (col - 2) * 174
  const y = (row - 1) * 158
  const z = -Math.abs(col - 2) * 26 - Math.abs(row - 1) * 18
  const rotate = (2 - col) * 3.2
  return {
    '--x': `${x}px`,
    '--y': `${y}px`,
    '--z': `${z}px`,
    '--card-rotate': `${rotate}deg`,
    '--card-delay': `${index * 34}ms`,
  }
}

function selectAsset(asset) {
  selectedId.value = selectedId.value === asset.id ? null : asset.id
}

function clearSelection() {
  selectedId.value = null
}

function resetView() {
  query.value = ''
  selectedId.value = null
  tiltX.value = 0
  tiltY.value = 0
}

function onStagePointerMove(event) {
  if (window.matchMedia?.('(pointer: coarse)').matches) return
  const bounds = event.currentTarget.getBoundingClientRect()
  const x = (event.clientX - bounds.left) / bounds.width - 0.5
  const y = (event.clientY - bounds.top) / bounds.height - 0.5
  tiltY.value = x * 4.5
  tiltX.value = y * -3.4
}

function onStagePointerLeave() {
  tiltX.value = 0
  tiltY.value = 0
}

function skipBoot() {
  booting.value = false
}

onMounted(() => {
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  bootTimer = window.setTimeout(() => {
    booting.value = false
  }, reduced ? 120 : 1380)
})

onBeforeUnmount(() => {
  if (bootTimer) window.clearTimeout(bootTimer)
})
</script>

<template>
  <section class="analysis-os" aria-label="JARVIS 研究终端">
    <div v-if="booting" class="boot-layer" @click="skipBoot">
      <div class="boot-mark" aria-hidden="true">
        <span></span><span></span><span></span>
      </div>
      <p class="boot-kicker">JARVIS SYSTEM / RESEARCH ACCESS</p>
      <h1>ANALYSIS OS</h1>
      <div class="boot-line"><i></i></div>
      <p class="boot-state">WORKSPACE AUTHENTICATED · LOADING MARKET ARCHIVES</p>
      <button type="button" class="boot-skip" @click.stop="skipBoot">SKIP</button>
    </div>

    <header class="os-header">
      <div>
        <p class="eyebrow">JARVIS / ANALYSIS OS</p>
        <h1>市场研究档案终端</h1>
        <p class="os-subtitle">将行情、AI 研究、财报、产业链、风险与模拟交易组织成同一研究入口。</p>
      </div>
      <div class="system-strip" aria-label="系统状态">
        <span><i class="status-dot"></i>DATA LINK</span>
        <strong>ONLINE</strong>
        <span>SESSION</span>
        <strong>LOCAL</strong>
        <span>MODE</span>
        <strong>POC</strong>
      </div>
    </header>

    <div class="os-toolbar">
      <label class="terminal-search">
        <span>SEARCH</span>
        <input v-model="query" type="search" placeholder="代码 / 名称 / 市场 / 行业" autocomplete="off" />
      </label>
      <div class="toolbar-actions">
        <button type="button" @click="resetView">RESET ARRAY</button>
        <button type="button" @click="emit('navigate', '行情')">MARKET</button>
        <button type="button" @click="emit('navigate', '模拟盘')">SIM TRADE</button>
      </div>
    </div>

    <div class="os-workspace">
      <div
        class="archive-stage"
        :style="stageStyle"
        @pointermove="onStagePointerMove"
        @pointerleave="onStagePointerLeave"
      >
        <div class="stage-grid" aria-hidden="true"></div>
        <div class="stage-axis stage-axis--x" aria-hidden="true"></div>
        <div class="stage-axis stage-axis--y" aria-hidden="true"></div>

        <div class="archive-plane" :class="{ 'has-selection': selectedAsset }">
          <button
            v-for="(asset, index) in assets"
            :key="asset.id"
            type="button"
            class="asset-archive"
            :class="{
              selected: selectedId === asset.id,
              muted: selectedAsset && selectedId !== asset.id,
              'query-muted': !matchesQuery(asset),
            }"
            :style="cardStyle(index)"
            :aria-pressed="selectedId === asset.id"
            @click="selectAsset(asset)"
          >
            <span class="archive-glass" aria-hidden="true"></span>
            <span class="archive-corner archive-corner--a" aria-hidden="true"></span>
            <span class="archive-corner archive-corner--b" aria-hidden="true"></span>
            <span class="archive-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="archive-sector">{{ asset.sector }}</span>
            <strong>{{ asset.symbol }}</strong>
            <span class="archive-name">{{ asset.name }}</span>
            <span class="archive-market">{{ asset.market }}</span>
            <span class="archive-price">{{ asset.price }}</span>
            <span class="archive-change" :class="asset.direction">{{ asset.change }}</span>
            <span class="archive-bars" aria-hidden="true">
              <i v-for="n in 7" :key="n" :style="{ height: `${18 + ((index + n * 3) % 7) * 6}%` }"></i>
            </span>
            <span class="archive-scan" aria-hidden="true"></span>
          </button>
        </div>

        <div class="stage-caption stage-caption--top">
          <span>MARKET ARCHIVE ARRAY</span>
          <strong>15 OBJECTS</strong>
        </div>
        <div class="stage-caption stage-caption--bottom">
          <span>SELECT AN OBJECT TO DECRYPT RESEARCH CONTEXT</span>
          <strong>{{ selectedAsset ? 'OBJECT LOCKED' : 'ARRAY READY' }}</strong>
        </div>
      </div>

      <aside class="research-panel" :class="{ active: selectedAsset }">
        <template v-if="selectedAsset">
          <button type="button" class="panel-close" aria-label="关闭资产详情" @click="clearSelection">×</button>
          <div class="panel-scanline" aria-hidden="true"></div>
          <p class="panel-overline">OBJECT / {{ selectedAsset.market }}</p>
          <div class="panel-title-row">
            <div>
              <h2>{{ selectedAsset.symbol }}</h2>
              <p>{{ selectedAsset.name }}</p>
            </div>
            <span class="confidence-ring">{{ selectedAsset.confidence }}<small>%</small></span>
          </div>

          <div class="quote-row">
            <strong>{{ selectedAsset.price }}</strong>
            <span :class="selectedAsset.direction">{{ selectedAsset.change }}</span>
          </div>

          <div class="detail-grid">
            <div><span>SECTOR</span><strong>{{ selectedAsset.sector }}</strong></div>
            <div><span>RISK</span><strong>{{ selectedAsset.risk }}</strong></div>
            <div><span>AI CONF.</span><strong>{{ selectedAsset.confidence }}%</strong></div>
            <div><span>STATE</span><strong>TRACKING</strong></div>
          </div>

          <div class="decrypt-block">
            <p class="decrypt-label">AI RESEARCH SNAPSHOT</p>
            <p>{{ selectedAsset.summary }}</p>
          </div>

          <div class="signal-list">
            <div><span>TECHNICAL</span><i style="--score: 74%"></i><strong>74</strong></div>
            <div><span>FUNDAMENTAL</span><i style="--score: 81%"></i><strong>81</strong></div>
            <div><span>SENTIMENT</span><i style="--score: 68%"></i><strong>68</strong></div>
          </div>

          <div class="panel-actions">
            <button type="button" class="primary" @click="emit('navigate', '研究助手')">AI 深度研究</button>
            <button type="button" @click="emit('navigate', '行情')">打开行情</button>
            <button type="button" @click="emit('navigate', '财报解析')">财报解析</button>
            <button type="button" @click="emit('navigate', '产业链图谱')">产业链</button>
            <button type="button" @click="emit('navigate', '风险预警')">风险预警</button>
            <button type="button" @click="emit('navigate', '模拟盘')">模拟交易</button>
          </div>
        </template>

        <template v-else>
          <div class="panel-idle-mark" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
          <p class="panel-overline">RESEARCH WORKSPACE / READY</p>
          <h2 class="idle-title">选择一份市场档案</h2>
          <p class="idle-copy">从左侧阵列选择标的。终端会将该资产的行情、AI 摘要、技术分析、财报、产业链和风险入口集中到同一上下文。</p>
          <div class="idle-protocol">
            <span>01</span><p><strong>SELECT</strong> 选择标的与市场对象</p>
            <span>02</span><p><strong>DECRYPT</strong> 展开研究摘要与信号</p>
            <span>03</span><p><strong>ROUTE</strong> 进入现有业务工作区</p>
          </div>
          <p class="poc-note">当前为交互与信息架构 PoC。行情数字用于界面占位，不作为实时价格或投资依据。</p>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.analysis-os {
  --os-bg: #e9e9e5;
  --os-ink: #111313;
  --os-muted: #666b68;
  --os-line: rgba(20, 24, 22, .15);
  --os-accent: #df5d24;
  --os-positive: #278d64;
  --os-negative: #b9483d;
  position: relative;
  min-height: calc(100vh - 154px);
  margin-top: 8px;
  overflow: hidden;
  color: var(--os-ink);
  border: 1px solid rgba(255, 255, 255, .18);
  border-radius: 4px;
  background:
    radial-gradient(circle at 31% 24%, rgba(255,255,255,.94), transparent 31%),
    linear-gradient(135deg, #f2f2ee 0%, #e4e5e0 48%, #d7d9d4 100%);
  box-shadow: 0 18px 70px rgba(0, 0, 0, .2);
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
.analysis-os::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: .34;
  background-image:
    linear-gradient(rgba(18, 22, 20, .035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(18, 22, 20, .035) 1px, transparent 1px);
  background-size: 32px 32px;
}
.os-header,
.os-toolbar,
.os-workspace { position: relative; z-index: 1; }
.os-header {
  display: flex;
  justify-content: space-between;
  gap: 32px;
  padding: 24px 28px 18px;
  border-bottom: 1px solid var(--os-line);
}
.eyebrow,
.panel-overline,
.boot-kicker,
.decrypt-label {
  margin: 0 0 7px;
  font-size: 10px;
  letter-spacing: .18em;
  font-weight: 760;
  color: var(--os-muted);
}
.os-header h1 { margin: 0; font-size: clamp(24px, 2.2vw, 36px); letter-spacing: -.035em; font-weight: 640; }
.os-subtitle { margin: 7px 0 0; max-width: 670px; color: var(--os-muted); font-size: 12px; }
.system-strip {
  display: grid;
  grid-template-columns: auto auto;
  gap: 4px 12px;
  align-content: center;
  min-width: 220px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 9px;
  letter-spacing: .08em;
}
.system-strip span { color: #737875; }
.system-strip strong { font-weight: 700; text-align: right; }
.status-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; margin-right: 7px; background: var(--os-positive); box-shadow: 0 0 0 3px rgba(39,141,100,.12); }
.os-toolbar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 28px;
  border-bottom: 1px solid var(--os-line);
  background: rgba(255,255,255,.28);
  backdrop-filter: blur(10px);
}
.terminal-search { display: grid; grid-template-columns: auto minmax(180px, 340px); align-items: center; gap: 12px; }
.terminal-search span { font-family: ui-monospace, monospace; font-size: 9px; font-weight: 750; letter-spacing: .12em; }
.terminal-search input {
  width: min(38vw, 360px);
  border: 0;
  border-bottom: 1px solid rgba(17,19,19,.38);
  outline: 0;
  padding: 7px 2px;
  color: var(--os-ink);
  background: transparent;
  font: 11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.terminal-search input:focus { border-color: var(--os-accent); }
.toolbar-actions { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
.toolbar-actions button,
.panel-actions button,
.boot-skip {
  border: 1px solid rgba(17,19,19,.2);
  background: rgba(255,255,255,.38);
  color: var(--os-ink);
  padding: 7px 10px;
  font: 700 9px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: .07em;
  cursor: pointer;
  transition: background .18s ease, border-color .18s ease, transform .18s ease;
}
.toolbar-actions button:hover,
.panel-actions button:hover { background: rgba(255,255,255,.8); border-color: rgba(17,19,19,.5); transform: translateY(-1px); }
.os-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 350px;
  min-height: 620px;
}
.archive-stage {
  --tilt-x: 0deg;
  --tilt-y: 0deg;
  position: relative;
  min-width: 0;
  min-height: 620px;
  overflow: hidden;
  perspective: 1200px;
  border-right: 1px solid var(--os-line);
}
.stage-grid {
  position: absolute;
  inset: 9% 7%;
  transform: perspective(700px) rotateX(68deg) translateY(36%);
  transform-origin: center bottom;
  background-image:
    linear-gradient(rgba(15,18,17,.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(15,18,17,.12) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: linear-gradient(to top, black, transparent 78%);
  opacity: .4;
}
.stage-axis { position: absolute; background: rgba(16,18,17,.14); pointer-events: none; }
.stage-axis--x { left: 6%; right: 6%; top: 50%; height: 1px; }
.stage-axis--y { top: 8%; bottom: 8%; left: 50%; width: 1px; }
.archive-plane {
  position: absolute;
  inset: 0;
  transform-style: preserve-3d;
  transform: rotateX(var(--tilt-x)) rotateY(var(--tilt-y));
  transition: transform .18s ease-out;
}
.asset-archive {
  --x: 0px; --y: 0px; --z: 0px; --card-rotate: 0deg; --card-delay: 0ms;
  position: absolute;
  left: 50%;
  top: 50%;
  width: 146px;
  height: 126px;
  padding: 12px 12px 10px;
  overflow: hidden;
  text-align: left;
  border: 1px solid rgba(27,31,29,.24);
  border-radius: 3px;
  color: #171a18;
  background: linear-gradient(145deg, rgba(255,255,255,.58), rgba(233,236,231,.24));
  box-shadow: 0 14px 32px rgba(36,40,37,.11), inset 0 0 0 1px rgba(255,255,255,.32);
  backdrop-filter: blur(8px) saturate(.8);
  cursor: pointer;
  transform-style: preserve-3d;
  transform: translate(-50%, -50%) translate3d(var(--x), var(--y), var(--z)) rotateY(var(--card-rotate));
  transition:
    transform .62s cubic-bezier(.2,.76,.18,1),
    opacity .3s ease,
    filter .3s ease,
    border-color .25s ease,
    box-shadow .3s ease;
  transition-delay: var(--card-delay);
}
.asset-archive:hover { border-color: rgba(223,93,36,.62); box-shadow: 0 20px 46px rgba(36,40,37,.18), inset 0 0 0 1px rgba(255,255,255,.55); }
.asset-archive.selected {
  z-index: 9;
  transform: translate(-50%, -50%) translate3d(0, 0, 240px) rotateY(0deg) scale(1.24);
  border-color: rgba(223,93,36,.72);
  box-shadow: 0 30px 70px rgba(28,32,29,.27), 0 0 0 1px rgba(223,93,36,.16);
}
.asset-archive.muted { opacity: .24; filter: grayscale(.8) blur(.35px); }
.asset-archive.query-muted { opacity: .10; filter: grayscale(1); pointer-events: none; }
.archive-glass { position: absolute; inset: 0; pointer-events: none; background: linear-gradient(120deg, rgba(255,255,255,.52), transparent 34%, rgba(255,255,255,.18) 66%, transparent); transform: translateZ(3px); }
.archive-corner { position: absolute; width: 12px; height: 12px; border-color: var(--os-accent); opacity: .75; }
.archive-corner--a { left: 7px; top: 7px; border-left: 1px solid; border-top: 1px solid; }
.archive-corner--b { right: 7px; bottom: 7px; border-right: 1px solid; border-bottom: 1px solid; }
.archive-index { position: absolute; top: 9px; right: 10px; font: 600 8px ui-monospace, monospace; color: #858a87; }
.archive-sector { display: block; max-width: 98px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font: 700 7px ui-monospace, monospace; letter-spacing: .08em; color: #717673; }
.asset-archive strong { display: block; margin-top: 7px; font: 720 22px/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: -.06em; }
.archive-name { display: block; margin-top: 4px; font-size: 9px; font-weight: 650; }
.archive-market { display: block; margin-top: 2px; color: #777c79; font: 7px ui-monospace, monospace; }
.archive-price { position: absolute; left: 12px; bottom: 12px; font: 700 9px ui-monospace, monospace; }
.archive-change { position: absolute; right: 10px; bottom: 12px; font: 700 8px ui-monospace, monospace; }
.up { color: var(--os-positive) !important; }
.down { color: var(--os-negative) !important; }
.archive-bars { position: absolute; right: 10px; top: 39px; width: 38px; height: 30px; display: flex; align-items: end; gap: 2px; opacity: .38; }
.archive-bars i { flex: 1; min-height: 3px; background: #535957; }
.archive-scan { position: absolute; left: 0; right: 0; top: -18%; height: 16%; opacity: 0; background: linear-gradient(to bottom, transparent, rgba(223,93,36,.24), transparent); }
.asset-archive.selected .archive-scan { opacity: 1; animation: scan-card 1.1s ease-in-out .22s 1 both; }
.stage-caption { position: absolute; left: 18px; right: 18px; display: flex; justify-content: space-between; gap: 12px; color: #777c79; font: 8px ui-monospace, monospace; letter-spacing: .08em; pointer-events: none; }
.stage-caption strong { color: #252927; }
.stage-caption--top { top: 14px; }
.stage-caption--bottom { bottom: 14px; }
.research-panel {
  position: relative;
  padding: 24px 22px 22px;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(248,248,244,.72), rgba(224,226,220,.82));
}
.research-panel::before { content: ""; position: absolute; inset: 0; pointer-events: none; background: linear-gradient(90deg, rgba(16,19,18,.035) 1px, transparent 1px); background-size: 12px 100%; }
.panel-close { position: absolute; right: 15px; top: 13px; z-index: 4; width: 28px; height: 28px; border: 0; background: transparent; color: #676c69; font-size: 22px; cursor: pointer; }
.panel-scanline { position: absolute; left: 0; right: 0; height: 34px; top: -40px; background: linear-gradient(transparent, rgba(223,93,36,.11), transparent); pointer-events: none; }
.research-panel.active .panel-scanline { animation: scan-panel 1.15s ease-out .12s 1 both; }
.panel-title-row { display: flex; justify-content: space-between; align-items: start; gap: 18px; padding-bottom: 16px; border-bottom: 1px solid var(--os-line); }
.panel-title-row h2 { margin: 0; font: 750 34px/1 ui-monospace, monospace; letter-spacing: -.07em; }
.panel-title-row p { margin: 6px 0 0; color: var(--os-muted); font-size: 12px; }
.confidence-ring { display: grid; place-items: center; width: 58px; height: 58px; border: 1px solid rgba(223,93,36,.48); border-radius: 50%; font: 700 17px ui-monospace, monospace; box-shadow: inset 0 0 0 5px rgba(223,93,36,.05); }
.confidence-ring small { font-size: 7px; margin-top: -15px; }
.quote-row { display: flex; align-items: baseline; justify-content: space-between; padding: 16px 0; border-bottom: 1px solid var(--os-line); }
.quote-row strong { font: 650 25px ui-monospace, monospace; }
.quote-row span { font: 700 10px ui-monospace, monospace; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; border-bottom: 1px solid var(--os-line); }
.detail-grid div { display: grid; gap: 4px; padding: 11px 0; }
.detail-grid div:nth-child(odd) { border-right: 1px solid var(--os-line); padding-right: 10px; }
.detail-grid div:nth-child(even) { padding-left: 10px; }
.detail-grid span { color: var(--os-muted); font: 7px ui-monospace, monospace; letter-spacing: .1em; }
.detail-grid strong { font: 700 9px ui-monospace, monospace; }
.decrypt-block { margin: 16px 0 14px; padding: 13px; border-left: 2px solid var(--os-accent); background: rgba(255,255,255,.34); }
.decrypt-block p:last-child { margin: 0; font-size: 11px; line-height: 1.7; color: #454a47; }
.signal-list { display: grid; gap: 8px; margin: 15px 0 18px; }
.signal-list div { display: grid; grid-template-columns: 82px 1fr 24px; align-items: center; gap: 9px; font: 7px ui-monospace, monospace; color: var(--os-muted); }
.signal-list i { position: relative; height: 2px; background: rgba(15,18,17,.12); }
.signal-list i::after { content: ""; position: absolute; inset: 0 auto 0 0; width: var(--score); background: var(--os-accent); }
.signal-list strong { color: var(--os-ink); text-align: right; }
.panel-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.panel-actions .primary { grid-column: 1 / -1; color: white; border-color: #161918; background: #161918; }
.panel-actions .primary:hover { color: white; background: #2a2e2c; }
.panel-idle-mark { position: relative; width: 94px; height: 94px; margin: 48px auto 34px; }
.panel-idle-mark span { position: absolute; inset: 0; border: 1px solid rgba(30,34,32,.24); border-radius: 50%; }
.panel-idle-mark span:nth-child(2) { inset: 15px; border-color: rgba(223,93,36,.4); }
.panel-idle-mark span:nth-child(3) { inset: 32px; background: var(--os-accent); border: 0; box-shadow: 0 0 0 8px rgba(223,93,36,.08); }
.idle-title { margin: 0 0 12px; font-size: 25px; letter-spacing: -.04em; }
.idle-copy { margin: 0; color: var(--os-muted); font-size: 11px; line-height: 1.75; }
.idle-protocol { display: grid; grid-template-columns: 28px 1fr; gap: 0 10px; margin-top: 28px; border-top: 1px solid var(--os-line); }
.idle-protocol > span { padding-top: 11px; color: var(--os-accent); font: 700 8px ui-monospace, monospace; }
.idle-protocol p { margin: 0; padding: 10px 0; border-bottom: 1px solid var(--os-line); color: var(--os-muted); font-size: 9px; }
.idle-protocol strong { color: var(--os-ink); margin-right: 7px; font: 700 8px ui-monospace, monospace; }
.poc-note { margin: 22px 0 0; color: #858a87; font-size: 8px; line-height: 1.6; }
.boot-layer {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: grid;
  place-content: center;
  justify-items: center;
  color: #171a18;
  background: #f4f4f0;
  cursor: pointer;
  animation: boot-out .48s ease 1.05s forwards;
}
.boot-mark { position: relative; width: 78px; height: 78px; margin-bottom: 24px; }
.boot-mark span { position: absolute; inset: 0; border: 1px solid rgba(24,28,26,.24); border-radius: 50%; animation: boot-ring .8s ease both; }
.boot-mark span:nth-child(2) { inset: 14px; border-color: rgba(223,93,36,.56); animation-delay: .12s; }
.boot-mark span:nth-child(3) { inset: 29px; border: 0; background: var(--os-accent); animation-delay: .2s; }
.boot-layer h1 { margin: 0; font: 700 clamp(38px,6vw,78px)/.95 ui-monospace, monospace; letter-spacing: -.08em; }
.boot-kicker { margin-bottom: 10px; }
.boot-line { width: min(480px, 60vw); height: 1px; margin: 24px 0 12px; background: rgba(22,25,24,.12); overflow: hidden; }
.boot-line i { display: block; height: 100%; width: 0; background: var(--os-accent); animation: boot-progress .86s cubic-bezier(.3,.8,.3,1) .1s forwards; }
.boot-state { margin: 0; color: #787d7a; font: 8px ui-monospace, monospace; letter-spacing: .11em; }
.boot-skip { position: absolute; right: 20px; bottom: 18px; }
@keyframes scan-card { from { transform: translateY(0); } to { transform: translateY(730%); } }
@keyframes scan-panel { from { transform: translateY(0); } to { transform: translateY(650px); } }
@keyframes boot-progress { to { width: 100%; } }
@keyframes boot-ring { from { transform: scale(.72); opacity: 0; } to { transform: scale(1); opacity: 1; } }
@keyframes boot-out { to { opacity: 0; visibility: hidden; } }
@media (max-width: 1180px) {
  .os-workspace { grid-template-columns: minmax(0, 1fr) 310px; }
  .asset-archive { width: 132px; }
}
@media (max-width: 980px) {
  .os-header { align-items: start; }
  .system-strip { min-width: 180px; }
  .os-workspace { grid-template-columns: 1fr; }
  .archive-stage { min-height: 560px; border-right: 0; border-bottom: 1px solid var(--os-line); }
  .research-panel { min-height: 360px; }
}
@media (max-width: 720px) {
  .analysis-os { min-height: auto; }
  .os-header { display: block; padding: 20px 18px 14px; }
  .system-strip { margin-top: 16px; grid-template-columns: auto 1fr auto 1fr auto 1fr; min-width: 0; }
  .system-strip strong { text-align: left; }
  .os-toolbar { align-items: stretch; flex-direction: column; padding: 10px 18px; }
  .terminal-search { grid-template-columns: 54px 1fr; }
  .terminal-search input { width: 100%; }
  .toolbar-actions { justify-content: flex-start; }
  .archive-stage { min-height: 680px; overflow-y: auto; perspective: none; padding: 50px 14px; }
  .stage-grid, .stage-axis { display: none; }
  .archive-plane { position: relative; inset: auto; display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 8px; transform: none !important; }
  .asset-archive,
  .asset-archive.selected,
  .asset-archive.muted {
    position: relative;
    left: auto;
    top: auto;
    width: 100%;
    height: 126px;
    opacity: 1;
    filter: none;
    transform: none;
    transition-delay: 0ms;
  }
  .asset-archive.muted { opacity: .38; }
  .asset-archive.query-muted { display: none; }
  .stage-caption--top { top: 17px; }
  .stage-caption--bottom { bottom: 15px; }
  .research-panel { padding: 22px 18px; }
}
@media (max-width: 430px) {
  .archive-plane { grid-template-columns: 1fr; }
  .archive-stage { min-height: 820px; }
  .system-strip { grid-template-columns: auto 1fr; }
  .panel-actions { grid-template-columns: 1fr; }
  .panel-actions .primary { grid-column: auto; }
}
@media (prefers-reduced-motion: reduce) {
  .asset-archive,
  .archive-plane,
  .toolbar-actions button,
  .panel-actions button { transition: none; }
  .asset-archive.selected .archive-scan,
  .research-panel.active .panel-scanline,
  .boot-mark span,
  .boot-line i,
  .boot-layer { animation: none; }
}
</style>
