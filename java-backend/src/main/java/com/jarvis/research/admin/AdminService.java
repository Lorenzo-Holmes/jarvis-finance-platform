package com.jarvis.research.admin;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiQuotaService;
import com.jarvis.research.user.AiQuota;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserFeaturePermission;
import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jarvis.research.admin.AdminDtos.*;

/** 管理员账户、AI 配额和用户功能权限管理。 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AiQuotaService quotaService;
    private final UserFeaturePermissionRepository permissionRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Map<String, Object> listUsers(String query, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1~200 之间");
        }
        PageRequest pageRequest = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> page = query == null || query.isBlank()
                ? userRepository.findAll(pageRequest)
                : userRepository.findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                        query.trim(), query.trim(), pageRequest);
        List<Map<String, Object>> users = page.getContent().stream().map(this::basicUserView).toList();
        return Map.of("items", users, "count", users.size(), "total", page.getTotalElements());
    }

    @Transactional
    public Map<String, Object> userDetails(Long userId) {
        User user = requireUser(userId);
        return fullUserView(user);
    }

    @Transactional
    public Map<String, Object> updateStatus(Long actorId, Long userId, boolean enabled, String clientIp) {
        if (actorId.equals(userId) && !enabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能禁用当前管理员账号");
        }
        User user = requireUser(userId);
        user.setEnabled(enabled);
        userRepository.save(user);
        auditService.record(actorId, "ADMIN_USER_STATUS", "user:" + userId, clientIp,
                "enabled=" + enabled + "; email=" + user.getEmail());
        return basicUserView(user);
    }

    @Transactional
    public Map<String, Object> updateRole(Long actorId, Long userId, String role, String clientIp) {
        User user = requireUser(userId);
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!"USER".equals(normalized) && !"ADMIN".equals(normalized)) {
            throw new IllegalArgumentException("role 只能为 USER 或 ADMIN");
        }
        if (actorId.equals(userId) && !"ADMIN".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除当前管理员的管理员权限");
        }
        user.setRole(normalized);
        userRepository.save(user);
        auditService.record(actorId, "ADMIN_USER_ROLE", "user:" + userId, clientIp,
                "role=" + normalized + "; email=" + user.getEmail());
        return basicUserView(user);
    }

    @Transactional
    public Map<String, Object> updateQuota(Long actorId, Long userId, QuotaRequest request, String clientIp) {
        User user = requireUser(userId);
        AiQuota quota = quotaService.getOrCreateForAdmin(userId);
        quota.setDailyRequestLimit(request.getDailyRequestLimit());
        quota.setMonthlyTokenLimit(request.getMonthlyTokenLimit());
        quota.setUpdatedAt(LocalDateTime.now());
        quotaService.save(quota);
        auditService.record(actorId, "ADMIN_AI_QUOTA", "user:" + userId, clientIp,
                "dailyRequestLimit=" + request.getDailyRequestLimit()
                        + "; monthlyTokenLimit=" + request.getMonthlyTokenLimit()
                        + "; reason=" + request.getReason());
        return quotaView(user, quota);
    }

    @Transactional
    public Map<String, Object> updatePermissions(Long actorId, Long userId,
                                                 PermissionsRequest request, String clientIp) {
        User user = requireUser(userId);
        List<String> features = request.getFeatures() == null ? List.of() : request.getFeatures().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(100)
                .toList();
        permissionRepository.deleteByUserId(userId);
        List<UserFeaturePermission> entities = new ArrayList<>();
        for (String feature : features) {
            entities.add(UserFeaturePermission.builder()
                    .userId(userId)
                    .featureKey(feature)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        permissionRepository.saveAll(entities);
        auditService.record(actorId, "ADMIN_USER_PERMISSIONS", "user:" + userId, clientIp,
                "features=" + String.join(",", features) + "; reason=" + request.getReason());
        return fullUserView(user);
    }

    private Map<String, Object> fullUserView(User user) {
        Map<String, Object> out = new LinkedHashMap<>(basicUserView(user));
        AiQuota quota = quotaService.getOrCreateForAdmin(user.getId());
        out.put("quota", quotaView(user, quota).get("quota"));
        out.put("permissions", permissionRepository.findByUserIdOrderByFeatureKey(user.getId()).stream()
                .map(UserFeaturePermission::getFeatureKey).toList());
        return out;
    }

    private Map<String, Object> quotaView(User user, AiQuota quota) {
        Map<String, Object> quotaData = new LinkedHashMap<>();
        quotaData.put("dailyRequestLimit", quota.getDailyRequestLimit());
        quotaData.put("dailyRequestUsed", quota.getDailyRequestUsed());
        quotaData.put("monthlyTokenLimit", quota.getMonthlyTokenLimit());
        quotaData.put("monthlyTokenUsed", quota.getMonthlyTokenUsed());
        quotaData.put("resetDate", quota.getResetDate());
        quotaData.put("periodMonth", quota.getPeriodMonth());
        return Map.of("userId", user.getId(), "quota", quotaData);
    }

    private Map<String, Object> basicUserView(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("email", user.getEmail());
        out.put("displayName", user.getDisplayName());
        out.put("role", user.getRole());
        out.put("enabled", user.isEnabled());
        out.put("createdAt", user.getCreatedAt());
        return out;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }
}
