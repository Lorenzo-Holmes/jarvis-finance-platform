package com.jarvis.research.service;

import com.jarvis.research.user.AiQuota;
import com.jarvis.research.user.AiQuotaRepository;
import com.jarvis.research.user.GroupAiQuota;
import com.jarvis.research.user.GroupAiQuotaRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMember;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuotaServiceGroupTest {

    private final AiQuotaRepository userQuotaRepository = mock(AiQuotaRepository.class);
    private final GroupAiQuotaRepository groupQuotaRepository = mock(GroupAiQuotaRepository.class);
    private final UserGroupMemberRepository memberRepository = mock(UserGroupMemberRepository.class);
    private final UserGroupRepository groupRepository = mock(UserGroupRepository.class);
    private final AiQuotaService service = new AiQuotaService(
            userQuotaRepository, groupQuotaRepository, memberRepository, groupRepository);

    @Test
    void groupQuotaIsUsedWhenUserHasNoExplicitQuota() {
        GroupAiQuota quota = groupQuota(2L, 10, 0L);
        when(userQuotaRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.empty());
        when(memberRepository.findByUserId(7L)).thenReturn(Optional.of(UserGroupMember.builder()
                .groupId(2L).userId(7L).createdAt(LocalDateTime.now()).build()));
        when(groupRepository.findById(2L)).thenReturn(Optional.of(UserGroup.builder()
                .id(2L).name("research").enabled(true).build()));
        when(groupQuotaRepository.findByGroupIdForUpdate(2L)).thenReturn(Optional.of(quota));

        service.consumeRequest(7L);

        assertEquals(1, quota.getDailyRequestUsed());
        verify(groupQuotaRepository).save(quota);
    }

    @Test
    void explicitUserQuotaOverridesGroupQuota() {
        AiQuota userQuota = AiQuota.builder()
                .userId(7L).dailyRequestLimit(5).dailyRequestUsed(0)
                .monthlyTokenLimit(0L).monthlyTokenUsed(0L)
                .resetDate(LocalDate.now()).periodMonth("2026-09")
                .updatedAt(LocalDateTime.now()).build();
        when(userQuotaRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.of(userQuota));

        service.consumeRequest(7L);

        assertEquals(1, userQuota.getDailyRequestUsed());
        verify(userQuotaRepository).save(userQuota);
    }

    @Test
    void groupTokenUsageIsRecordedWhenThereIsNoUserQuota() {
        GroupAiQuota quota = groupQuota(2L, 10, 1000L);
        when(userQuotaRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.empty());
        when(memberRepository.findByUserId(7L)).thenReturn(Optional.of(UserGroupMember.builder()
                .groupId(2L).userId(7L).createdAt(LocalDateTime.now()).build()));
        when(groupRepository.findById(2L)).thenReturn(Optional.of(UserGroup.builder()
                .id(2L).name("research").enabled(true).build()));
        when(groupQuotaRepository.findByGroupIdForUpdate(2L)).thenReturn(Optional.of(quota));

        service.consumeTokens(7L, 120L);

        assertEquals(120L, quota.getMonthlyTokenUsed());
        verify(groupQuotaRepository).save(quota);
    }

    private GroupAiQuota groupQuota(Long groupId, int dailyLimit, long monthlyLimit) {
        return GroupAiQuota.builder()
                .groupId(groupId).dailyRequestLimit(dailyLimit).dailyRequestUsed(0)
                .monthlyTokenLimit(monthlyLimit).monthlyTokenUsed(0L)
                .resetDate(LocalDate.now()).periodMonth("2026-09")
                .updatedAt(LocalDateTime.now()).build();
    }
}
