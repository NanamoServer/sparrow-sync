package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.MongoException;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.bson.BsonMaximumSizeExceededException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 把写入失败归类为 {@link SaveResult}, 决定这份快照该重试还是保存在本地交给人工介入.
 */
final class MongoFailureClassifier {
    // 服务端给可重试写入打的标签.
    private static final String RETRYABLE_WRITE_ERROR = "RetryableWriteError";

    @NotNull
    static SaveResult classify(@NotNull Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            // 文档超出 BSON 上限, 不可重试.
            if (current instanceof BsonMaximumSizeExceededException) {
                return SaveResult.REJECTED_OVERSIZED;
            }
            // 编解码与压缩的失败产生的 IOException, 不可重试.
            if (current instanceof IOException) {
                return SaveResult.REJECTED_MALFORMED;
            }
            // 连不上数据库, 重试.
            if (current instanceof MongoException mongo && mongo.hasErrorLabel(RETRYABLE_WRITE_ERROR)) {
                return SaveResult.RETRY_LATER;
            }
        }
        return SaveResult.RETRY_LATER;
    }
}
