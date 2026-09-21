package com.jarvis.research.social;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {
    long countBySenderUserId(Long senderUserId);
    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    @Query("select m from DirectMessage m where m.senderUserId = :userId or m.recipientUserId = :userId order by m.createdAt desc")
    List<DirectMessage> findRecentForUser(@Param("userId") Long userId, Pageable pageable);

    @Query(value = """
            SELECT id, sender_user_id, recipient_user_id, content, created_at, read_at
            FROM (
                SELECT dm.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY CASE
                               WHEN dm.sender_user_id = :userId THEN dm.recipient_user_id
                               ELSE dm.sender_user_id
                           END
                           ORDER BY dm.created_at DESC, dm.id DESC
                       ) AS rn
                FROM direct_message dm
                WHERE dm.sender_user_id = :userId OR dm.recipient_user_id = :userId
            ) ranked
            WHERE rn = 1
            ORDER BY created_at DESC, id DESC
            """, nativeQuery = true)
    List<DirectMessage> findLatestConversationMessages(@Param("userId") Long userId, Pageable pageable);

    @Query("select m.senderUserId, count(m) from DirectMessage m " +
            "where m.recipientUserId = :userId and m.readAt is null group by m.senderUserId")
    List<Object[]> countUnreadBySender(@Param("userId") Long userId);

    @Query("select m from DirectMessage m where " +
            "(m.senderUserId = :a and m.recipientUserId = :b) or " +
            "(m.senderUserId = :b and m.recipientUserId = :a) order by m.createdAt desc")
    Page<DirectMessage> findThread(@Param("a") Long a, @Param("b") Long b, Pageable pageable);

    @Modifying
    @Query("update DirectMessage m set m.readAt = :now where m.senderUserId = :other " +
            "and m.recipientUserId = :current and m.readAt is null")
    int markThreadRead(@Param("current") Long current, @Param("other") Long other,
                       @Param("now") LocalDateTime now);
}
