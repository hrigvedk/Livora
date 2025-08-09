package org.livora;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.typed.Cluster;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;

import org.livora.actors.*;
import org.livora.api.RestApiRoutes;
import org.livora.messages.Command;
import org.livora.services.IndexingService;
import org.livora.models.Apartment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

public class Main {

    private static ActorRef<VectorSearchActor.Command> vectorSearchActor;

    public static void main(String[] args) {
        System.out.println("=== Starting Livora Apartment Finder with RAG ===");

        // Check environment
        String clusterRole = System.getProperty("akka.cluster.roles.0", "user-facing");
        String qdrantHost = System.getProperty("qdrant.host",
                System.getenv().getOrDefault("QDRANT_HOST", "localhost"));

        System.out.println("Configuration:");
        System.out.println("  Cluster role: " + clusterRole);
        System.out.println("  Qdrant host: " + qdrantHost);
        System.out.println("  Gemini API Key configured: " + (System.getenv("GEMINI_API_KEY") != null));

        try {
            ActorSystem<Void> system = ActorSystem.create(
                    Behaviors.setup(context -> {

                        Cluster cluster = Cluster.get(context.getSystem());
                        context.getLog().info("Starting node with role: {} on {}",
                                clusterRole, cluster.selfMember().address());

                        // Spawn actors based on cluster role
                        if ("user-facing".equals(clusterRole)) {
                            ActorRef<Command> userActor = setupUserFacingNode(context);

                            // Initialize Vector Search and indexing
                            context.getSystem().scheduler().scheduleOnce(
                                    Duration.ofSeconds(3),
                                    () -> {
                                        System.out.println("Initializing Vector Search and RAG components...");
                                        initializeRAG(context.getSystem());
                                    },
                                    context.getExecutionContext()
                            );

                            // Start HTTP server
                            context.getSystem().scheduler().scheduleOnce(
                                    Duration.ofSeconds(5),
                                    () -> {
                                        System.out.println("Starting HTTP server...");
                                        startHttpServer(context.getSystem(), userActor);
                                    },
                                    context.getExecutionContext()
                            );

                        } else if ("backend".equals(clusterRole)) {
                            setupBackendNode(context);
                        }

                        return Behaviors.empty();
                    }),
                    "ApartmentSystem"
            );

            System.out.println("ActorSystem created successfully");
            System.out.println("System initialization complete");

            // Print helpful information
            if ("user-facing".equals(clusterRole)) {
                System.out.println("\n=== Livora is starting up ===");
                System.out.println("API will be available at: http://localhost:8080");
                System.out.println("Qdrant dashboard: http://localhost:6335");
                System.out.println("Health check: http://localhost:8080/health");
                System.out.println("\nInitializing vector database...");
            }

        } catch (Exception e) {
            System.err.println("Failed to start system: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down Livora system...");
        }));

        // Keep main thread alive
        System.out.println("System started. Press Ctrl+C to stop.\n");

        // Wait for system termination
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted");
        }
    }

    private static ActorRef<Command> setupUserFacingNode(akka.actor.typed.javadsl.ActorContext<Void> context) {
        context.getLog().info("Setting up user-facing node with RAG support...");

        // Spawn user-facing actors
        ActorRef<Command> userActor = context.spawn(UserRequestActor.create(), "userRequestActor");

        // Spawn VectorSearchActor for RAG - cast to the right type
        vectorSearchActor = context.spawn(VectorSearchActor.create(), "vectorSearchActor");

        // Spawn MapVisualizationActor
        context.spawn(MapVisualizationActor.create(), "mapVisualizationActor");

        // Spawn logging actor
        context.spawn(LoggingActor.create(), "userFacingLogger");

        context.getLog().info("User-facing actors spawned with RAG support");
        return userActor;
    }

    private static void setupBackendNode(akka.actor.typed.javadsl.ActorContext<Void> context) {
        context.getLog().info("Setting up backend node...");

        // Spawn backend processing actors
        context.spawn(QueryParserActor.create(), "queryParserActor");
        context.spawn(ApartmentSearchActor.create(), "apartmentSearchActor");
        context.spawn(LoggingActor.create(), "backendLogger");

        context.getLog().info("Backend actors spawned");
    }

    private static void initializeRAG(ActorSystem<Void> system) {
        system.log().info("Initializing RAG components and vector database...");

        try {
            // Load apartments data
            List<Apartment> apartments = loadApartments(system);

            if (apartments.isEmpty()) {
                system.log().warn("No apartments found to index");
                return;
            }

            system.log().info("Loaded {} apartments, starting indexing...", apartments.size());

            // Index apartments in vector database
            IndexingService.indexAllApartments(system, vectorSearchActor, apartments)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            system.log().error("Failed to index apartments", throwable);
                        } else {
                            system.log().info("Indexing complete: {}", result.getSummary());
                            if (!result.errors.isEmpty()) {
                                system.log().warn("Indexing errors: {}", result.errors);
                            }
                            System.out.println("\n✅ Vector database initialized with " +
                                    result.totalIndexed + " apartments");
                        }
                    });

            // Index some sample successful queries for learning
            indexSampleQueries(system);

        } catch (Exception e) {
            system.log().error("Failed to initialize RAG components", e);
        }
    }

    private static List<Apartment> loadApartments(ActorSystem<Void> system) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Try multiple paths to find the file
        String[] possiblePaths = {
                "/apartments.json",
                "apartments.json",
                "/data/apartments.json",
                "data/apartments.json"
        };

        for (String path : possiblePaths) {
            try (InputStream is = Main.class.getResourceAsStream(path)) {
                if (is != null) {
                    List<Apartment> apartments = objectMapper.readValue(is,
                            new TypeReference<List<Apartment>>() {});
                    system.log().info("Loaded {} apartments from {}", apartments.size(), path);
                    return apartments;
                }
            } catch (Exception e) {
                system.log().debug("Could not load from path: {}", path);
            }
        }

        system.log().warn("Could not find apartments.json, using sample data");
        return createSampleApartments();
    }

    private static List<Apartment> createSampleApartments() {
        // Create some sample apartments for demo
        return List.of(
                new Apartment(
                        "apt-001",
                        "Luxury Downtown Loft with City Views",
                        2500,
                        2,
                        2,
                        new Apartment.Location("100 Main St, Downtown", 42.3601, -71.0589, "downtown"),
                        true,
                        true,
                        List.of("gym", "rooftop", "concierge", "pool"),
                        1200,
                        "2024-02-01",
                        List.of()
                ),
                new Apartment(
                        "apt-002",
                        "Cozy Studio Near University",
                        1200,
                        0,
                        1,
                        new Apartment.Location("50 College Ave", 42.3505, -71.1054, "allston"),
                        false,
                        false,
                        List.of("laundry", "bike-storage"),
                        450,
                        "2024-02-15",
                        List.of()
                ),
                new Apartment(
                        "apt-003",
                        "Family-Friendly 3BR in Quiet Neighborhood",
                        3200,
                        3,
                        2,
                        new Apartment.Location("25 Oak Street", 42.3751, -71.1056, "cambridge"),
                        true,
                        true,
                        List.of("yard", "garage", "playground"),
                        1800,
                        "2024-03-01",
                        List.of()
                )
        );
    }

    private static void indexSampleQueries(ActorSystem<Void> system) {
        // Index some successful query examples for RAG learning
        String[] sampleQueries = {
                "pet friendly apartment with parking under 2000",
                "2 bedroom near downtown with gym",
                "studio apartment close to university",
                "luxury apartment with rooftop and concierge",
                "affordable housing near public transport",
                "family friendly with yard and good schools"
        };

        for (String query : sampleQueries) {
            IndexingService.storeSuccessfulQuery(vectorSearchActor, query, 5);
        }

        system.log().info("Indexed {} sample queries for RAG learning", sampleQueries.length);
    }

    private static void startHttpServer(ActorSystem<Void> system, ActorRef<Command> userRequestActor) {
        int httpPort = Integer.parseInt(System.getProperty("http.port", "8080"));

        try {
            RestApiRoutes apiRoutes = new RestApiRoutes(system, userRequestActor);

            CompletionStage<ServerBinding> binding = Http.get(system)
                    .newServerAt("localhost", httpPort)
                    .bind(apiRoutes.createRoutes());

            binding.whenComplete((bind, ex) -> {
                if (bind != null) {
                    system.log().info("HTTP server started at http://localhost:{}/", httpPort);
                    System.out.println("\n✅ HTTP API ready at http://localhost:" + httpPort);
                    System.out.println("\n=== Available Endpoints ===");
                    System.out.println("  POST /api/search - Search apartments with natural language");
                    System.out.println("  GET  /api/apartments - Get all apartments");
                    System.out.println("  GET  /health - Health check");
                    System.out.println("  GET  /cluster - Cluster status");
                    System.out.println("\n💡 Try: \"2 bedroom pet friendly apartment near downtown under $2000\"");
                } else {
                    system.log().error("Failed to start HTTP server", ex);
                    system.terminate();
                }
            });

        } catch (Exception e) {
            system.log().error("Error setting up HTTP server", e);
            system.terminate();
        }
    }
}