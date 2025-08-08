package org.livora;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.typed.Cluster;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import org.livora.actors.UserRequestActor;
import org.livora.actors.QueryParserActor;
import org.livora.actors.ApartmentSearchActor;
import org.livora.actors.LoggingActor;
import org.livora.actors.MapVisualizationActor;
import org.livora.api.RestApiRoutes;
import org.livora.messages.Command;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

public class Main {

    public static void main(String[] args) {
        System.out.println("=== Starting Livora Apartment Finder ===");

        String clusterRole = System.getProperty("akka.cluster.roles.0", "user-facing");
        System.out.println("Cluster role: " + clusterRole);
        System.out.println("Gemini API Key configured: " + (System.getenv("GEMINI_API_KEY") != null));

        try {
            ActorSystem<Void> system = ActorSystem.create(
                    Behaviors.setup(context -> {

                        Cluster cluster = Cluster.get(context.getSystem());
                        context.getLog().info("Starting node with role: {} on {}",
                                clusterRole, cluster.selfMember().address());

                        // Spawn actors based on cluster role
                        if ("user-facing".equals(clusterRole)) {
                            ActorRef<Command> userActor = setupUserFacingNode(context);

                            // Start HTTP server with the valid actor reference
                            context.getSystem().scheduler().scheduleOnce(
                                    Duration.ofSeconds(1),
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

        } catch (Exception e) {
            System.err.println("Failed to start system: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down system...");
        }));

        // Keep main thread alive
        System.out.println("System started. Press Ctrl+C to stop.");

        // Wait for system termination
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted");
        }
    }

    private static ActorRef<Command> setupUserFacingNode(akka.actor.typed.javadsl.ActorContext<Void> context) {
        context.getLog().info("Setting up user-facing node...");

        // Spawn user-facing actors and return reference
        ActorRef<Command> userActor = context.spawn(UserRequestActor.create(), "userRequestActor");
        context.spawn(LoggingActor.create(), "userFacingLogger");

        context.getLog().info("User-facing actors spawned");
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
                    system.log().info("API endpoints available:");
                    system.log().info("  POST /api/search - Search apartments");
                    system.log().info("  GET  /health - Health check");
                    system.log().info("  GET  /cluster - Cluster status");
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