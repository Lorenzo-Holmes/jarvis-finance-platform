package com.jarvis.research.service;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 教学/个人规模的用户级 AI 限流。
 * 单实例下按用户限制 10 次/分钟、100 次/自然日。
 * 迁移到多实例后应替换为 Redis/Bucket4j 等分布式实现。
 */
@Service
public class AiRateLimitService {

    private static final int PER_MINUTE = 10;
    private static final int PER_DAY = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Map<Long, Usage> usageByUser = new HashMap<>();
    private final AiQuotaService quotaService;

    @Autowired
    public AiRateLimitService(AiQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 供不加载 Spring 容器的单元测试使用。 */
    public AiRateLimitService() {
        this.quotaService = null;
    }

    public synchronized void consume(Long userId) {
        long minute = System.currentTimeMillis() / 60_000L;
        LocalDate day = LocalDate.now(ZONE);
        Usage usage = usageByUser.computeIfAbsent(userId, ignored -> new Usage(minute, day));

        if (usage.minuteBucket != minute) {
            usage.minuteBucket = minute;
            usage.minuteCount = 0;
        }
        if (!usage.day.equals(day)) {
            usage.day = day;
            usage.dayCount = 0;
        }

        if (usage.minuteCount >= PER_MINUTE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI 请求过于频繁，请稍后再试（每分钟最多10次）");
        }
        // 生产环境由持久化 AiQuota 控制每日额度；无 Spring 容器的单测/本地轻量实例保留默认硬上限。
        if (quotaService == null && usage.dayCount >= PER_DAY) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日 AI 调用额度已用完（每天最多100次）");
        }

        if (quotaService != null) {
            quotaService.consumeRequest(userId);
        }
        usage.minuteCount++;
        usage.dayCount++;
    }

    /**
     * 记录上游返回的 total_tokens。
     *
     * <p><b>这里曾经是一个静默失效的配额。</b>本方法原先只看顶层的 {@code usage}，
     * 但唯一的调用方（AiController.postAndRecord）传进来的是 Python 服务的响应信封
     * {@code {code, message, data:{..., usage:{total_tokens}}}}——usage 在 {@code data} 里面。
     * 于是在 {@code instanceof Map} 检查处就提前返回了，{@code monthlyTokenUsed} 永远是 0。</p>
     *
     * <p>后果不只是"统计为零"：{@link AiQuotaService#consumeRequest} 会用
     * {@code monthlyTokenUsed >= monthlyTokenLimit} 拦截请求，所以管理员设置的
     * **月度 token 配额从未真正生效**——用户只要守着每分钟 10 次的限流，就能无限消耗 token。
     * 之所以一直没被发现，是因为没有任何测试覆盖本方法。</p>
     *
     * <p>现在两种信封形状都认：顶层 usage（某些调用方直接传内层 data）与 data.usage。
     * 取 {@code data} 里的那个是主路径，顶层那个保留是为了兼容与向后安全。</p>
     */
    public void recordTokens(Long userId, Object aiResponse) {
        if (quotaService == null || !(aiResponse instanceof Map<?, ?> response)) return;
        Map<?, ?> usage = findUsage(response);
        Map<?, ?> reviewerUsage = findReviewerUsage(response);
        long totalTokens = totalTokens(usage) + totalTokens(reviewerUsage);
        if (totalTokens > 0) quotaService.consumeTokens(userId, totalTokens);
    }

    /** 先看信封里的 data，再看顶层；都不是 map 就返回 null。 */
    private static Map<?, ?> findUsage(Map<?, ?> response) {
        if (response.get("data") instanceof Map<?, ?> data
                && data.get("usage") instanceof Map<?, ?> nested) {
            return nested;
        }
        if (response.get("usage") instanceof Map<?, ?> flat) {
            return flat;
        }
        return null;
    }

    /** 输出审查智能体同样消耗模型 token，必须计入用户月度 token 配额。 */
    private static Map<?, ?> findReviewerUsage(Map<?, ?> response) {
        Object data = response.get("data");
        if (data instanceof Map<?, ?> dataMap
                && dataMap.get("safety") instanceof Map<?, ?> safety
                && safety.get("reviewer_usage") instanceof Map<?, ?> usage) {
            return usage;
        }
        if (response.get("safety") instanceof Map<?, ?> safety
                && safety.get("reviewer_usage") instanceof Map<?, ?> usage) {
            return usage;
        }
        return null;
    }

    private static long totalTokens(Map<?, ?> usage) {
        if (usage == null) return 0L;
        Object total = usage.get("total_tokens");
        if (total instanceof Number number) return Math.max(0L, number.longValue());
        if (total != null) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(total).trim()));
            } catch (NumberFormatException ignored) {
                // 兼容非标准上游 usage 格式，不阻断 AI 业务结果。
            }
        }
        return 0L;
    }

    private static final class Usage {
        long minuteBucket;
        int minuteCount;
        LocalDate day;
        int dayCount;

        Usage(long minuteBucket, LocalDate day) {
            this.minuteBucket = minuteBucket;
            this.day = day;
        }
    }
}
