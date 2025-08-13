package org.livora.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.livora.messages.*;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;

public class ApartmentSearchActor extends AbstractBehavior<Command> {

    private final List<Apartment> apartmentDatabase;
    private final ActorRef<Command> logger;
    private final ObjectMapper objectMapper;

    public static Behavior<Command> create() {
        return Behaviors.setup(ApartmentSearchActor::new);
    }

    private ApartmentSearchActor(ActorContext<Command> context) {
        super(context);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.logger = context.spawn(LoggingActor.create(), "searchLogger");

        this.apartmentDatabase = loadApartmentData();

        getContext().getLog().info("ApartmentSearchActor started with {} apartments loaded",
                apartmentDatabase.size());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ApartmentSearchMessages.FindApartments.class, this::onFindApartments)
                .onMessage(ApartmentSearchMessages.FindApartmentsHybrid.class, this::onFindApartmentsHybrid)
                .build();
    }

    private Behavior<Command> onFindApartments(
            ApartmentSearchMessages.FindApartments request) {

        long startTime = System.currentTimeMillis();

        getContext().getLog().info("Searching apartments with criteria: bedrooms={}, maxPrice={}, location={}",
                request.criteria.getBedrooms().orElse(null),
                request.criteria.getMaxPrice().orElse(null),
                request.criteria.getLocation().orElse("any"));

        List<Apartment> results = searchApartments(request.criteria);

        long searchTime = System.currentTimeMillis() - startTime;

        getContext().getLog().debug("Forwarding search metrics to logger...");

        LoggingMessages.LogSearchPerformed logMessage =
                new LoggingMessages.LogSearchPerformed(
                        request.criteria,
                        results.size(),
                        getContext().getSelf()
                );

        logger.tell(logMessage);

        getContext().getLog().info("Found {} apartments in {}ms", results.size(), searchTime);

        request.replyTo.tell(new ApartmentSearchMessages.ApartmentsFound(results, results.size()));

        return this;
    }

    private Behavior<Command> onFindApartmentsHybrid(ApartmentSearchMessages.FindApartmentsHybrid request) {
        getContext().getLog().info("Performing hybrid search with {} vector results",
                request.vectorResults.apartments.size());

        long startTime = System.currentTimeMillis();

        try {
            List<VectorSearchActor.ScoredApartment> vectorCandidates = request.vectorResults.apartments;

            List<Apartment> filteredCandidates = vectorCandidates.stream()
                    .map(scored -> scored.apartment)
                    .filter(apt -> matchesCriteria(apt, request.criteria))
                    .collect(Collectors.toList());

            getContext().getLog().info("After filtering {} vector candidates, {} remain",
                    vectorCandidates.size(), filteredCandidates.size());

            List<Apartment> finalResults;
            if (filteredCandidates.size() < 5) {
                getContext().getLog().info("Too few filtered results, expanding search with relaxed criteria");
                finalResults = performExpandedSearch(request.criteria, vectorCandidates);
            } else {
                finalResults = filteredCandidates;
            }

            finalResults = rerankWithHybridScoring(finalResults, vectorCandidates, request.criteria);

            finalResults = finalResults.stream().limit(50).collect(Collectors.toList());

            long searchTime = System.currentTimeMillis() - startTime;
            getContext().getLog().info("Hybrid search found {} apartments in {}ms",
                    finalResults.size(), searchTime);

            getContext().getLog().info("Search completed: {} results for criteria with {} bedrooms, max price {}",
                    finalResults.size(),
                    request.criteria.getBedrooms().orElse(-1),
                    request.criteria.getMaxPrice().orElse(-1));

            request.replyTo.tell(new ApartmentSearchMessages.ApartmentsFound(
                    finalResults,
                    finalResults.size()
            ));

        } catch (Exception e) {
            getContext().getLog().error("Hybrid search failed", e);
            request.replyTo.tell(new ApartmentSearchMessages.ApartmentsFound(List.of(), 0));
        }

        return this;
    }

    private List<Apartment> performExpandedSearch(
            SearchCriteria criteria,
            List<VectorSearchActor.ScoredApartment> vectorCandidates) {

        getContext().getLog().info("Performing expanded search with relaxed criteria");

        SearchCriteria relaxedCriteria = createRelaxedCriteria(criteria);

        List<Apartment> relaxedResults = vectorCandidates.stream()
                .map(scored -> scored.apartment)
                .filter(apt -> matchesCriteria(apt, relaxedCriteria))
                .collect(Collectors.toList());

        if (relaxedResults.size() < 3) {
            getContext().getLog().info("Vector search insufficient, searching full apartment database");
            return loadApartmentData().stream()
                    .filter(apt -> matchesCriteria(apt, relaxedCriteria))
                    .limit(80)
                    .collect(Collectors.toList());
        }

        return relaxedResults;
    }

    private SearchCriteria createRelaxedCriteria(SearchCriteria original) {
        Integer relaxedMaxPrice = original.getMaxPrice()
                .map(price -> (int) (price * 1.25))
                .orElse(null);

        Integer relaxedMinPrice = original.getMinPrice()
                .map(price -> (int) (price * 0.75))
                .orElse(null);

        String relaxedLocation = original.getLocation().orElse(null);

        return new SearchCriteria(
                relaxedMinPrice,
                relaxedMaxPrice,
                original.getBedrooms().orElse(null),
                original.getBathrooms().orElse(null),
                original.getPetFriendly().orElse(null),
                original.getParking().orElse(null),
                relaxedLocation,
                original.getAmenities(),
                original.getProximity().orElse(null)
        );
    }

    private List<Apartment> rerankWithHybridScoring(
            List<Apartment> apartments,
            List<VectorSearchActor.ScoredApartment> vectorCandidates,
            SearchCriteria criteria) {

        getContext().getLog().info("Re-ranking {} apartments with hybrid scoring", apartments.size());

        Map<String, Float> vectorScores = vectorCandidates.stream()
                .collect(Collectors.toMap(
                        scored -> scored.apartment.getId(),
                        scored -> scored.score
                ));

        return apartments.stream()
                .map(apt -> {
                    double totalScore = calculateHybridScore(apt, criteria, vectorScores);
                    return new ScoredApartment(apt, totalScore);
                })
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .map(scored -> scored.apartment)
                .collect(Collectors.toList());
    }

    private double calculateHybridScore(
            Apartment apartment,
            SearchCriteria criteria,
            Map<String, Float> vectorScores) {

        double score = 0.0;

        float vectorScore = vectorScores.getOrDefault(apartment.getId(), 0.0f);
        score += vectorScore * 0.4;

        double criteriaScore = 0.0;
        int matchingCriteria = 0;
        int totalCriteria = 0;

        if (criteria.getMaxPrice().isPresent()) {
            totalCriteria++;
            if (apartment.getPrice() <= criteria.getMaxPrice().get()) {
                matchingCriteria++;
                double priceRatio = (double) apartment.getPrice() / criteria.getMaxPrice().get();
                criteriaScore += Math.max(0, 1.5 - priceRatio);
            }
        }

        if (criteria.getBedrooms().isPresent()) {
            totalCriteria++;
            if (apartment.getBedrooms() == criteria.getBedrooms().get()) {
                matchingCriteria++;
                criteriaScore += 1.0;
            }
        }

        if (criteria.getPetFriendly().isPresent()) {
            totalCriteria++;
            if (apartment.isPetFriendly() == criteria.getPetFriendly().get()) {
                matchingCriteria++;
                criteriaScore += 1.0;
            }
        }

        if (criteria.getParking().isPresent()) {
            totalCriteria++;
            if (apartment.isParkingAvailable() == criteria.getParking().get()) {
                matchingCriteria++;
                criteriaScore += 1.0;
            }
        }

        if (criteria.getLocation().isPresent()) {
            totalCriteria++;
            String targetLocation = criteria.getLocation().get().toLowerCase();
            String aptNeighborhood = apartment.getLocation().getNeighborhood().toLowerCase();

            if (aptNeighborhood.contains(targetLocation) || targetLocation.contains(aptNeighborhood)) {
                matchingCriteria++;
                criteriaScore += 1.0;
            } else if (isNearbyNeighborhood(aptNeighborhood, targetLocation)) {
                criteriaScore += 0.5;
            }
        }

        if (!criteria.getAmenities().isEmpty()) {
            totalCriteria++;
            long matchingAmenities = criteria.getAmenities().stream()
                    .mapToLong(amenity -> apartment.getAmenities().contains(amenity) ? 1 : 0)
                    .sum();

            if (matchingAmenities > 0) {
                matchingCriteria++;
                criteriaScore += (double) matchingAmenities / criteria.getAmenities().size();
            }
        }

        if (totalCriteria > 0) {
            criteriaScore = criteriaScore / totalCriteria;
        }

        score += criteriaScore * 0.6;

        getContext().getLog().debug("Apartment {} scored: vector={}, criteria={}, total={}",
                apartment.getId(), vectorScore, criteriaScore, score);

        return score;
    }

    private boolean isNearbyNeighborhood(String neighborhood1, String neighborhood2) {
        Map<String, List<String>> nearbyMap = Map.of(
                "downtown", List.of("financial district", "government center", "theater district"),
                "back bay", List.of("south end", "copley", "newbury street"),
                "cambridge", List.of("somerville", "porter square", "davis square"),
                "south end", List.of("back bay", "roxbury"),
                "seaport", List.of("fort point", "financial district"),
                "beacon hill", List.of("north end", "downtown"),
                "north end", List.of("beacon hill", "charlestown")
        );

        return nearbyMap.getOrDefault(neighborhood1, List.of()).contains(neighborhood2) ||
                nearbyMap.getOrDefault(neighborhood2, List.of()).contains(neighborhood1);
    }

    private static class ScoredApartment {
        public final Apartment apartment;
        public final double score;

        public ScoredApartment(Apartment apartment, double score) {
            this.apartment = apartment;
            this.score = score;
        }
    }

    private List<Apartment> searchApartments(SearchCriteria criteria) {
        return apartmentDatabase.stream()
                .filter(apt -> matchesCriteria(apt, criteria))
                .limit(60)
                .collect(Collectors.toList());
    }

    private boolean matchesCriteria(Apartment apartment, SearchCriteria criteria) {
        if (isEmptyCriteria(criteria)) {
            return true;
        }

        if (criteria.getMinPrice().isPresent() && apartment.getPrice() < criteria.getMinPrice().get()) {
            return false;
        }
        if (criteria.getMaxPrice().isPresent() && apartment.getPrice() > criteria.getMaxPrice().get()) {
            return false;
        }

        if (criteria.getBedrooms().isPresent() && apartment.getBedrooms() != criteria.getBedrooms().get()) {
            return false;
        }

        if (criteria.getBathrooms().isPresent() && apartment.getBathrooms() < criteria.getBathrooms().get()) {
            return false;
        }

        if (criteria.getPetFriendly().isPresent() && criteria.getPetFriendly().get() && !apartment.isPetFriendly()) {
            return false;
        }

        if (criteria.getParking().isPresent() && criteria.getParking().get() && !apartment.isParkingAvailable()) {
            return false;
        }

        if (criteria.getLocation().isPresent()) {
            String queryLocation = criteria.getLocation().get().toLowerCase();
            String aptNeighborhood = apartment.getLocation().getNeighborhood().toLowerCase();
            String aptAddress = apartment.getLocation().getAddress().toLowerCase();

            if (!aptNeighborhood.contains(queryLocation) && !aptAddress.contains(queryLocation)) {
                return false;
            }
        }

        if (!criteria.getAmenities().isEmpty()) {
            List<String> aptAmenities = apartment.getAmenities().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());

            for (String requestedAmenity : criteria.getAmenities()) {
                boolean hasAmenity = aptAmenities.stream()
                        .anyMatch(aptAmenity -> aptAmenity.contains(requestedAmenity.toLowerCase()));
                if (!hasAmenity) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isEmptyCriteria(SearchCriteria criteria) {
        return criteria.getMinPrice().isEmpty() &&
                criteria.getMaxPrice().isEmpty() &&
                criteria.getBedrooms().isEmpty() &&
                criteria.getBathrooms().isEmpty() &&
                criteria.getPetFriendly().isEmpty() &&
                criteria.getParking().isEmpty() &&
                criteria.getLocation().isEmpty() &&
                criteria.getAmenities().isEmpty();
    }

    private List<Apartment> loadApartmentData() {
        String[] possiblePaths = {
                "/apartments.json",
                "apartments.json",
                "/data/apartments.json",
                "data/apartments.json"
        };

        for (String path : possiblePaths) {
            getContext().getLog().debug("Trying to load apartments from: {}", path);
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    getContext().getLog().info("Successfully found apartments.json at: {}", path);
                    List<Apartment> apartments = objectMapper.readValue(is, new TypeReference<List<Apartment>>() {});
                    getContext().getLog().info("Loaded {} apartments from JSON file", apartments.size());
                    return apartments;
                }
            } catch (Exception e) {
                getContext().getLog().warn("Failed to load from path {}: {}", path, e.getMessage());
            }
        }

        getContext().getLog().error("Could not find apartments.json in any expected location");
        getContext().getLog().info("Classpath roots: {}",
                System.getProperty("java.class.path"));

        URL resource = getClass().getResource("/");
        if (resource != null) {
            getContext().getLog().info("Resource root: {}", resource.getPath());
        }

        getContext().getLog().warn("Using fallback apartment data");
        return createFallbackData();
    }

    private List<Apartment> createFallbackData() {
        String filePath = System.getProperty("apartments.file.path");
        if (filePath != null) {
            try {
                getContext().getLog().info("Trying to load from file system: {}", filePath);
                File file = new File(filePath);
                if (file.exists()) {
                    List<Apartment> apartments = objectMapper.readValue(file, new TypeReference<List<Apartment>>() {});
                    getContext().getLog().info("Loaded {} apartments from file system", apartments.size());
                    return apartments;
                }
            } catch (Exception e) {
                getContext().getLog().error("Failed to load from file system", e);
            }
        }

        getContext().getLog().info("Creating fallback apartment data");
        return List.of(
                new Apartment(
                        "apt-fallback-001",
                        "Downtown Studio",
                        1200,
                        0, // studio
                        1,
                        new Apartment.Location("100 Main St", 40.7128, -74.0060, "downtown"),
                        false,
                        true,
                        List.of("gym"),
                        600,
                        "2024-02-01",
                        List.of() // empty photos list
                ),
                new Apartment(
                        "apt-fallback-002",
                        "Pet-Friendly 2BR",
                        1800,
                        2,
                        2,
                        new Apartment.Location("200 Oak Ave", 40.7500, -73.9900, "midtown"),
                        true,
                        true,
                        List.of("gym", "rooftop"),
                        1100,
                        "2024-02-15",
                        List.of() // empty photos list
                )
        );
    }
}