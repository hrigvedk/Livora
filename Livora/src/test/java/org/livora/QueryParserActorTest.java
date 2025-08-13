package org.livora;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.*;
import org.livora.actors.QueryParserActor;
import org.livora.config.GeminiConfig;
import org.livora.messages.*;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class QueryParserActorTest {

    private static ActorTestKit testKit;
    private ActorRef<Command> queryParserActor;

    @BeforeAll
    static void setupClass() {
        String config = """
            akka {
              loglevel = "WARNING"
              log-dead-letters = off
            }
            """;

        testKit = ActorTestKit.create(ConfigFactory.parseString(config));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void setup() {
        queryParserActor = testKit.spawn(QueryParserActor.create(), "test-parser-actor-" + System.nanoTime());
    }

    @Test
    @DisplayName("Actor should start successfully")
    void testActorStarts() {
        assertNotNull(queryParserActor);
    }

    @Test
    @DisplayName("Should handle ParseQuery message")
    void testParseQuery() {
        TestProbe<QueryParserMessages.QueryParsed> probe = testKit.createTestProbe();
        String query = "2 bedroom apartment under 2000";

        queryParserActor.tell(new QueryParserMessages.ParseQuery(
                query,
                probe.getRef()
        ));

        QueryParserMessages.QueryParsed response =
                probe.expectMessageClass(QueryParserMessages.QueryParsed.class, Duration.ofSeconds(15));

        assertNotNull(response);
        assertNotNull(response.criteria);
        assertTrue(response.confidence >= 0.0 && response.confidence <= 1.0);
    }

    @Test
    @DisplayName("Should handle empty query")
    void testParseEmptyQuery() {
        TestProbe<QueryParserMessages.QueryParsed> probe = testKit.createTestProbe();
        String emptyQuery = "";

        queryParserActor.tell(new QueryParserMessages.ParseQuery(
                emptyQuery,
                probe.getRef()
        ));

        QueryParserMessages.QueryParsed response =
                probe.expectMessageClass(QueryParserMessages.QueryParsed.class, Duration.ofSeconds(15));

        assertNotNull(response);
        assertNotNull(response.criteria);
    }

    @Test
    @DisplayName("Should handle query with price")
    void testParseQueryWithPrice() {
        TestProbe<QueryParserMessages.QueryParsed> probe = testKit.createTestProbe();
        String query = "apartment under 1500";

        queryParserActor.tell(new QueryParserMessages.ParseQuery(
                query,
                probe.getRef()
        ));

        QueryParserMessages.QueryParsed response =
                probe.expectMessageClass(QueryParserMessages.QueryParsed.class, Duration.ofSeconds(15));

        assertNotNull(response);
        assertNotNull(response.criteria);

        assertTrue(response.confidence >= 0.0 && response.confidence <= 1.0,
                "Should have valid confidence score");
    }

    @Test
    @DisplayName("Should handle query with pet mention")
    void testParseQueryWithPets() {
        TestProbe<QueryParserMessages.QueryParsed> probe = testKit.createTestProbe();
        String query = "pet friendly apartment";

        queryParserActor.tell(new QueryParserMessages.ParseQuery(
                query,
                probe.getRef()
        ));

        QueryParserMessages.QueryParsed response =
                probe.expectMessageClass(QueryParserMessages.QueryParsed.class, Duration.ofSeconds(15));

        assertNotNull(response);
        assertNotNull(response.criteria);

        assertTrue(response.confidence >= 0.0 && response.confidence <= 1.0,
                "Should have valid confidence score");
    }

    @Test
    @DisplayName("Should handle complex query")
    void testParseComplexQuery() {
        TestProbe<QueryParserMessages.QueryParsed> probe = testKit.createTestProbe();
        String query = "2 bedroom pet friendly apartment with parking near downtown under 2500";

        queryParserActor.tell(new QueryParserMessages.ParseQuery(
                query,
                probe.getRef()
        ));

        QueryParserMessages.QueryParsed response =
                probe.expectMessageClass(QueryParserMessages.QueryParsed.class, Duration.ofSeconds(15));

        assertNotNull(response);
        assertNotNull(response.criteria);

        assertTrue(response.confidence >= 0.0 && response.confidence <= 1.0,
                "Should have valid confidence score");
    }
}