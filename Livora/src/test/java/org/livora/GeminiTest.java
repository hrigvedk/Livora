package org.livora;

import org.livora.config.GeminiConfig;
import org.livora.config.GeminiPrompts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.concurrent.CompletableFuture;

public class GeminiTest {

    @Test
    public void testGeminiConnection() {
        Assumptions.assumeTrue(GeminiConfig.isConfigured(), 
            "GEMINI_API_KEY not configured - skipping test");

        try {
            String prompt = GeminiPrompts.formatParseQuery(
                "I need a 2 bedroom apartment with parking near downtown under $1500");
            
            CompletableFuture<String> future = GeminiConfig.generateContent(prompt);
            String result = future.get(); // Wait for completion
            
            System.out.println("Gemini Response: " + result);
            
        } catch (Exception e) {
            System.err.println("Gemini test failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}