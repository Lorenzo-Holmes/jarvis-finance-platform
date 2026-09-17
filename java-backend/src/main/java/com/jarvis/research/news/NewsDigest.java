package com.jarvis.research.news;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日要闻的响应整形。
 *
 * <p>Python 侧 {@code /internal/rss/digest} 是权威来源，它返回的每篇文章只带
 * {@code source_id}（机器可读），而界面需要人可读的来源名——两者的映射表就在同一次
 * 响应的 {@code sources} 里，所以在这里一次性做完，避免前端再拼一张表。
 *
 * <p>刻意做成不依赖 Spring / WebClient 的纯类：HTTP 打桩在 {@code AiProxyService}
 * 里没有注入点，把逻辑留在这里才能被真正测到（见 {@code NewsDigestTest}）。
 *
 * <p>约定与全仓一致：服务不可用时返回 {@code available=false} + {@code reason}，
 * 而不是抛错或返回空列表——前端必须能区分"没有资讯"和"拿不到资讯"。
 */
public final class NewsDigest {

    /** 源不可用时的原因码（稳定字符串，便于前端判断）。 */
    public static final String REASON_UNAVAILABLE = "ai_service_unavailable";
    public static final String REASON_EMPTY = "no_digest_payload";

    private NewsDigest() {
    }

    /** 不可用信封：结构稳定，前端按 available 降级展示。 */
    public static Map<String, Object> unavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("reason", reason == null || reason.isBlank() ? REASON_UNAVAILABLE : reason);
        out.put("items", List.of());
        out.put("total_sources", 0);
        out.put("ok_sources", 0);
        out.put("sources", List.of());
        return out;
    }

    /**
     * 把 digest 载荷整形为前端要闻列表。
     *
     * <p>规则：没有标题的条目丢弃（无法展示）；{@code limit} 只截断**不改顺序**
     * （资讯按发布时间的语义就在顺序里）；{@code url} 原样返回，是否可跳转由前端
     * 判断协议，Java 不做过滤以免把合法但非 http 的链接悄悄吞掉。
     */
    public static Map<String, Object> fromDigest(Map<String, Object> raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return unavailable(REASON_EMPTY);
        }

        Map<String, String> sourceNames = sourceNameIndex(raw.get("sources"));
        List<Map<String, Object>> items = new ArrayList<>();
        int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        for (Object entry : asList(raw.get("articles"))) {
            if (items.size() >= cap) {
                break;
            }
            if (!(entry instanceof Map<?, ?> article)) {
                continue;
            }
            String title = text(article.get("title"));
            if (title.isEmpty()) {
                continue;
            }
            String sourceId = text(article.get("source_id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", title);
            item.put("url", text(article.get("url")));
            item.put("source", sourceNames.getOrDefault(sourceId, sourceId));
            item.put("source_id", sourceId);
            item.put("published", text(article.get("published")));
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("reason", null);
        out.put("generated_at", text(raw.get("generated_at")));
        out.put("total_sources", number(raw.get("total_sources")));
        out.put("ok_sources", number(raw.get("ok_sources")));
        out.put("sources", asList(raw.get("sources")));
        out.put("items", items);
        return out;
    }

    /** source_id → 可读名称；缺名称时回退为 id，不产生空标签。 */
    private static Map<String, String> sourceNameIndex(Object sources) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Object entry : asList(sources)) {
            if (!(entry instanceof Map<?, ?> source)) {
                continue;
            }
            String id = text(source.get("source_id"));
            if (id.isEmpty()) {
                continue;
            }
            String name = text(source.get("name"));
            index.put(id, name.isEmpty() ? id : name);
        }
        return index;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}