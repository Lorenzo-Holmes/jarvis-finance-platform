<script setup>
import { formatNumber } from '../../utils/formatters'

defineProps({ analysis: { type: Object, default: null } })
const fmt = value => formatNumber(value, 2, 4)
</script>

<template>
  <section class="rail-panel technical-rail">
    <div class="rail-title">技术指标</div>
    <template v-if="analysis?.status === 'ok'">
      <div class="technical-grid">
        <div><span>趋势</span><b :class="analysis.trend">{{ analysis.trend_label }}</b></div>
        <div><span>动能</span><b>{{ analysis.momentum_label }}</b></div>
        <div><span>RSI(14)</span><b>{{ fmt(analysis.indicators?.rsi14) }}</b></div>
        <div><span>MACD</span><b>{{ fmt(analysis.indicators?.macd) }}</b></div>
        <div><span>ADX(14)</span><b>{{ fmt(analysis.indicators?.adx14) }}</b></div>
        <div><span>ATR(14)</span><b>{{ fmt(analysis.indicators?.atr14) }}</b></div>
        <div><span>随机K</span><b>{{ fmt(analysis.indicators?.stoch_k14) }}</b></div>
        <div><span>随机D</span><b>{{ fmt(analysis.indicators?.stoch_d3) }}</b></div>
        <div><span>Williams %R</span><b>{{ fmt(analysis.indicators?.williams_r14) }}</b></div>
        <div><span>布林位置</span><b>{{ fmt(analysis.indicators?.bollinger_position) }}%</b></div>
        <div><span>布林宽度</span><b>{{ fmt(analysis.indicators?.bollinger_width) }}%</b></div>
        <div><span>ROC(12)</span><b>{{ fmt(analysis.indicators?.roc12) }}%</b></div>
        <div><span>成交量均线</span><b>{{ fmt(analysis.indicators?.volume_sma20) }}</b></div>
        <div><span>20期支撑</span><b>{{ fmt(analysis.support_20) }}</b></div>
        <div><span>20期阻力</span><b>{{ fmt(analysis.resistance_20) }}</b></div>
      </div>
    </template>
    <div v-else class="rail-empty">当前周期暂无技术指标</div>
  </section>
</template>

<style scoped>
.rail-panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px; }
.rail-title { color: var(--text); font-size: 11px; font-weight: 650; }
.technical-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; margin-top: 7px; }
.technical-grid > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 28px; border-bottom: 1px solid #222529; }
.technical-grid span { color: var(--subtle); font-size: 9px; }
.technical-grid b { color: var(--text); font-size: 10px; font-weight: 600; }
.technical-grid b.bullish { color: #27c46b; }
.technical-grid b.bearish { color: #ef5350; }
.rail-empty { color: var(--subtle); font-size: 10px; line-height: 1.6; margin-top: 10px; }
</style>
