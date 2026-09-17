package com.jarvis.research.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ResearchTaskType#parse} 的行为。
 *
 * <p>它是控制器的入口校验：认不出来返回 null 而不是抛异常，这样控制器能给出统一的
 * 400 文案，而不是把 {@code IllegalArgumentException} 泄成 500。</p>
 */
class ResearchTaskTypeTest {

    @Test
    void parsesEveryDeclaredTypeCaseInsensitivelyAndTrimmed() {
        for (ResearchTaskType type : ResearchTaskType.values()) {
            assertEquals(type, ResearchTaskType.parse(type.name()));
            assertEquals(type, ResearchTaskType.parse(type.name().toLowerCase(java.util.Locale.ROOT)));
            assertEquals(type, ResearchTaskType.parse("  " + type.name() + "  "));
        }
    }

    @Test
    void returnsNullForAnythingItDoesNotKnow() {
        assertNull(ResearchTaskType.parse(null), "空值不该抛异常");
        assertNull(ResearchTaskType.parse(""));
        assertNull(ResearchTaskType.parse("   "));
        assertNull(ResearchTaskType.parse("UNKNOWN_TYPE"));
        // 中文名不是协议的一部分：协议是枚举名，别让人以为写中文也行。
        assertNull(ResearchTaskType.parse("情绪"));
    }
}