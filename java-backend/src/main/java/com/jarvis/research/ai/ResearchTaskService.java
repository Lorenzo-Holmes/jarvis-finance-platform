package com.jarvis.research.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 研究任务的编排：创建 → 构建上下文 → 调 AI → 落库。
 *
 * <p>三个刻意的设计选择，都写在下面各方法上：
 * <ol>
 *   <li><b>run 不包事务</b>：它要发起一次可能长达 65 秒的外部调用。包在一个事务里
 *       会让数据库连接被占住整个时长，池子很快就被拖干（配额那边还用了行锁）。
 *       所以每次 repository.save 各自成一个小事务。</li>
 *   <li><b>AI 失败也是一种结果</b>：转成 FAILED + error_message 落库并原样返回任务，
 *       不往上抛。调用方拿到的是"这次研究失败了、原因是这个"，而不是一个丢失了记录的错误。</li>
 *   <li><b>上下文先落库再调 AI</b>：上下文记录的是"发起这次研究时手上有什么数据"，
 *       即使 AI 调用失败它也有价值——它是排查"是数据没取到还是模型没答好"的唯一依据。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchTaskService {

    /** 与 error_message 列宽一致；超长的异常消息若直接落库会让写入本身失败。 */
    static final int MAX_ERROR_MESSAGE = 1000;
    static final int MAX_TITLE = 200;
    static final int MAX_QUESTION = 2000;
    static final int MAX_SYMBOL = 32;
    static final int MAX_MARKET = 20;
    static final int MAX_HISTORY_PAGE_SIZE = 50;

    /** Python 侧的研究报告端点，与 backend/app/ai_routes.py 一一对应。 */
    static final String AI_REPORT_PATH = "/api/ai/research/report";

    private final ResearchTaskRepository repository;
    private final ResearchContextBuilder contextBuilder;
    private final AiProxyService aiProxyService;
    private final AiRateLimitService aiRateLimitService;
    private final ObjectMapper objectMapper;

    /** 新的研究请求。taskType 为 null 时按综合报告处理。 */
    public record NewTask(String title, ResearchTaskType taskType, String market,
                          String symbol, String question) {
    }

    /**
     * 创建任务，状态 PENDING。
     *
     * <p>这里**显式设置状态**而不依赖实体的 {@code @PrePersist}：生命周期回调只在通过 JPA
     * 真正持久化时触发，单元测试里不会跑。让状态由业务代码决定，行为就不依赖运行环境。</p>
     *
     * <p>字段长度在入库前校验成 400，而不是让数据库抛"value too long"——
     * 前者是客户端错误，后者是 500，两者的责任方不同。</p>
     */
    public ResearchTask create(Long userId, NewTask request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String question = trimToNull(request.question());
        String symbol = trimToNull(request.symbol());
        String market = trimToNull(request.market());
        String title = trimToNull(request.title());

        if (question == null && symbol == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "研究任务至少需要一个问题或一个标的");
        }
        requireLength(title, MAX_TITLE, "标题");
        requireLength(question, MAX_QUESTION, "问题");
        requireLength(symbol, MAX_SYMBOL, "标的代码");
        requireLength(market, MAX_MARKET, "市场");

        ResearchTask task = ResearchTask.builder()
                .userId(userId)
                .title(title)
                .taskType(request.taskType() == null ? ResearchTaskType.REPORT : request.taskType())
                .market(market)
                .symbol(symbol)
                .question(question)
                .status(ResearchTaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        return repository.save(task);
    }

    /** 取任务；不存在或不属于该用户都返回 404（不区分，避免泄漏他人任务是否存在）。 */
    public ResearchTask get(Long userId, Long taskId) {
        return repository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "研究任务不存在"));
    }

    /** 历史列表，按创建时间倒序，页大小收敛到 1..50。 */
    public Page<ResearchTask> history(Long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_HISTORY_PAGE_SIZE);
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(safePage, safeSize));
    }

    /**
     * 执行任务：构建上下文 → 调 AI → 落库，返回最终状态的任务。
     *
     * <p>失败不抛异常，而是把任务标成 FAILED 并记下原因返回。理由：一次研究失败之后，
     * 用户最需要的是"失败了、因为什么"，而不是一条错误响应加一个状态不明的任务。
     * 只有"任务不存在"（404）、"已在执行中"（409）与配额拒绝（429）才往上抛。</p>
     */
    public ResearchTask run(Long userId, Long taskId) {
        ResearchTask task = get(userId, taskId);
        if (task.getStatus() == ResearchTaskStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该研究任务正在执行中");
        }

        // 先扣配额再干别的：让限流成为真正的闸门。放在 try 外面是故意的——
        // 被限流不该把任务改成 FAILED，那不是任务失败，是这次请求被拒。
        aiRateLimitService.consume(userId);

        task.setStatus(ResearchTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setFinishedAt(null);
        task.setErrorMessage(null);
        task = repository.save(task);

        try {
            ResearchContext context = contextBuilder.build(task);
            // 先落上下文：AI 失败时它是排查"数据没取到还是模型没答好"的唯一依据
            task.setContextJson(writeJson(context));
            task = repository.save(task);

            Map<String, Object> response = aiProxyService.post(AI_REPORT_PATH, reportBody(task, context));
            Map<String, Object> report = requireSuccessEnvelope(response);

            task.setReportJson(writeJson(report));
            task.setModel(asText(report.get("model")));
            applyTokenUsage(task, report.get("usage"));
            task.setStatus(ResearchTaskStatus.SUCCEEDED);
            task.setFinishedAt(LocalDateTime.now());
            task = repository.save(task);

            aiRateLimitService.recordTokens(userId, response);
            return task;
        } catch (Exception e) {
            log.warn("研究任务执行失败 taskId={}, message={}", taskId, e.getMessage());
            task.setStatus(ResearchTaskStatus.FAILED);
            task.setErrorMessage(truncate(describe(e)));
            task.setFinishedAt(LocalDateTime.now());
            return repository.save(task);
        }
    }

    private Map<String, Object> reportBody(ResearchTask task, ResearchContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_type", task.getTaskType() == null ? "REPORT" : task.getTaskType().name());
        body.put("title", task.getTitle());
        body.put("question", task.getQuestion());
        body.put("market", task.getMarket());
        body.put("symbol", task.getSymbol());
        // Java 算好的指标与数据缺口：Python 侧不重算，报告里的数字必须与任务页一致
        body.put("metrics", context.metrics());
        body.put("quote", context.quote());
        body.put("warnings", context.warnings());
        return body;
    }

    /**
     * 取出 Python 信封里的 data，非 200 视为失败。
     *
     * <p>把 {code:500} 这种信封当成失败而不是当成报告，理由很直接：
     * 一个 code=500 的 body 里没有报告，把它存进 report_json 会让前端拿到一个"有报告但看不懂"的任务。</p>
     */
    private Map<String, Object> requireSuccessEnvelope(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("AI 服务返回空响应");
        }
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 200) {
            throw new IllegalStateException("AI 服务返回错误: " + asText(response.get("message")));
        }
        if (!(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("AI 服务响应缺少 data");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        data.forEach((key, value) -> report.put(String.valueOf(key), value));
        return report;
    }

    private void applyTokenUsage(ResearchTask task, Object usage) {
        if (!(usage instanceof Map<?, ?> map)) {
            return;
        }
        task.setPromptTokens(asInteger(map.get("prompt_tokens")));
        task.setCompletionTokens(asInteger(map.get("completion_tokens")));
    }

    /**
     * 序列化失败与长度超限都**不截断**。
     *
     * <p>截断一段 JSON 只会得到无法解析的垃圾，比"写入失败"更糟：前者是静默的数据损坏，
     * 后者至少会明确报错。当前上下文约 8KB、报告约 5KB，相对 20000/40000 的列宽有充分余量。</p>
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("研究上下文/报告序列化失败: " + e.getMessage(), e);
        }
    }

    /** 异常描述优先取根因消息，否则 WebClient 的外层文字会掩盖真正的原因。 */
    private static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_MESSAGE ? value : value.substring(0, MAX_ERROR_MESSAGE);
    }

    private static void requireLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "过长（最多 " + max + " 字）");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}