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
        // 拿不准时留在重试队列里, 数据不会被丢到需要人工介入的角落
        assertEquals(SaveResult.RETRY_LATER, MongoFailureClassifier.classify(new IllegalStateException("something odd")));
    }

    @Test
    void classificationLooksThroughWrapperExceptions() {
        // 异步链路会把原始异常包一层, 分类要顺着 cause 找下去
        Throwable wrapped = new CompletionException(new MongoException("write failed", new BsonMaximumSizeExceededException("too big")));

        assertEquals(SaveResult.REJECTED_OVERSIZED, MongoFailureClassifier.classify(wrapped));
    }
}
