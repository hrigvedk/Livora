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
import org.livora.models.SearchCriteria;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class UserRequestActor extends AbstractBehavior<Command> {

    private final ActorRef<VectorSearchActor.Command> vectorSearch;
    private final ActorRef<Command> queryParser;
    private final ActorRef<Command> apartmentSearch;
    private final ActorRef<Command> logger;

    private final java.util.Map<String, UserRequestMessages.SearchRequest> pendingRequests =
            new ConcurrentHashMap<>();

    private final java.util.Map<String, VectorSearchActor.SearchResults> vectorResults =
            new ConcurrentHashMap<>();

    public static Behavior<Command> create() {
        return Behaviors.setup(UserRequestActor::new);
    }

    private UserRequestActor(ActorContext<Command> context) {
        super(context);

        Cluster cluster = Cluster.get(context.getSystem());

        this.vectorSearch = context.spawn(VectorSearchActor.create(), "vectorSearch");
        this.queryParser = context.spawn(QueryParserActor.create(), "queryParser");
        this.apartmentSearch = context.spawn(ApartmentSearchActor.create(), "apartmentSearch");
        this.logger = context.spawn(LoggingActor.create(), "logger");

        context.getLog().info("UserRequestActor started with RAG integration on node with roles: {}",
                cluster.selfMember().roles());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(UserRequestMessages.SearchRequest.class, this::onSearchRequest)
                .onMessage(UserRequestMessages.VectorSearchComplete.class, this::onVectorSearchComplete)
                .onMessage(UserRequestMessages.ProcessParsedQuery.class, this::onProcessParsedQuery)
                .onMessage(UserRequestMessages.QueryParsingFailed.class, this::onQueryParsingFailed)
                .onMessage(CorrelatedApartmentsFound.class, this::onCorrelatedApartmentsFound)
                .build();
    }

//    private Behavior<Command> onSearchRequest(UserRequestMessages.SearchRequest request) {
//        long startTime = System.currentTimeMillis();
//
//        getContext().getLog().info("Processing search request: {} for session: {}",
//                request.query, request.sessionId);
//
//        // Store the request for correlation
//        pendingRequests.put(request.sessionId, request);
//
//        // Log the incoming request using TELL pattern (fire and forget)
//        logger.tell(new LoggingMessages.LogEntry(
//                "Search request received: " + request.query,
//                Instant.now(),
//                getContext().getSelf()
//        ));
//
//        // STEP 1: First do vector search to get semantically similar apartments
//        getContext().getLog().info("Step 1: Performing vector search for semantic similarity");
//
//        getContext().ask(
//                VectorSearchActor.SearchResults.class,
//                vectorSearch,
//                Duration.ofSeconds(10),
//                (ActorRef<VectorSearchActor.SearchResults> ref) ->
//                        new VectorSearchActor.SearchSimilar(request.query, 20, ref),
//                (response, throwable) -> {
//                    if (response != null) {
//                        return new UserRequestMessages.VectorSearchComplete(response, request.sessionId);
//                    } else {
//                        // Fallback to empty vector results
//                        getContext().getLog().warn("Vector search failed, proceeding without RAG context");
//                        VectorSearchActor.SearchResults emptyResults =
//                                new VectorSearchActor.SearchResults(List.of(), List.of(), 0);
//                        return new UserRequestMessages.VectorSearchComplete(emptyResults, request.sessionId);
//                    }
//                }
//        );
//
//        return this;
//    }


    private Behavior<Command> onSearchRequest(UserRequestMessages.SearchRequest request) {
        long startTime = System.currentTimeMillis();

        getContext().getLog().info("Processing search request: {} for session: {}",
                request.query, request.sessionId);

        pendingRequests.put(request.sessionId, request);

        logger.tell(new LoggingMessages.LogEntry(
                "Search request received: " + request.query,
                Instant.now(),
                getContext().getSelf()
        ));

        if (request.query == null || request.query.trim().isEmpty()) {
            getContext().getLog().info("Empty query detected, returning all apartments");

            SearchCriteria allApartmentsCriteria = new SearchCriteria(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null
            );

            getContext().ask(
                    ApartmentSearchMessages.ApartmentsFound.class,
                    apartmentSearch,
                    Duration.ofSeconds(10),
                    (ActorRef<ApartmentSearchMessages.ApartmentsFound> ref) ->
                            new ApartmentSearchMessages.FindApartments(allApartmentsCriteria, ref),
                    (response, throwable) -> {
                        if (response != null) {
                            return new CorrelatedApartmentsFound(response, request.sessionId);
                        } else {
                            getContext().getLog().error("Apartment search failed for empty query");
                            return new CorrelatedApartmentsFound(
                                    new ApartmentSearchMessages.ApartmentsFound(List.of(), 0),
                                    request.sessionId
                            );
                        }
                    }
            );

            return this;
        }

        getContext().getLog().info("Step 1: Performing vector search for semantic similarity");

        getContext().ask(
                VectorSearchActor.SearchResults.class,
                vectorSearch,
                Duration.ofSeconds(10),
                (ActorRef<VectorSearchActor.SearchResults> ref) ->
                        new VectorSearchActor.SearchSimilar(request.query, 20, ref),
                (response, throwable) -> {
                    if (response != null) {
                        return new UserRequestMessages.VectorSearchComplete(response, request.sessionId);
                    } else {
                        getContext().getLog().warn("Vector search failed, proceeding without RAG context");
                        VectorSearchActor.SearchResults emptyResults =
                                new VectorSearchActor.SearchResults(List.of(), List.of(), 0);
                        return new UserRequestMessages.VectorSearchComplete(emptyResults, request.sessionId);
                    }
                }
        );

        return this;
    }

    private Behavior<Command> onVectorSearchComplete(UserRequestMessages.VectorSearchComplete message) {
        getContext().getLog().info("Step 2: Vector search complete, found {} similar apartments for session {}",
                message.vectorResults.apartments.size(), message.sessionId);

        vectorResults.put(message.sessionId, message.vectorResults);

        UserRequestMessages.SearchRequest originalRequest = pendingRequests.get(message.sessionId);
        if (originalRequest == null) {
            getContext().getLog().error("Lost original request for session: {}", message.sessionId);
            return this;
        }

        getContext().getLog().info("Step 3: Parsing query with RAG context");

        getContext().ask(
                QueryParserMessages.QueryParsed.class,
                queryParser,
                Duration.ofSeconds(10),
                (ActorRef<QueryParserMessages.QueryParsed> ref) ->
                        new QueryParserMessages.ParseQueryWithRAG(
                                originalRequest.query,
                                message.vectorResults,
                                ref
                        ),
                (response, throwable) -> {
                    if (response != null) {
                        return new UserRequestMessages.ProcessParsedQuery(response, originalRequest);
                    } else {
                        getContext().getLog().error("Query parsing failed for session: {}",
                                originalRequest.sessionId, throwable);
                        return new UserRequestMessages.QueryParsingFailed(originalRequest);
                    }
                }
        );

        return this;
    }

    private Behavior<Command> onProcessParsedQuery(UserRequestMessages.ProcessParsedQuery message) {
        getContext().getLog().info("Step 4: Query parsed with confidence: {}",
                message.queryParsed.confidence);

        String sessionId = message.originalRequest.sessionId;

        VectorSearchActor.SearchResults vectorResults = this.vectorResults.get(sessionId);

        if (message.queryParsed.confidence < 0.3) {
            getContext().getLog().warn("Low confidence parse, prioritizing vector search results");
        }

        getContext().getLog().info("Step 5: Performing hybrid search (structured + semantic)");

        getContext().ask(
                ApartmentSearchMessages.ApartmentsFound.class,
                apartmentSearch,
                Duration.ofSeconds(10),
                (ActorRef<ApartmentSearchMessages.ApartmentsFound> ref) ->
                        new ApartmentSearchMessages.FindApartmentsHybrid(
                                message.queryParsed.criteria,
                                vectorResults, // Include vector results for hybrid scoring
                                ref
                        ),
                (response, throwable) -> {
                    if (response != null) {
                        return new CorrelatedApartmentsFound(response, sessionId);
                    } else {
                        return new CorrelatedApartmentsFound(
                                new ApartmentSearchMessages.ApartmentsFound(List.of(), 0), sessionId);
                    }
                }
        );

        return this;
    }

    private Behavior<Command> onCorrelatedApartmentsFound(CorrelatedApartmentsFound message) {
        getContext().getLog().info("Step 6: Received {} apartment results for session {}",
                message.apartmentsFound.apartments.size(), message.sessionId);

        UserRequestMessages.SearchRequest originalRequest = pendingRequests.remove(message.sessionId);

        vectorResults.remove(message.sessionId);

        if (originalRequest != null) {
            logger.tell(new LoggingMessages.LogSearchPerformed(
                    null,
                    message.apartmentsFound.apartments.size(),
                    getContext().getSelf()
            ));

            UserRequestMessages.SearchMetadata metadata = new UserRequestMessages.SearchMetadata(
                    message.apartmentsFound.totalMatches,
                    50L,
                    0.9
            );

            originalRequest.replyTo.tell(
                    new UserRequestMessages.SearchResponse(
                            message.apartmentsFound.apartments,
                            originalRequest.sessionId,
                            metadata
                    )
            );

            getContext().getLog().info("Step 7: Response sent for session: {}", originalRequest.sessionId);

            logger.tell(new LoggingMessages.LogEntry(
                    String.format("Search completed with %d results",
                            message.apartmentsFound.apartments.size()),
                    Instant.now(),
                    getContext().getSelf()
            ));

        } else {
            getContext().getLog().error("Lost original request for session: {}", message.sessionId);
        }

        return this;
    }

    private Behavior<Command> onQueryParsingFailed(UserRequestMessages.QueryParsingFailed message) {
        getContext().getLog().error("Query parsing failed for session: {}",
                message.originalRequest.sessionId);

        pendingRequests.remove(message.originalRequest.sessionId);
        vectorResults.remove(message.originalRequest.sessionId);

        UserRequestMessages.SearchMetadata metadata = new UserRequestMessages.SearchMetadata(0, 0, 0.0);
        message.originalRequest.replyTo.tell(
                new UserRequestMessages.SearchResponse(
                        List.of(),
                        message.originalRequest.sessionId,
                        metadata
                )
        );

        return this;
    }

    public static final class CorrelatedApartmentsFound implements Command {
        public final ApartmentSearchMessages.ApartmentsFound apartmentsFound;
        public final String sessionId;

        public CorrelatedApartmentsFound(
                ApartmentSearchMessages.ApartmentsFound apartmentsFound,
                String sessionId) {
            this.apartmentsFound = apartmentsFound;
            this.sessionId = sessionId;
        }
    }
}