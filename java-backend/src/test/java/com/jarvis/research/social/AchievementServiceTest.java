package com.jarvis.research.social;

import org.junit.jupiter.api.Test;
import com.jarvis.research.ai.ResearchTaskRepository;
import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.schedule.ScheduledTaskRepository;
import com.jarvis.research.schedule.ScheduledTaskRunRepository;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AchievementServiceTest {

    @Test
    void overviewReadsUnlockedAchievementsOnceInsteadOfCheckingEveryKey() {
        UserRepository users = mock(UserRepository.class);
        AuditEventRepository audit = mock(AuditEventRepository.class);
        ResearchTaskRepository research = mock(ResearchTaskRepository.class);
        ScheduledTaskRepository tasks = mock(ScheduledTaskRepository.class);
        ScheduledTaskRunRepository runs = mock(ScheduledTaskRunRepository.class);
        CommunityPostRepository posts = mock(CommunityPostRepository.class);
        CommunityGroupMemberRepository members = mock(CommunityGroupMemberRepository.class);
        DirectMessageRepository messages = mock(DirectMessageRepository.class);
        UserAchievementRepository achievements = mock(UserAchievementRepository.class);
        UserActivityRepository activities = mock(UserActivityRepository.class);
        User user = User.builder().id(7L).displayName("User").email("u@example.test")
                .passwordHash("x").enabled(true).createdAt(LocalDateTime.now()).build();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(audit.findByUserIdAndActionInOrderByCreatedAtDesc(anyLong(), any(), any())).thenReturn(java.util.List.of());
        when(achievements.findByUserIdOrderByUnlockedAtAsc(7L)).thenReturn(java.util.List.of());
        AchievementService service = new AchievementService(users, audit, research, tasks, runs,
                posts, members, messages, achievements, activities);

        service.overview(7L);

        verify(achievements, times(1)).findByUserIdOrderByUnlockedAtAsc(7L);
        verify(achievements, never()).existsByUserIdAndAchievementKey(anyLong(), anyString());
    }

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
