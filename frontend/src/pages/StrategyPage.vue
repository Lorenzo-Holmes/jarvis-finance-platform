<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'
import MarkdownContent from '../components/common/MarkdownContent.vue'
import ChoicePopover from '../components/common/ChoicePopover.vue'

const emit = defineEmits(['send-backtest'])

// ---- 风险偏好问卷（FR-11）：全部为固定选项，保证问卷口径可复现 ----
const horizonYears = ref(3)
const maxDrawdownPct = ref(10)
const targetReturnPct = ref(5)
const experience = ref('basic')
const capital = ref(null) // 可选：资金规模，用于换算黄金 ETF 建议金额
const generating = ref(false)
const result = ref(null) // { profile, content }
const error = ref('')
const inputPanelOpen = ref(typeof window === 'undefined' || window.innerWidth > 1120)

const EXPERIENCE_LABELS = { none: '无经验', basic: '有一定经验', rich: '经验丰富' }
const HORIZON_OPTIONS = [
  { value: 1, label: '1 年以内' },
  { value: 3, label: '1 - 3 年' },
  { value: 5, label: '3 - 5 年' },
  { value: 10, label: '5 年以上' },
]
const DRAWDOWN_OPTIONS = [
  { value: 5, label: '5%（几乎不想亏）' },
  { value: 10, label: '10%（小幅波动可接受）' },
  { value: 20, label: '20%（能扛中等回撤）' },
  { value: 30, label: '30%（追求收益愿担风险）' },
]
const RETURN_OPTIONS = [
  { value: 3, label: '3%（稳健保值）' },
  { value: 5, label: '5%（略高于存款）' },
  { value: 8, label: '8%（平衡增值）' },
  { value: 12, label: '12%（追求成长）' },
]
const EXPERIENCE_OPTIONS = [
  { value: 'none', label: '无经验' },
  { value: 'basic', label: '有一定经验' },
  { value: 'rich', label: '经验丰富' },
]

// 等级 → 展示色带的映射（与风险预警页同一套语义）
const LEVEL_CLASS = { conservative: 'low', balanced: 'medium', aggressive: 'high' }
const LEVEL_LABEL = { conservative: '保守型', balanced: '稳健型', aggressive: '积极型' }

const levelClass = computed(() => LEVEL_CLASS[result.value?.profile?.level] || '')
const levelLabel = computed(
  () => result.value?.profile?.level_label || LEVEL_LABEL[result.value?.profile?.level] || '—',
)

const allocation = computed(() => result.value?.profile?.allocation || [])
const subScores = computed(() => result.value?.profile?.sub_scores || [])
const reasons = computed(() => result.value?.profile?.reasons || [])

async function generate() {
  if (generating.value) return
  generating.value = true
  error.value = ''
  result.value = null
  try {
    const response = await api.aiStrategy({
      horizon_years: Number(horizonYears.value),
      max_drawdown_pct: Number(maxDrawdownPct.value),
      target_return_pct: Number(targetReturnPct.value),
      experience: experience.value,
      capital: capital.value && Number(capital.value) > 0 ? Number(capital.value) : null,
    })
    if (response.code !== 200 || !response.data) throw new Error(response.message || '策略生成失败')
    if (response.data.available === false) {
      error.value = response.data.reason === 'invalid_questionnaire'
        ? '问卷数据不完整，请重新选择风险偏好'
        : '策略生成暂不可用'
      return
    }
    result.value = response.data
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    generating.value = false
  }
}

function clearAll() {
  result.value = null
  error.value = ''
}

function fmtPct(value) {
  if (value == null || value === '') return '—'
  return `${value}%`
}

function fmtMoney(value) {
  if (value == null || value === '') return '—'
  return `¥${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

function barWidth(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${Math.max(0, Math.min(numeric, 100))}%` : '0%'
}

function sendToBacktest() {
  if (!result.value?.profile) return
  emit('send-backtest', {
    source: 'strategy',
    questionnaire: {
      horizonYears: Number(horizonYears.value),
      maxDrawdownPct: Number(maxDrawdownPct.value),
      targetReturnPct: Number(targetReturnPct.value),
      experience: experience.value,
      capital: capital.value && Number(capital.value) > 0 ? Number(capital.value) : null,
    },
    profile: {
      level: result.value.profile.level,
      levelLabel: levelLabel.value,
      score: result.value.profile.score,
      allocation: allocation.value.map(item => ({ id: item.id, label: item.label, pct: item.pct })),
    },
  })
}
</script>

<template>
  <section class="sg-workspace">
    <div class="section-bar">
      <div>
        <h1>策略生成</h1>
        <span>目标 · 风险预算 · 约束 · 确定性配置 · AI 策略说明</span>
      </div>
      <div class="section-actions">
        <button
          type="button"
          class="panel-toggle"
          :class="{ active: inputPanelOpen }"
          :aria-expanded="inputPanelOpen"
          aria-controls="strategy-input-panel"
          @click="inputPanelOpen = !inputPanelOpen"
        >
          <i aria-hidden="true"></i>
          参数
        </button>
        <span class="section-status"><b>{{ generating ? '生成中' : '参数已完整' }}</b><em>{{ generating ? '正在计算风险画像与配置' : '4 / 4 必填项已完成' }}</em></span>
      </div>
    </div>

    <div class="sg-layout" :class="{ 'input-collapsed': !inputPanelOpen }">
      <aside id="strategy-input-panel" class="sg-input-panel" :class="{ open: inputPanelOpen }">
        <button type="button" class="input-panel-close" aria-label="收起策略参数" @click="inputPanelOpen = false">×</button>
        <div class="sg-panel-title">
          <b>风险预算输入</b>
          <small>4 项必填 · 1 项可选</small>
        </div>

        <div class="sg-field" data-index="01">
          <label>投资期限</label>
          <ChoicePopover v-model="horizonYears" :options="HORIZON_OPTIONS" aria-label="投资期限" />
          <span>期限越长，越能承受短期波动。</span>
        </div>

        <div class="sg-field" data-index="02">
          <label>可承受最大回撤</label>
          <ChoicePopover v-model="maxDrawdownPct" :options="DRAWDOWN_OPTIONS" aria-label="可承受最大回撤" />
          <span>账户浮亏到这个幅度时你仍能持有。</span>
        </div>

        <div class="sg-field" data-index="03">
          <label>目标年化收益</label>
          <ChoicePopover v-model="targetReturnPct" :options="RETURN_OPTIONS" aria-label="目标年化收益" />
          <span>收益目标越激进，配置中的权益比例越高。</span>
        </div>

        <div class="sg-field" data-index="04">
          <label>投资经验</label>
          <ChoicePopover v-model="experience" :options="EXPERIENCE_OPTIONS" aria-label="投资经验" />
          <span>用于校准建议的风险敞口。</span>
        </div>

        <div class="sg-field optional" data-index="05">
          <label>资金规模（可选）</label>
          <input
            v-model.number="capital"
            class="sg-input"
            type="number"
            min="0"
            aria-label="资金规模"
            placeholder="如 100000，用于换算配置金额"
          />
          <span>填写后给出黄金 ETF 的建议金额。</span>
        </div>

        <button class="btn primary sg-run" type="button" :disabled="generating" @click="generate">
          {{ generating ? '生成中…' : '生成策略' }}
        </button>
        <button v-if="result || error" type="button" class="text-action" @click="clearAll">清空结果</button>
        <div class="sg-note">风险等级与配置比例由确定性计算层按问卷计算，模型仅负责解读；建议为展示型参考，不构成投资建议。</div>
      </aside>

      <div class="sg-main">
        <template v-if="result">
          <div class="sg-banner" :class="levelClass">
            <span class="sg-banner-dot"></span>
            <div>
              <b>风险画像 · {{ levelLabel }}</b>
              <span>
                综合得分 {{ result.profile.score }} / 100 ·
                期限 {{ horizonYears }} 年 · 可承受回撤 {{ maxDrawdownPct }}% · 目标年化 {{ targetReturnPct }}%
              </span>
            </div>
          </div>

          <div class="panel sg-card">
            <div class="sg-card-head">
              <div><b>建议资产配置</b><span>合计 100% · 由问卷确定性映射，AI 不改写比例</span></div>
              <span v-if="result.profile.gold_amount != null" class="sg-amount">
                黄金 ETF 建议金额 {{ fmtMoney(result.profile.gold_amount) }}
              </span>
            </div>
            <div class="sg-alloc">
              <div v-for="item in allocation" :key="item.id" class="sg-alloc-row">
                <span class="sg-alloc-label">{{ item.label }}</span>
                <div class="sg-alloc-track">
                  <i :style="{ width: barWidth(item.pct) }" :class="{ gold: item.id === 'gold_etf' }"></i>
                </div>
                <b>{{ fmtPct(item.pct) }}</b>
              </div>
            </div>
          </div>

          <div class="sg-metrics">
            <div v-for="item in subScores" :key="item.id" class="sg-metric">
              <span>{{ item.label }}</span>
              <b>{{ item.score }}</b>
              <small>权重 {{ fmtPct(item.weight_pct) }}</small>
            </div>
          </div>

          <div v-if="reasons.length" class="sg-reasons">
            <div v-for="item in reasons" :key="item.id" class="sg-reason">
              <span>{{ item.text }}</span>
            </div>
          </div>

          <div class="panel sg-report-panel">
            <div class="sg-report-head">
              <div><b>策略说明</b><span>基于确定性等级与配置比例生成，数值口径以配置区为准</span></div>
            </div>
            <MarkdownContent class="sg-output" :content="result.content?.content || result.content || '（暂无策略说明）'" />
            <button type="button" class="sg-backtest-action" @click="sendToBacktest">
              发送到回测 <span>→</span>
            </button>
          </div>
        </template>

        <DataState
          v-else-if="generating"
          state="loading"
          title="正在生成个性化策略"
          message="根据问卷计算风险等级与配置比例，并生成策略说明，通常需要数十秒"
        />

        <DataState v-else-if="error" state="error" title="策略生成失败" :message="error" retryable @retry="generate" />

        <div v-else class="sg-blueprint" aria-label="策略蓝图">
          <header class="sg-blueprint-head">
            <div>
              <b>策略蓝图</b>
            </div>
            <small>输入持续生效 · 结果尚未生成</small>
          </header>

          <div class="sg-blueprint-flow" aria-hidden="true">
            <span class="active">01 问卷</span>
            <i>→</i>
            <span>02 风险画像</span>
            <i>→</i>
            <span>03 资产配置</span>
            <i>→</i>
            <span>04 策略说明</span>
            <i>→</i>
            <span>05 回测</span>
          </div>

          <section class="sg-blueprint-grid">
            <div class="sg-blueprint-section risk-budget wide">
              <header><b>风险预算</b></header>
              <div class="risk-axis primary-axis">
                <div class="risk-axis-scale"><i></i><i></i><i></i><i></i><i></i></div>
                <div class="risk-axis-marker" :style="{ left: `${Math.min(Math.max(maxDrawdownPct, 0), 30) / 30 * 100}%` }">
                  <span>{{ maxDrawdownPct }}%</span>
                </div>
              </div>
              <div class="risk-axis-meta"><span>0%</span><b>当前回撤预算 {{ maxDrawdownPct }}%</b><span>30%</span></div>
              <p>这里是整套策略的主约束轴。生成前仅表达用户已经确认的风险预算，不提前推断风险等级。</p>
            </div>

            <div class="sg-blueprint-section input-snapshot">
              <header><b>当前输入</b></header>
              <dl>
                <div><dt>投资期限</dt><dd>{{ horizonYears }} 年</dd></div>
                <div><dt>最大回撤</dt><dd>{{ maxDrawdownPct }}%</dd></div>
                <div><dt>目标年化</dt><dd>{{ targetReturnPct }}%</dd></div>
                <div><dt>投资经验</dt><dd>{{ EXPERIENCE_LABELS[experience] }}</dd></div>
                <div><dt>资金规模</dt><dd>{{ capital && Number(capital) > 0 ? fmtMoney(capital) : '未填写' }}</dd></div>
              </dl>
            </div>

            <div class="sg-blueprint-section allocation-pending">
              <header><b>待生成配置</b></header>
              <div class="pending-bars">
                <div><span>权益资产</span><i></i><em>待计算</em></div>
                <div><span>黄金 ETF</span><i></i><em>待计算</em></div>
                <div><span>低风险资产</span><i></i><em>待计算</em></div>
              </div>
            </div>

            <div class="sg-blueprint-section narrative-pending wide">
              <header><b>策略说明</b></header>
              <div class="pending-lines"><i></i><i></i><i></i><i></i></div>
              <p>生成后在此形成风险画像解释、资产配置逻辑、约束条件和失效边界。</p>
            </div>
          </section>

          <footer class="sg-blueprint-footer">
            <span>完成参数输入后，从左侧执行「生成策略」；系统才会计算风险等级与配置比例。</span>
            <em>等待生成</em>
          </footer>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.sg-workspace { display: flex; flex-direction: column; gap: 14px; margin: 0; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 48px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 21px; font-weight: 660; letter-spacing: -.018em; }
.section-bar > div:first-child > span { display: block; margin-top: 5px; color: var(--subtle); font-size: 11px; }
.section-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.panel-toggle { min-height: 31px; display: inline-flex; align-items: center; gap: 7px; padding: 0 10px; border: 1px solid var(--material-border, var(--line)); border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font: 600 10px/1 Inter, "MiSans", "PingFang SC", sans-serif; transition: color .16s ease, background .16s ease, border-color .16s ease, transform .1s ease; }
.panel-toggle > i { width: 10px; height: 10px; border: 1px solid currentColor; border-radius: 2px; box-shadow: inset 3px 0 0 color-mix(in srgb, currentColor 42%, transparent); opacity: .72; }
.panel-toggle:hover { color: var(--text); background: var(--workspace-hover-bg, rgba(255,255,255,.035)); }
.panel-toggle.active { color: var(--accent-strong); border-color: color-mix(in srgb, var(--accent) 38%, var(--material-border, var(--line))); background: color-mix(in srgb, var(--accent) 5%, transparent); }
.panel-toggle:active { transform: scale(.97); }
.panel-toggle:focus-visible { outline: 0; box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus, var(--accent)) 12%, transparent); }
.section-status { display: grid; justify-items: end; gap: 4px; color: var(--muted); }
.section-status b { color: var(--accent-strong); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.section-status em { color: var(--subtle); font-size: 10px; font-style: normal; }
.sg-layout { display: grid; grid-template-columns: 232px minmax(0, 1fr); gap: 16px; align-items: start; border: 0; transition: grid-template-columns .3s cubic-bezier(.22,1,.36,1), gap .24s ease; }
.sg-layout.input-collapsed { grid-template-columns: 0 minmax(0, 1fr); gap: 0; }
.sg-layout > .sg-input-panel { position: sticky; top: 8px; min-width: 0; padding: 14px; border: 1px solid var(--material-border, var(--line)) !important; border-radius: 12px !important; background: color-mix(in srgb, var(--material-glass, transparent) 58%, transparent) !important; box-shadow: inset 0 1px 0 rgba(255,255,255,.024), 0 16px 40px rgba(0,0,0,.06) !important; backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); opacity: 1; transform: translateX(0); transform-origin: left center; transition: opacity .18s ease, transform .28s cubic-bezier(.22,1,.36,1), padding .24s ease, border-color .18s ease; }
.sg-layout.input-collapsed > .sg-input-panel { padding-left: 0; padding-right: 0; border-color: transparent !important; box-shadow: none !important; opacity: 0; pointer-events: none; transform: translateX(-14px); overflow: hidden; }
.input-panel-close { display: none; position: absolute; top: 10px; right: 10px; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 7px; background: rgba(255,255,255,.025); color: var(--subtle); cursor: pointer; font-size: 17px; line-height: 1; transition: color .16s ease, background .16s ease, transform .1s ease; }
.input-panel-close:hover { color: var(--text); background: rgba(255,255,255,.05); }
.input-panel-close:active { transform: scale(.94); }
.sg-panel-title { display: grid; gap: 5px; color: var(--text); padding: 0 0 12px 12px; border-bottom: 1px solid color-mix(in srgb, var(--line) 58%, transparent); position: relative; }
.sg-panel-title::before { content: ''; position: absolute; left: 0; top: 1px; bottom: 12px; width: 1px; background: var(--accent); }
.sg-panel-title span { color: var(--subtle); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .08em; }
.sg-panel-title b { font-size: 13px; font-weight: 650; }
.sg-panel-title small { color: var(--subtle); font-size: 9px; font-weight: 500; }
.sg-field { position: relative; display: flex; flex-direction: column; gap: 5px; margin-top: 0; padding: 12px 0 11px 28px; border-bottom: 1px solid color-mix(in srgb, var(--line) 56%, transparent); }
.sg-field::before { content: attr(data-index); position: absolute; left: 0; top: 16px; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .04em; }
.sg-field.optional::before { color: var(--accent-strong); }
.sg-field label { color: var(--muted); font-size: 11px; }
.sg-field > span { color: var(--subtle); font-size: 10px; line-height: 1.55; }
.sg-input { width: 100%; height: 36px; background: rgba(255,255,255,.018); border: 1px solid transparent; border-bottom-color: color-mix(in srgb, var(--line-strong) 76%, transparent); border-radius: 8px 8px 4px 4px; color: var(--text); padding: 0 9px; font-size: 12px; outline: none; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.sg-input:focus { border-color: var(--workspace-focus); }
.sg-input:focus { background: rgba(255,255,255,.03); box-shadow: 0 0 0 3px color-mix(in srgb, var(--workspace-focus) 8%, transparent); }
.sg-run { width: 100%; min-height: 36px; margin-top: 14px; }
.sg-input-panel .text-action { margin-top: 8px; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.sg-input-panel .text-action:hover { color: var(--text); }
.sg-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.sg-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; min-height: 620px; }
.sg-banner { display: flex; align-items: center; gap: 10px; padding: 13px 15px; border-radius: 10px; border: 1px solid var(--material-border, var(--line)); background: rgba(255,255,255,.018); }
.sg-banner .sg-banner-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--workspace-neutral-dot); flex: 0 0 auto; }
.sg-banner.low .sg-banner-dot { background: var(--ok); }
.sg-banner.medium { border-color: rgba(227,180,102,.4); background: transparent; }
.sg-banner.medium .sg-banner-dot { background: #e3b466; }
.sg-banner.high { border-color: rgba(239,83,80,.45); background: transparent; }
.sg-banner.high .sg-banner-dot { background: #ef5350; }
.sg-banner b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-banner span { display: block; margin-top: 2px; color: var(--subtle); font-size: 9px; }
.sg-card { display: flex; flex-direction: column; gap: 12px; padding: 15px 16px; background: rgba(255,255,255,.016) !important; border: 1px solid var(--material-border, var(--line)) !important; box-shadow: inset 0 1px 0 rgba(255,255,255,.018) !important; border-radius: 11px !important; }
.sg-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.sg-card-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-card-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.sg-card-head .sg-amount { margin-top: 0; color: var(--accent); font-size: 11px; }
.sg-alloc { display: flex; flex-direction: column; gap: 9px; }
.sg-alloc-row { display: grid; grid-template-columns: 84px minmax(0, 1fr) 62px; align-items: center; gap: 10px; }
.sg-alloc-label { color: var(--muted); font-size: 10px; }
.sg-alloc-track { position: relative; height: 7px; border-radius: 999px; background: var(--workspace-track); border: 0; overflow: hidden; }
.sg-alloc-track i { display: block; height: 100%; border-radius: 999px; background: var(--workspace-track-fill); transition: width .45s cubic-bezier(.22,1,.36,1); }
.sg-alloc-track i.gold { background: var(--accent); }
.sg-alloc-row b { color: var(--text); font-size: 11px; font-weight: 650; text-align: right; font-variant-numeric: tabular-nums; }
.sg-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
.sg-metric { background: rgba(255,255,255,.018); border: 1px solid var(--material-border, var(--line)); border-radius: 9px; padding: 11px 12px; display: flex; flex-direction: column; gap: 5px; }
.sg-metric span { color: var(--subtle); font-size: 9px; }
.sg-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.sg-metric small { color: var(--muted); font-size: 8px; }
.sg-reasons { display: flex; flex-direction: column; gap: 6px; }
.sg-reason { border-left: 2px solid color-mix(in srgb, var(--accent) 72%, transparent); background: rgba(255,255,255,.014); border-radius: 0 8px 8px 0; padding: 9px 11px; }
.sg-reason span { color: var(--muted); font-size: 10px; line-height: 1.55; }
.sg-report-panel { display: flex; flex-direction: column; background: transparent !important; border: 0 !important; border-top: 1px solid var(--line) !important; box-shadow: none !important; border-radius: 0 !important; }
.sg-report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.sg-report-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-report-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.sg-output { margin-top: 10px; max-height: 420px; overflow: auto; background: transparent; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); border-radius: 0; padding: 14px 2px; color: var(--text); font-size: 11px; line-height: 1.78; overflow-wrap: anywhere; }
.sg-backtest-action { margin-top: 14px; min-height: 38px; padding: 0 14px; border: 1px solid color-mix(in srgb, var(--accent) 64%, var(--line)); border-radius: 9px; background: rgba(255,255,255,.018); color: var(--accent-strong); cursor: pointer; font: 650 10px/1 Inter, "MiSans", "PingFang SC", sans-serif; letter-spacing: 0; box-shadow: none; transition: background .16s ease, transform .1s ease, border-color .16s ease; }
.sg-backtest-action:hover { background: rgba(255,255,255,.04); border-color: var(--accent); }
.sg-backtest-action:active { transform: scale(.98); }
.sg-backtest-action span { margin-left: 30px; font-size: 15px; vertical-align: -1px; }
.sg-blueprint { min-height: 620px; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 10px; background: transparent; }
.sg-blueprint-head { min-height: 62px; display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 10px 4px 6px; }
.sg-blueprint-head > div { display: grid; gap: 6px; }
.sg-blueprint-head span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.sg-blueprint-head b { color: var(--text); font-size: 20px; font-weight: 650; letter-spacing: -.015em; }
.sg-blueprint-head small { color: var(--subtle); font-size: 10px; }
.sg-blueprint-flow { min-height: 38px; display: flex; align-items: center; gap: 11px; padding: 0 4px; color: var(--subtle); font: 560 10px/1 Inter, "MiSans", "PingFang SC", sans-serif; overflow-x: auto; }
.sg-blueprint-flow span { position: relative; white-space: nowrap; }
.sg-blueprint-flow span.active { color: var(--accent-strong); }
.sg-blueprint-flow span.active::before { content: ''; position: absolute; left: 0; right: 0; bottom: -8px; height: 2px; border-radius: 999px; background: var(--accent); }
.sg-blueprint-flow i { color: var(--line-strong); font-style: normal; }
.sg-blueprint-grid { min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); grid-auto-rows: minmax(180px, auto); gap: 10px; }
.sg-blueprint-section { min-width: 0; padding: 18px; border: 1px solid color-mix(in srgb, var(--material-border, var(--line)) 82%, transparent); border-radius: 12px; background: color-mix(in srgb, var(--material-glass, transparent) 32%, transparent); box-shadow: inset 0 1px 0 rgba(255,255,255,.018); transition: border-color .18s ease, background .18s ease, transform .14s ease, box-shadow .18s ease; }
.sg-blueprint-section:hover { border-color: color-mix(in srgb, var(--text) 12%, var(--material-border, var(--line))); background: color-mix(in srgb, var(--material-glass, transparent) 40%, transparent); transform: translateY(-1px); box-shadow: inset 0 1px 0 rgba(255,255,255,.026), 0 12px 28px rgba(0,0,0,.07); }
.sg-blueprint-section.wide { grid-column: 1 / -1; }
.sg-blueprint-section header { display: grid; gap: 5px; padding-bottom: 8px; }
.sg-blueprint-section header span { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.sg-blueprint-section header b { color: var(--text); font-size: 12px; font-weight: 650; }
.input-snapshot dl { margin: 0; }
.input-snapshot dl > div { min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid color-mix(in srgb, var(--line) 50%, transparent); }
.input-snapshot dt { color: var(--muted); font-size: 10px; }
.input-snapshot dd { margin: 0; color: var(--text); font: 600 10px/1 ui-monospace, monospace; text-align: right; }
.risk-budget { position: relative; }
.risk-axis { position: relative; height: 62px; margin-top: 18px; border-top: 1px solid color-mix(in srgb, var(--line-strong) 62%, transparent); border-bottom: 0; }
.primary-axis { height: 82px; margin-top: 20px; }
.risk-axis-scale { position: absolute; inset: 0; display: grid; grid-template-columns: repeat(5, 1fr); }
.risk-axis-scale i { border-left: 1px solid color-mix(in srgb, var(--line) 48%, transparent); }
.risk-axis-scale i:last-child { border-right: 1px solid color-mix(in srgb, var(--line) 48%, transparent); }
.risk-axis-marker { position: absolute; top: -7px; bottom: -7px; width: 1px; background: var(--accent); transform: translateX(-50%); box-shadow: none; }
.risk-axis-marker span { position: absolute; top: 8px; left: 8px; color: var(--accent-strong); font: 650 8px/1 ui-monospace, monospace; white-space: nowrap; }
.risk-axis-meta { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; margin-top: 8px; color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.risk-axis-meta b { color: var(--accent-strong); font-weight: 650; text-align: center; }
.risk-axis-meta span:last-child { text-align: right; }
.risk-budget p, .narrative-pending p { margin: 13px 0 0; color: var(--subtle); font-size: 10px; line-height: 1.68; }
.pending-bars { display: grid; gap: 13px; margin-top: 16px; }
.pending-bars > div { display: grid; grid-template-columns: 86px minmax(0, 1fr) 52px; align-items: center; gap: 10px; }
.pending-bars span { color: var(--muted); font-size: 10px; }
.pending-bars i { position: relative; height: 5px; background: var(--workspace-track); overflow: hidden; }
.pending-bars i::after { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 22%; background: repeating-linear-gradient(90deg, var(--line-strong) 0 1px, transparent 1px 7px); opacity: .55; }
.pending-bars em { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; font-style: normal; text-align: right; }
.pending-lines { display: grid; gap: 11px; margin-top: 18px; }
.pending-lines i { height: 1px; background: linear-gradient(90deg, var(--line-strong), var(--line) 68%, transparent); }
.pending-lines i:nth-child(2) { width: 88%; }
.pending-lines i:nth-child(3) { width: 72%; }
.pending-lines i:nth-child(4) { width: 81%; }
.sg-blueprint-footer { min-height: 44px; display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 0 4px; color: var(--subtle); font-size: 10px; }
.sg-blueprint-footer em { color: var(--subtle); font-size: 8px; font-style: normal; }
@media (max-width: 1120px) {
  .sg-layout,
  .sg-layout.input-collapsed { position: relative; grid-template-columns: minmax(0, 1fr); gap: 0; }
  .sg-layout > .sg-input-panel { position: absolute; z-index: 24; top: 8px; left: 8px; width: min(292px, calc(100% - 16px)); max-height: calc(100% - 16px); overflow: auto; transform: translateX(0); }
  .sg-layout.input-collapsed > .sg-input-panel { padding: 14px; border-color: var(--material-border, var(--line)) !important; opacity: 0; transform: translateX(calc(-100% - 20px)); }
  .input-panel-close { display: grid; }
  .sg-panel-title { padding-right: 34px; }
}
@media (max-width: 900px) { .sg-blueprint-grid { grid-template-columns: 1fr; } .sg-blueprint-section { border-right: 0; } }
@media (max-width: 760px) {
  .section-bar { align-items: flex-start; }
  .section-actions { gap: 8px; }
  .section-status { display: none; }
  .sg-layout,
  .sg-layout.input-collapsed { grid-template-columns: 1fr; }
  .sg-layout > .sg-input-panel { position: fixed; z-index: 95; left: 12px; right: 12px; top: auto; bottom: 12px; width: auto; max-height: min(74vh, 640px); transform: translateY(0); }
  .sg-layout.input-collapsed > .sg-input-panel { opacity: 0; transform: translateY(calc(100% + 28px)); }
  .sg-main { min-height: 0; }
  .sg-blueprint { min-height: 0; }
  .sg-blueprint-footer { align-items: flex-start; flex-direction: column; padding-top: 12px; padding-bottom: 12px; }
}
</style>
