package org.livora.messages;

import akka.actor.typed.ActorRef;
import org.livora.models.SearchCriteria;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class LoggingMessages {

    public static final class LogEntry implements Command {
        public final String message;
        public final Instant timestamp;
        public final ActorRef<Command> originalSender;

        @JsonCreator
        public LogEntry(
                @JsonProperty("message") String message,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("originalSender") ActorRef<Command> originalSender) {
            this.message = message;
            this.timestamp = timestamp;
            this.originalSender = originalSender;
        }
    }

    public static final class LogSearchPerformed implements Command {
        public final SearchCriteria criteria;
        public final int resultCount;
        public final ActorRef<Command> requester;

        public LogSearchPerformed(SearchCriteria criteria, int resultCount, 
                                ActorRef<Command> requester) {
            this.criteria = criteria;
            this.resultCount = resultCount;
            this.requester = requester;
        }
    }
}