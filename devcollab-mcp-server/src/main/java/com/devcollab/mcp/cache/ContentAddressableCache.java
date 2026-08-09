package com.devcollab.mcp.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class ContentAddressableCache<T> {

    private final int capacity;
    private final LinkedHashMap<Key, T> entries;
    private long hitCount;
    private long missCount;

    public ContentAddressableCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, T> eldest) {
                return size() > ContentAddressableCache.this.capacity;
            }
        };
    }

    public synchronized Optional<T> get(String blobSha, String parserVersion) {
        Key key = key(blobSha, parserVersion);
        T value = entries.get(key);
        if (value == null) {
            missCount++;
            return Optional.empty();
        }
        hitCount++;
        return Optional.of(value);
    }

    public synchronized T getOrCompute(
            String blobSha,
            String parserVersion,
            Supplier<T> supplier
    ) {
        return get(blobSha, parserVersion).orElseGet(() -> {
            T value = supplier.get();
            put(blobSha, parserVersion, value);
            return value;
        });
    }

    public synchronized void put(String blobSha, String parserVersion, T value) {
        entries.put(key(blobSha, parserVersion), Objects.requireNonNull(value, "value"));
    }

    public synchronized CacheStats stats() {
        return new CacheStats(hitCount, missCount, entries.size(), capacity);
    }

    public synchronized void clear() {
        entries.clear();
        hitCount = 0;
        missCount = 0;
    }

    private Key key(String blobSha, String parserVersion) {
        if (blobSha == null || blobSha.isBlank()) {
            throw new IllegalArgumentException("blobSha must not be blank");
        }
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("parserVersion must not be blank");
        }
        return new Key(blobSha, parserVersion);
    }

    public record CacheStats(long hitCount, long missCount, int size, int capacity) {
    }

    private record Key(String blobSha, String parserVersion) {
    }
}
