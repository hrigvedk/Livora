package org.livora.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Apartment {
    private final String id;
    private final String title;
    private final int price;
    private final int bedrooms;
    private final int bathrooms;
    private final Location location;
    private final boolean petFriendly;
    private final boolean parkingAvailable;
    private final List<String> amenities;
    private final int squareFeet;
    private final String available;
    private final List<String> photos;

    @JsonCreator
    public Apartment(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("price") int price,
            @JsonProperty("bedrooms") int bedrooms,
            @JsonProperty("bathrooms") int bathrooms,
            @JsonProperty("location") Location location,
            @JsonProperty("petFriendly") boolean petFriendly,
            @JsonProperty("parkingAvailable") boolean parkingAvailable,
            @JsonProperty("amenities") List<String> amenities,
            @JsonProperty("squareFeet") int squareFeet,
            @JsonProperty("available") String available,
            @JsonProperty("photos") List<String> photos) {
        this.id = id;
        this.title = title;
        this.price = price;
        this.bedrooms = bedrooms;
        this.bathrooms = bathrooms;
        this.location = location;
        this.petFriendly = petFriendly;
        this.parkingAvailable = parkingAvailable;
        this.amenities = amenities;
        this.squareFeet = squareFeet;
        this.available = available;
        this.photos = photos != null ? photos : List.of();
    }

    // Getters
    public String getId() { return id; }
    public String getTitle() { return title; }
    public int getPrice() { return price; }
    public int getBedrooms() { return bedrooms; }
    public int getBathrooms() { return bathrooms; }
    public Location getLocation() { return location; }
    public boolean isPetFriendly() { return petFriendly; }
    public boolean isParkingAvailable() { return parkingAvailable; }
    public List<String> getAmenities() { return amenities; }
    public int getSquareFeet() { return squareFeet; }
    public String getAvailable() { return available; }
    public List<String> getPhotos() { return photos; }

    public static class Location {
        private final String address;
        private final double latitude;
        private final double longitude;
        private final String neighborhood;

        @JsonCreator
        public Location(
                @JsonProperty("address") String address,
                @JsonProperty("latitude") double latitude,
                @JsonProperty("longitude") double longitude,
                @JsonProperty("neighborhood") String neighborhood) {
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
            this.neighborhood = neighborhood;
        }

        public String getAddress() { return address; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public String getNeighborhood() { return neighborhood; }
    }
}