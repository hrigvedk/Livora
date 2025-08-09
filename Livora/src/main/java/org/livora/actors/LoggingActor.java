package org.livora.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.livora.messages.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public class LoggingActor extends AbstractBehavior<Command> {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(java.time.ZoneId.systemDefault());

    private final AtomicLong messageCounter = new AtomicLong(0);
    private final String nodeId;

    public static Behavior<Command> create() {
        return Behaviors.setup(LoggingActor::new);
    }

    private LoggingActor(ActorContext<Command> context) {
        super(context);
        this.nodeId = context.getSystem().address().toString();

        getContext().getLog().info("LoggingActor started on node: {}", nodeId);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(LoggingMessages.LogEntry.class, this::onLogEntry)
                .onMessage(LoggingMessages.LogSearchPerformed.class, this::onLogSearchPerformed)
                .build();
    }

    private Behavior<Command> onLogEntry(LoggingMessages.LogEntry logEntry) {
        long msgId = messageCounter.incrementAndGet();

        String formattedTimestamp = TIMESTAMP_FORMAT.format(logEntry.timestamp.atZone(java.time.ZoneId.systemDefault()));
        String senderInfo = logEntry.originalSender.path().toString();

        // Structured logging output
        getContext().getLog().info(
                "[LOG-{}] [{}] [FROM: {}] {}",
                msgId,
                formattedTimestamp,
                senderInfo,
                logEntry.message
        );

        // Could also write to file, send to monitoring system, etc.
        logToStructuredOutput("INFO", logEntry.message, senderInfo, msgId);

        return this;
    }

    private Behavior<Command> onLogSearchPerformed(
            LoggingMessages.LogSearchPerformed searchLog) {

        long msgId = messageCounter.incrementAndGet();
        String requesterPath = searchLog.requester.path().toString();

        // FIX: Add null check for criteria
        if (searchLog.criteria == null) {
            getContext().getLog().error("SearchCriteria is null in LogSearchPerformed message");
            return this;
        }

        // This demonstrates FORWARD pattern handling - we can see the original
        // requester information preserved in the message
        getContext().getLog().info(
                "[SEARCH-{}] [FROM: {}] Search performed - {} results for criteria: bedrooms={}, maxPrice={}, location={}",
                msgId,
                requesterPath,
                searchLog.resultCount,
                searchLog.criteria.getBedrooms().orElse(null),
                searchLog.criteria.getMaxPrice().orElse(null),
                searchLog.criteria.getLocation().orElse("any")
        );

        // Log detailed search metrics
        logSearchMetrics(searchLog, msgId);

        return this;
    }

    private void logToStructuredOutput(String level, String message, String sender, long msgId) {
        // This could write to a structured log file, send to ELK stack, etc.
        // For demo purposes, we'll just format it nicely
        System.out.printf("[%s] [Node: %s] [Msg: %d] [Sender: %s] %s%n",
                level, nodeId, msgId, sender, message);
    }

    private void logSearchMetrics(LoggingMessages.LogSearchPerformed searchLog, long msgId) {
        // FIX: Add null check for criteria
        if (searchLog.criteria == null) {
            getContext().getLog().error("Cannot log search metrics - criteria is null");
            return;
        }

        // Extract search criteria for metrics
        StringBuilder criteriaBuilder = new StringBuilder();

        searchLog.criteria.getBedrooms().ifPresent(br ->
                criteriaBuilder.append("bedrooms=").append(br).append(" "));
        searchLog.criteria.getMaxPrice().ifPresent(price ->
                criteriaBuilder.append("maxPrice=").append(price).append(" "));
        searchLog.criteria.getLocation().ifPresent(loc ->
                criteriaBuilder.append("location=").append(loc).append(" "));
        searchLog.criteria.getPetFriendly().ifPresent(pets ->
                criteriaBuilder.append("petFriendly=").append(pets).append(" "));
        searchLog.criteria.getParking().ifPresent(parking ->
                criteriaBuilder.append("parking=").append(parking).append(" "));

        if (!searchLog.criteria.getAmenities().isEmpty()) {
            criteriaBuilder.append("amenities=").append(searchLog.criteria.getAmenities()).append(" ");
        }

        String criteriaStr = criteriaBuilder.toString().trim();
        if (criteriaStr.isEmpty()) {
            criteriaStr = "no_criteria";
        }

        // Structured metrics output
        System.out.printf("[METRICS] [Node: %s] [Msg: %d] SEARCH_PERFORMED results=%d criteria=[%s] requester=%s%n",
                nodeId, msgId, searchLog.resultCount, criteriaStr,
                searchLog.requester.path().toString());

        // Track search patterns for analytics
        trackSearchPattern(searchLog);
    }

    private void trackSearchPattern(LoggingMessages.LogSearchPerformed searchLog) {
        // This could feed into analytics, ML models for recommendations, etc.
        // For now, just log interesting patterns

        if (searchLog.resultCount == 0) {
            getContext().getLog().warn("Zero results for search criteria - may need data expansion");
        } else if (searchLog.resultCount > 20) {
            getContext().getLog().info("High result count ({}) - search may benefit from more specific criteria",
                    searchLog.resultCount);
        }

        // Log expensive searches
        boolean hasMultipleCriteria =
                searchLog.criteria.getBedrooms().isPresent() &&
                        searchLog.criteria.getMaxPrice().isPresent() &&
                        searchLog.criteria.getLocation().isPresent();

        if (hasMultipleCriteria) {
            getContext().getLog().debug("Complex multi-criteria search performed");
        }
    }
}