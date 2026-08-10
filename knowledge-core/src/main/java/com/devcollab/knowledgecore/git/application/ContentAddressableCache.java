package com.devcollab.knowledgecore.git.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ContentAddressableCache<T> {

    private final Map<Key, T> entries;
    private long hitCount;
    private long missCount;

    public ContentAddressableCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, T> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized T getOrCompute(
            String blobSha,
            String parserVersion,
            Supplier<T> supplier
    ) {
        Key key = new Key(blobSha, parserVersion);
        T cached = entries.get(key);
        if (cached != null) {
            hitCount++;
            return cached;
        }
        missCount++;
        T value = supplier.get();
        entries.put(key, value);
        return value;
    }

    public synchronized long hitCount() {
        return hitCount;
    }

    public synchronized long missCount() {
        return missCount;
    }

    public synchronized int size() {
        return entries.size();
    }

    private record Key(String blobSha, String parserVersion) {
        private Key {
            if (blobSha == null || blobSha.isBlank()) {
                throw new IllegalArgumentException("blobSha must not be blank");
            }
            if (parserVersion == null || parserVersion.isBlank()) {
                throw new IllegalArgumentException("parserVersion must not be blank");
            }
            blobSha = blobSha.trim();
            parserVersion = parserVersion.trim();
        }
    }
}
