package com.jarvis.research.ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 研究上下文的输入快照：交给 AI 的"事实"，全部由 Java 确定性构建。
 *
 * <p>为什么是一个结构化记录而不是一段拼好的提示词：
 * <ul>
 *   <li>它要落库（{@code research_task.context_json}），半年后要能回答
 *       "这份报告当时是基于什么得出的"；一段提示词文本做不到这件事。</li>
 *   <li>数值都在 {@link #metrics} 里算好了。让模型自己从K线列表算涨跌幅和波动率，
 *       是这类系统最常见也最隐蔽的错误来源。</li>
 *   <li>提示词的组装是 Python 侧的事（它更清楚模型的偏好），
 *       Java 只负责给事实。</li>
 * </ul>
 *
 * @param market       标的所属市场；宏观问题为 null
 * @param symbol       标的代码；宏观问题为 null
 * @param interval     取数的K线周期，当前固定日线
 * @param asOf         上下文构建时刻（不是行情时间——行情时间在 quote 里）
 * @param barCount     实际拿到的K线根数
 * @param firstBarDate 第一根K线日期；没有数据为 null
 * @param lastBarDate  最后一根K线日期；没有数据为 null
 * @param quote        报价快照；取不到为 null
 * @param metrics      确定性指标，**只含算得出来的**（算不出的键直接不出现）
 * @param warnings     取数过程中的缺失说明，供报告里如实交代
 */
public record ResearchContext(
        String market,
        String symbol,
        String interval,
        LocalDateTime asOf,
        int barCount,
        String firstBarDate,
        String lastBarDate,
        Map<String, Object> quote,
        Map<String, Object> metrics,
        List<String> warnings) {

    /** 日K的取数窗口：约半年交易日。给模型的样本既够看趋势，也不至于塞满上下文。 */
    public static final int DAILY_BAR_LIMIT = 120;

    /** 是否拿到了可用于分析的价格数据。 */
    public boolean hasPriceData() {
        return !metrics.isEmpty();
    }
}