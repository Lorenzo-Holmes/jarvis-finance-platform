package com.jarvis.research.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器 SPI 的装配契约：真的被 Spring 认到，且认到的就是已完成的那几个。
 *
 * <p>为什么要单独钉一条：创建接口靠 {@link ScheduledTaskRunner#supports} 决定
 * "这个任务类型能不能建"，而 {@code supports} 读的是构造时注入的执行器表。
 * 一旦某个执行器没被扫到（改了包名、忘了 {@code @Component}、构造依赖缺失导致
 * 整表构建失败），表现是**该类型静默变成"尚未开放"**——创建被 400 拒掉，
 * 而单元测试里那些执行器是被 mock 掉的，全都发现不了。</p>
 *
 * <p>⚠️ 下面的"支持类型集合"断言是<strong>刻意的维护成本</strong>，与
 * {@code FlywaySchemaContractTest} 里那份写死的表清单同一个道理：
 * 新增/移除执行器时必须显式改一次这里，逼你确认那是有意为之。
 * {@code DAILY_DIGEST} 执行器落地时这条如期变红，已按约定把它加进集合。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sched-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 本测试只关心 bean 装配，表结构由 FlywaySchemaContractTest 单独负责。
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=integration-test-jwt-secret-key-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        // 别让风控常驻扫描在测试期间抢 CPU。
        "jarvis.risk.poll-interval-ms=3600000"
})
class ScheduledTaskExecutorWiringTest {

    @Autowired
    private ScheduledTaskRunner runner;
    @Autowired
    private List<ScheduledTaskExecutor> executors;

    @Test
    void theSupportedTypeSetIsExactlyWhatHasBeenImplemented() {
        assertEquals(Set.of(ScheduledTaskType.MARKET_SCAN,
                        ScheduledTaskType.BACKTEST,
                        ScheduledTaskType.RISK_CHECK,
                        ScheduledTaskType.DAILY_DIGEST),
                runner.supportedTypes());
    }

    @Test
    void everyDeclaredTypeNowHasAnExecutor() {
        // 这条不是"实现细节"而是"产品承诺"：ScheduledTaskType 里声明的每一个类型
        // 都应当能被创建。以前 DAILY_DIGEST 是刻意的例外（未注册 → 创建接口 400），
        // 现在四个执行器齐了，就不该再留任何"建不出来"的类型 —— 否则前端把它列出来、
        // 用户一建就 400，而错误信息只说"该类型尚未开放"。
        for (ScheduledTaskType type : ScheduledTaskType.values()) {
            assertTrue(runner.supports(type),
                    "类型 " + type + " 没有任何执行器认领，创建接口会直接 400");
        }
        assertEquals(ScheduledTaskType.values().length, runner.supportedTypes().size());
    }

    @Test
    void everyTypeIsClaimedByAtMostOneExecutor() {
        // 一个类型两个执行器会让 ScheduledTaskRunner 的构造直接抛异常（启动即失败），
        // 这条断言把那个契约显式写出来，而不是只依赖 toMap 的副作用。
        Set<ScheduledTaskType> claimed = executors.stream()
                .map(ScheduledTaskExecutor::type)
                .collect(Collectors.toSet());

        assertEquals(executors.size(), claimed.size(), "同一类型被多个执行器认领");
        assertTrue(claimed.containsAll(runner.supportedTypes()));
    }
}
