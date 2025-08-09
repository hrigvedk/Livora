package org.livora.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
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

    // New field for RAG/semantic search scores
    private Map<String, Float> semanticScores;

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
        this.semanticScores = null; // Will be set later if using RAG
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

    // Getter and setter for semantic scores
    public Map<String, Float> getSemanticScores() {
        return semanticScores;
    }

    public void setSemanticScores(Map<String, Float> semanticScores) {
        this.semanticScores = semanticScores;
    }

    // Helper method to check if semantic search is enabled
    public boolean hasSemanticScores() {
        return semanticScores != null && !semanticScores.isEmpty();
    }

    // Helper method to get semantic score for a specific apartment
    public float getSemanticScore(String apartmentId) {
        if (semanticScores == null) {
            return 0.0f;
        }
        return semanticScores.getOrDefault(apartmentId, 0.0f);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SearchCriteria{");

        minPrice.ifPresent(p -> sb.append("minPrice=").append(p).append(", "));
        maxPrice.ifPresent(p -> sb.append("maxPrice=").append(p).append(", "));
        bedrooms.ifPresent(b -> sb.append("bedrooms=").append(b).append(", "));
        bathrooms.ifPresent(b -> sb.append("bathrooms=").append(b).append(", "));
        petFriendly.ifPresent(p -> sb.append("petFriendly=").append(p).append(", "));
        parking.ifPresent(p -> sb.append("parking=").append(p).append(", "));
        location.ifPresent(l -> sb.append("location='").append(l).append("', "));

        if (!amenities.isEmpty()) {
            sb.append("amenities=").append(amenities).append(", ");
        }

        proximity.ifPresent(p -> sb.append("proximity='").append(p).append("', "));

        if (hasSemanticScores()) {
            sb.append("semanticScoresCount=").append(semanticScores.size()).append(", ");
        }

        // Remove trailing comma and space if present
        if (sb.length() > 15) {
            sb.setLength(sb.length() - 2);
        }

        sb.append("}");
        return sb.toString();
    }
}