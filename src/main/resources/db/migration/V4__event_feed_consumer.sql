-- Consumer side of the backend's domain event feed (GET /internal/v1/events).
-- event_cursors holds the last feed sequence each consumer has handled;
-- processed_events makes handling idempotent when an event is read twice
-- (a replayed page, or two instances polling at the same time).
CREATE TABLE event_cursors (
    consumer varchar(64) PRIMARY KEY,
    last_sequence bigint NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE TABLE processed_events (
    event_id uuid PRIMARY KEY,
    processed_at timestamp(6) NOT NULL
);
CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);
