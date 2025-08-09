package org.livora.messages;

import akka.actor.typed.ActorRef;
import org.livora.actors.VectorSearchActor;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class QueryParserMessages {

    public static final class ParseQuery implements Command {
        public final String naturalLanguageQuery;
        public final ActorRef<QueryParsed> replyTo;

        @JsonCreator
        public ParseQuery(
                @JsonProperty("naturalLanguageQuery") String naturalLanguageQuery,
                @JsonProperty("replyTo") ActorRef<QueryParsed> replyTo) {
            this.naturalLanguageQuery = naturalLanguageQuery;
            this.replyTo = replyTo;
        }
    }

    public static final class QueryParsed implements Command {
        public final SearchCriteria criteria;
        public final double confidence;

        @JsonCreator
        public QueryParsed(
                @JsonProperty("criteria") SearchCriteria criteria,
                @JsonProperty("confidence") double confidence) {
            this.criteria = criteria;
            this.confidence = confidence;
        }
    }

    public static final class ParseQueryWithRAG implements Command {
        public final String naturalLanguageQuery;
        public final VectorSearchActor.SearchResults ragContext;
        public final ActorRef<QueryParsed> replyTo;

        @JsonCreator
        public ParseQueryWithRAG(
                @JsonProperty("naturalLanguageQuery") String naturalLanguageQuery,
                @JsonProperty("ragContext") VectorSearchActor.SearchResults ragContext,
                @JsonProperty("replyTo") ActorRef<QueryParsed> replyTo) {
            this.naturalLanguageQuery = naturalLanguageQuery;
            this.ragContext = ragContext;
            this.replyTo = replyTo;
        }
    }
}