package com.jarvis.research.ai;

import com.jarvis.research.market.dto.KlineBarDTO;

import java.util.List;
import java.util.Map;

/**
 * 研究上下文取行情数据的端口。
 *
 * <p>存在的理由是**可测**：行情服务的方法返回 {@code Map<String,Object>} 信封，
 * 直接依赖它会让"上下文构建"这件事只能靠加载 Spring 容器来测。这层端口把
 * 信封形状挡在外面，构建逻辑于是可以用一个 lambda 桩测干净。</p>
 *
 * <p>契约只有两条，都很关键：
 * <ul>
 *   <li><b>取不到就返回空</b>（报价返回 null / K线返回空列表），**不要抛异常**。
 *       研究任务不能因为休市或上游抖动整个失败——缺什么应当是报告里的一句说明，
 *       而不是一次任务失败。</li>
 *   <li>实现方负责把来源的杂格式规整成 {@link KlineBarDTO}，
 *       让构建逻辑只面对干净数据。</li>
 * </ul>
 */
public interface ResearchMarketDataGateway {

    /** 最新报价快照；取不到返回 null。 */
    Map<String, Object> quote(String market, String symbol);

    /** 最近的日K，按时间升序；取不到返回空列表。 */
    List<KlineBarDTO> dailyBars(String market, String symbol, int limit);
}