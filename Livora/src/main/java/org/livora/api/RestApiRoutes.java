package org.livora.api;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.model.HttpMethods;
import akka.cluster.typed.Cluster;
import org.livora.messages.*;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.UUID;
import java.util.Arrays;

import static akka.http.javadsl.server.Directives.*;

public class RestApiRoutes {

    private final ActorSystem<Void> system;
    private final ActorRef<Command> userRequestActor;
    private final Duration askTimeout = Duration.ofSeconds(15);

    public RestApiRoutes(ActorSystem<Void> system, ActorRef<Command> userRequestActor) {
        this.system = system;
        this.userRequestActor = userRequestActor;
    }

    public Route createRoutes() {
        return route(
                respondWithHeaders(Arrays.asList(
                                RawHeader.create("Access-Control-Allow-Origin", "*"),
                                RawHeader.create("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS"),
                                RawHeader.create("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin, Cache-Control"),
                                RawHeader.create("Access-Control-Allow-Credentials", "true"),
                                RawHeader.create("Access-Control-Max-Age", "3600"),
                                RawHeader.create("Access-Control-Expose-Headers", "Content-Type, X-Session-Id")
                        ), () ->
                                route(
                                        options(() -> {
                                            system.log().debug("CORS preflight request received");
                                            return complete(StatusCodes.OK, "CORS preflight handled");
                                        }),

                                        pathPrefix("api", () ->
                                                route(
                                                        path("search", () ->
                                                                post(() ->
                                                                        entity(Jackson.unmarshaller(SearchRequestDTO.class), searchRequest -> {

                                                                            system.log().info("REST API received search request: {}",
                                                                                    searchRequest.getQuery());

                                                                            String sessionId = searchRequest.getSessionId() != null ?
                                                                                    searchRequest.getSessionId() : UUID.randomUUID().toString();

                                                                            CompletionStage<UserRequestMessages.SearchResponse> responseFuture =
                                                                                    AskPattern.ask(
                                                                                            userRequestActor,
                                                                                            (ActorRef<UserRequestMessages.SearchResponse> replyTo) ->
                                                                                                    new UserRequestMessages.SearchRequest(
                                                                                                            searchRequest.getQuery(),
                                                                                                            sessionId,
                                                                                                            replyTo
                                                                                                    ),
                                                                                            askTimeout,
                                                                                            system.scheduler()
                                                                                    );

                                                                            return onSuccess(responseFuture, response -> {
                                                                                system.log().info("Returning {} results for session {}",
                                                                                        response.results.size(), response.sessionId);

                                                                                return complete(StatusCodes.OK, response, Jackson.marshaller());
                                                                            });
                                                                        })
                                                                )
                                                        ),

                                                        path("apartments", () ->
                                                                get(() -> {
                                                                    system.log().info("REST API received request for all apartments");

                                                                    String sessionId = UUID.randomUUID().toString();

                                                                    CompletionStage<UserRequestMessages.SearchResponse> responseFuture =
                                                                            AskPattern.ask(
                                                                                    userRequestActor,
                                                                                    (ActorRef<UserRequestMessages.SearchResponse> replyTo) ->
                                                                                            new UserRequestMessages.SearchRequest("", sessionId, replyTo),
                                                                                    askTimeout,
                                                                                    system.scheduler()
                                                                            );

                                                                    return onSuccess(responseFuture, response -> {
                                                                        system.log().info("Returning {} apartments from database for session {}",
                                                                                response.results.size(), response.sessionId);

                                                                        return complete(StatusCodes.OK, response, Jackson.marshaller());
                                                                    });
                                                                })
                                                        ),

                                                        path("test", () ->
                                                                get(() -> {
                                                                    TestResponse testResponse = new TestResponse(
                                                                            "API connection successful",
                                                                            System.currentTimeMillis(),
                                                                            "Backend is reachable from frontend"
                                                                    );
                                                                    return complete(StatusCodes.OK, testResponse, Jackson.marshaller());
                                                                })
                                                        )
                                                )
                                        ),

                                        path("health", () ->
                                                get(() -> {
                                                    HealthResponse healthResponse = new HealthResponse(
                                                            "healthy",
                                                            system.address().toString(),
                                                            System.currentTimeMillis()
                                                    );
                                                    return complete(StatusCodes.OK, healthResponse, Jackson.marshaller());
                                                })
                                        ),

                                        path("cluster", () ->
                                                get(() -> {
                                                    Cluster cluster = Cluster.get(system);
                                                    String rolesStr = cluster.selfMember().roles().mkString(",");
                                                    ClusterStatus status = new ClusterStatus(
                                                            cluster.selfMember().address().toString(),
                                                            rolesStr,
                                                            cluster.state().members().size()
                                                    );
                                                    return complete(StatusCodes.OK, status, Jackson.marshaller());
                                                })
                                        ),

                                        pathPrefix("debug", () ->
                                                path("cors", () ->
                                                        get(() -> {
                                                            CorsDebugResponse debugResponse = new CorsDebugResponse(
                                                                    "CORS is working correctly",
                                                                    "If you can see this from your React frontend, CORS headers are properly configured",
                                                                    "Backend reachable from: " + system.address().toString()
                                                            );
                                                            return complete(StatusCodes.OK, debugResponse, Jackson.marshaller());
                                                        })
                                                )
                                        )
                                )
                )
        );
    }

    public static class SearchRequestDTO {
        private String query;
        private String sessionId;

        public SearchRequestDTO() {}

        public SearchRequestDTO(String query, String sessionId) {
            this.query = query;
            this.sessionId = sessionId;
        }

        public String getQuery() { return query; }
        public String getSessionId() { return sessionId; }
        public void setQuery(String query) { this.query = query; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }



    public static class HealthResponse {
        private String status;
        private String nodeAddress;
        private long timestamp;

        public HealthResponse() {}

        public HealthResponse(String status, String nodeAddress, long timestamp) {
            this.status = status;
            this.nodeAddress = nodeAddress;
            this.timestamp = timestamp;
        }

        public String getStatus() { return status; }
        public String getNodeAddress() { return nodeAddress; }
        public long getTimestamp() { return timestamp; }
        public void setStatus(String status) { this.status = status; }
        public void setNodeAddress(String nodeAddress) { this.nodeAddress = nodeAddress; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    public static class ClusterStatus {
        private String selfAddress;
        private String roles;
        private int memberCount;

        public ClusterStatus() {}

        public ClusterStatus(String selfAddress, String roles, int memberCount) {
            this.selfAddress = selfAddress;
            this.roles = roles;
            this.memberCount = memberCount;
        }

        public String getSelfAddress() { return selfAddress; }
        public String getRoles() { return roles; }
        public int getMemberCount() { return memberCount; }
        public void setSelfAddress(String selfAddress) { this.selfAddress = selfAddress; }
        public void setRoles(String roles) { this.roles = roles; }
        public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    }

    public static class TestResponse {
        private String message;
        private long timestamp;
        private String details;

        public TestResponse() {}

        public TestResponse(String message, long timestamp, String details) {
            this.message = message;
            this.timestamp = timestamp;
            this.details = details;
        }

        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
        public String getDetails() { return details; }
        public void setMessage(String message) { this.message = message; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public void setDetails(String details) { this.details = details; }
    }

    public static class CorsDebugResponse {
        private String message;
        private String corsStatus;
        private String nodeInfo;

        public CorsDebugResponse() {}

        public CorsDebugResponse(String message, String corsStatus, String nodeInfo) {
            this.message = message;
            this.corsStatus = corsStatus;
            this.nodeInfo = nodeInfo;
        }

        public String getMessage() { return message; }
        public String getCorsStatus() { return corsStatus; }
        public String getNodeInfo() { return nodeInfo; }
        public void setMessage(String message) { this.message = message; }
        public void setCorsStatus(String corsStatus) { this.corsStatus = corsStatus; }
        public void setNodeInfo(String nodeInfo) { this.nodeInfo = nodeInfo; }
    }
}