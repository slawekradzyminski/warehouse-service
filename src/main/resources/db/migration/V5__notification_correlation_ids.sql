-- Correlation ids of the request, feed event or job that enqueued a notification,
-- restored into the logging context and sent to the backend on delivery.
-- correlation_id is the X-Request-Id value (request_id stays the idempotency key);
-- both are null for rows written before 1.4.0.
ALTER TABLE notification_outbox ADD COLUMN correlation_id varchar(128);
ALTER TABLE notification_outbox ADD COLUMN traceparent varchar(55);
