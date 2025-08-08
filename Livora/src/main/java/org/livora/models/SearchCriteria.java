package org.livora.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public class SearchCriteria {
    private final Optional<Integer> minPrice;
    private final Optional<Integer> maxPrice;
    private final Optional<Integer> bedrooms;
    private final Optional<Integer> bathrooms;
    private final Optional<Boolean> petFriendly;
    private final Optional<Boolean> parking;
    private final Optional<String> location;
    private final List<String> amenities;
    private final Optional<String> proximity;

    @JsonCreator
    public SearchCriteria(
            @JsonProperty("minPrice") Integer minPrice,
            @JsonProperty("maxPrice") Integer maxPrice,
            @JsonProperty("bedrooms") Integer bedrooms,
            @JsonProperty("bathrooms") Integer bathrooms,
            @JsonProperty("petFriendly") Boolean petFriendly,
            @JsonProperty("parking") Boolean parking,
            @JsonProperty("location") String location,
            @JsonProperty("amenities") List<String> amenities,
            @JsonProperty("proximity") String proximity) {
        this.minPrice = Optional.ofNullable(minPrice);
        this.maxPrice = Optional.ofNullable(maxPrice);
        this.bedrooms = Optional.ofNullable(bedrooms);
        this.bathrooms = Optional.ofNullable(bathrooms);
        this.petFriendly = Optional.ofNullable(petFriendly);
        this.parking = Optional.ofNullable(parking);
        this.location = Optional.ofNullable(location);
        this.amenities = amenities != null ? amenities : List.of();
        this.proximity = Optional.ofNullable(proximity);
    }

    // Getters
    public Optional<Integer> getMinPrice() { return minPrice; }
    public Optional<Integer> getMaxPrice() { return maxPrice; }
    public Optional<Integer> getBedrooms() { return bedrooms; }
    public Optional<Integer> getBathrooms() { return bathrooms; }
    public Optional<Boolean> getPetFriendly() { return petFriendly; }
    public Optional<Boolean> getParking() { return parking; }
    public Optional<String> getLocation() { return location; }
    public List<String> getAmenities() { return amenities; }
    public Optional<String> getProximity() { return proximity; }
}