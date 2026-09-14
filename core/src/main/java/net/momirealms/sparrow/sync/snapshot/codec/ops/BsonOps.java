package net.momirealms.sparrow.sync.snapshot.codec.ops;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.momirealms.sparrow.nbt.util.UUIDUtil;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * 在 BSON 和 NBT 之间转换数据, 玩家类型的 Codec 仍在 NBT 上运行.
 * byte/short 转为 int32, float 转为 double; 布尔读取兼容 NBT 的 0/1.
 * 整数数组用保留字段 {@code __i32a}、{@code __i64a} 标记, Binary 和标记文档不作为普通列表读取.
 */
public final class BsonOps implements DynamicOps<Object> {
    public static final BsonOps INSTANCE = new BsonOps();
    public static final String INT_ARRAY_MARKER = "__i32a";
    public static final String LONG_ARRAY_MARKER = "__i64a";

    private BsonOps() {
    }

    @Override
    public Object empty() {
        return null;
    }

    @Override
    public Object emptyMap() {
        return new Document();
    }

    @Override
    public Object emptyList() {
        return new ArrayList<>();
    }

    @Override
    public <U> U convertTo(DynamicOps<U> outOps, Object input) {
        return switch (input) {
            case null -> outOps.empty();
            case Document document -> {
                // 先识别数组标记, 再处理普通文档
                int[] ints = intArrayValue(document);
                if (ints != null) yield outOps.createIntList(Arrays.stream(ints));
                long[] longs = longArrayValue(document);
                if (longs != null) yield outOps.createLongList(Arrays.stream(longs));
                yield this.convertMap(outOps, document);
            }
            case Binary binary -> outOps.createByteList(ByteBuffer.wrap(binary.getData()));
            case byte[] bytes -> outOps.createByteList(ByteBuffer.wrap(bytes));
            case List<?> ignored -> this.convertList(outOps, input);
            case String string -> outOps.createString(string);
            case Boolean bool -> outOps.createBoolean(bool);
            case Integer value -> outOps.createInt(value);
            case Long value -> outOps.createLong(value);
            case Double value -> outOps.createDouble(value);
            case Date date -> outOps.createLong(date.getTime());
            // 将 BSON 特有类型转换为目标格式可表示的值
            case UUID uuid -> outOps.createIntList(Arrays.stream(UUIDUtil.uuidToIntArray(uuid)));
            case Decimal128 decimal -> outOps.createDouble(decimal.doubleValue());
            case ObjectId objectId -> outOps.createString(objectId.toHexString());
            case BsonTimestamp timestamp -> outOps.createLong(timestamp.getValue());
            case Number number -> outOps.createNumeric(number);
            default -> throw new UnsupportedOperationException("bson value type " + input.getClass().getName() + " cannot be converted");
        };
    }

    // 忽略 BSON null 字段, NBT Compound 中的 EndTag 会提前结束序列化
    @Override
    public <U> U convertMap(DynamicOps<U> outOps, Object input) {
        if (!(input instanceof Document document)) {
            return outOps.createMap(Stream.empty());
        }
        return outOps.createMap(document.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> Pair.of(outOps.createString(entry.getKey()), this.convertTo(outOps, entry.getValue()))));
    }

    @Override
    public <U> U convertList(DynamicOps<U> outOps, Object input) {
        if (!(input instanceof List<?> list)) {
            return outOps.createList(Stream.empty());
        }
        return outOps.createList(list.stream().map(element -> {
            // 列表中的 null 直接报错, 不将其转换为 EndTag
            if (element == null) {
                throw new IllegalArgumentException("null element in bson list");
            }
            return this.convertTo(outOps, element);
        }));
    }

    // ---- 数值: 窄类型升宽, 读侧宽容 ----

    @Override
    public DataResult<Number> getNumberValue(Object input) {
        if (input instanceof Number number) return DataResult.success(number);
        if (input instanceof Boolean bool) return DataResult.success(bool ? (byte) 1 : (byte) 0);
        return DataResult.error(() -> "Not a number: " + input);
    }

    @Override
    public Object createNumeric(Number value) {
        // 按实际数值类型转换, long 保持整数精度
        return switch (value) {
            case Byte number -> (int) number;
            case Short number -> (int) number;
            case Integer number -> number;
            case Long number -> number;
            case Float number -> (double) number;
            case Double number -> number;
            default -> value.doubleValue();
        };
    }

    @Override
    public Object createByte(byte value) {
        return (int) value;
    }

    @Override
    public Object createShort(short value) {
        return (int) value;
    }

    @Override
    public Object createInt(int value) {
        return value;
    }

    @Override
    public Object createLong(long value) {
        return value;
    }

    @Override
    public Object createFloat(float value) {
        return (double) value;
    }

    @Override
    public Object createDouble(double value) {
        return value;
    }

    @Override
    public DataResult<Boolean> getBooleanValue(Object input) {
        if (input instanceof Boolean bool) return DataResult.success(bool);
        // 从 NBT 转来的布尔值使用数值 0/1
        if (input instanceof Number number) return DataResult.success(number.byteValue() != 0);
        return DataResult.error(() -> "Not a boolean: " + input);
    }

    @Override
    public Object createBoolean(boolean value) {
        return value;
    }

    @Override
    public DataResult<String> getStringValue(Object input) {
        if (input instanceof String string) return DataResult.success(string);
        return DataResult.error(() -> "Not a string: " + input);
    }

    @Override
    public Object createString(String value) {
        return value;
    }

    @Override
    public DataResult<Stream<Object>> getStream(Object input) {
        if (input instanceof List<?> list) {
            @SuppressWarnings("unchecked") List<Object> values = (List<Object>) list;
            return DataResult.success(values.stream());
        }
        return DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public Object createList(Stream<Object> input) {
        return input.collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public DataResult<Object> mergeToList(Object list, Object value) {
        if (list == null) {
            List<Object> result = new ArrayList<>(1);
            result.add(value);
            return DataResult.success(result);
        }
        if (list instanceof List<?> existing) {
            List<Object> result = new ArrayList<>(existing.size() + 1);
            result.addAll(existing);
            result.add(value);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "mergeToList called with not a list: " + list, list);
    }

    @Override
    public DataResult<Object> mergeToList(Object list, List<Object> values) {
        if (list == null) {
            return DataResult.success(new ArrayList<>(values));
        }
        if (list instanceof List<?> existing) {
            List<Object> result = new ArrayList<>(existing.size() + values.size());
            result.addAll(existing);
            result.addAll(values);
            return DataResult.success(result);
        }
        return DataResult.error(() -> "mergeToList called with not a list: " + list, list);
    }

    // byte[] 使用 Binary, int[] 和 long[] 使用标记文档
    @Override
    public Object createByteList(ByteBuffer input) {
        // 按 DFU 约定读取整个缓冲区, 范围由 capacity 决定
        ByteBuffer whole = input.duplicate().clear();
        byte[] bytes = new byte[input.capacity()];
        whole.get(0, bytes, 0, bytes.length);
        return new Binary(bytes);
    }

    @Override
    public DataResult<ByteBuffer> getByteBuffer(Object input) {
        if (input instanceof Binary binary) return DataResult.success(ByteBuffer.wrap(binary.getData()));
        if (input instanceof byte[] bytes) return DataResult.success(ByteBuffer.wrap(bytes));
        // 普通数字列表逐项转换为字节
        return this.getStream(input).flatMap(stream -> {
            List<Object> list = stream.toList();
            int size = list.size();
            ByteBuffer buffer = ByteBuffer.wrap(new byte[size]);
            for (int i = 0; i < size; i++) {
                if (!(list.get(i) instanceof Number number)) {
                    return DataResult.error(() -> "Some elements are not bytes: " + input);
                }
                buffer.put(i, number.byteValue());
            }
            return DataResult.success(buffer);
        });
    }

    @Override
    public Object createIntList(IntStream input) {
        return new Document(INT_ARRAY_MARKER, input.boxed().collect(Collectors.toCollection(ArrayList::new)));
    }

    @Override
    public DataResult<IntStream> getIntStream(Object input) {
        if (input instanceof Document document) {
            int[] value = intArrayValue(document);
            if (value != null) return DataResult.success(Arrays.stream(value));
        }
        return this.getStream(input).flatMap(stream -> {
            List<Object> list = stream.toList();
            int size = list.size();
            int[] value = new int[size];
            for (int i = 0; i < size; i++) {
                if (!(list.get(i) instanceof Number number)) {
                    return DataResult.error(() -> "Some elements are not ints: " + input);
                }
                value[i] = number.intValue();
            }
            return DataResult.success(Arrays.stream(value));
        });
    }

    @Override
    public Object createLongList(LongStream input) {
        return new Document(LONG_ARRAY_MARKER, input.boxed().collect(Collectors.toCollection(ArrayList::new)));
    }

    @Override
    public DataResult<LongStream> getLongStream(Object input) {
        if (input instanceof Document document) {
            long[] value = longArrayValue(document);
            if (value != null) return DataResult.success(Arrays.stream(value));
        }
        return this.getStream(input).flatMap(stream -> {
            List<Object> list = stream.toList();
            int size = list.size();
            long[] value = new long[size];
            for (int i = 0; i < size; i++) {
                if (!(list.get(i) instanceof Number number)) {
                    return DataResult.error(() -> "Some elements are not longs: " + input);
                }
                value[i] = number.longValue();
            }
            return DataResult.success(Arrays.stream(value));
        });
    }

    // ---- Map: 标记文档不参与 map 语义 ----

    @Override
    public DataResult<MapLike<Object>> getMap(Object input) {
        if (input instanceof Document document && !isArrayMarker(document)) {
            return DataResult.success(new MapLike<>() {
                @Nullable
                @Override
                public Object get(Object key) {
                    if (key instanceof String string) return document.get(string);
                    throw new UnsupportedOperationException("Cannot get map entry with non-string key: " + key);
                }

                @Nullable
                @Override
                public Object get(String key) {
                    return document.get(key);
                }

                @Override
                public Stream<Pair<Object, Object>> entries() {
                    return document.entrySet().stream().map(entry -> Pair.of(entry.getKey(), entry.getValue()));
                }

                @Override
                public String toString() {
                    return "MapLike[" + document + "]";
                }
            });
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Stream<Pair<Object, Object>>> getMapValues(Object input) {
        if (input instanceof Document document && !isArrayMarker(document)) {
            return DataResult.success(document.entrySet().stream().map(entry -> Pair.of(entry.getKey(), entry.getValue())));
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Consumer<BiConsumer<Object, Object>>> getMapEntries(Object input) {
        if (input instanceof Document document && !isArrayMarker(document)) {
            return DataResult.success(consumer -> document.forEach(consumer::accept));
        }
        return DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public Object createMap(Stream<Pair<Object, Object>> map) {
        Document document = new Document();
        map.forEach(pair -> {
            if (pair.getFirst() instanceof String key) {
                document.append(key, pair.getSecond());
            } else {
                throw new UnsupportedOperationException("Cannot create map with non-string key: " + pair.getFirst());
            }
        });
        return document;
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, Object key, Object value) {
        if (map != null && !(map instanceof Document)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }
        if (!(key instanceof String stringKey)) {
            // DataResult 的 partial 不能为 null, 空前缀时只返回错误
            return map == null ? DataResult.error(() -> "key is not a string: " + key) : DataResult.error(() -> "key is not a string: " + key, map);
        }
        Document result = map instanceof Document existing ? new Document(existing) : new Document();
        result.append(stringKey, value);
        return DataResult.success(result);
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, MapLike<Object> values) {
        if (map != null && !(map instanceof Document)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }
        Document result = map instanceof Document existing ? new Document(existing) : new Document();
        List<Object> invalidKeys = new ArrayList<>();
        values.entries().forEach(pair -> {
            if (pair.getFirst() instanceof String key) {
                result.append(key, pair.getSecond());
            } else {
                invalidKeys.add(pair.getFirst());
            }
        });
        if (invalidKeys.isEmpty()) return DataResult.success(result);
        return DataResult.error(() -> "Invalid keys: " + invalidKeys, result);
    }

    @Override
    public Object remove(Object input, String key) {
        if (input instanceof Document document) {
            Document copied = new Document(document);
            copied.remove(key);
            return copied;
        }
        return input;
    }

    @Override
    public RecordBuilder<Object> mapBuilder() {
        return new BsonRecordBuilder();
    }

    @Override
    public String toString() {
        return "BSON";
    }

    @Nullable
    private static int[] intArrayValue(Document document) {
        if (document.size() != 1) return null;
        if (!(document.get(INT_ARRAY_MARKER) instanceof List<?> list)) return null;
        int[] value = new int[list.size()];
        for (int i = 0; i < value.length; i++) {
            if (!(list.get(i) instanceof Number number)) return null;
            value[i] = number.intValue();
        }
        return value;
    }

    @Nullable
    private static long[] longArrayValue(Document document) {
        if (document.size() != 1) return null;
        if (!(document.get(LONG_ARRAY_MARKER) instanceof List<?> list)) return null;
        long[] value = new long[list.size()];
        for (int i = 0; i < value.length; i++) {
            if (!(list.get(i) instanceof Number number)) return null;
            value[i] = number.longValue();
        }
        return value;
    }

    // 只有元素类型也正确时才识别为数组标记, 其余按普通文档处理
    private static boolean isArrayMarker(Document document) {
        return intArrayValue(document) != null || longArrayValue(document) != null;
    }

    // 键始终为 String, 直接累积字段并在结束时构造文档
    private final class BsonRecordBuilder extends RecordBuilder.AbstractStringBuilder<Object, Document> {

        private BsonRecordBuilder() {
            super(BsonOps.this);
        }

        @Override
        protected Document initBuilder() {
            return new Document();
        }

        @Override
        protected Document append(String key, Object value, Document builder) {
            builder.append(key, value);
            return builder;
        }

        @Override
        protected DataResult<Object> build(Document builder, Object prefix) {
            if (prefix == null) return DataResult.success(builder);
            if (!(prefix instanceof Document existing)) {
                return DataResult.error(() -> "mergeToMap called with not a map: " + prefix, prefix);
            }
            Document merged = new Document(existing);
            merged.putAll(builder);
            return DataResult.success(merged);
        }
    }
}
