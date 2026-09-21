package com.jarvis.research.social;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:direct-message-repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DirectMessageRepositoryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DirectMessageRepository repository;
    long me;
    long a;
    long b;

    @BeforeEach
    void seedUsers() {
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", "dm-me@example.com", "x");
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", "dm-a@example.com", "x");
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", "dm-b@example.com", "x");
        me = id("dm-me@example.com"); a = id("dm-a@example.com"); b = id("dm-b@example.com");
    }

    @Test
    void latestConversationQueryReturnsOneNewestMessagePerPartner() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(message(a, me, "old-a", now.minusMinutes(3)));
        repository.save(message(me, a, "new-a", now.minusMinutes(1)));
        repository.save(message(b, me, "only-b", now.minusMinutes(2)));

        List<DirectMessage> rows = repository.findLatestConversationMessages(me, PageRequest.of(0, 20));

        assertEquals(List.of("new-a", "only-b"), rows.stream().map(DirectMessage::getContent).toList());
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private DirectMessage message(long from, long to, String content, LocalDateTime at) {
        return DirectMessage.builder().senderUserId(from).recipientUserId(to).content(content).createdAt(at).build();
    }
}
