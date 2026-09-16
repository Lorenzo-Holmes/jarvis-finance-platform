package com.jarvis.research.ai;

import com.jarvis.research.market.dto.KlineBarDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * K线的确定性指标计算。
 *
 * <p>存在的理由来自架构原则：**金融数值计算保持确定性，不交由大模型生成**。
 * 研究上下文里凡是数字（涨跌幅、均线、波动率、回撤）都由这里算好再交给 AI，
 * AI 只负责解释。让模型自己算这些数，是这类系统最常见也最隐蔽的错误来源——
 * 它给出的数字看起来很合理，但没人能复现。</p>
 *
 * <p>几条刻意的约定，都是为了让结果可复现、可解释：
 * <ul>
 *   <li><b>收益率用简单收益率</b>（{@code (cur-prev)/prev}），不用对数收益率：
 *       平台里对外显示的都是涨跌幅，两者混用会在小涨小跌时出现对不上的数字。</li>
 *   <li><b>波动率用样本标准差</b>（除以 {@code n-1}），这是金融惯例；
 *       并且按 {@code sqrt(252)} 年化，前提是**日线**。
 *       用在分钟线上不会报错但含义不对，调用方负责只对日线用。</li>
 *   <li><b>数据不足返回 null，绝不抛异常</b>：研究任务不能因为"这只票上市才三天"
 *       就整个失败。缺哪个指标就让那个指标为空，报告里说明即可。</li>
 *   <li><b>缺收盘价的行直接跳过</b>：与行情侧的宽松解析一致——
 *       一行的畸形不该让整段历史作废。</li>
 * </ul>
 */
public final class MarketMetrics {

    /** 年化用的交易日数。A股与美股都惯用 252。 */
    private static final int TRADING_DAYS_PER_YEAR = 252;

    private MarketMetrics() {}

    /** 最后一根的收盘价；没有可用数据返回 null。 */
    public static Double latestClose(List<KlineBarDTO> bars) {
        List<KlineBarDTO> usable = usable(bars);
        return usable.isEmpty() ? null : usable.get(usable.size() - 1).close();
    }

    /** 最近两根之间的涨跌幅（%）。只有一根时返回 null。 */
    public static Double changePct(List<KlineBarDTO> bars) {
        List<KlineBarDTO> usable = usable(bars);
        if (usable.size() < 2) {
            return null;
        }
        Double previous = usable.get(usable.size() - 2).close();
        Double latest = usable.get(usable.size() - 1).close();
        return percentChange(previous, latest);
    }

    /** 整段区间首尾的涨跌幅（%）。只有一根时返回 null。 */
    public static Double periodChangePct(List<KlineBarDTO> bars) {
        List<KlineBarDTO> usable = usable(bars);
        if (usable.size() < 2) {
            return null;
        }
        return percentChange(usable.get(0).close(), usable.get(usable.size() - 1).close());
    }

    /** 区间最高价（取各根最高价的最大值）。 */
    public static Double periodHigh(List<KlineBarDTO> bars) {
        Double high = null;
        for (KlineBarDTO bar : usable(bars)) {
            if (bar.high() != null && (high == null || bar.high() > high)) {
                high = bar.high();
            }
        }
        return high;
    }

    /** 区间最低价（取各根最低价的最小值）。 */
    public static Double periodLow(List<KlineBarDTO> bars) {
        Double low = null;
        for (KlineBarDTO bar : usable(bars)) {
            if (bar.low() != null && (low == null || bar.low() < low)) {
                low = bar.low();
            }
        }
        return low;
    }

    /** 平均成交量。全都没有成交量时返回 null。 */
    public static Double averageVolume(List<KlineBarDTO> bars) {
        double sum = 0;
        int count = 0;
        for (KlineBarDTO bar : usable(bars)) {
            if (bar.volume() != null) {
                sum += bar.volume();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /** 最近 {@code window} 根的收盘均价。根数不够返回 null（不拿不足的窗口硬算）。 */
    public static Double movingAverage(List<KlineBarDTO> bars, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("窗口长度必须为正: " + window);
        }
        List<KlineBarDTO> usable = usable(bars);
        if (usable.size() < window) {
            return null;
        }
        double sum = 0;
        for (KlineBarDTO bar : usable.subList(usable.size() - window, usable.size())) {
            sum += bar.close();
        }
        return sum / window;
    }

    /**
     * 年化波动率（小数，不是百分比）。少于两根返回 null。
     *
     * <p>价格没变时返回 0（而不是 null）："这几天完全没波动"是一个有意义的结论，
     * 和"数据不够"是两回事。</p>
     */
    public static Double volatility(List<KlineBarDTO> bars) {
        List<Double> returns = returns(bars);
        if (returns.size() < 2) {
            return null;
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSquares = 0;
        for (double r : returns) {
            sumSquares += (r - mean) * (r - mean);
        }
        double sampleStdDev = Math.sqrt(sumSquares / (returns.size() - 1));
        return sampleStdDev * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /**
     * 区间最大回撤（%，**负数**）。没有回撤时返回 0，数据不足返回 null。
     *
     * <p>返回负数是为了不含糊：{@code -25.0} 表示从区间内的高点回撤了 25%。
     * 有些库返回正的"回撤幅度"，两种约定混在一起最容易在报告里写反方向。</p>
     */
    public static Double maxDrawdownPct(List<KlineBarDTO> bars) {
        List<KlineBarDTO> usable = usable(bars);
        if (usable.isEmpty()) {
            return null;
        }
        double peak = usable.get(0).close();
        double worst = 0.0;
        for (KlineBarDTO bar : usable) {
            double close = bar.close();
            if (close > peak) {
                peak = close;
            }
            if (peak > 0) {
                double drawdown = (close - peak) / peak;
                if (drawdown < worst) {
                    worst = drawdown;
                }
            }
        }
        return worst * 100.0;
    }

    /**
     * 收益率序列（简单收益率）。
     *
     * <p>{@code previous == 0} 的分支是防御性的：{@link #usable} 已经剔除了零价，
     * 所以正常情况下走不到。留着是因为"绝不产出 Infinity/NaN"这件事值得多一道保证——
     * 一个 NaN 混进报告比缺一个数字糟糕得多。</p>
     */
    private static List<Double> returns(List<KlineBarDTO> bars) {
        List<KlineBarDTO> usable = usable(bars);
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < usable.size(); i++) {
            double previous = usable.get(i - 1).close();
            double current = usable.get(i).close();
            if (previous == 0) {
                continue;
            }
            returns.add((current - previous) / previous);
        }
        return returns;
    }

    private static Double percentChange(double previous, double current) {
        if (previous == 0) {
            return null;
        }
        return (current - previous) / previous * 100.0;
    }

    /**
     * 只保留收盘价可用的行——后续所有计算都以收盘价为准。
     *
     * <p><b>恰好为 0 的收盘价按"缺值"看待并整行跳过。</b>行情源用 0 表示"这根没有数据"
     * （停牌、未成形、字段缺失），若当成真实价格，一根 {@code 100 -> 0} 会给出一笔
     * -100% 的收益率，直接把波动率与回撤算成垃圾——而且看起来像个"结论"。
     * 所以这个约定只在这一处表达一次，其余指标自动一致。</p>
     *
     * <p>之所以判的是 {@code != 0} 而不是 {@code > 0}：**负价是真实存在的**
     * （2020 年 4 月 WTI 原油期货就收在负值）。用 {@code > 0} 会把这些合法数据
     * 一起丢掉，那是个更隐蔽的错误。</p>
     */
    private static List<KlineBarDTO> usable(List<KlineBarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        List<KlineBarDTO> usable = new ArrayList<>();
        for (KlineBarDTO bar : bars) {
            if (bar != null && bar.close() != null && bar.close() != 0.0) {
                usable.add(bar);
            }
        }
        return usable;
    }
}