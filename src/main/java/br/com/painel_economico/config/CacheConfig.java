package br.com.painel_economico.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    @SuppressWarnings("unchecked")
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("indicators", "news", "historical");

        cacheManager.setAsyncCacheMode(true);

        cacheManager.setCaffeine((Caffeine<Object, Object>) Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats());

        return cacheManager;
    }
}