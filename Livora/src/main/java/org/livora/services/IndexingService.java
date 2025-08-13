package org.livora.services;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AskPattern;
import org.livora.actors.VectorSearchActor;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexingService {

    private static final int BATCH_SIZE = 10;
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(30);

    public static CompletableFuture<IndexingResult> indexAllApartments(
            ActorSystem<Void> system,
            ActorRef<VectorSearchActor.Command> vectorSearch,
            List<Apartment> apartments) {

        system.log().info("Starting to index {} apartments in batches of {}",
                apartments.size(), BATCH_SIZE);

        CompletableFuture<IndexingResult> future = new CompletableFuture<>();
        AtomicInteger totalIndexed = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);
        List<String> allErrors = new ArrayList<>();

        List<List<Apartment>> batches = createBatches(apartments, BATCH_SIZE);
        system.log().info("Created {} batches for indexing", batches.size());

        processBatchesSequentially(system, vectorSearch, batches, 0,
                totalIndexed, totalFailed, allErrors, future);

        return future;
    }

    public static CompletableFuture<IndexingResult> indexFromFile(
            ActorSystem<Void> system,
            ActorRef<VectorSearchActor.Command> vectorSearch,
            String filePath) {

        try {
            List<Apartment> apartments = loadApartmentsFromFile(filePath);
            system.log().info("Loaded {} apartments from file: {}", apartments.size(), filePath);
            return indexAllApartments(system, vectorSearch, apartments);
        } catch (Exception e) {
            system.log().error("Failed to load apartments from file", e);
            CompletableFuture<IndexingResult> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    public static CompletableFuture<Boolean> indexSingleApartment(
            ActorSystem<Void> system,
            ActorRef<VectorSearchActor.Command> vectorSearch,
            Apartment apartment) {

        CompletionStage<VectorSearchActor.IndexingResult> futureResult =
                AskPattern.ask(
                        vectorSearch,
                        (ActorRef<VectorSearchActor.IndexingResult> ref) ->
                                new VectorSearchActor.IndexApartment(apartment, ref),
                        ASK_TIMEOUT,
                        system.scheduler()
                );

        return futureResult.toCompletableFuture()
                .thenApply(result -> {
                    if (result.success) {
                        system.log().info("Successfully indexed apartment: {}", apartment.getId());
                    } else {
                        system.log().error("Failed to index apartment {}: {}",
                                apartment.getId(), result.error);
                    }
                    return result.success;
                });
    }

    public static void storeSuccessfulQuery(
            ActorRef<VectorSearchActor.Command> vectorSearch,
            String query,
            int resultCount) {

        SearchCriteria criteria = new SearchCriteria(
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

        vectorSearch.tell(new VectorSearchActor.StoreSuccessfulQuery(
                query,
                criteria,
                resultCount
        ));
    }

    private static void processBatchesSequentially(
            ActorSystem<Void> system,
            ActorRef<VectorSearchActor.Command> vectorSearch,
            List<List<Apartment>> batches,
            int currentBatchIndex,
            AtomicInteger totalIndexed,
            AtomicInteger totalFailed,
            List<String> allErrors,
            CompletableFuture<IndexingResult> finalResult) {

        if (currentBatchIndex >= batches.size()) {
            IndexingResult result = new IndexingResult(
                    totalIndexed.get(),
                    totalFailed.get(),
                    allErrors
            );
            system.log().info("Indexing complete: {} indexed, {} failed",
                    result.totalIndexed, result.totalFailed);
            finalResult.complete(result);
            return;
        }

        List<Apartment> currentBatch = batches.get(currentBatchIndex);
        system.log().info("Processing batch {}/{} with {} apartments",
                currentBatchIndex + 1, batches.size(), currentBatch.size());

        CompletionStage<VectorSearchActor.BatchIndexingResult> batchFuture =
                AskPattern.ask(
                        vectorSearch,
                        (ActorRef<VectorSearchActor.BatchIndexingResult> ref) ->
                                new VectorSearchActor.IndexBatch(currentBatch, ref),
                        Duration.ofSeconds(60), // Longer timeout for batches
                        system.scheduler()
                );

        batchFuture.whenComplete((batchResult, throwable) -> {
            if (throwable != null) {
                system.log().error("Batch {} failed entirely", currentBatchIndex + 1, throwable);
                totalFailed.addAndGet(currentBatch.size());
                allErrors.add("Batch " + (currentBatchIndex + 1) + " failed: " + throwable.getMessage());
            } else {
                totalIndexed.addAndGet(batchResult.totalIndexed);
                totalFailed.addAndGet(batchResult.failed);
                allErrors.addAll(batchResult.errors);

                system.log().info("Batch {}/{} complete: {} indexed, {} failed",
                        currentBatchIndex + 1, batches.size(),
                        batchResult.totalIndexed, batchResult.failed);
            }

            try {
                Thread.sleep(500); // 500ms delay between batches
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            processBatchesSequentially(system, vectorSearch, batches,
                    currentBatchIndex + 1, totalIndexed, totalFailed, allErrors, finalResult);
        });
    }

    private static List<List<Apartment>> createBatches(List<Apartment> apartments, int batchSize) {
        List<List<Apartment>> batches = new ArrayList<>();

        for (int i = 0; i < apartments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, apartments.size());
            batches.add(new ArrayList<>(apartments.subList(i, end)));
        }

        return batches;
    }

    private static List<Apartment> loadApartmentsFromFile(String filePath) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        InputStream is = IndexingService.class.getResourceAsStream(filePath);
        if (is != null) {
            return objectMapper.readValue(is, new TypeReference<List<Apartment>>() {});
        }

        File file = new File(filePath);
        if (file.exists()) {
            return objectMapper.readValue(file, new TypeReference<List<Apartment>>() {});
        }

        throw new IllegalArgumentException("Cannot find apartments file: " + filePath);
    }

    public static class IndexingResult {
        public final int totalIndexed;
        public final int totalFailed;
        public final List<String> errors;

        public IndexingResult(int totalIndexed, int totalFailed, List<String> errors) {
            this.totalIndexed = totalIndexed;
            this.totalFailed = totalFailed;
            this.errors = errors;
        }

        public boolean isSuccess() {
            return totalFailed == 0;
        }

        public String getSummary() {
            return String.format("Indexed: %d, Failed: %d, Errors: %d",
                    totalIndexed, totalFailed, errors.size());
        }
    }

    public static CompletableFuture<IndexingResult> reindexAll(
            ActorSystem<Void> system,
            ActorRef<VectorSearchActor.Command> vectorSearch) {

        system.log().info("Starting full reindex of all apartments");

        try {
            List<Apartment> apartments = loadApartmentsFromFile("/apartments.json");

            return indexAllApartments(system, vectorSearch, apartments);
        } catch (Exception e) {
            system.log().error("Failed to reindex apartments", e);
            CompletableFuture<IndexingResult> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
}