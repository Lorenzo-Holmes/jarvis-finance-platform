package com.jarvis.research.news;

import com.jarvis.research.service.AiProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RSS 全局缓存刷新器。
 *
 * <p>资讯页的首次访问不能成为唯一抓取入口：没有用户打开页面时，RSS 缓存也应按
 * PRD 的「每日自动更新」要求保持新鲜。本任务只调用 Python 的 RSS digest，不绑定
 * 用户、不会写用户通知，也不会消耗用户 AI 配额；高风险提醒仍由用户自己的
 * {@code DAILY_DIGEST} 定时任务和通知监听器负责。</p>
 *
 * <p>刷新失败只记录日志并保留 Python 侧已有缓存，不能因为单次上游超时影响 Java
 * 主服务启动或其它定时任务。</p>
 */
@Component
@Slf4j
public class NewsDigestRefreshScheduler {

    static final String DIGEST_PATH = "/internal/rss/digest?refresh=true&force=false";

    private final AiProxyService aiProxyService;

    public NewsDigestRefreshScheduler(AiProxyService aiProxyService) {
        this.aiProxyService = aiProxyService;
    }

    /** 默认启动后 60 秒执行一次，之后每日执行；可用环境变量调整节奏。 */
    @Scheduled(
            initialDelayString = "${jarvis.news.refresh-initial-delay-ms:60000}",
            fixedDelayString = "${jarvis.news.refresh-interval-ms:86400000}")
    public void refresh() {
        try {
            Map<String, Object> raw = aiProxyService.post(DIGEST_PATH, Map.of());
            Map<String, Object> shaped = NewsDigest.fromDigest(raw, Integer.MAX_VALUE);
            List<?> items = shaped.get("items") instanceof List<?> list ? list : List.of();
            log.info("RSS 自动刷新完成。资讯 {} 条，来源 {}/{}，实际刷新 {} 个源",
                    items.size(),
                    shaped.getOrDefault("ok_sources", 0),
                    shaped.getOrDefault("total_sources", 0),
                    raw == null ? 0 : raw.getOrDefault("refreshed", 0));
        } catch (Exception failure) {
            log.warn("RSS 自动刷新失败，保留现有缓存，不影响主服务与用户任务。", failure);
        }
    }
}
