package org.livora.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpsertPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.JsonWithInt.Value;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;

import org.livora.messages.Command;
import org.livora.messages.LoggingMessages;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.time.Instant;

public class VectorSearchActor extends AbstractBehavior<VectorSearchActor.Command> {

    // Message definitions (keeping these the same)
    public interface Command extends org.livora.messages.Command {}

    public static final class IndexApartment implements Command {
        public final Apartment apartment;
        public final ActorRef<IndexingResult> replyTo;

        @JsonCreator
        public IndexApartment(
                @JsonProperty("apartment") Apartment apartment,
                @JsonProperty("replyTo") ActorRef<IndexingResult> replyTo) {
            this.apartment = apartment;
            this.replyTo = replyTo;
        }
    }

    public static final class SearchSimilar implements Command {
        public final String query;
        public final int limit;
        public final ActorRef<SearchResults> replyTo;

        @JsonCreator
        public SearchSimilar(
                @JsonProperty("query") String query,
                @JsonProperty("limit") int limit,
                @JsonProperty("replyTo") ActorRef<SearchResults> replyTo) {
            this.query = query;
            this.limit = limit;
            this.replyTo = replyTo;
        }
    }

    public static final class IndexBatch implements Command {
        public final List<Apartment> apartments;
        public final ActorRef<BatchIndexingResult> replyTo;

        @JsonCreator
        public IndexBatch(
                @JsonProperty("apartments") List<Apartment> apartments,
                @JsonProperty("replyTo") ActorRef<BatchIndexingResult> replyTo) {
            this.apartments = apartments;
            this.replyTo = replyTo;
        }
    }

    public static final class StoreSuccessfulQuery implements Command {
        public final String query;
        public final SearchCriteria criteria;
        public final int resultCount;

        @JsonCreator
        public StoreSuccessfulQuery(
                @JsonProperty("query") String query,
                @JsonProperty("criteria") SearchCriteria criteria,
                @JsonProperty("resultCount") int resultCount) {
            this.query = query;
            this.criteria = criteria;
            this.resultCount = resultCount;
        }
    }

    public static final class IndexingResult implements Command {
        public final boolean success;
        public final String apartmentId;
        public final String error;

        @JsonCreator
        public IndexingResult(
                @JsonProperty("success") boolean success,
                @JsonProperty("apartmentId") String apartmentId,
                @JsonProperty("error") String error) {
            this.success = success;
            this.apartmentId = apartmentId;
            this.error = error;
        }
    }

    public static final class BatchIndexingResult implements Command {
        public final int totalIndexed;
        public final int failed;
        public final List<String> errors;

        @JsonCreator
        public BatchIndexingResult(
                @JsonProperty("totalIndexed") int totalIndexed,
                @JsonProperty("failed") int failed,
                @JsonProperty("errors") List<String> errors) {
            this.totalIndexed = totalIndexed;
            this.failed = failed;
            this.errors = errors;
        }
    }

    public static final class SearchResults implements Command {
        public final List<ScoredApartment> apartments;
        public final List<String> similarQueries;
        public final long searchTimeMs;

        @JsonCreator
        public SearchResults(
                @JsonProperty("apartments") List<ScoredApartment> apartments,
                @JsonProperty("similarQueries") List<String> similarQueries,
                @JsonProperty("searchTimeMs") long searchTimeMs) {
            this.apartments = apartments;
            this.similarQueries = similarQueries;
            this.searchTimeMs = searchTimeMs;
        }
    }

    public static class ScoredApartment {
        public final Apartment apartment;
        public final float score;

        @JsonCreator
        public ScoredApartment(
                @JsonProperty("apartment") Apartment apartment,
                @JsonProperty("score") float score) {
            this.apartment = apartment;
            this.score = score;
        }
    }

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final Map<String, float[]> embeddingCache;
    private final String APARTMENTS_COLLECTION = "apartments";
    private final String QUERIES_COLLECTION = "successful_queries";
    private final ActorRef<org.livora.messages.Command> logger;
    private final Map<String, Apartment> apartmentCache;

    // IMPORTANT: Map original apartment IDs to UUIDs for Qdrant
    private final Map<String, String> apartmentIdToUuid;
    private final Map<String, String> uuidToApartmentId;

    public static Behavior<Command> create() {
        return Behaviors.setup(VectorSearchActor::new);
    }

    private VectorSearchActor(ActorContext<Command> context) {
        super(context);

        // Get Qdrant host from environment or system property
        String qdrantHost = System.getProperty("qdrant.host",
                System.getenv().getOrDefault("QDRANT_HOST", "localhost"));
        int qdrantPort = Integer.parseInt(System.getProperty("qdrant.port",
                System.getenv().getOrDefault("QDRANT_PORT", "6334")));

        // Initialize Qdrant client
        this.qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build()
        );

        // Initialize embedding model
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // Initialize caches
        this.embeddingCache = new ConcurrentHashMap<>();
        this.apartmentCache = new ConcurrentHashMap<>();

        // Initialize ID mapping
        this.apartmentIdToUuid = new ConcurrentHashMap<>();
        this.uuidToApartmentId = new ConcurrentHashMap<>();

        // Spawn logger
        this.logger = context.spawn(LoggingActor.create(), "vectorSearchLogger");

        // Initialize collections
        initializeCollections();

        getContext().getLog().info("VectorSearchActor started with Qdrant connection to {}:{}",
                qdrantHost, qdrantPort);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(IndexApartment.class, this::onIndexApartment)
                .onMessage(SearchSimilar.class, this::onSearchSimilar)
                .onMessage(IndexBatch.class, this::onIndexBatch)
                .onMessage(StoreSuccessfulQuery.class, this::onStoreSuccessfulQuery)
                .build();
    }

    private void initializeCollections() {
        try {
            // Check if collections exist
            boolean apartmentsExists = false;
            boolean queriesExists = false;

            try {
                ListenableFuture<Collections.CollectionInfo> collectionInfo =
                        qdrantClient.getCollectionInfoAsync(APARTMENTS_COLLECTION);
                collectionInfo.get();
                apartmentsExists = true;
                getContext().getLog().info("Apartments collection already exists");
            } catch (Exception e) {
                // Collection doesn't exist
            }

            try {
                ListenableFuture<Collections.CollectionInfo> collectionInfo =
                        qdrantClient.getCollectionInfoAsync(QUERIES_COLLECTION);
                collectionInfo.get();
                queriesExists = true;
                getContext().getLog().info("Queries collection already exists");
            } catch (Exception e) {
                // Collection doesn't exist
            }

            // Create apartments collection if needed
            if (!apartmentsExists) {
                Collections.CreateCollection createCollection = Collections.CreateCollection.newBuilder()
                        .setCollectionName(APARTMENTS_COLLECTION)
                        .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                .setParams(VectorParams.newBuilder()
                                        .setSize(384) // AllMiniLmL6V2 embedding size
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        .build();

                ListenableFuture<Collections.CollectionOperationResponse> createFuture =
                        qdrantClient.createCollectionAsync(createCollection);
                createFuture.get();
                getContext().getLog().info("Created apartments collection in Qdrant");
            }

            // Create queries collection if needed
            if (!queriesExists) {
                Collections.CreateCollection createCollection = Collections.CreateCollection.newBuilder()
                        .setCollectionName(QUERIES_COLLECTION)
                        .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                .setParams(VectorParams.newBuilder()
                                        .setSize(384)
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        .build();

                ListenableFuture<Collections.CollectionOperationResponse> createFuture =
                        qdrantClient.createCollectionAsync(createCollection);
                createFuture.get();
                getContext().getLog().info("Created queries collection in Qdrant");
            }

        } catch (Exception e) {
            getContext().getLog().error("Failed to initialize Qdrant collections", e);
        }
    }

    /**
     * Generate or get a UUID for an apartment ID
     */
    private String getOrCreateUuid(String apartmentId) {
        return apartmentIdToUuid.computeIfAbsent(apartmentId, id -> {
            // Generate a deterministic UUID based on the apartment ID
            // This ensures the same apartment always gets the same UUID
            String uuid = UUID.nameUUIDFromBytes(id.getBytes()).toString();
            uuidToApartmentId.put(uuid, id);
            return uuid;
        });
    }

    private Behavior<Command> onIndexApartment(IndexApartment msg) {
        long startTime = System.currentTimeMillis();

        try {
            // Create text representation for embedding
            String apartmentText = createApartmentText(msg.apartment);

            // Check cache first
            float[] vector = embeddingCache.get(apartmentText);
            if (vector == null) {
                // Generate embedding
                Response<Embedding> embeddingResponse = embeddingModel.embed(apartmentText);
                vector = embeddingResponse.content().vector();
                embeddingCache.put(apartmentText, vector);
            }

            // Store apartment in cache
            apartmentCache.put(msg.apartment.getId(), msg.apartment);

            // Get or create UUID for this apartment
            String uuid = getOrCreateUuid(msg.apartment.getId());

            // Create payload with apartment data
            Map<String, Value> payload = createApartmentPayload(msg.apartment);

            // Convert float[] to List<Float> for Qdrant
            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            // Create the point with UUID
            PointStruct point = PointStruct.newBuilder()
                    .setId(PointId.newBuilder()
                            .setUuid(uuid)  // Use the generated UUID
                            .build())
                    .setVectors(Points.Vectors.newBuilder()
                            .setVector(Points.Vector.newBuilder()
                                    .addAllData(vectorList)
                                    .build())
                            .build())
                    .putAllPayload(payload)
                    .build();

            // Create upsert request
            UpsertPoints upsertPoints = UpsertPoints.newBuilder()
                    .setCollectionName(APARTMENTS_COLLECTION)
                    .addPoints(point)
                    .build();

            // Execute upsert
            ListenableFuture<UpdateResult> upsertFuture =
                    qdrantClient.upsertAsync(upsertPoints);
            upsertFuture.get();

            long indexTime = System.currentTimeMillis() - startTime;
            getContext().getLog().debug("Indexed apartment {} with UUID {} in {}ms",
                    msg.apartment.getId(), uuid, indexTime);

            msg.replyTo.tell(new IndexingResult(true, msg.apartment.getId(), null));

        } catch (Exception e) {
            getContext().getLog().error("Failed to index apartment {}", msg.apartment.getId(), e);
            msg.replyTo.tell(new IndexingResult(false, msg.apartment.getId(), e.getMessage()));
        }

        return this;
    }

    private Behavior<Command> onIndexBatch(IndexBatch msg) {
        getContext().getLog().info("Starting batch indexing of {} apartments", msg.apartments.size());

        int indexed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Apartment apartment : msg.apartments) {
            try {
                String apartmentText = createApartmentText(apartment);

                float[] vector = embeddingCache.get(apartmentText);
                if (vector == null) {
                    Response<Embedding> embeddingResponse = embeddingModel.embed(apartmentText);
                    vector = embeddingResponse.content().vector();
                    embeddingCache.put(apartmentText, vector);
                }

                apartmentCache.put(apartment.getId(), apartment);

                // Get or create UUID for this apartment
                String uuid = getOrCreateUuid(apartment.getId());

                Map<String, Value> payload = createApartmentPayload(apartment);

                // Convert float[] to List<Float>
                List<Float> vectorList = new ArrayList<>();
                for (float v : vector) {
                    vectorList.add(v);
                }

                PointStruct point = PointStruct.newBuilder()
                        .setId(PointId.newBuilder()
                                .setUuid(uuid)  // Use the generated UUID
                                .build())
                        .setVectors(Points.Vectors.newBuilder()
                                .setVector(Points.Vector.newBuilder()
                                        .addAllData(vectorList)
                                        .build())
                                .build())
                        .putAllPayload(payload)
                        .build();

                UpsertPoints upsertPoints = UpsertPoints.newBuilder()
                        .setCollectionName(APARTMENTS_COLLECTION)
                        .addPoints(point)
                        .build();

                ListenableFuture<UpdateResult> upsertFuture =
                        qdrantClient.upsertAsync(upsertPoints);
                upsertFuture.get();
                indexed++;

                getContext().getLog().debug("Successfully indexed apartment {} with UUID {}",
                        apartment.getId(), uuid);

            } catch (Exception e) {
                failed++;
                errors.add(String.format("Failed to index %s: %s", apartment.getId(), e.getMessage()));
                getContext().getLog().error("Failed to index apartment {}", apartment.getId(), e);
            }
        }

        getContext().getLog().info("Batch indexing complete: {} indexed, {} failed", indexed, failed);
        msg.replyTo.tell(new BatchIndexingResult(indexed, failed, errors));

        return this;
    }

    private Behavior<Command> onSearchSimilar(SearchSimilar msg) {
        long startTime = System.currentTimeMillis();

        try {
            // Generate query embedding (with caching)
            float[] queryVector = embeddingCache.get(msg.query);
            if (queryVector == null) {
                Response<Embedding> embeddingResponse = embeddingModel.embed(msg.query);
                queryVector = embeddingResponse.content().vector();
                embeddingCache.put(msg.query, queryVector);
            }

            // Convert float[] to List<Float>
            List<Float> queryVectorList = new ArrayList<>();
            for (float v : queryVector) {
                queryVectorList.add(v);
            }

            // Create search request
            SearchPoints searchPoints = SearchPoints.newBuilder()
                    .setCollectionName(APARTMENTS_COLLECTION)
                    .addAllVector(queryVectorList)
                    .setLimit(msg.limit)
                    .setWithPayload(WithPayloadSelector.newBuilder()
                            .setEnable(true)
                            .build())
                    .build();

            // Execute search - returns List<ScoredPoint> directly
            ListenableFuture<List<ScoredPoint>> searchFuture = qdrantClient.searchAsync(searchPoints);
            List<ScoredPoint> searchResults = searchFuture.get();

            // Convert results to ScoredApartments
            List<ScoredApartment> scoredApartments = new ArrayList<>();
            for (ScoredPoint scoredPoint : searchResults) {
                Map<String, Value> payload = scoredPoint.getPayloadMap();
                String apartmentId = payload.get("id").getStringValue();

                // Try to get from cache first
                Apartment apt = apartmentCache.get(apartmentId);
                if (apt == null) {
                    apt = reconstructApartment(payload);
                }

                scoredApartments.add(new ScoredApartment(apt, scoredPoint.getScore()));
            }

            // Search for similar past queries
            List<String> similarQueries = searchSimilarQueries(queryVectorList);

            long searchTime = System.currentTimeMillis() - startTime;
            getContext().getLog().debug("Vector search completed in {}ms, found {} apartments",
                    searchTime, scoredApartments.size());

            msg.replyTo.tell(new SearchResults(scoredApartments, similarQueries, searchTime));

        } catch (Exception e) {
            getContext().getLog().error("Vector search failed for query: {}", msg.query, e);
            msg.replyTo.tell(new SearchResults(List.of(), List.of(), 0));
        }

        return this;
    }

    private Behavior<Command> onStoreSuccessfulQuery(StoreSuccessfulQuery msg) {
        // Store successful queries for learning
        if (msg.resultCount > 0) {
            try {
                float[] queryVector = embeddingCache.get(msg.query);
                if (queryVector == null) {
                    Response<Embedding> embeddingResponse = embeddingModel.embed(msg.query);
                    queryVector = embeddingResponse.content().vector();
                    embeddingCache.put(msg.query, queryVector);
                }

                // Convert float[] to List<Float>
                List<Float> queryVectorList = new ArrayList<>();
                for (float v : queryVector) {
                    queryVectorList.add(v);
                }

                Map<String, Value> payload = new HashMap<>();
                payload.put("query", Value.newBuilder().setStringValue(msg.query).build());
                payload.put("resultCount", Value.newBuilder().setIntegerValue(msg.resultCount).build());
                payload.put("timestamp", Value.newBuilder()
                        .setStringValue(Instant.now().toString()).build());

                PointStruct point = PointStruct.newBuilder()
                        .setId(PointId.newBuilder()
                                .setUuid(UUID.randomUUID().toString())
                                .build())
                        .setVectors(Points.Vectors.newBuilder()
                                .setVector(Points.Vector.newBuilder()
                                        .addAllData(queryVectorList)
                                        .build())
                                .build())
                        .putAllPayload(payload)
                        .build();

                UpsertPoints upsertPoints = UpsertPoints.newBuilder()
                        .setCollectionName(QUERIES_COLLECTION)
                        .addPoints(point)
                        .build();

                ListenableFuture<UpdateResult> upsertFuture =
                        qdrantClient.upsertAsync(upsertPoints);
                upsertFuture.get();

                getContext().getLog().debug("Stored successful query: {}", msg.query);

            } catch (Exception e) {
                getContext().getLog().warn("Failed to store successful query", e);
            }
        }

        return this;
    }

    private String createApartmentText(Apartment apt) {
        StringBuilder text = new StringBuilder();

        text.append(apt.getTitle()).append(". ");
        text.append(apt.getBedrooms()).append(" bedroom ");
        text.append(apt.getBathrooms()).append(" bathroom apartment in ");
        text.append(apt.getLocation().getNeighborhood()).append(". ");
        text.append("Price: $").append(apt.getPrice()).append(" per month. ");

        if (apt.isPetFriendly()) {
            text.append("Pet friendly. ");
        }

        if (apt.isParkingAvailable()) {
            text.append("Parking available. ");
        }

        if (!apt.getAmenities().isEmpty()) {
            text.append("Amenities: ").append(String.join(", ", apt.getAmenities())).append(". ");
        }

        if (apt.getSquareFeet() > 0) {
            text.append(apt.getSquareFeet()).append(" square feet. ");
        }

        text.append("Located at ").append(apt.getLocation().getAddress());

        return text.toString();
    }

    private Map<String, Value> createApartmentPayload(Apartment apt) {
        Map<String, Value> payload = new HashMap<>();

        // Store the original apartment ID in the payload
        payload.put("id", Value.newBuilder().setStringValue(apt.getId()).build());
        payload.put("title", Value.newBuilder().setStringValue(apt.getTitle()).build());
        payload.put("price", Value.newBuilder().setIntegerValue(apt.getPrice()).build());
        payload.put("bedrooms", Value.newBuilder().setIntegerValue(apt.getBedrooms()).build());
        payload.put("bathrooms", Value.newBuilder().setIntegerValue(apt.getBathrooms()).build());
        payload.put("neighborhood", Value.newBuilder()
                .setStringValue(apt.getLocation().getNeighborhood()).build());
        payload.put("address", Value.newBuilder()
                .setStringValue(apt.getLocation().getAddress()).build());
        payload.put("petFriendly", Value.newBuilder()
                .setBoolValue(apt.isPetFriendly()).build());
        payload.put("parkingAvailable", Value.newBuilder()
                .setBoolValue(apt.isParkingAvailable()).build());
        payload.put("squareFeet", Value.newBuilder()
                .setIntegerValue(apt.getSquareFeet()).build());

        // Store amenities as comma-separated string
        if (!apt.getAmenities().isEmpty()) {
            payload.put("amenities", Value.newBuilder()
                    .setStringValue(String.join(",", apt.getAmenities())).build());
        }

        return payload;
    }

    private Apartment reconstructApartment(Map<String, Value> payload) {
        // Reconstruct apartment from Qdrant payload
        List<String> amenities = List.of();
        if (payload.containsKey("amenities")) {
            String amenitiesStr = payload.get("amenities").getStringValue();
            if (!amenitiesStr.isEmpty()) {
                amenities = Arrays.asList(amenitiesStr.split(","));
            }
        }

        return new Apartment(
                payload.get("id").getStringValue(),
                payload.get("title").getStringValue(),
                (int) payload.get("price").getIntegerValue(),
                (int) payload.get("bedrooms").getIntegerValue(),
                (int) payload.get("bathrooms").getIntegerValue(),
                new Apartment.Location(
                        payload.get("address").getStringValue(),
                        0.0, // We're not storing lat/lon in payload for simplicity
                        0.0,
                        payload.get("neighborhood").getStringValue()
                ),
                payload.get("petFriendly").getBoolValue(),
                payload.get("parkingAvailable").getBoolValue(),
                amenities,
                payload.containsKey("squareFeet") ? (int) payload.get("squareFeet").getIntegerValue() : 0,
                "2024-01-01", // Default available date
                List.of() // Default photos
        );
    }

    private List<String> searchSimilarQueries(List<Float> queryVector) {
        try {
            SearchPoints searchPoints = SearchPoints.newBuilder()
                    .setCollectionName(QUERIES_COLLECTION)
                    .addAllVector(queryVector)
                    .setLimit(3)
                    .setScoreThreshold(0.7f) // Only return highly similar queries
                    .setWithPayload(WithPayloadSelector.newBuilder()
                            .setEnable(true)
                            .build())
                    .build();

            ListenableFuture<List<ScoredPoint>> searchFuture = qdrantClient.searchAsync(searchPoints);
            List<ScoredPoint> searchResults = searchFuture.get();

            List<String> similarQueries = new ArrayList<>();
            for (ScoredPoint scoredPoint : searchResults) {
                String query = scoredPoint.getPayloadMap().get("query").getStringValue();
                similarQueries.add(query);
            }

            return similarQueries;

        } catch (Exception e) {
            getContext().getLog().warn("Failed to search similar queries", e);
            return List.of();
        }
    }
}