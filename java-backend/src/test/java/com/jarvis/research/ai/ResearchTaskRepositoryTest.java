package com.jarvis.research.ai;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.service.JdGoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 研究任务仓储的行为测试。
 *
 * <p>这里用 H2 + {@code ddl-auto=create-drop}（表由 Hibernate 按实体生成），
 * 与 {@code FlywaySchemaContractTest} 正好互补：那边证明迁移脚本能建出实体要的表，
 * 这边证明实体的映射与查询方法真的能跑。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:research-task;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=integration-test-jwt-secret-key-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class ResearchTaskRepositoryTest {

    @Autowired private ResearchTaskRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private MarketDataService marketDataService;
    @MockBean private JdGoldService jdGoldService;

    @AfterEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void aTaskRoundTripsWithItsContextAndReport() {
        ResearchTask saved = repository.save(ResearchTask.builder()
                .userId(7L)
                .title("贵州茅台情绪与风险")
                .taskType(ResearchTaskType.SENTIMENT)
                .market("a_share")
                .symbol("sh600519")
                .question("最近的情绪和风险怎么样？")
                .status(ResearchTaskStatus.SUCCEEDED)
                .contextJson("{\"market\":\"a_share\"}")
                .reportJson("{\"summary\":\"偏乐观\"}")
                .model("deepseek-chat")
                .promptTokens(1200)
                .completionTokens(800)
                .startedAt(LocalDateTime.of(2026, 9, 16, 10, 0))
                .finishedAt(LocalDateTime.of(2026, 9, 16, 10, 1))
                .build());

        assertNotNull(saved.getId(), "自增主键应当被回填");
        assertNotNull(saved.getCreatedAt(), "@PrePersist 应当补上创建时间");
        assertNotNull(saved.getVersion(), "@Version 应当被初始化");

        ResearchTask reloaded = repository.findById(saved.getId()).orElseThrow();
        assertEquals(7L, reloaded.getUserId());
        assertEquals(ResearchTaskType.SENTIMENT, reloaded.getTaskType());
        assertEquals(ResearchTaskStatus.SUCCEEDED, reloaded.getStatus());
        assertEquals("sh600519", reloaded.getSymbol());
        assertEquals("{\"market\":\"a_share\"}", reloaded.getContextJson());
        assertEquals("{\"summary\":\"偏乐观\"}", reloaded.getReportJson());
        assertEquals("deepseek-chat", reloaded.getModel());
        assertEquals(1200, reloaded.getPromptTokens());
        assertEquals(800, reloaded.getCompletionTokens());
    }

    /**
     * 枚举必须按**名字**落库，不能存序号。
     *
     * <p>这条用原始 SQL 读那一列来验证——只看实体读回来对不对是测不到的：
     * 存序号时读回来同样正确，但一旦以后往枚举中间插入一个值，
     * 历史数据的含义就会整批错位。所以这里断言的是**库里那个字符串**。</p>
     */
    @Test
    void enumsAreStoredAsNamesNotOrdinals() {
        ResearchTask saved = repository.save(ResearchTask.builder()
                .userId(1L)
                .title("t")
                .taskType(ResearchTaskType.CHAIN)
                .question("q")
                .status(ResearchTaskStatus.FAILED)
                .build());

        String rawTaskType = jdbcTemplate.queryForObject(
                "SELECT task_type FROM research_task WHERE id = ?", String.class, saved.getId());
        String rawStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM research_task WHERE id = ?", String.class, saved.getId());

        assertEquals("CHAIN", rawTaskType, "存的是枚举名");
        assertEquals("FAILED", rawStatus, "存的是枚举名");
    }

    /** {@code @PrePersist} 兜底：状态与创建时间没给也能落库。 */
    @Test
    void pendingIsTheDefaultStatusAndCreatedAtIsFilledIn() {
        ResearchTask saved = repository.save(ResearchTask.builder()
                .userId(1L)
                .title("t")
                .taskType(ResearchTaskType.REPORT)
                .question("q")
                .build());

        assertEquals(ResearchTaskStatus.PENDING, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertFalse(saved.isFinished(), "PENDING 不算跑完");
    }

    @Test
    void historyIsNewestFirstAndPaged() {
        saveAt(1L, "旧", LocalDateTime.of(2026, 9, 14, 9, 0));
        saveAt(1L, "新", LocalDateTime.of(2026, 9, 16, 9, 0));
        saveAt(1L, "中", LocalDateTime.of(2026, 9, 15, 9, 0));
        saveAt(2L, "别人的", LocalDateTime.of(2026, 9, 17, 9, 0));

        Page<ResearchTask> firstPage = repository.findByUserIdOrderByCreatedAtDesc(
                1L, PageRequest.of(0, 2));

        assertEquals(List.of("新", "中"),
                firstPage.getContent().stream().map(ResearchTask::getTitle).toList(),
                "最新在前，且只含自己的记录");
        assertEquals(3, firstPage.getTotalElements(), "总数只算自己的");

        Pageable secondPage = PageRequest.of(1, 2);
        assertEquals(List.of("旧"),
                repository.findByUserIdOrderByCreatedAtDesc(1L, secondPage)
                        .getContent().stream().map(ResearchTask::getTitle).toList());
    }

    /**
     * 取单条必须带 userId——这是"研究记录是私人内容"的实现保证。
     *
     * <p>用 id 拿别人的任务必须拿不到。仓储里之所以不提供无主的 {@code findById} 便捷查询，
     * 就是为了让越权在这一层做不到，而不是靠每个调用点记得校验。</p>
     */
    @Test
    void aTaskCannotBeFetchedByAnotherUser() {
        ResearchTask mine = repository.save(ResearchTask.builder()
                .userId(1L).title("t").taskType(ResearchTaskType.RISK).question("q").build());

        assertTrue(repository.findByIdAndUserId(mine.getId(), 1L).isPresent(), "本人可见");
        assertTrue(repository.findByIdAndUserId(mine.getId(), 2L).isEmpty(), "他人不可见");
        // 悄悄地说清另一半：无主的 findById 当然取得到，所以调用方不许用它对外。
        assertTrue(repository.findById(mine.getId()).isPresent());
    }

    @Test
    void countsAreScopedToTheUserAndCanBeBrokenDownByStatus() {
        saveWithStatus(1L, ResearchTaskStatus.SUCCEEDED);
        saveWithStatus(1L, ResearchTaskStatus.FAILED);
        saveWithStatus(1L, ResearchTaskStatus.PENDING);
        saveWithStatus(2L, ResearchTaskStatus.SUCCEEDED);

        assertEquals(3, repository.countByUserId(1L));
        assertEquals(1, repository.countByUserIdAndStatus(1L, ResearchTaskStatus.SUCCEEDED));
        assertEquals(1, repository.countByUserIdAndStatus(1L, ResearchTaskStatus.FAILED));
        assertEquals(0, repository.countByUserIdAndStatus(1L, ResearchTaskStatus.RUNNING));
    }

    /** 可空的业务字段（无标的的宏观问题）不该被 NOT NULL 挡住。 */
    @Test
    void aTaskWithoutAMarketOrSymbolIsAllowed() {
        ResearchTask saved = repository.save(ResearchTask.builder()
                .userId(1L)
                .title("宏观：降息预期")
                .taskType(ResearchTaskType.REPORT)
                .question("美联储下一步会怎么走？")
                .build());

        ResearchTask reloaded = repository.findById(saved.getId()).orElseThrow();
        assertNull(reloaded.getMarket());
        assertNull(reloaded.getSymbol());
        assertNull(reloaded.getReportJson());
        assertNull(reloaded.getErrorMessage());
        assertNull(reloaded.getModel());
    }

    private void saveAt(Long userId, String title, LocalDateTime createdAt) {
        repository.save(ResearchTask.builder()
                .userId(userId)
                .title(title)
                .taskType(ResearchTaskType.REPORT)
                .question("q")
                .status(ResearchTaskStatus.SUCCEEDED)
                .createdAt(createdAt)
                .build());
    }

    private void saveWithStatus(Long userId, ResearchTaskStatus status) {
        repository.save(ResearchTask.builder()
                .userId(userId)
                .title("t")
                .taskType(ResearchTaskType.REPORT)
                .question("q")
                .status(status)
                .build());
    }
}