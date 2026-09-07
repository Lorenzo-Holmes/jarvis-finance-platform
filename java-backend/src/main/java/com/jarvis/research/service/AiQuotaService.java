package com.jarvis.research.service;

import com.jarvis.research.user.AiQuota;
import com.jarvis.research.user.AiQuotaRepository;
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

    @Transactional
    public void consumeRequest(Long userId) {
        AiQuota quota = repository.findByUserIdForUpdate(userId).orElseGet(() -> repository.save(
                AiQuota.builder()
                        .userId(userId)
                        .dailyRequestLimit(DEFAULT_DAILY_REQUEST_LIMIT)
                        .dailyRequestUsed(0)
                        .monthlyTokenLimit(0L)
                        .monthlyTokenUsed(0L)
                        .resetDate(LocalDate.now())
                        .periodMonth(YearMonth.now().toString())
                        .updatedAt(LocalDateTime.now())
                        .build()));
        resetPeriods(quota);
        if (quota.getDailyRequestUsed() >= quota.getDailyRequestLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日 AI 请求额度已用完，请联系管理员调整配额");
        }
        if (quota.getMonthlyTokenLimit() > 0
                && quota.getMonthlyTokenUsed() >= quota.getMonthlyTokenLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "本月 AI Token 额度已用完，请联系管理员调整配额");
        }
        quota.setDailyRequestUsed(quota.getDailyRequestUsed() + 1);
        quota.setUpdatedAt(LocalDateTime.now());
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

    @Transactional
    public AiQuota save(AiQuota quota) {
        return repository.save(quota);
    }

    /** 记录上游返回的 total_tokens；未返回 usage 的兼容模型不影响请求结果。 */
    @Transactional
    public void consumeTokens(Long userId, long tokens) {
        if (tokens <= 0) return;
        AiQuota quota = repository.findByUserIdForUpdate(userId).orElseGet(() -> repository.save(
                AiQuota.builder()
                        .userId(userId)
                        .dailyRequestLimit(DEFAULT_DAILY_REQUEST_LIMIT)
                        .resetDate(LocalDate.now())
                        .periodMonth(YearMonth.now().toString())
                        .updatedAt(LocalDateTime.now())
                        .build()));
        resetPeriods(quota);
        quota.setMonthlyTokenUsed(Math.addExact(quota.getMonthlyTokenUsed(), tokens));
        quota.setUpdatedAt(LocalDateTime.now());
        repository.save(quota);
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
}
