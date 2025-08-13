package org.livora.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.livora.config.GeminiConfig;
import org.livora.config.GeminiPrompts;
import org.livora.messages.*;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//public class QueryParserActor extends AbstractBehavior<Command> {
//
//    private final ObjectMapper objectMapper;
//    private final ActorRef<Command> logger;
//
//    public static Behavior<Command> create() {
//        return Behaviors.setup(QueryParserActor::new);
//    }
//
//    private QueryParserActor(ActorContext<Command> context) {
//        super(context);
//        this.objectMapper = new ObjectMapper();
//        this.logger = context.spawn(LoggingActor.create(), "queryParserLogger");
//
//        getContext().getLog().info("QueryParserActor started. Gemini configured: {}",
//                GeminiConfig.isConfigured());
//    }
//
//    @Override
//    public Receive<Command> createReceive() {
//        return newReceiveBuilder()
//                .onMessage(QueryParserMessages.ParseQuery.class, this::onParseQuery)
//                .onMessage(GeminiResponseReceived.class, this::onGeminiResponseReceived)
//                .build();
//    }
//
//    private Behavior<Command> onParseQuery(QueryParserMessages.ParseQuery request) {
//        getContext().getLog().info("Parsing query: {}", request.naturalLanguageQuery);
//
//        if (GeminiConfig.isConfigured()) {
//            // Use Gemini for intelligent parsing
//            parseWithGemini(request);
//        } else {
//            // Fallback to basic regex-based parsing
//            getContext().getLog().warn("Gemini not configured, using fallback parsing");
//            SearchCriteria criteria = parseWithFallback(request.naturalLanguageQuery);
//            request.replyTo.tell(new QueryParserMessages.QueryParsed(criteria, 0.5));
//        }
//
//        return this;
//    }
//
//    private Behavior<Command> onGeminiResponseReceived(GeminiResponseReceived message) {
//        QueryParserMessages.ParseQuery originalRequest = message.originalRequest;
//
//        if (message.throwable != null) {
//            getContext().getLog().error("Gemini API call failed", message.throwable);
//
//            // Log the failure
//            logger.tell(new LoggingMessages.LogEntry(
//                    "Gemini API failed: " + message.throwable.getMessage(),
//                    java.time.Instant.now(),
//                    getContext().getSelf()
//            ));
//
//            // Fallback to basic parsing
//            SearchCriteria fallbackCriteria = parseWithFallback(originalRequest.naturalLanguageQuery);
//            originalRequest.replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.3));
//
//        } else {
//            try {
//                // Parse Gemini's JSON response
//                SearchCriteria criteria = parseGeminiResponse(message.result);
//
//                // Log successful parsing
//                logger.tell(new LoggingMessages.LogEntry(
//                        "Successfully parsed query with Gemini",
//                        java.time.Instant.now(),
//                        getContext().getSelf()
//                ));
//
//                originalRequest.replyTo.tell(new QueryParserMessages.QueryParsed(criteria, 0.9));
//
//            } catch (Exception e) {
//                getContext().getLog().error("Failed to parse Gemini response: {}", message.result, e);
//
//                // Fallback parsing
//                SearchCriteria fallbackCriteria = parseWithFallback(originalRequest.naturalLanguageQuery);
//                originalRequest.replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.4));
//            }
//        }
//
//        return this;
//    }
//
//    // Helper class for async Gemini responses
//    public static class GeminiResponseReceived implements Command {
//        public final QueryParserMessages.ParseQuery originalRequest;
//        public final String result;
//        public final Throwable throwable;
//
//        public GeminiResponseReceived(QueryParserMessages.ParseQuery originalRequest, String result, Throwable throwable) {
//            this.originalRequest = originalRequest;
//            this.result = result;
//            this.throwable = throwable;
//        }
//    }
//
//    private void parseWithGemini(QueryParserMessages.ParseQuery request) {
//        String prompt = GeminiPrompts.formatParseQuery(request.naturalLanguageQuery);
//
//        CompletableFuture<String> geminiResponse = GeminiConfig.generateContent(prompt);
//
//        // Handle the async response
//        getContext().pipeToSelf(geminiResponse, (result, throwable) -> {
//            if (throwable != null) {
//                getContext().getLog().error("Gemini API call failed", throwable);
//
//                // Log the failure
//                logger.tell(new LoggingMessages.LogEntry(
//                        "Gemini API failed: " + throwable.getMessage(),
//                        java.time.Instant.now(),
//                        getContext().getSelf()
//                ));
//
//                // Fallback to basic parsing
//                SearchCriteria fallbackCriteria = parseWithFallback(request.naturalLanguageQuery);
//                request.replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.3));
//                return null;
//            }
//
//            try {
//                // Parse Gemini's JSON response
//                SearchCriteria criteria = parseGeminiResponse(result);
//
//                // Log successful parsing
//                logger.tell(new LoggingMessages.LogEntry(
//                        "Successfully parsed query with Gemini",
//                        java.time.Instant.now(),
//                        getContext().getSelf()
//                ));
//
//                request.replyTo.tell(new QueryParserMessages.QueryParsed(criteria, 0.9));
//
//            } catch (Exception e) {
//                getContext().getLog().error("Failed to parse Gemini response: {}", result, e);
//
//                // Fallback parsing
//                SearchCriteria fallbackCriteria = parseWithFallback(request.naturalLanguageQuery);
//                request.replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.4));
//            }
//
//            return null;
//        });
//    }
//
//    private SearchCriteria parseGeminiResponse(String geminiResponse) throws Exception {
//        // Clean up the response - Gemini sometimes includes extra text
//        String jsonString = extractJsonFromResponse(geminiResponse);
//
//        getContext().getLog().debug("Parsing Gemini JSON: {}", jsonString);
//
//        JsonNode jsonNode = objectMapper.readTree(jsonString);
//
//        // Extract fields from JSON, handling nulls gracefully
//        Integer minPrice = jsonNode.has("minPrice") && !jsonNode.get("minPrice").isNull()
//                ? jsonNode.get("minPrice").asInt() : null;
//        Integer maxPrice = jsonNode.has("maxPrice") && !jsonNode.get("maxPrice").isNull()
//                ? jsonNode.get("maxPrice").asInt() : null;
//        Integer bedrooms = jsonNode.has("bedrooms") && !jsonNode.get("bedrooms").isNull()
//                ? jsonNode.get("bedrooms").asInt() : null;
//        Integer bathrooms = jsonNode.has("bathrooms") && !jsonNode.get("bathrooms").isNull()
//                ? jsonNode.get("bathrooms").asInt() : null;
//        Boolean petFriendly = jsonNode.has("petFriendly") && !jsonNode.get("petFriendly").isNull()
//                ? jsonNode.get("petFriendly").asBoolean() : null;
//        Boolean parking = jsonNode.has("parking") && !jsonNode.get("parking").isNull()
//                ? jsonNode.get("parking").asBoolean() : null;
//        String location = jsonNode.has("location") && !jsonNode.get("location").isNull()
//                ? jsonNode.get("location").asText() : null;
//        String proximity = jsonNode.has("proximity") && !jsonNode.get("proximity").isNull()
//                ? jsonNode.get("proximity").asText() : null;
//
//        // Parse amenities array
//        List<String> amenities = List.of();
//        if (jsonNode.has("amenities") && jsonNode.get("amenities").isArray()) {
//            amenities = objectMapper.convertValue(
//                    jsonNode.get("amenities"),
//                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
//            );
//        }
//
//        return new SearchCriteria(minPrice, maxPrice, bedrooms, bathrooms,
//                petFriendly, parking, location, amenities, proximity);
//    }
//
//    private String extractJsonFromResponse(String response) {
//        // Remove any markdown formatting or extra text
//        String cleaned = response.trim();
//
//        // Look for JSON object boundaries
//        int startIndex = cleaned.indexOf('{');
//        int endIndex = cleaned.lastIndexOf('}') + 1;
//
//        if (startIndex >= 0 && endIndex > startIndex) {
//            return cleaned.substring(startIndex, endIndex);
//        }
//
//        // If no clear JSON found, return original
//        return cleaned;
//    }
//
//    private SearchCriteria parseWithFallback(String query) {
//        getContext().getLog().info("Using fallback parsing for: {}", query);
//
//        String lowerQuery = query.toLowerCase();
//
//        // Basic regex patterns for fallback parsing
//        Integer bedrooms = extractNumber(lowerQuery, "(\\d+)\\s*(?:bedroom|br|bed)");
//        Integer bathrooms = extractNumber(lowerQuery, "(\\d+)\\s*(?:bathroom|bath|ba)");
//        Integer maxPrice = extractNumber(lowerQuery, "(?:under|below|less than|max|maximum)\\s*\\$?(\\d+)");
//        Integer minPrice = extractNumber(lowerQuery, "(?:over|above|more than|min|minimum)\\s*\\$?(\\d+)");
//
//        // Boolean flags
//        Boolean petFriendly = lowerQuery.contains("pet") ? true : null;
//        Boolean parking = lowerQuery.contains("parking") || lowerQuery.contains("garage") ? true : null;
//
//        // Location extraction (simple keyword matching)
//        String location = null;
//        if (lowerQuery.contains("downtown")) location = "downtown";
//        else if (lowerQuery.contains("university")) location = "university";
//        else if (lowerQuery.contains("suburban")) location = "suburban";
//
//        // Proximity
//        String proximity = null;
//        if (lowerQuery.contains("near") || lowerQuery.contains("close")) proximity = "near";
//        else if (lowerQuery.contains("walking distance")) proximity = "walking distance";
//
//        // Basic amenities
//        List<String> amenities = List.of();
//        if (lowerQuery.contains("gym")) {
//            amenities = Arrays.asList("gym");
//        }
//
//        return new SearchCriteria(minPrice, maxPrice, bedrooms, bathrooms,
//                petFriendly, parking, location, amenities, proximity);
//    }
//
//    private Integer extractNumber(String text, String pattern) {
//        Pattern p = Pattern.compile(pattern);
//        Matcher m = p.matcher(text);
//        if (m.find()) {
//            try {
//                return Integer.parseInt(m.group(1));
//            } catch (NumberFormatException e) {
//                return null;
//            }
//        }
//        return null;
//    }
//
//    private Behavior<Command> onApartmentsFound(
//            ApartmentSearchMessages.ApartmentsFound apartmentsFound) {
//
//        getContext().getLog().info("Received {} apartment results",
//                apartmentsFound.apartments.size());
//
//        // This would typically be correlated back to the original request
//        // For now, just log the completion
//        logger.tell(new LoggingMessages.LogEntry(
//                String.format("Search completed with %d results", apartmentsFound.apartments.size()),
//                java.time.Instant.now(),
//                getContext().getSelf()
//        ));
//
//        return this;
//    }
//}

public class QueryParserActor extends AbstractBehavior<Command> {

    private final ObjectMapper objectMapper;
    private final ActorRef<Command> logger;

    public static Behavior<Command> create() {
        return Behaviors.setup(QueryParserActor::new);
    }

    private QueryParserActor(ActorContext<Command> context) {
        super(context);

        this.objectMapper = new ObjectMapper();
        this.logger = context.spawn(LoggingActor.create(), "queryParserLogger");

        context.getLog().info("QueryParserActor started with RAG support. Gemini configured: {}",
                GeminiConfig.isConfigured());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(QueryParserMessages.ParseQuery.class, this::onParseQuery)
                .onMessage(QueryParserMessages.ParseQueryWithRAG.class, this::onParseQueryWithRAG)
                .onMessage(GeminiResponseReceived.class, this::onGeminiResponseReceived)
                .build();
    }

    public static class GeminiResponseReceived implements Command {
        public final QueryParserMessages.ParseQuery originalRequest;
        public final QueryParserMessages.ParseQueryWithRAG originalRAGRequest;
        public final String result;
        public final Throwable throwable;
        public final boolean isRAGRequest;

        public GeminiResponseReceived(QueryParserMessages.ParseQuery originalRequest, String result, Throwable throwable) {
            this.originalRequest = originalRequest;
            this.originalRAGRequest = null;
            this.result = result;
            this.throwable = throwable;
            this.isRAGRequest = false;
        }

        public GeminiResponseReceived(QueryParserMessages.ParseQueryWithRAG originalRAGRequest, String result, Throwable throwable) {
            this.originalRequest = null;
            this.originalRAGRequest = originalRAGRequest;
            this.result = result;
            this.throwable = throwable;
            this.isRAGRequest = true;
        }
    }

    private Behavior<Command> onParseQuery(QueryParserMessages.ParseQuery message) {
        return parseQueryInternal(message.naturalLanguageQuery, null, message.replyTo);
    }

    private Behavior<Command> onParseQueryWithRAG(QueryParserMessages.ParseQueryWithRAG message) {
        return parseQueryInternal(message.naturalLanguageQuery, message.ragContext, message.replyTo);
    }

    private Behavior<Command> parseQueryInternal(
            String query,
            VectorSearchActor.SearchResults ragContext,
            ActorRef<QueryParserMessages.QueryParsed> replyTo) {

        getContext().getLog().info("Parsing query: {}", query);

        if (GeminiConfig.isConfigured()) {
            try {
                String prompt = buildRAGEnhancedPrompt(query, ragContext);

                getContext().getLog().debug("Generated prompt with RAG context: {}", prompt);

                CompletableFuture<String> geminiResponse = GeminiConfig.generateContent(prompt);

                if (ragContext != null) {
                    QueryParserMessages.ParseQueryWithRAG ragRequest =
                            new QueryParserMessages.ParseQueryWithRAG(query, ragContext, replyTo);
                    getContext().pipeToSelf(geminiResponse,
                            (result, throwable) -> new GeminiResponseReceived(ragRequest, result, throwable));
                } else {
                    QueryParserMessages.ParseQuery regularRequest =
                            new QueryParserMessages.ParseQuery(query, replyTo);
                    getContext().pipeToSelf(geminiResponse,
                            (result, throwable) -> new GeminiResponseReceived(regularRequest, result, throwable));
                }

            } catch (Exception e) {
                getContext().getLog().error("Failed to call Gemini", e);
                replyTo.tell(new QueryParserMessages.QueryParsed(
                        createFallbackCriteria(query), 0.1));
            }
        } else {
            getContext().getLog().warn("Gemini not configured, using fallback parsing");
            SearchCriteria criteria = createFallbackCriteria(query);
            replyTo.tell(new QueryParserMessages.QueryParsed(criteria, 0.5));
        }

        return this;
    }

    private String buildRAGEnhancedPrompt(String query, VectorSearchActor.SearchResults ragContext) {
        StringBuilder prompt = new StringBuilder();

        String basePrompt = GeminiPrompts.PARSE_QUERY_PROMPT.replace("%s", query);
        prompt.append(basePrompt);

        if (ragContext != null && !ragContext.apartments.isEmpty()) {
            prompt.append("\n\nCONTEXT: Here are some similar apartments that might be relevant:\n");

            ragContext.apartments.stream()
                    .limit(3)
                    .forEach(scored -> {
                        Apartment apt = scored.apartment;
                        prompt.append("- ").append(apt.getTitle())
                                .append(" ($").append(apt.getPrice())
                                .append(", ").append(apt.getBedrooms()).append("BR")
                                .append(" in ").append(apt.getLocation().getNeighborhood())
                                .append(")\n");
                    });

            if (!ragContext.similarQueries.isEmpty()) {
                prompt.append("\nSimilar past queries:\n");
                ragContext.similarQueries.forEach(q ->
                        prompt.append("- \"").append(q).append("\"\n"));
            }

            prompt.append("\nIMPORTANT: Be flexible with price ranges. If user says \"under $2000\" but similar apartments ");
            prompt.append("cost more, consider suggesting a slightly higher range like $2500.\n");
        }

        return prompt.toString();
    }

    private Behavior<Command> onGeminiResponseReceived(GeminiResponseReceived message) {
        ActorRef<QueryParserMessages.QueryParsed> replyTo;
        String originalQuery;

        if (message.isRAGRequest) {
            replyTo = message.originalRAGRequest.replyTo;
            originalQuery = message.originalRAGRequest.naturalLanguageQuery;
        } else {
            replyTo = message.originalRequest.replyTo;
            originalQuery = message.originalRequest.naturalLanguageQuery;
        }

        if (message.throwable != null) {
            getContext().getLog().error("Gemini API call failed for query: {}", originalQuery, message.throwable);

            logger.tell(new LoggingMessages.LogEntry(
                    "Gemini API failed: " + message.throwable.getMessage(),
                    Instant.now(),
                    getContext().getSelf()
            ));

            SearchCriteria fallbackCriteria = createFallbackCriteria(originalQuery);
            replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.3));

        } else {
            try {
                getContext().getLog().debug("Gemini response: {}", message.result);

                String cleanedJson = extractJsonFromResponse(message.result);
                getContext().getLog().debug("Cleaned JSON: {}", cleanedJson);

                SearchCriteria criteria = objectMapper.readValue(cleanedJson, SearchCriteria.class);

                double confidence = calculateConfidence(criteria, originalQuery);

                getContext().getLog().info("Parsed criteria: {} with confidence: {}", criteria, confidence);

                logger.tell(new LoggingMessages.LogEntry(
                        "Successfully parsed query with Gemini",
                        Instant.now(),
                        getContext().getSelf()
                ));

                replyTo.tell(new QueryParserMessages.QueryParsed(criteria, confidence));

            } catch (JsonProcessingException e) {
                getContext().getLog().error("Failed to parse Gemini JSON response: {}", message.result, e);

                SearchCriteria fallbackCriteria = createFallbackCriteria(originalQuery);
                replyTo.tell(new QueryParserMessages.QueryParsed(fallbackCriteria, 0.2));

            } catch (Exception e) {
                getContext().getLog().error("Error handling Gemini response", e);
                replyTo.tell(new QueryParserMessages.QueryParsed(
                        createFallbackCriteria(originalQuery), 0.1));
            }
        }

        return this;
    }

    private String extractJsonFromResponse(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```")) {
            int firstLineEnd = cleaned.indexOf('\n');
            if (firstLineEnd > 0) {
                cleaned = cleaned.substring(firstLineEnd + 1);
            }

            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }

        int startIndex = cleaned.indexOf('{');
        int endIndex = cleaned.lastIndexOf('}') + 1;

        if (startIndex >= 0 && endIndex > startIndex) {
            return cleaned.substring(startIndex, endIndex);
        }

        return cleaned;
    }

    private double calculateConfidence(SearchCriteria criteria, String originalQuery) {
        double confidence = 0.5;

        if (criteria.getBedrooms().isPresent()) confidence += 0.1;
        if (criteria.getMaxPrice().isPresent()) confidence += 0.1;
        if (criteria.getLocation().isPresent()) confidence += 0.15;
        if (criteria.getPetFriendly().isPresent()) confidence += 0.1;
        if (criteria.getParking().isPresent()) confidence += 0.05;

        String queryLower = originalQuery.toLowerCase();
        if (queryLower.contains("luxury") || queryLower.contains("modern") ||
                queryLower.contains("spacious")) {
            confidence += 0.1;
        }

        return Math.min(confidence, 1.0);
    }

    private SearchCriteria createFallbackCriteria(String query) {
        return new SearchCriteria(
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
    }
}