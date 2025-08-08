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
        // Configure ObjectMapper to ignore unknown properties
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.logger = context.spawn(LoggingActor.create(), "searchLogger");

        // Load apartment data on startup
        this.apartmentDatabase = loadApartmentData();

        getContext().getLog().info("ApartmentSearchActor started with {} apartments loaded",
                apartmentDatabase.size());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ApartmentSearchMessages.FindApartments.class, this::onFindApartments)
                .build();
    }

    private Behavior<Command> onFindApartments(
            ApartmentSearchMessages.FindApartments request) {

        long startTime = System.currentTimeMillis();

        getContext().getLog().info("Searching apartments with criteria: bedrooms={}, maxPrice={}, location={}",
                request.criteria.getBedrooms().orElse(null),
                request.criteria.getMaxPrice().orElse(null),
                request.criteria.getLocation().orElse("any"));

        // Perform the search with filtering
        List<Apartment> results = searchApartments(request.criteria);

        long searchTime = System.currentTimeMillis() - startTime;

        // FORWARD pattern - preserve the original sender when logging
        // This demonstrates the forward pattern by passing along the search metrics
        // while preserving the original message sender context
        getContext().getLog().debug("Forwarding search metrics to logger...");

        // Create a log entry that will be forwarded, preserving sender information
        LoggingMessages.LogSearchPerformed logMessage =
                new LoggingMessages.LogSearchPerformed(
                        request.criteria,
                        results.size(),
                        getContext().getSelf()  // This preserves the searcher as the context
                );

        // FORWARD the message to logger - this preserves the original sender context
        // The logger will know this search operation came from ApartmentSearchActor
        logger.tell(logMessage);

        getContext().getLog().info("Found {} apartments in {}ms", results.size(), searchTime);

        // TELL pattern - respond to the requester
        request.replyTo.tell(new ApartmentSearchMessages.ApartmentsFound(results, results.size()));

        return this;
    }

    private List<Apartment> searchApartments(SearchCriteria criteria) {
        return apartmentDatabase.stream()
                .filter(apt -> matchesCriteria(apt, criteria))
                .limit(50) // Limit results for performance
                .collect(Collectors.toList());
    }

    private boolean matchesCriteria(Apartment apartment, SearchCriteria criteria) {
        // Price filtering
        if (criteria.getMinPrice().isPresent() && apartment.getPrice() < criteria.getMinPrice().get()) {
            return false;
        }
        if (criteria.getMaxPrice().isPresent() && apartment.getPrice() > criteria.getMaxPrice().get()) {
            return false;
        }

        // Bedroom filtering
        if (criteria.getBedrooms().isPresent() && apartment.getBedrooms() != criteria.getBedrooms().get()) {
            return false;
        }

        // Bathroom filtering
        if (criteria.getBathrooms().isPresent() && apartment.getBathrooms() < criteria.getBathrooms().get()) {
            return false;
        }

        // Pet-friendly filtering
        if (criteria.getPetFriendly().isPresent() && criteria.getPetFriendly().get() && !apartment.isPetFriendly()) {
            return false;
        }

        // Parking filtering
        if (criteria.getParking().isPresent() && criteria.getParking().get() && !apartment.isParkingAvailable()) {
            return false;
        }

        // Location filtering (case-insensitive substring match)
        if (criteria.getLocation().isPresent()) {
            String queryLocation = criteria.getLocation().get().toLowerCase();
            String aptNeighborhood = apartment.getLocation().getNeighborhood().toLowerCase();
            String aptAddress = apartment.getLocation().getAddress().toLowerCase();

            if (!aptNeighborhood.contains(queryLocation) && !aptAddress.contains(queryLocation)) {
                return false;
            }
        }

        // Amenities filtering (apartment must have all requested amenities)
        if (!criteria.getAmenities().isEmpty()) {
            List<String> aptAmenities = apartment.getAmenities().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());

            for (String requiredAmenity : criteria.getAmenities()) {
                if (!aptAmenities.contains(requiredAmenity.toLowerCase())) {
                    return false;
                }
            }
        }

        return true;
    }

    private List<Apartment> loadApartmentData() {
        // Try multiple paths to find the file
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

        // If we get here, no file was found
        getContext().getLog().error("Could not find apartments.json in any expected location");
        getContext().getLog().info("Classpath roots: {}",
                System.getProperty("java.class.path"));

        // Check if we're running from JAR or IDE
        URL resource = getClass().getResource("/");
        if (resource != null) {
            getContext().getLog().info("Resource root: {}", resource.getPath());
        }

        getContext().getLog().warn("Using fallback apartment data");
        return createFallbackData();
    }

    private List<Apartment> createFallbackData() {
        // First try to load from file system as absolute path
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

        // Create some basic apartment data if JSON loading fails
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