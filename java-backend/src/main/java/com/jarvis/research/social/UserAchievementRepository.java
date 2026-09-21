package com.jarvis.research.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserAchievementRepository extends JpaRepository<UserAchievement, Long> {
    List<UserAchievement> findByUserIdOrderByUnlockedAtAsc(Long userId);
    boolean existsByUserIdAndAchievementKey(Long userId, String achievementKey);
}
