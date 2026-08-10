package com.devcollab.knowledgecore.git.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContentAddressableCacheTests {

    @Test
    void hitsWhenBlobShaAndParserVersionMatch() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(8);
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getOrCompute("blob-a", "regex-v1", () -> value(calls, "parsed"));
        String second = cache.getOrCompute("blob-a", "regex-v1", () -> value(calls, "changed"));

        assertThat(first).isEqualTo("parsed");
        assertThat(second).isEqualTo("parsed");
        assertThat(calls).hasValue(1);
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(1);
    }

    @Test
    void missesWhenParserVersionChanges() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(8);
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute("blob-a", "regex-v1", () -> value(calls, "v1"));
        String second = cache.getOrCompute("blob-a", "regex-v2", () -> value(calls, "v2"));

        assertThat(second).isEqualTo("v2");
        assertThat(calls).hasValue(2);
        assertThat(cache.hitCount()).isZero();
        assertThat(cache.missCount()).isEqualTo(2);
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(2);
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute("blob-a", "regex-v1", () -> value(calls, "a1"));
        cache.getOrCompute("blob-b", "regex-v1", () -> value(calls, "b1"));
        cache.getOrCompute("blob-a", "regex-v1", () -> value(calls, "a2"));
        cache.getOrCompute("blob-c", "regex-v1", () -> value(calls, "c1"));
        String b = cache.getOrCompute("blob-b", "regex-v1", () -> value(calls, "b2"));

        assertThat(b).isEqualTo("b2");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(4);
    }

    private String value(AtomicInteger calls, String value) {
        calls.incrementAndGet();
        return value;
    }
}
