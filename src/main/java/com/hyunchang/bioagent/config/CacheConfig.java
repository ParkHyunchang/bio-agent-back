package com.hyunchang.bioagent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * PubMed 응답 캐시.
 * - search: 동일 쿼리+필터 조합. 페이지 이동/필터 토글 반복에서 NCBI 호출 절감.
 * - detail: PMID별 상세. 검색→상세 왕복이나 히스토리 다시 보기에서 절감.
 * 짧은 TTL이면 신선도와 절감의 균형이 맞고, NCBI rate limit 여유도 늘어난다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SEARCH_CACHE = "pubmedSearch";
    public static final String DETAIL_CACHE = "pubmedDetail";
    public static final String PMC_FULLTEXT_CACHE = "pubmedPmcFullText";

    @Bean
    public CacheManager cacheManager() {
        // 본문(PMC)은 entry당 수십~수백 KB 가능하니 maximumSize를 별도 캐시로 분리해 작게 둔다.
        CaffeineCacheManager mgr = new CaffeineCacheManager() {
            @Override
            protected com.github.benmanes.caffeine.cache.Cache<Object, Object> createNativeCaffeineCache(String name) {
                if (PMC_FULLTEXT_CACHE.equals(name)) {
                    return Caffeine.newBuilder()
                            .expireAfterWrite(30, TimeUnit.MINUTES)
                            .maximumSize(50)
                            .build();
                }
                return Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(500)
                        .build();
            }
        };
        mgr.setCacheNames(java.util.List.of(SEARCH_CACHE, DETAIL_CACHE, PMC_FULLTEXT_CACHE));
        return mgr;
    }
}
