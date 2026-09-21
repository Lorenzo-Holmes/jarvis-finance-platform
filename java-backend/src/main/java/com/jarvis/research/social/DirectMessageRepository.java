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
