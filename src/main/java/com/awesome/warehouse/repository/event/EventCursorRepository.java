package com.awesome.warehouse.repository.event;

import com.awesome.warehouse.entity.event.EventCursorEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventCursorRepository extends JpaRepository<EventCursorEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EventCursorEntity c where c.consumer = :consumer")
    Optional<EventCursorEntity> findForUpdate(@Param("consumer") String consumer);
}
