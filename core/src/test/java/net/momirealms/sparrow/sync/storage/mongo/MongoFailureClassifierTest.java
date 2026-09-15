package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.MongoException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.ServerAddress;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.bson.BsonMaximumSizeExceededException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoFailureClassifierTest {

    @Test
    void oversizedDocumentNeedsAttentionInsteadOfRetry() {
        assertEquals(SaveResult.REJECTED_OVERSIZED, MongoFailureClassifier.classify(new BsonMaximumSizeExceededException("too big")));
    }

    @Test
    void encodingFailureNeedsAttentionInsteadOfRetry() {
        assertEquals(SaveResult.REJECTED_MALFORMED, MongoFailureClassifier.classify(new IOException("compression failed")));
    }

    @Test
    void unreachableStorageIsRetriable() {
        assertEquals(SaveResult.RETRY_LATER, MongoFailureClassifier.classify(new MongoSocketOpenException("connect failed", new ServerAddress())));
    }

    @Test
    void unknownFailureIsRetriable() {
        assertEquals(SaveResult.RETRY_LATER, MongoFailureClassifier.classify(new IllegalStateException("something odd")));
    }

    @Test
    void classificationLooksThroughWrapperExceptions() {
        Throwable wrapped = new CompletionException(new MongoException("write failed", new BsonMaximumSizeExceededException("too big")));

        assertEquals(SaveResult.REJECTED_OVERSIZED, MongoFailureClassifier.classify(wrapped));
    }
}
