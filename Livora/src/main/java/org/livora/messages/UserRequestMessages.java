package org.livora.messages;

import akka.actor.typed.ActorRef;
import org.livora.models.Apartment;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class UserRequestMessages {

    public static final class SearchRequest implements Command {
        public final String query;
        public final String sessionId;
        public final ActorRef<SearchResponse> replyTo;

        @JsonCreator
        public SearchRequest(
                @JsonProperty("query") String query,
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("replyTo") ActorRef<SearchResponse> replyTo) {
            this.query = query;
            this.sessionId = sessionId;
            this.replyTo = replyTo;
        }
    }

    public static final class SearchResponse implements Command {
        public final List<Apartment> results;
        public final String sessionId;
        public final SearchMetadata metadata;

        @JsonCreator
        public SearchResponse(
                @JsonProperty("results") List<Apartment> results,
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("metadata") SearchMetadata metadata) {
            this.results = results;
            this.sessionId = sessionId;
            this.metadata = metadata;
        }
    }

    public static final class ProcessParsedQuery implements Command {
        public final QueryParserMessages.QueryParsed queryParsed;
        public final SearchRequest originalRequest;

        public ProcessParsedQuery(QueryParserMessages.QueryParsed queryParsed, 
                                SearchRequest originalRequest) {
            this.queryParsed = queryParsed;
            this.originalRequest = originalRequest;
        }
    }

    public static final class QueryParsingFailed implements Command {
        public final SearchRequest originalRequest;
        public final Throwable error;

        public QueryParsingFailed(SearchRequest originalRequest, Throwable error) {
            this.originalRequest = originalRequest;
            this.error = error;
        }
    }

    public static class SearchMetadata {
        public final int totalResults;
        public final long searchTimeMs;
        public final double confidence;

        @JsonCreator
        public SearchMetadata(
                @JsonProperty("totalResults") int totalResults,
                @JsonProperty("searchTimeMs") long searchTimeMs,
                @JsonProperty("confidence") double confidence) {
            this.totalResults = totalResults;
            this.searchTimeMs = searchTimeMs;
            this.confidence = confidence;
        }
    }
}