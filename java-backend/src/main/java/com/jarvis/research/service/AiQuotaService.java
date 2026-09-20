package com.jarvis.research.service;

import com.jarvis.research.user.AiQuota;
import com.jarvis.research.user.AiQuotaRepository;
import com.jarvis.research.user.GroupAiQuota;
import com.jarvis.research.user.GroupAiQuotaRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** 持久化 AI 请求配额，使用行锁保证多请求并发下不会超扣或漏扣。 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private static final int DEFAULT_DAILY_REQUEST_LIMIT = 100;
    private final AiQuotaRepository repository;
    private final GroupAiQuotaRepository groupQuotaRepository;
    private final UserGroupMemberRepository groupMemberRepository;
    private final UserGroupRepository groupRepository;

    @Transactional
    public void consumeRequest(Long userId) {
        AiQuota userQuota = repository.findByUserIdForUpdate(userId).orElse(null);
        if (userQuota != null) {
            consumeRequest(userQuota);
            repository.save(userQuota);
            return;
        }

        GroupAiQuota groupQuota = effectiveGroupQuotaForUpdate(userId);
        if (groupQuota != null) {
            consumeRequest(groupQuota);
            groupQuotaRepository.save(groupQuota);
            return;
        }

        AiQuota quota = repository.save(newUserQuota(userId));
        consumeRequest(quota);
        repository.save(quota);
    }

    @Transactional
    public AiQuota getOrCreateForAdmin(Long userId) {
        return repository.findByUserId(userId).orElseGet(() -> repository.save(
                AiQuota.builder()
                        .userId(userId)
                        .dailyRequestLimit(DEFAULT_DAILY_REQUEST_LIMIT)
                        .resetDate(LocalDate.now())
                        .periodMonth(YearMonth.now().toString())
                        .updatedAt(LocalDateTime.now())
                .build()));
    }

    /** 只读查看，不因管理员打开用户详情而创建用户级覆盖。 */
    @Transactional(readOnly = true)
    public java.util.Optional<AiQuota> findForAdmin(Long userId) {
        return repository.findByUserId(userId);
    }

    @Transactional
    public AiQuota save(AiQuota quota) {
        return repository.save(quota);
    }

    /** 记录上游返回的 total_tokens；未返回 usage 的兼容模型不影响请求结果。 */
    @Transactional
    public void consumeTokens(Long userId, long tokens) {
        if (tokens <= 0) return;
        AiQuota userQuota = repository.findByUserIdForUpdate(userId).orElse(null);
        if (userQuota != null) {
            consumeTokens(userQuota, tokens);
            repository.save(userQuota);
            return;
        }
        GroupAiQuota groupQuota = effectiveGroupQuotaForUpdate(userId);
        if (groupQuota != null) {
            consumeTokens(groupQuota, tokens);
            groupQuotaRepository.save(groupQuota);
            return;
        }
        AiQuota quota = repository.save(newUserQuota(userId));
        consumeTokens(quota, tokens);
        repository.save(quota);
    }

    private AiQuota newUserQuota(Long userId) {
        return AiQuota.builder()
                .userId(userId)
                .dailyRequestLimit(DEFAULT_DAILY_REQUEST_LIMIT)
                .dailyRequestUsed(0)
                .monthlyTokenLimit(0L)
                .monthlyTokenUsed(0L)
                .resetDate(LocalDate.now())
                .periodMonth(YearMonth.now().toString())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GroupAiQuota effectiveGroupQuotaForUpdate(Long userId) {
        return groupMemberRepository.findByUserId(userId)
                .flatMap(member -> groupRepository.findById(member.getGroupId()))
                .filter(UserGroup::isEnabled)
                .flatMap(group -> groupQuotaRepository.findByGroupIdForUpdate(group.getId()))
                .orElse(null);
    }

    private void consumeRequest(AiQuota quota) {
        resetPeriods(quota);
        ensureAvailable(quota.getDailyRequestUsed(), quota.getDailyRequestLimit(),
                "今日 AI 请求额度已用完，请联系管理员调整配额");
        if (quota.getMonthlyTokenLimit() > 0) {
            ensureAvailable(quota.getMonthlyTokenUsed(), quota.getMonthlyTokenLimit(),
                    "本月 AI Token 额度已用完，请联系管理员调整配额");
        }
        quota.setDailyRequestUsed(quota.getDailyRequestUsed() + 1);
        quota.setUpdatedAt(LocalDateTime.now());
    }

    private void consumeRequest(GroupAiQuota quota) {
        resetPeriods(quota);
        ensureAvailable(quota.getDailyRequestUsed(), quota.getDailyRequestLimit(),
                "用户组今日 AI 请求额度已用完，请联系管理员调整配额");
        if (quota.getMonthlyTokenLimit() > 0) {
            ensureAvailable(quota.getMonthlyTokenUsed(), quota.getMonthlyTokenLimit(),
                    "用户组本月 AI Token 额度已用完，请联系管理员调整配额");
        }
        quota.setDailyRequestUsed(quota.getDailyRequestUsed() + 1);
        quota.setUpdatedAt(LocalDateTime.now());
    }

    private void consumeTokens(AiQuota quota, long tokens) {
        resetPeriods(quota);
        quota.setMonthlyTokenUsed(Math.addExact(quota.getMonthlyTokenUsed(), tokens));
        quota.setUpdatedAt(LocalDateTime.now());
    }

    private void consumeTokens(GroupAiQuota quota, long tokens) {
        resetPeriods(quota);
        quota.setMonthlyTokenUsed(Math.addExact(quota.getMonthlyTokenUsed(), tokens));
        quota.setUpdatedAt(LocalDateTime.now());
    }

    private void ensureAvailable(long used, long limit, String message) {
        if (used >= limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    private void resetPeriods(AiQuota quota) {
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.now();
        if (quota.getResetDate() == null || !today.equals(quota.getResetDate())) {
            quota.setDailyRequestUsed(0);
            quota.setResetDate(today);
        }
        if (quota.getPeriodMonth() == null || !month.toString().equals(quota.getPeriodMonth())) {
            quota.setMonthlyTokenUsed(0L);
            quota.setPeriodMonth(month.toString());
        }
    }

    private void resetPeriods(GroupAiQuota quota) {
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.now();
        if (quota.getResetDate() == null || !today.equals(quota.getResetDate())) {
            quota.setDailyRequestUsed(0);
            quota.setResetDate(today);
        }
        if (quota.getPeriodMonth() == null || !month.toString().equals(quota.getPeriodMonth())) {
            quota.setMonthlyTokenUsed(0L);
            quota.setPeriodMonth(month.toString());
        }
    }
}
