package mine.coding.ai.transport;

import mine.coding.ai.spatial.FocusRequest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Read-only world observation used by the bounded AI attention loop. */
@FunctionalInterface
public interface FocusReader {
    CompletableFuture<String> read(FocusRequest request);

    static FocusReader unavailable() {
        return request -> CompletableFuture.failedFuture(new IllegalStateException("focus reader unavailable"));
    }
}
