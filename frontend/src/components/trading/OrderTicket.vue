<script setup>
import { formatNumber, formatPercent } from '../../utils/formatters'

const props = defineProps({
  symbol: { type: String, required: true },
  type: { type: String, required: true },
  quantity: { type: Number, required: true },
  leverage: { type: Number, required: true },
  selectedPrice: { type: Number, default: 0 },
  estimatedNotional: { type: Number, default: 0 },
  estimatedMargin: { type: Number, default: 0 },
  ready: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  message: { type: String, default: '' },
  messageType: { type: String, default: 'info' },
})

const emit = defineEmits([
  'update:symbol', 'update:type', 'update:quantity', 'update:leverage', 'submit',
])

const fmt = value => formatNumber(value)
const fmtPct = value => formatPercent(value)
</script>

<template>
  <aside class="panel order-ticket">
    <div class="ticket-head">
      <div><h2>订单票据</h2><span>模拟成交 · 市价单</span></div>
      <span class="live-dot">LIVE</span>
    </div>

    <label class="ticket-field">
      <span>交易标的</span>
      <select :value="symbol" class="select" @change="emit('update:symbol', $event.target.value)">
        <option value="sh518850">黄金ETF华夏 · sh518850</option>
        <option value="hf_XAU">伦敦金 · hf_XAU</option>
        <option value="jd_zheshang">浙商积存金 · jd_zheshang</option>
        <option value="jd_minsheng">民生积存金 · jd_minsheng</option>
      </select>
    </label>

    <div class="ticket-price"><span>参考价格</span><b>{{ selectedPrice ? fmt(selectedPrice) : '-' }}</b></div>

    <div class="ticket-field">
      <span>方向</span>
      <div class="type-toggle">
        <button type="button" :class="['side-btn', 'buy-side', { active: type === 'BUY' }]" :aria-pressed="type === 'BUY'" @click="emit('update:type', 'BUY')">买入</button>
        <button type="button" :class="['side-btn', 'sell-side', { active: type === 'SELL' }]" :aria-pressed="type === 'SELL'" @click="emit('update:type', 'SELL')">卖出</button>
      </div>
    </div>

    <label class="ticket-field">
      <span>数量</span>
      <input type="number" :value="quantity" class="num" min="1" aria-label="订单数量" placeholder="输入数量"
             @input="emit('update:quantity', Number($event.target.value))" />
    </label>

    <div v-if="type === 'BUY'" class="ticket-field">
      <span>杠杆</span>
      <div class="lev-btns">
        <button v-for="value in [1, 2, 3, 5]" :key="value" type="button" :class="['lev-btn', { active: leverage === value }]"
                :aria-pressed="leverage === value" @click="emit('update:leverage', value)">{{ value }}x</button>
      </div>
    </div>

    <div class="order-preview">
      <div><span>预估名义金额</span><b>{{ estimatedNotional ? fmt(estimatedNotional) : '-' }}</b></div>
      <div><span>{{ type === 'BUY' ? '预计占用资金' : '预计卖出金额' }}</span><b>{{ estimatedMargin ? fmt(estimatedMargin) : '-' }}</b></div>
      <div v-if="type === 'BUY' && leverage > 1"><span>融资比例</span><b class="warn">{{ fmtPct(100 - 100 / leverage) }}</b></div>
    </div>

    <button type="button" class="submit-order" :class="type === 'BUY' ? 'buy-side' : 'sell-side'"
            :disabled="submitting || !ready" @click="emit('submit')">
      {{ submitting ? '订单提交中…' : `${type === 'BUY' ? '买入' : '卖出'} ${quantity || 0}` }}
    </button>

    <div v-if="type === 'BUY' && leverage > 1" class="lev-tip">
      {{ leverage }}x 杠杆仅冻结 {{ fmtPct(100 / leverage) }} 保证金；价格反向波动可能触发强平。
    </div>
    <div v-if="message" class="msg" :class="messageType" role="status" aria-live="polite">{{ message }}</div>
  </aside>
</template>

<style scoped>
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; min-width: 0; }
.ticket-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ticket-head h2 { margin: 0; color: var(--text); font-size: 13px; font-weight: 680; }
.ticket-head > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.live-dot { margin: 0 !important; color: #67c98e !important; border: 1px solid rgba(39,196,107,.28); border-radius: 3px; padding: 2px 5px; font-size: 8px !important; letter-spacing: .06em; }
.ticket-field { display: flex; flex-direction: column; gap: 6px; margin-top: 13px; }
.ticket-field > span { color: var(--muted); font-size: 9px; }
.select, .num { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); padding: 0 9px; font-size: 10px; outline: none; }
.select:focus, .num:focus { border-color: #695b40; }
.ticket-price { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-top: 10px; padding: 9px 10px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.ticket-price span { color: var(--subtle); font-size: 9px; }
.ticket-price b { color: var(--accent-strong); font-size: 17px; font-weight: 680; font-variant-numeric: tabular-nums; }
.type-toggle { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.side-btn { height: 34px; border: 1px solid var(--line-strong); background: var(--surface); border-radius: var(--radius-sm); color: var(--muted); font-size: 10px; cursor: pointer; }
.side-btn.buy-side.active { border-color: rgba(39,196,107,.6); background: rgba(39,196,107,.09); color: #67d69a; }
.side-btn.sell-side.active { border-color: rgba(239,83,80,.6); background: rgba(239,83,80,.09); color: #ff817e; }
.lev-btns { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; }
.lev-btn { height: 31px; background: var(--surface); border: 1px solid var(--line-strong); color: var(--muted); border-radius: var(--radius-sm); cursor: pointer; font-size: 10px; }
.lev-btn.active { background: rgba(201,166,95,.08); border-color: #75613f; color: var(--accent-strong); font-weight: 650; }
.order-preview { margin-top: 13px; border-top: 1px solid var(--line); }
.order-preview > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 29px; border-bottom: 1px solid #222529; }
.order-preview span { color: var(--subtle); font-size: 9px; }
.order-preview b { color: var(--text); font-size: 10px; font-weight: 600; font-variant-numeric: tabular-nums; }
.submit-order { width: 100%; height: 38px; margin-top: 12px; border-radius: var(--radius-sm); font-size: 11px; font-weight: 700; cursor: pointer; }
.submit-order.buy-side { border: 1px solid rgba(39,196,107,.6); background: rgba(39,196,107,.12); color: #72dda1; }
.submit-order.sell-side { border: 1px solid rgba(239,83,80,.6); background: rgba(239,83,80,.12); color: #ff8582; }
.submit-order:disabled { opacity: .4; cursor: not-allowed; }
.lev-tip { margin-top: 10px; padding: 8px 9px; border-left: 2px solid #8b6d3c; background: rgba(227,180,102,.055); color: #d6b36e; font-size: 9px; line-height: 1.55; }
.msg { margin-top: 10px; padding: 8px 9px; border-radius: var(--radius-sm); font-size: 9px; line-height: 1.55; }
.msg.ok { background: rgba(39,196,107,.08); color: #67d69a; border: 1px solid rgba(39,196,107,.18); }
.msg.error { background: rgba(239,83,80,.08); color: #ff817e; border: 1px solid rgba(239,83,80,.18); }
.warn { color: #e2b85e !important; }
</style>
