package org.livora.messages;

import akka.actor.typed.ActorRef;
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
}