package org.livora.config;

import okhttp3.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class GeminiConfig {
    private static final String API_KEY = getApiKey();

    private static String getApiKey() {
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            return envKey;
        }
        return System.getProperty("GEMINI_API_KEY");
    }
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static CompletableFuture<String> generateContent(String prompt) {
        if (API_KEY == null || API_KEY.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("GEMINI_API_KEY environment variable not set"));
        }

        String requestBody = String.format("""
            {
              "contents": [{
                "parts": [{
                  "text": "%s"
                }]
              }]
            }
            """, prompt.replace("\"", "\\\""));

        Request request = new Request.Builder()
                .url(API_URL + "?key=" + API_KEY)
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        CompletableFuture<String> future = new CompletableFuture<>();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("Unexpected response: " + response));
                        return;
                    }

                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    String text = jsonNode
                        .path("candidates")
                        .get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();

                    future.complete(text);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    public static boolean isConfigured() {
        return API_KEY != null && !API_KEY.isEmpty();
    }
}