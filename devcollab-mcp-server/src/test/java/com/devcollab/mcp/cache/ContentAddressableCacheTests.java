package com.devcollab.mcp.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentAddressableCacheTests {

    @Test
    void sameBlobAndParserVersionHitsOnSecondRead() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(2);

        assertThat(cache.get("blob-a", "parser-v1")).isEmpty();
        cache.put("blob-a", "parser-v1", "parsed-a");

        assertThat(cache.get("blob-a", "parser-v1")).contains("parsed-a");
        assertThat(cache.stats().missCount()).isEqualTo(1);
        assertThat(cache.stats().hitCount()).isEqualTo(1);
    }

    @Test
    void parserVersionIsPartOfTheCacheKey() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(2);

        cache.put("blob-a", "parser-v1", "parsed-a");

        assertThat(cache.get("blob-a", "parser-v2")).isEmpty();
        assertThat(cache.stats().missCount()).isEqualTo(1);
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        ContentAddressableCache<String> cache = new ContentAddressableCache<>(2);
        cache.put("blob-a", "parser-v1", "parsed-a");
        cache.put("blob-b", "parser-v1", "parsed-b");

        assertThat(cache.get("blob-a", "parser-v1")).contains("parsed-a");
        cache.put("blob-c", "parser-v1", "parsed-c");

        assertThat(cache.get("blob-b", "parser-v1")).isEmpty();
        assertThat(cache.get("blob-a", "parser-v1")).contains("parsed-a");
        assertThat(cache.get("blob-c", "parser-v1")).contains("parsed-c");
    }
}
