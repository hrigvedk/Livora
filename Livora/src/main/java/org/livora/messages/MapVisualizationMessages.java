package org.livora.messages;

import akka.actor.typed.ActorRef;
import org.livora.models.Apartment;
import org.livora.actors.MapVisualizationActor.MapVisualizationData;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class MapVisualizationMessages {

    public static final class FormatForMap implements Command {
        public final List<Apartment> apartments;
        public final ActorRef<MapFormatted> replyTo;

        @JsonCreator
        public FormatForMap(
                @JsonProperty("apartments") List<Apartment> apartments,
                @JsonProperty("replyTo") ActorRef<MapFormatted> replyTo) {
            this.apartments = apartments;
            this.replyTo = replyTo;
        }
    }

    public static final class MapFormatted implements Command {
        public final MapVisualizationData mapData;

        @JsonCreator
        public MapFormatted(
                @JsonProperty("mapData") MapVisualizationData mapData) {
            this.mapData = mapData;
        }
    }
}