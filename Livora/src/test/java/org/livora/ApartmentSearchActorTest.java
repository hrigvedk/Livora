package org.livora;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.*;
import org.livora.actors.ApartmentSearchActor;
import org.livora.messages.*;
import org.livora.models.SearchCriteria;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ApartmentSearchActorTest {

    private static ActorTestKit testKit;
    private ActorRef<Command> apartmentSearchActor;

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
        apartmentSearchActor = testKit.spawn(ApartmentSearchActor.create(), "test-search-actor-" + System.nanoTime());
    }

    @Test
    @DisplayName("Actor should start successfully")
    void testActorStarts() {
        assertNotNull(apartmentSearchActor);
    }

    @Test
    @DisplayName("Should handle FindApartments with empty criteria")
    void testFindApartmentsEmptyCriteria() {
        TestProbe<ApartmentSearchMessages.ApartmentsFound> probe = testKit.createTestProbe();
        SearchCriteria emptyCriteria = new SearchCriteria(
            null, null, null, null, null, null, null, List.of(), null
        );
        
        apartmentSearchActor.tell(new ApartmentSearchMessages.FindApartments(
            emptyCriteria, 
            probe.getRef()
        ));
        
        ApartmentSearchMessages.ApartmentsFound response =
            probe.expectMessageClass(ApartmentSearchMessages.ApartmentsFound.class, Duration.ofSeconds(10));
        
        assertNotNull(response);
        assertNotNull(response.apartments);
        assertTrue(response.totalMatches >= 0);
    }

    @Test
    @DisplayName("Should handle FindApartments with price criteria")
    void testFindApartmentsWithPrice() {
        TestProbe<ApartmentSearchMessages.ApartmentsFound> probe = testKit.createTestProbe();
        SearchCriteria priceCriteria = new SearchCriteria(
            null, 2000, null, null, null, null, null, List.of(), null
        );
        
        apartmentSearchActor.tell(new ApartmentSearchMessages.FindApartments(
            priceCriteria, 
            probe.getRef()
        ));
        
        ApartmentSearchMessages.ApartmentsFound response =
            probe.expectMessageClass(ApartmentSearchMessages.ApartmentsFound.class, Duration.ofSeconds(10));
        
        assertNotNull(response);
        assertNotNull(response.apartments);
        
        response.apartments.forEach(apt ->
            assertTrue(apt.getPrice() <= 2000, "Apartment price should be <= 2000")
        );
    }

    @Test
    @DisplayName("Should handle FindApartments with bedroom criteria")
    void testFindApartmentsWithBedrooms() {
        TestProbe<ApartmentSearchMessages.ApartmentsFound> probe = testKit.createTestProbe();
        SearchCriteria bedroomCriteria = new SearchCriteria(
            null, null, 2, null, null, null, null, List.of(), null
        );
        
        apartmentSearchActor.tell(new ApartmentSearchMessages.FindApartments(
            bedroomCriteria, 
            probe.getRef()
        ));
        
        ApartmentSearchMessages.ApartmentsFound response =
            probe.expectMessageClass(ApartmentSearchMessages.ApartmentsFound.class, Duration.ofSeconds(10));
        
        assertNotNull(response);
        assertNotNull(response.apartments);
        
        response.apartments.forEach(apt ->
            assertEquals(2, apt.getBedrooms(), "Should have 2 bedrooms")
        );
    }

    @Test
    @DisplayName("Should handle FindApartments with pet-friendly criteria")
    void testFindApartmentsPetFriendly() {
        TestProbe<ApartmentSearchMessages.ApartmentsFound> probe = testKit.createTestProbe();
        SearchCriteria petCriteria = new SearchCriteria(
            null, null, null, null, true, null, null, List.of(), null
        );
        
        apartmentSearchActor.tell(new ApartmentSearchMessages.FindApartments(
            petCriteria, 
            probe.getRef()
        ));
        
        ApartmentSearchMessages.ApartmentsFound response =
            probe.expectMessageClass(ApartmentSearchMessages.ApartmentsFound.class, Duration.ofSeconds(10));
        
        assertNotNull(response);
        assertNotNull(response.apartments);
        
        response.apartments.forEach(apt ->
            assertTrue(apt.isPetFriendly(), "Should be pet-friendly")
        );
    }
}