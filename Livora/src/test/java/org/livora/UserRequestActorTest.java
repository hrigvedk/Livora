package org.livora;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.*;
import org.livora.actors.UserRequestActor;
import org.livora.messages.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class UserRequestActorTest {

    private static ActorTestKit testKit;
    private ActorRef<Command> userRequestActor;

    @BeforeAll
    static void setupClass() {
        String config = """
            akka {
              actor.provider = "cluster"
              remote.artery.canonical.hostname = "127.0.0.1"
              remote.artery.canonical.port = 0
              cluster.seed-nodes = []
              loglevel = "WARNING"
            }
            """;

        testKit = ActorTestKit.create(ConfigFactory.parseString(config));

        Cluster cluster = Cluster.get(testKit.system());
        cluster.manager().tell(new Join(cluster.selfMember().address()));

        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void setup() {
        userRequestActor = testKit.spawn(UserRequestActor.create(), "test-actor-" + System.nanoTime());
    }

    @Test
    @DisplayName("Actor should start successfully")
    void testActorStarts() {
        assertNotNull(userRequestActor);
    }

    @Test
    @DisplayName("Should accept SearchRequest message")
    void testAcceptsSearchRequest() {
        TestProbe<UserRequestMessages.SearchResponse> probe = testKit.createTestProbe();

        userRequestActor.tell(new UserRequestMessages.SearchRequest(
                "test",
                "session-1",
                probe.getRef()
        ));

        UserRequestMessages.SearchResponse response =
                probe.expectMessageClass(UserRequestMessages.SearchResponse.class, Duration.ofSeconds(30));

        assertNotNull(response);
    }

    @Test
    @DisplayName("Should handle empty query")
    void testEmptyQuery() {
        TestProbe<UserRequestMessages.SearchResponse> probe = testKit.createTestProbe();

        userRequestActor.tell(new UserRequestMessages.SearchRequest(
                "",
                "session-2",
                probe.getRef()
        ));

        UserRequestMessages.SearchResponse response =
                probe.expectMessageClass(UserRequestMessages.SearchResponse.class, Duration.ofSeconds(30));

        assertNotNull(response);
        assertEquals("session-2", response.sessionId);
    }

    @Test
    @DisplayName("Should handle QueryParsingFailed message")
    void testQueryParsingFailed() {
        TestProbe<UserRequestMessages.SearchResponse> probe = testKit.createTestProbe();
        UserRequestMessages.SearchRequest request =
                new UserRequestMessages.SearchRequest("test", "session-3", probe.getRef());

        userRequestActor.tell(new UserRequestMessages.QueryParsingFailed(request));

        UserRequestMessages.SearchResponse response =
                probe.expectMessageClass(UserRequestMessages.SearchResponse.class, Duration.ofSeconds(10));

        assertNotNull(response);
        assertTrue(response.results.isEmpty());
    }
}