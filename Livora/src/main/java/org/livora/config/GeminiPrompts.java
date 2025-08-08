package org.livora.config;

public class GeminiPrompts {
    
    public static final String PARSE_QUERY_PROMPT = """
        Parse the following apartment search query into structured JSON format.
        
        Query: "%s"
        
        Extract these fields if mentioned (use null for unspecified):
        - minPrice: minimum rent price (integer)
        - maxPrice: maximum rent price (integer)
        - bedrooms: number of bedrooms (integer, use 0 for studio)
        - bathrooms: number of bathrooms (integer)
        - petFriendly: boolean if pets are mentioned
        - parking: boolean if parking is mentioned
        - location: area or neighborhood name (string)
        - amenities: array of mentioned amenities (strings)
        - proximity: proximity indicator like "near", "walking distance" (string)
        
        Return ONLY valid JSON with no additional text. Example:
        {"maxPrice": 1800, "bedrooms": 2, "petFriendly": true, "location": "downtown"}
        
        If you cannot extract meaningful information, return: {"confidence": 0.1}
        """;

    public static String formatParseQuery(String query) {
        return String.format(PARSE_QUERY_PROMPT, query);
    }
}