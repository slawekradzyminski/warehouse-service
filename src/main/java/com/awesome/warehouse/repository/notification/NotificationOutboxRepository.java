package com.awesome.warehouse.repository.notification;

import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, Long> {

    /** Unsent notifications whose next attempt is due, oldest first. */
    @Query("""
            select n from NotificationOutboxEntity n
            where n.sentAt is null and n.attempts < :maxAttempts and n.nextAttemptAt <= :now
            order by n.id""")
    List<NotificationOutboxEntity> findDue(@Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts,
                                           Pageable pageable);

    /** Pending rows: not sent and fewer than {@code maxAttempts} attempts (ADR 3 row state). */
    @Query("""
            select count(n) from NotificationOutboxEntity n
            where n.sentAt is null and n.attempts < :maxAttempts""")
    long countPending(@Param("maxAttempts") int maxAttempts);
}
