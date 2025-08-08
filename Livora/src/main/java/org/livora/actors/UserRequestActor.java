package org.livora.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.ClusterSingleton;
import akka.cluster.typed.SingletonActor;
import org.livora.messages.*;
import org.livora.models.Apartment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class UserRequestActor extends AbstractBehavior<Command> {

    private final ActorRef<Command> queryParser;
    private final ActorRef<Command> apartmentSearch;
    private final ActorRef<Command> logger;

    // Store pending requests to correlate responses
    private final java.util.Map<String, UserRequestMessages.SearchRequest> pendingRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static Behavior<Command> create() {
        return Behaviors.setup(UserRequestActor::new);
    }

    private UserRequestActor(ActorContext<Command> context) {
        super(context);

        // Get cluster instance to find actors on other nodes
        Cluster cluster = Cluster.get(context.getSystem());

        // Initialize actor references - these would be discovered via cluster
        // For now, spawning locally for demonstration
        this.queryParser = context.spawn(QueryParserActor.create(), "queryParser");
        this.apartmentSearch = context.spawn(ApartmentSearchActor.create(), "apartmentSearch");
        this.logger = context.spawn(LoggingActor.create(), "logger");

        context.getLog().info("UserRequestActor started on node with roles: {}",
                cluster.selfMember().roles());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(UserRequestMessages.SearchRequest.class, this::onSearchRequest)
                .onMessage(UserRequestMessages.ProcessParsedQuery.class, this::onProcessParsedQuery)
                .onMessage(UserRequestMessages.QueryParsingFailed.class, this::onQueryParsingFailed)
                .onMessage(CorrelatedApartmentsFound.class, this::onCorrelatedApartmentsFound)
                .build();
    }

    private Behavior<Command> onSearchRequest(
            UserRequestMessages.SearchRequest request) {

        long startTime = System.currentTimeMillis();

        getContext().getLog().info("Processing search request: {} for session: {}",
                request.query, request.sessionId);

        // Log the incoming request using TELL pattern (fire and forget)
        logger.tell(new LoggingMessages.LogEntry(
                "Search request received: " + request.query,
                Instant.now(),
                getContext().getSelf()
        ));

        // Use ASK pattern to get parsed query from QueryParserActor
        getContext().ask(
                QueryParserMessages.QueryParsed.class,
                queryParser,
                Duration.ofSeconds(10),
                (ActorRef<QueryParserMessages.QueryParsed> ref) ->
                        new QueryParserMessages.ParseQuery(request.query, ref),
                (response, throwable) -> {
                    if (response != null) {
                        return new UserRequestMessages.ProcessParsedQuery(response, request);
                    } else {
                        return new UserRequestMessages.QueryParsingFailed(request, throwable);
                    }
                }
        );

        return this;
    }

    private Behavior<Command> onProcessParsedQuery(
            UserRequestMessages.ProcessParsedQuery message) {

        getContext().getLog().info("Query parsed with confidence: {}",
                message.queryParsed.confidence);

        // Store the original request for correlation
        String requestId = message.originalRequest.sessionId;
        pendingRequests.put(requestId, message.originalRequest);

        // If confidence is too low, could implement fallback logic here
        if (message.queryParsed.confidence < 0.3) {
            getContext().getLog().warn("Low confidence parse, using fallback criteria");
        }

        // Use ASK pattern to search for apartments
        getContext().ask(
                ApartmentSearchMessages.ApartmentsFound.class,
                apartmentSearch,
                Duration.ofSeconds(5),
                (ActorRef<ApartmentSearchMessages.ApartmentsFound> ref) ->
                        new ApartmentSearchMessages.FindApartments(message.queryParsed.criteria, ref),
                (response, throwable) -> {
                    if (response != null) {
                        // Create a correlated response
                        return new CorrelatedApartmentsFound(response, requestId);
                    } else {
                        // Create empty response for error case
                        return new CorrelatedApartmentsFound(
                                new ApartmentSearchMessages.ApartmentsFound(List.of(), 0), requestId);
                    }
                }
        );

        return this;
    }

    private Behavior<Command> onCorrelatedApartmentsFound(CorrelatedApartmentsFound message) {
        getContext().getLog().info("Received {} apartment results for session {}",
                message.apartmentsFound.apartments.size(), message.sessionId);

        // Get the original request
        UserRequestMessages.SearchRequest originalRequest = pendingRequests.remove(message.sessionId);

        if (originalRequest != null) {
            // Create response metadata
            UserRequestMessages.SearchMetadata metadata = new UserRequestMessages.SearchMetadata(
                    message.apartmentsFound.totalMatches,
                    50L, // searchTimeMs - could calculate actual time
                    0.9   // confidence from parser
            );

            // Send response back to HTTP layer
            originalRequest.replyTo.tell(
                    new UserRequestMessages.SearchResponse(
                            message.apartmentsFound.apartments,
                            originalRequest.sessionId,
                            metadata
                    )
            );

            getContext().getLog().info("Response sent for session: {}", originalRequest.sessionId);
        } else {
            getContext().getLog().warn("No pending request found for session: {}", message.sessionId);
        }

        // Log completion
        logger.tell(new LoggingMessages.LogEntry(
                String.format("Search completed with %d results", message.apartmentsFound.apartments.size()),
                java.time.Instant.now(),
                getContext().getSelf()
        ));

        return this;
    }

    // Helper class to correlate apartment search results with original requests
    public static class CorrelatedApartmentsFound implements Command {
        public final ApartmentSearchMessages.ApartmentsFound apartmentsFound;
        public final String sessionId;

        public CorrelatedApartmentsFound(ApartmentSearchMessages.ApartmentsFound apartmentsFound, String sessionId) {
            this.apartmentsFound = apartmentsFound;
            this.sessionId = sessionId;
        }
    }

    private Behavior<Command> onQueryParsingFailed(
            UserRequestMessages.QueryParsingFailed failed) {

        getContext().getLog().error("Query parsing failed for: {}",
                failed.originalRequest.query, failed.error);

        // Log the failure using TELL pattern
        logger.tell(new LoggingMessages.LogEntry(
                "Query parsing failed: " + failed.error.getMessage(),
                Instant.now(),
                getContext().getSelf()
        ));

        // Could implement fallback parsing logic here
        // For now, return empty results to the original requester
        UserRequestMessages.SearchMetadata metadata = new UserRequestMessages.SearchMetadata(
                0, 0, 0.0);

        failed.originalRequest.replyTo.tell(
                new UserRequestMessages.SearchResponse(
                        List.of(),
                        failed.originalRequest.sessionId,
                        metadata
                )
        );

        return this;
    }
}