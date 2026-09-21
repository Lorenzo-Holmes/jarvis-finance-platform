package com.jarvis.research.social;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AchievementServiceTest {

    @Test
    void streakCountsConsecutiveDaysEndingToday() {
        LocalDate today = LocalDate.now();

        assertEquals(3, AchievementService.streakDays(List.of(
                today, today.minusDays(1), today.minusDays(2))));
    }

    @Test
    void streakAllowsLatestLoginYesterdayButStopsAtAGap() {
        LocalDate today = LocalDate.now();

        assertEquals(2, AchievementService.streakDays(List.of(
                today.minusDays(1), today.minusDays(2), today.minusDays(4))));
    }

    @Test
    void staleLoginHistoryHasNoCurrentStreak() {
        LocalDate today = LocalDate.now();

        assertEquals(0, AchievementService.streakDays(List.of(
                today.minusDays(3), today.minusDays(4))));
    }
}
