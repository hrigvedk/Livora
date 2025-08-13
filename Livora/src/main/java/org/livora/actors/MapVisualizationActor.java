package org.livora.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.livora.messages.*;
import org.livora.models.Apartment;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;

public class MapVisualizationActor extends AbstractBehavior<Command> {

    private final ActorRef<Command> logger;

    public static Behavior<Command> create() {
        return Behaviors.setup(MapVisualizationActor::new);
    }

    private MapVisualizationActor(ActorContext<Command> context) {
        super(context);
        this.logger = context.spawn(LoggingActor.create(), "mapLogger");

        getContext().getLog().info("MapVisualizationActor started");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(MapVisualizationMessages.FormatForMap.class, this::onFormatForMap)
                .build();
    }

    private Behavior<Command> onFormatForMap(
            MapVisualizationMessages.FormatForMap request) {

        getContext().getLog().info("Formatting {} apartments for map visualization",
                request.apartments.size());

        MapBounds bounds = calculateMapBounds(request.apartments);

        List<MapMarker> markers = request.apartments.stream()
                .map(this::createMapMarker)
                .collect(Collectors.toList());

        Map<String, List<MapMarker>> clusteredMarkers = clusterNearbyMarkers(markers);

        logger.tell(new LoggingMessages.LogEntry(
                String.format("Map formatted: %d markers, %d clusters, bounds=[%.4f,%.4f to %.4f,%.4f]",
                        markers.size(),
                        clusteredMarkers.size(),
                        bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon),
                Instant.now(),
                getContext().getSelf()
        ));

        MapVisualizationData mapData = new MapVisualizationData(
                markers,
                clusteredMarkers,
                bounds
        );

        request.replyTo.tell(new MapVisualizationMessages.MapFormatted(mapData));

        return this;
    }

    private MapMarker createMapMarker(Apartment apartment) {
        return new MapMarker(
                apartment.getId(),
                apartment.getLocation().getLatitude(),
                apartment.getLocation().getLongitude(),
                apartment.getTitle(),
                apartment.getPrice(),
                apartment.getBedrooms(),
                apartment.getLocation().getAddress(),
                apartment.isPetFriendly(),
                apartment.getAmenities()
        );
    }

    private MapBounds calculateMapBounds(List<Apartment> apartments) {
        if (apartments.isEmpty()) {
            return new MapBounds(40.6892, -74.0445, 40.7589, -73.9851);
        }

        double minLat = apartments.stream()
                .mapToDouble(apt -> apt.getLocation().getLatitude())
                .min().orElse(40.7128);

        double maxLat = apartments.stream()
                .mapToDouble(apt -> apt.getLocation().getLatitude())
                .max().orElse(40.7128);

        double minLon = apartments.stream()
                .mapToDouble(apt -> apt.getLocation().getLongitude())
                .min().orElse(-74.0060);

        double maxLon = apartments.stream()
                .mapToDouble(apt -> apt.getLocation().getLongitude())
                .max().orElse(-74.0060);

        double latPadding = (maxLat - minLat) * 0.1;
        double lonPadding = (maxLon - minLon) * 0.1;

        return new MapBounds(
                minLat - latPadding,
                minLon - lonPadding,
                maxLat + latPadding,
                maxLon + lonPadding
        );
    }

    private Map<String, List<MapMarker>> clusterNearbyMarkers(List<MapMarker> markers) {
        final double CLUSTER_THRESHOLD = 0.01;

        return markers.stream()
                .collect(Collectors.groupingBy(marker -> {
                    double roundedLat = Math.round(marker.latitude / CLUSTER_THRESHOLD) * CLUSTER_THRESHOLD;
                    double roundedLon = Math.round(marker.longitude / CLUSTER_THRESHOLD) * CLUSTER_THRESHOLD;
                    return String.format("%.3f,%.3f", roundedLat, roundedLon);
                }));
    }

    public static class MapMarker {
        public final String id;
        public final double latitude;
        public final double longitude;
        public final String title;
        public final int price;
        public final int bedrooms;
        public final String address;
        public final boolean petFriendly;
        public final List<String> amenities;

        public MapMarker(String id, double latitude, double longitude, String title,
                         int price, int bedrooms, String address, boolean petFriendly,
                         List<String> amenities) {
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.title = title;
            this.price = price;
            this.bedrooms = bedrooms;
            this.address = address;
            this.petFriendly = petFriendly;
            this.amenities = amenities;
        }
    }

    public static class MapBounds {
        public final double minLat;
        public final double minLon;
        public final double maxLat;
        public final double maxLon;

        public MapBounds(double minLat, double minLon, double maxLat, double maxLon) {
            this.minLat = minLat;
            this.minLon = minLon;
            this.maxLat = maxLat;
            this.maxLon = maxLon;
        }
    }

    public static class MapVisualizationData {
        public final List<MapMarker> markers;
        public final Map<String, List<MapMarker>> clusters;
        public final MapBounds bounds;

        public MapVisualizationData(List<MapMarker> markers,
                                    Map<String, List<MapMarker>> clusters,
                                    MapBounds bounds) {
            this.markers = markers;
            this.clusters = clusters;
            this.bounds = bounds;
        }
    }
}