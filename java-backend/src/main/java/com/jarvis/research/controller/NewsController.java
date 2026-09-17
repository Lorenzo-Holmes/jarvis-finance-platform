package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.service.AiProxyService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日要闻。
 *
 * <p>数据链路：Java（本控制器，对外 API 与鉴权）→ Python {@code /internal/rss/digest}
 * （负责抓取、去重、排序）。Java 只做整形与降级，不自己抓 feed——抓取与资讯源配置
 * 归 Python 侧，这与三栈的分工一致。
 *
 * <p>降级契约：Python 不可用时返回 {@code available=false} + {@code reason}，
 * HTTP 仍是 200。要闻是行情页的辅助区块，不该因为它让整页报错。
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final AiProxyService aiProxyService;

    public NewsController(AiProxyService aiProxyService) {
        this.aiProxyService = aiProxyService;
    }

    /**
     * 最新要闻。
     *
     * @param limit   返回条数上限（只截断，不改顺序）
     * @param refresh 是否触发一次抓取（受 Python 侧最小间隔限制）
     * @param force   绕过最小抓取间隔，用户手工点刷新时用
     */
    @GetMapping("/daily")
    public ApiResponse<Object> daily(@RequestParam(defaultValue = "12") int limit,
                                     @RequestParam(defaultValue = "true") boolean refresh,
                                     @RequestParam(defaultValue = "false") boolean force) {
        // FastAPI 的 rss_digest 从查询串读取这两个开关，JSON body 会被忽略。
        String path = "/internal/rss/digest?refresh=" + refresh + "&force=" + force;
        try {
            Map<String, Object> digest = aiProxyService.post(path, Map.of());
            return ApiResponse.ok(NewsDigest.fromDigest(digest, limit));
        } catch (Exception e) {
            // 不把异常文本透给前端：内部地址与栈信息不该出现在响应里。
            return ApiResponse.ok(NewsDigest.unavailable(NewsDigest.REASON_UNAVAILABLE));
        }
    }
}