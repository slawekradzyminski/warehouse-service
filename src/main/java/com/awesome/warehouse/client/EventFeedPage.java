package com.awesome.warehouse.client;

import java.util.List;

/**
 * Body of {@code GET /internal/v1/events}: events ordered by sequence ascending,
 * the cursor to pass as {@code after} next time and whether more events are waiting.
 */
public record EventFeedPage(List<FeedEvent> events, long nextCursor, boolean hasMore) {

    public EventFeedPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
