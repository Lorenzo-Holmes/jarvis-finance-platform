package com.jarvis.research.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等键的唯一约束必须由**数据库**兜住，而不是靠应用层"先查再插"。
 *
 * <p>为什么这条非测不可：整套防重复执行的假设就是"同一幂等键第二次插入一定失败"。
 * 若约束没建上、或列宽不够导致键被截断，保护会<strong>静默失效</strong> ——
 * 表现是用户偶尔收到两份日报，几乎不可能在联调时被发现。
 * 而"先查再插"在并发下本就不可靠，所以这里直接对数据库下断言。</p>
 *
 * <p>⚠️ <strong>表必须由 Flyway 建</strong>（{@code flyway.enabled=true} +
 * {@code ddl-auto=validate}），不能用 {@code @DataJpaTest} 默认的按实体建表。
 * 这个区别是实质性的：按实体建表只能证明"实体上声明了约束"，
 * 而我们要保证的是"<strong>迁移脚本里确实建了约束</strong>"—— 那才是生产要用的表。</p>
 */
@DataJpaTest(properties = {
        // MODE=PostgreSQL 让迁移脚本里的 BIGSERIAL 可用；DATABASE_TO_LOWER 对齐 Postgres
        // 把未加引号的标识符折叠成小写的习惯。
        "spring.datasource.url=jdbc:h2:mem:sched-idempotency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScheduledTaskRunIdempotencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ScheduledTaskRepository taskRepository;
    @Autowired
    private ScheduledTaskRunRepository runRepository;

    private Long taskId;

    /**
     * 建好完整的数据链：{@code users → scheduled_task}。
     *
     * <p>必须真的建，因为迁移脚本给 {@code task_id} 加了外键 —— 这也顺带证明了
     * 迁移产出的约束是真的生效的（而不是只写在 SQL 里没被应用）。</p>
     */
    @BeforeEach
    void createParentTask() {
        jdbcTemplate.update("INSERT INTO users (email, password_hash) VALUES (?, ?)",
                "scheduler-idempotency@example.com", "not-a-real-hash");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "scheduler-idempotency@example.com");

        ScheduledTask task = ScheduledTask.builder()
                .userId(userId)
                .name("幂等测试任务")
                .taskType(ScheduledTaskType.MARKET_SCAN)
                .cronExpr("0 0 9 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson("{}")
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();
        taskId = taskRepository.saveAndFlush(task).getId();
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByTheDatabase() {
        runRepository.saveAndFlush(newRun("1789606081"));

        assertThrows(DataIntegrityViolationException.class,
                () -> runRepository.saveAndFlush(newRun("1789606081")),
                "同一幂等键第二次插入必须被唯一约束拒绝，否则防重复执行形同虚设");
    }

    @Test
    void differentScheduledMomentsAreTreatedAsDifferentExecutions() {
        runRepository.saveAndFlush(newRun("1789606081"));

        assertDoesNotThrow(() -> runRepository.saveAndFlush(newRun("1789606082")),
                "相邻的计划时刻是两次独立执行，不应互相阻塞");
        assertEquals(2, runRepository.countByTaskId(taskId));
    }

    @Test
    void manualTriggerKeyIsDistinctFromScheduledKey() {
        runRepository.saveAndFlush(newRun("1789606081"));

        assertDoesNotThrow(() -> runRepository.saveAndFlush(newRun("manual:1789606081")),
                "手动触发不应被同一秒的计划触发挡住");
    }

    @Test
    void lookupByIdempotencyKeyReturnsTheClaimedRun() {
        runRepository.saveAndFlush(newRun("1789606000"));

        assertEquals(TaskRunStatus.RUNNING,
                runRepository.findByIdempotencyKey(taskId + ":1789606000").orElseThrow().getStatus());
    }

    @Test
    void foreignKeyToTaskIsEnforcedByTheMigratedSchema() {
        // 顺带证明迁移脚本里的 fk_task_run_task 真的建上了：孤儿 run 必须插不进去。
        DataIntegrityViolationException violation = assertThrows(DataIntegrityViolationException.class,
                () -> runRepository.saveAndFlush(ScheduledTaskRun.builder()
                        .taskId(999_999L)
                        .triggerType(TaskTriggerType.SCHEDULED)
                        .scheduledAt(LocalDateTime.now())
                        .startedAt(LocalDateTime.now())
                        .status(TaskRunStatus.RUNNING)
                        .idempotencyKey("999999:1789606081")
                        .build()),
                "外键未生效时，任务删除后会留下孤儿执行记录");
        assertTrue(violation.getMessage() != null);
    }

    private ScheduledTaskRun newRun(String keySuffix) {
        return ScheduledTaskRun.builder()
                .taskId(taskId)
                .triggerType(TaskTriggerType.SCHEDULED)
                .scheduledAt(LocalDateTime.now())
                .startedAt(LocalDateTime.now())
                .status(TaskRunStatus.RUNNING)
                .idempotencyKey(taskId + ":" + keySuffix)
                .build();
    }
}
