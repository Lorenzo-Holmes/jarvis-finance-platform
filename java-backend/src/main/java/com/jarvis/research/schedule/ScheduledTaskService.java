package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务的增删改查与调度器联动。
 *
 * <p>这是"用户操作"与"调度内核"之间唯一的接缝。内核（{@link ScheduledTaskRegistry}）
 * 只认数据库里的 {@code ACTIVE} 行，所以<strong>任何让任务"该跑/不该跑"发生变化的写操作，
 * 都必须在这里同时落库并调内核</strong> —— 只改库不调内核会得到"列表显示已暂停、
 * 实际还在按时触发"这种最难查的不一致。</p>
 *
 * <p>三个刻意的设计选择：</p>
 * <ol>
 *   <li><b>方法不包大事务</b>：每个 {@code repository.save} 各自成一个小事务，
 *       写完再调内核。若把 {@code registry.register(...)} 包在事务里，
 *       事务一旦回滚就会出现"调度已注册、库里却没有这条任务"的幽灵触发。</li>
 *   <li><b>校验全部前置成 400</b>：cron 语法、触发频率、名称长度、参数形状都在落库前判掉。
 *       否则用户拿到的是数据库的 500（"value too long"、"constraint violation"），
 *       而这类错误的正确责任方是客户端。</li>
 *   <li><b>删除是软删</b>：执行历史靠外键挂在任务上，物理删会级联带走历史，
 *       而验收要求"记录执行历史与异常"。软删的同时把名称改写，
 *       让这个名字可以被重新使用（见 {@link #releaseName}）。</li>
 * </ol>
 *
 * <p>⚠️ 与 {@code ResearchTaskService} 的命名区分：那边的 {@code research_task} 是
 * "用户手动发起一次研究请求"，本类是"按周期自动重复的定时任务"。中文都叫"任务"，
 * 代码里一律用 {@code ScheduledTask} 前缀。</p>
 */
@Service
@Slf4j
public class ScheduledTaskService {

    /** 与 {@code scheduled_task.name} 列宽一致。 */
    static final int MAX_NAME = 80;
    /** 与 {@code scheduled_task.cron_expr} 列宽一致。 */
    static final int MAX_CRON = 64;
    /** 与 {@code scheduled_task.params_json} 列宽一致。 */
    static final int MAX_PARAMS_JSON = 4000;
    /** 与 {@code scheduled_task.timezone} 列宽一致。 */
    static final int MAX_TIMEZONE = 40;

    static final int MAX_LIST_PAGE_SIZE = 50;
    static final int MAX_RUN_PAGE_SIZE = 100;

    /** 本期只允许这个时区，理由见 {@link #validateTimezone}。 */
    static final String SUPPORTED_TIMEZONE = "Asia/Shanghai";

    /** 给前端预览的"未来若干次触发时刻"条数。 */
    public static final int NEXT_RUN_PREVIEW_COUNT = 3;

    /**
     * 频率下限探测次数：从当前时刻往后连算 5 次触发，任意相邻两次间隔低于下限即拒绝。
     *
     * <p>为什么不直接"比较 cron 里的分钟字段"：那样等于自己写一个 cron 解析器，
     * 而且 {@code 0 0 9,10 * * *} 这类写法根本不是靠单个字段能判断的。
     * 用 {@link CronExpression#next} 实测间隔是唯一与 Spring 真实行为一致的做法。</p>
     */
    private static final int FREQUENCY_PROBE_COUNT = 5;

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final ScheduledTaskRegistry registry;
    private final ScheduledTaskRunner runner;
    private final UserTaskSchedulerProvider schedulerProvider;
    private final ObjectMapper objectMapper;
    private final int maxTasksPerUser;
    private final long minIntervalSeconds;

    public ScheduledTaskService(ScheduledTaskRepository taskRepository,
                                ScheduledTaskRunRepository runRepository,
                                ScheduledTaskRegistry registry,
                                ScheduledTaskRunner runner,
                                UserTaskSchedulerProvider schedulerProvider,
                                ObjectMapper objectMapper,
                                @Value("${jarvis.scheduled-task.max-tasks-per-user:20}") int maxTasksPerUser,
                                @Value("${jarvis.scheduled-task.min-interval-seconds:60}") long minIntervalSeconds) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.registry = registry;
        this.runner = runner;
        this.schedulerProvider = schedulerProvider;
        this.objectMapper = objectMapper;
        this.maxTasksPerUser = Math.max(1, maxTasksPerUser);
        this.minIntervalSeconds = Math.max(1, minIntervalSeconds);
    }

    /**
     * 创建/编辑共用的入参。
     *
     * <p>{@code taskType} 与 {@code cronExpr} 刻意声明成 {@code String} 而不是枚举：
     * 枚举字段一旦收到不认识的取值，Jackson 会抛 {@code HttpMessageNotReadableException}，
     * 而全局异常处理器没有为它准备分支，最终会变成 500 —— 一个纯客户端错误却报服务器故障。
     * 收成字符串、由 {@link ScheduledTaskType#parse} 宽松解析，才能给出可读的 400。</p>
     *
     * <p>{@code params} 声明为 {@code Object} 同理：客户端传数组或字符串时，
     * 直接声明成 {@code Map} 会在反序列化阶段就炸成 500。</p>
     */
    public record TaskRequest(String name, String taskType, String cronExpr,
                              String timezone, Object params) {
    }

    // ------------------------------------------------------------------ 写操作

    /**
     * 创建任务，落库后立即注册进调度器（创建即生效）。
     */
    public ScheduledTask create(Long userId, TaskRequest request) {
        if (request == null) {
            throw badRequest("请求体不能为空");
        }

        String name = requireName(request.name());
        CronExpression expression = requireCron(request.cronExpr());
        ZoneId zone = validateTimezone(request.timezone());
        String paramsJson = requireParams(request.params());
        ScheduledTaskType type = requireSupportedType(request.taskType());

        long existing = taskRepository.countByUserIdAndStatusNot(userId, ScheduledTaskStatus.DELETED);
        if (existing >= maxTasksPerUser) {
            throw badRequest("定时任务数量已达上限（" + maxTasksPerUser + " 个）");
        }

        requireAcceptableFrequency(expression, zone);
        requireNameAvailable(userId, name, null);

        ScheduledTask task = ScheduledTask.builder()
                .userId(userId)
                .name(name)
                .taskType(type)
                .cronExpr(request.cronExpr().trim())
                .timezone(zone.getId())
                .paramsJson(paramsJson)
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();

        ScheduledTask saved = saveGuardingNameConflict(task);
        // 落库之后再注册：反过来的话，注册成功而落库失败会留下一个永远查不到的幽灵调度。
        registry.register(saved);
        log.info("定时任务已创建。taskId={} userId={} type={} cron={}",
                saved.getId(), userId, type, saved.getCronExpr());
        return saved;
    }

    /**
     * 全量编辑一条任务（名称、类型、周期、参数）。
     *
     * <p>状态不在这里改：暂停/恢复走独立端点，免得"编辑时顺手带个 status 字段"
     * 把状态机绕过去。</p>
     */
    public ScheduledTask update(Long userId, Long taskId, TaskRequest request) {
        if (request == null) {
            throw badRequest("请求体不能为空");
        }
        ScheduledTask task = require(userId, taskId);

        String name = requireName(request.name());
        CronExpression expression = requireCron(request.cronExpr());
        ZoneId zone = validateTimezone(request.timezone());
        String paramsJson = requireParams(request.params());
        ScheduledTaskType type = requireSupportedType(request.taskType());

        requireAcceptableFrequency(expression, zone);
        if (!name.equals(task.getName())) {
            requireNameAvailable(userId, name, taskId);
        }

        task.setName(name);
        task.setTaskType(type);
        task.setCronExpr(request.cronExpr().trim());
        task.setTimezone(zone.getId());
        task.setParamsJson(paramsJson);
        // 改配置即视为"重新开始"：清掉上一次失败留下的计数与错误，
        // 否则改完周期后第一次失败就直接撞上自动暂停阈值。
        task.setConsecutiveFailures(0);
        task.setLastError(null);

        ScheduledTask saved = saveGuardingNameConflict(task);

        if (saved.isRunnable()) {
            // register 内部会先摘掉旧调度，所以改周期不会新旧两份同时触发。
            registry.register(saved);
        } else {
            saved.setNextRunAt(null);
            saveGuardingNameConflict(saved);
            registry.cancel(saved.getId());
        }
        return saved;
    }

    /** 暂停：只影响自动触发，手动"立即执行"仍可用。 */
    public ScheduledTask pause(Long userId, Long taskId) {
        ScheduledTask task = require(userId, taskId);
        if (task.getStatus() == ScheduledTaskStatus.PAUSED) {
            return task;
        }
        task.setStatus(ScheduledTaskStatus.PAUSED);
        task.setNextRunAt(null);
        ScheduledTask saved = taskRepository.save(task);
        registry.cancel(taskId);
        log.info("定时任务已暂停。taskId={} userId={}", taskId, userId);
        return saved;
    }

    /** 恢复：重新算下次触发时刻并注册回调度器。 */
    public ScheduledTask resume(Long userId, Long taskId) {
        ScheduledTask task = require(userId, taskId);
        if (task.getStatus() == ScheduledTaskStatus.ACTIVE) {
            return task;
        }
        // 恢复前重新验一遍 cron：这条任务可能是旧版本写进去的、或是有人直接改过库。
        requireAcceptableFrequency(requireCron(task.getCronExpr()), resolveZone(task.getTimezone()));
        task.setStatus(ScheduledTaskStatus.ACTIVE);
        task.setConsecutiveFailures(0);
        task.setLastError(null);
        ScheduledTask saved = taskRepository.save(task);
        registry.register(saved);
        log.info("定时任务已恢复。taskId={} userId={}", taskId, userId);
        return saved;
    }

    /**
     * 软删：移出调度、从列表消失，但执行历史保留。
     *
     * <p>同时把名称改写成 {@code 原名（已删除-<id>）}，把这个名字释放出来给新任务用。
     * 之所以不靠"部分唯一索引"解决：唯一约束 {@code uk_scheduled_task_user_name}
     * 是 {@code (user_id, name)} 上的普通唯一约束，改成部分索引需要新增迁移，
     * 而软删改名在 H2 与 PostgreSQL 上行为一致、也顺带让历史记录里能看出这是哪一条。</p>
     */
    public ScheduledTask delete(Long userId, Long taskId) {
        ScheduledTask task = require(userId, taskId);
        registry.cancel(taskId);
        task.setStatus(ScheduledTaskStatus.DELETED);
        task.setNextRunAt(null);
        task.setName(releaseName(task));
        ScheduledTask saved = taskRepository.save(task);
        log.info("定时任务已删除（软删）。taskId={} userId={}", taskId, userId);
        return saved;
    }

    /**
     * 立即执行一次（不受 {@link ScheduledTaskStatus#PAUSED} 限制）。
     *
     * <p>刻意<strong>在专用调度池上异步派发，而不是在请求线程里同步跑</strong>：
     * 回测是分钟级操作，让 HTTP 请求挂着等它跑完，前端只能长时间白屏，
     * 也极易被网关或浏览器超时打断。派发后立刻返回，结果写进执行历史由用户回看。</p>
     *
     * <p>连点由内核的幂等键兜住（手动触发带秒级时间戳），所以这里不需要额外的防抖逻辑。</p>
     */
    public void runNow(Long userId, Long taskId) {
        require(userId, taskId);
        schedulerProvider.scheduler().schedule(
                // 按 id 重新取一次：派发与执行之间可能隔着几百毫秒，读到最新定义更安全；
                // 顺带跳过"刚被删掉"的任务，免得用户点完删除还看到一条幽灵执行。
                () -> taskRepository.findById(taskId)
                        .filter(fresh -> fresh.getStatus() != ScheduledTaskStatus.DELETED)
                        .ifPresent(fresh -> runner.trigger(fresh, TaskTriggerType.MANUAL, LocalDateTime.now())),
                Instant.now());
        log.info("定时任务已派发手动执行。taskId={} userId={}", taskId, userId);
    }

    // ------------------------------------------------------------------ 读操作

    /**
     * 任务列表。{@code type} 为 null 时不按类型过滤（「风险中心 &gt; 监控任务」
     * 就是传 {@code type=RISK_CHECK} 的同一条路径）。
     */
    public Page<ScheduledTask> list(Long userId, String type, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_LIST_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        if (type == null || type.isBlank()) {
            return taskRepository.findByUserIdAndStatusNotOrderByCreatedAtDesc(
                    userId, ScheduledTaskStatus.DELETED, pageable);
        }
        ScheduledTaskType parsed = ScheduledTaskType.parse(type);
        if (parsed == null) {
            throw badRequest("未知的任务类型：" + type);
        }
        return taskRepository.findByUserIdAndTaskTypeAndStatusNotOrderByCreatedAtDesc(
                userId, parsed, ScheduledTaskStatus.DELETED, pageable);
    }

    /** 取任务；不存在、不属于该用户、已软删，三种情况都返回 404（不区分，避免泄漏他人任务是否存在）。 */
    public ScheduledTask get(Long userId, Long taskId) {
        return require(userId, taskId);
    }

    /** 执行历史，最新在前。归属校验先过 {@link #require}，之后才允许读它的历史。 */
    public Page<ScheduledTaskRun> runs(Long userId, Long taskId, int page, int size) {
        require(userId, taskId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_RUN_PAGE_SIZE);
        return runRepository.findByTaskIdOrderByCreatedAtDesc(taskId, PageRequest.of(safePage, safeSize));
    }

    /** 已就绪的任务类型（其余类型建出来只会每次失败，创建接口会拒掉）。 */
    public List<Map<String, Object>> typeCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (ScheduledTaskType type : ScheduledTaskType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type.name());
            item.put("supported", runner.supports(type));
            catalog.add(item);
        }
        return catalog;
    }

    // ------------------------------------------------------------------ 校验

    private ScheduledTask require(Long userId, Long taskId) {
        ScheduledTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "定时任务不存在"));
        if (task.getStatus() == ScheduledTaskStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时任务不存在");
        }
        return task;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw badRequest("任务名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw badRequest("任务名称过长（最多 " + MAX_NAME + " 字）");
        }
        return name;
    }

    /**
     * 校验 cron 语法并返回已解析对象。
     *
     * <p>Spring 是 <strong>6 段</strong>（秒 分 时 日 月 周），不是 Quartz 的 5 段 + {@code ?}。
     * 这里必须用与调度器同一个 {@link CronExpression#parse}，否则会出现
     * "创建时校验通过、注册时被 {@code ScheduledTaskRegistry} 判为非法而静默不注册"——
     * 表现为任务显示正常但永远不触发。</p>
     */
    private static CronExpression requireCron(String raw) {
        String cron = raw == null ? "" : raw.trim();
        if (cron.isEmpty()) {
            throw badRequest("cron 表达式不能为空");
        }
        if (cron.length() > MAX_CRON) {
            throw badRequest("cron 表达式过长（最多 " + MAX_CRON + " 字）");
        }
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException invalid) {
            throw badRequest("cron 表达式非法（需要 6 段：秒 分 时 日 月 周）：" + invalid.getMessage());
        }
    }

    /**
     * 频率下限。
     *
     * <p>不加这条，一个 {@code * * * * * *} 的任务就能每秒拉起一次回测，
     * 把调度池和数据库一起打满 —— 而它的"非法性"完全看不出来，
     * 因为语法上它是合法的。用实测间隔判定，与调度器真实行为一致。</p>
     */
    private void requireAcceptableFrequency(CronExpression expression, ZoneId zone) {
        LocalDateTime cursor = LocalDateTime.now(zone);
        LocalDateTime previous = null;
        for (int i = 0; i < FREQUENCY_PROBE_COUNT; i++) {
            LocalDateTime next = expression.next(cursor);
            if (next == null) {
                // 没有任何未来触发时刻（如某年某月某日），交给用户自己判断要不要留。
                return;
            }
            if (previous != null) {
                long gap = Math.abs(Duration.between(previous, next).getSeconds());
                if (gap < minIntervalSeconds) {
                    throw badRequest("触发过于频繁（间隔 " + gap + " 秒，最少 " + minIntervalSeconds + " 秒）");
                }
            }
            previous = next;
            cursor = next;
        }
    }

    /**
     * 本期只接受 {@code Asia/Shanghai}。
     *
     * <p>{@code next_run_at} 存的是<strong>无时区</strong>的 {@code TIMESTAMP}，
     * 展示时直接当成本地时间用。若允许任务跑在别的时区，算出来的时刻会被当成上海时间显示，
     * 用户看到的"下次运行 09:00"与实际触发时刻对不上 —— 这种偏差极难自查。
     * 所以列留着（将来要多时区不必改表），但本期在入口就把非上海时区挡掉。</p>
     */
    private static ZoneId validateTimezone(String raw) {
        String value = raw == null || raw.isBlank() ? SUPPORTED_TIMEZONE : raw.trim();
        if (value.length() > MAX_TIMEZONE) {
            throw badRequest("时区名称过长（最多 " + MAX_TIMEZONE + " 字）");
        }
        if (!SUPPORTED_TIMEZONE.equals(value)) {
            throw badRequest("本期仅支持时区 " + SUPPORTED_TIMEZONE);
        }
        return ZoneId.of(SUPPORTED_TIMEZONE);
    }

    private String requireParams(Object params) {
        if (params == null) {
            return "{}";
        }
        if (!(params instanceof Map<?, ?> map)) {
            throw badRequest("params 必须是 JSON 对象");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw badRequest("params 无法序列化：" + e.getMessage());
        }
        if (json.length() > MAX_PARAMS_JSON) {
            throw badRequest("params 过大（最多 " + MAX_PARAMS_JSON + " 字符）");
        }
        return json;
    }

    /**
     * 类型必须已存在执行器。
     *
     * <p>否则用户可以建出一个 {@code BACKTEST} 任务（执行器还没实现）：
     * 每次触发都因"没有执行器认领"判为失败，连续 5 次后被自动暂停，
     * 而用户在界面上完全看不出原因。等执行器注册进来，这里自动放行。</p>
     */
    private ScheduledTaskType requireSupportedType(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw badRequest("任务类型不能为空");
        }
        ScheduledTaskType type = ScheduledTaskType.parse(value);
        if (type == null) {
            throw badRequest("未知的任务类型：" + value);
        }
        if (!runner.supports(type)) {
            throw badRequest("该任务类型尚未开放：" + type.name());
        }
        return type;
    }

    /** 重名提前判一次，为的是给出可读的 400/409；唯一约束仍是最终防线。 */
    private void requireNameAvailable(Long userId, String name, Long selfId) {
        if (!taskRepository.existsByUserIdAndName(userId, name)) {
            return;
        }
        if (selfId != null) {
            // 名称没变的情况在调用方已经排除了，走到这里说明撞上了别人的同名任务。
            log.debug("任务名称已被占用。userId={} name={}", userId, name);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "已有同名任务：" + name);
    }

    /**
     * 兜住三件"并发下才会出现"的写失败。
     *
     * <p>并发同名 → 唯一约束冲突（预检过了也仍可能撞上，所以必须兜）；
     * 两个标签页同时编辑 → 乐观锁 {@code @Version} 冲突；
     * 其余脏数据冲突原样放行成 409 而不是伪装成 500。</p>
     */
    private ScheduledTask saveGuardingNameConflict(ScheduledTask task) {
        try {
            return taskRepository.saveAndFlush(task);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已被其他会话修改，请刷新后重试");
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务名称已存在或数据不合法");
        }
    }

    /** 软删时的改名：截断原名后拼接唯一后缀，保证既不超列宽也不会与任何现存名称冲突。 */
    private static String releaseName(ScheduledTask task) {
        String suffix = "（已删除-" + task.getId() + "）";
        String base = task.getName() == null ? "" : task.getName();
        int keep = Math.max(0, MAX_NAME - suffix.length());
        return (base.length() <= keep ? base : base.substring(0, keep)) + suffix;
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of(SUPPORTED_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of(SUPPORTED_TIMEZONE);
        }
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
