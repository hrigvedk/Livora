package org.livora.messages;

import akka.actor.typed.ActorRef;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ApartmentSearchMessages {

    public static final class FindApartments implements Command {
        public final SearchCriteria criteria;
        public final ActorRef<ApartmentsFound> replyTo;

        @JsonCreator
        public FindApartments(
                @JsonProperty("criteria") SearchCriteria criteria,
                @JsonProperty("replyTo") ActorRef<ApartmentsFound> replyTo) {
            this.criteria = criteria;
            this.replyTo = replyTo;
        }
    }

    public static final class ApartmentsFound implements Command {
        public final List<Apartment> apartments;
        public final int totalMatches;

        @JsonCreator
        public ApartmentsFound(
                @JsonProperty("apartments") List<Apartment> apartments,
                @JsonProperty("totalMatches") int totalMatches) {
            this.apartments = apartments;
            this.totalMatches = totalMatches;
        }
    }
}