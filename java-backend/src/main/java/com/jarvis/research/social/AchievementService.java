package com.jarvis.research.social;

import com.jarvis.research.ai.ResearchTaskRepository;
import com.jarvis.research.ai.ResearchTaskStatus;
import com.jarvis.research.audit.AuditEvent;
import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.schedule.ScheduledTaskRepository;
import com.jarvis.research.schedule.ScheduledTaskRunRepository;
import com.jarvis.research.schedule.ScheduledTaskStatus;
import com.jarvis.research.schedule.TaskRunStatus;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AchievementService {
    private static final Set<String> LOGIN_ACTIONS = Set.of(
            "USER_LOGIN", "USER_REGISTER", "USER_GITHUB_LOGIN", "USER_GITHUB_REGISTER");

    private record Definition(String key, String title, String description, String category, int target) { }

    private static final List<Definition> CATALOG = List.of(
            new Definition("FIRST_LOGIN", "初次抵达", "完成首次登录", "LOGIN", 1),
            new Definition("STREAK_3", "连续研究 3 日", "连续登录 3 天", "LOGIN", 3),
            new Definition("STREAK_7", "一周研究节律", "连续登录 7 天", "LOGIN", 7),
            new Definition("FIRST_RESEARCH", "第一份研究", "完成 1 个研究任务", "RESEARCH", 1),
            new Definition("RESEARCH_5", "研究序列", "完成 5 个研究任务", "RESEARCH", 5),
            new Definition("FIRST_AUTOMATION", "自动化起点", "创建至少 1 个有效定时任务", "AUTOMATION", 1),
            new Definition("TASK_ACHIEVER_5", "任务达成者", "自动化任务成功执行 5 次", "AUTOMATION", 5),
            new Definition("FIRST_POST", "公开观点", "发布首条社区动态", "SOCIAL", 1),
            new Definition("FIRST_GROUP", "加入研究共同体", "加入或创建首个研究小组", "SOCIAL", 1),
            new Definition("FIRST_MESSAGE", "建立连接", "发送首条站内私信", "SOCIAL", 1),
            new Definition("PROFILE_COMPLETE", "完整档案", "完善头像、签名和联系方式", "PROFILE", 1)
    );

    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;
    private final ResearchTaskRepository researchTaskRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunRepository scheduledTaskRunRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityGroupMemberRepository memberRepository;
    private final DirectMessageRepository messageRepository;
    private final UserAchievementRepository achievementRepository;
    private final UserActivityRepository activityRepository;

    @Transactional
    public Map<String, Object> overview(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        Metrics metrics = metrics(user);
        Map<String, LocalDateTime> unlocked = evaluate(user, metrics);
        return Map.of(
                "streakDays", metrics.streakDays,
                "researchCompleted", metrics.researchCompleted,
                "automationCount", metrics.automationCount,
                "items", catalogState(metrics, unlocked)
        );
    }

    @Transactional
    public void evaluateSocial(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;
        Metrics metrics = metrics(user);
        evaluate(user, metrics);
    }

    public List<Map<String, Object>> persistedForPublicProfile(Long userId) {
        Map<String, Definition> defs = new HashMap<>();
        CATALOG.forEach(d -> defs.put(d.key, d));
        return achievementRepository.findByUserIdOrderByUnlockedAtAsc(userId).stream().map(row -> {
            Definition d = defs.get(row.getAchievementKey());
            if (d == null) return Map.<String, Object>of(
                    "key", row.getAchievementKey(), "title", row.getAchievementKey(), "unlocked", true,
                    "unlockedAt", row.getUnlockedAt());
            return Map.<String, Object>of(
                    "key", d.key, "title", d.title, "description", d.description,
                    "category", d.category, "unlocked", true, "unlockedAt", row.getUnlockedAt());
        }).toList();
    }

    private Map<String, LocalDateTime> evaluate(User user, Metrics m) {
        Map<String, LocalDateTime> unlocked = unlockedMap(user.getId());
        maybeUnlock(user.getId(), "FIRST_LOGIN", m.loginDays >= 1, "完成首次登录", unlocked);
        maybeUnlock(user.getId(), "STREAK_3", m.streakDays >= 3, "连续登录达到 3 天", unlocked);
        maybeUnlock(user.getId(), "STREAK_7", m.streakDays >= 7, "连续登录达到 7 天", unlocked);
        maybeUnlock(user.getId(), "FIRST_RESEARCH", m.researchCompleted >= 1, "完成首个研究任务", unlocked);
        maybeUnlock(user.getId(), "RESEARCH_5", m.researchCompleted >= 5, "累计完成 5 个研究任务", unlocked);
        maybeUnlock(user.getId(), "FIRST_AUTOMATION", m.automationCount >= 1, "创建首个定时任务", unlocked);
        maybeUnlock(user.getId(), "TASK_ACHIEVER_5", m.successfulRuns >= 5, "自动化任务累计成功执行 5 次", unlocked);
        maybeUnlock(user.getId(), "FIRST_POST", m.postCount >= 1, "发布首条社区动态", unlocked);
        maybeUnlock(user.getId(), "FIRST_GROUP", m.groupCount >= 1, "加入首个研究小组", unlocked);
        maybeUnlock(user.getId(), "FIRST_MESSAGE", m.messageCount >= 1, "发送首条站内私信", unlocked);
        maybeUnlock(user.getId(), "PROFILE_COMPLETE", profileComplete(user), "完成个人档案", unlocked);
        return unlocked;
    }

    private void maybeUnlock(Long userId, String key, boolean condition, String summary,
                             Map<String, LocalDateTime> unlocked) {
        if (!condition || unlocked.containsKey(key)) return;
        LocalDateTime unlockedAt = LocalDateTime.now();
        achievementRepository.save(UserAchievement.builder()
                .userId(userId).achievementKey(key).unlockedAt(unlockedAt).build());
        unlocked.put(key, unlockedAt);
        activityRepository.save(UserActivity.builder()
                .userId(userId).activityType("ACHIEVEMENT_UNLOCKED")
                .summary("解锁成就：" + summary).referenceType("ACHIEVEMENT").referenceId(key)
                .createdAt(LocalDateTime.now()).build());
    }

    private Map<String, LocalDateTime> unlockedMap(Long userId) {
        Map<String, LocalDateTime> unlocked = new HashMap<>();
        achievementRepository.findByUserIdOrderByUnlockedAtAsc(userId)
                .forEach(a -> unlocked.put(a.getAchievementKey(), a.getUnlockedAt()));
        return unlocked;
    }

    private List<Map<String, Object>> catalogState(Metrics m, Map<String, LocalDateTime> unlocked) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition d : CATALOG) {
            int progress = progressFor(d.key, m);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", d.key); item.put("title", d.title); item.put("description", d.description);
            item.put("category", d.category); item.put("target", d.target);
            item.put("progress", Math.min(progress, d.target));
            item.put("unlocked", unlocked.containsKey(d.key));
            item.put("unlockedAt", unlocked.get(d.key));
            out.add(item);
        }
        return out;
    }

    private int progressFor(String key, Metrics m) {
        return switch (key) {
            case "FIRST_LOGIN" -> m.loginDays;
            case "STREAK_3", "STREAK_7" -> m.streakDays;
            case "FIRST_RESEARCH", "RESEARCH_5" -> (int) Math.min(Integer.MAX_VALUE, m.researchCompleted);
            case "FIRST_AUTOMATION" -> (int) Math.min(Integer.MAX_VALUE, m.automationCount);
            case "TASK_ACHIEVER_5" -> (int) Math.min(Integer.MAX_VALUE, m.successfulRuns);
            case "FIRST_POST" -> (int) Math.min(Integer.MAX_VALUE, m.postCount);
            case "FIRST_GROUP" -> (int) Math.min(Integer.MAX_VALUE, m.groupCount);
            case "FIRST_MESSAGE" -> (int) Math.min(Integer.MAX_VALUE, m.messageCount);
            case "PROFILE_COMPLETE" -> m.profileComplete ? 1 : 0;
            default -> 0;
        };
    }

    private Metrics metrics(User user) {
        List<AuditEvent> logins = auditEventRepository.findByUserIdAndActionInOrderByCreatedAtDesc(
                user.getId(), LOGIN_ACTIONS, PageRequest.of(0, 180));
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        for (AuditEvent event : logins) if (event.getCreatedAt() != null) dates.add(event.getCreatedAt().toLocalDate());
        int streak = streakDays(dates);
        long research = researchTaskRepository.countByUserIdAndStatus(user.getId(), ResearchTaskStatus.SUCCEEDED);
        long automation = scheduledTaskRepository.countByUserIdAndStatusNot(user.getId(), ScheduledTaskStatus.DELETED);
        long successfulRuns = scheduledTaskRunRepository.countByUserIdAndStatus(user.getId(), TaskRunStatus.SUCCESS);
        return new Metrics(dates.size(), streak, research, automation,
                successfulRuns,
                postRepository.countByAuthorUserId(user.getId()), memberRepository.countByUserId(user.getId()),
                messageRepository.countBySenderUserId(user.getId()), profileComplete(user));
    }

    static int streakDays(Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) return 0;
        TreeSet<LocalDate> sorted = new TreeSet<>(Comparator.reverseOrder());
        sorted.addAll(dates);
        LocalDate latest = sorted.first();
        LocalDate today = LocalDate.now();
        if (latest.isBefore(today.minusDays(1))) return 0;
        int streak = 0;
        LocalDate cursor = latest;
        for (LocalDate date : sorted) {
            if (date.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (date.isBefore(cursor)) {
                break;
            }
        }
        return streak;
    }

    private static boolean profileComplete(User user) {
        return hasText(user.getDisplayName()) && hasText(user.getAvatarUrl())
                && hasText(user.getSignature()) && hasText(user.getContactInfo());
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private record Metrics(int loginDays, int streakDays, long researchCompleted, long automationCount,
                           long successfulRuns, long postCount, long groupCount, long messageCount,
                           boolean profileComplete) { }
}
