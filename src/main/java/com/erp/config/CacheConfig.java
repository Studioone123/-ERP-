package com.erp.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 缓存总开关与 Redis 缓存序列化策略。
 *
 * <p><b>为什么默认模式也有缓存？</b>
 * {@code spring.cache.type=simple} 会用 Spring 自带的 ConcurrentMapCacheManager，
 * 不需要任何额外依赖。也就是说，即使完全不装 Redis，看板和下拉选项依然走缓存，
 * 优化照样生效；Redis 只是把这份缓存从「单进程内」升级成「多实例共享」。
 *
 * <p><b>为什么自定义序列化？</b>
 * Spring Boot 默认用 JDK 序列化，Redis 里存的是二进制，redis-cli 看不到内容，
 * 而且要求被缓存对象实现 Serializable。这里改成 JSON：
 * <ul>
 *   <li>key 用 StringRedisSerializer，形如 {@code erp:dashboard::summary}，可读</li>
 *   <li>value 开启默认类型信息（{@code @class}），让 BigDecimal、Timestamp、
 *       LinkedHashMap 等反序列化后能还原成原来的类型，而不是统统变成 Double / Map</li>
 * </ul>
 * 只在 redis profile 下定义这个 Bean；默认模式由 Boot 自动配置内存缓存。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Profile("redis")
    public RedisCacheConfiguration redisCacheConfiguration() {
        ObjectMapper mapper = JsonMapper.builder()
                .activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                        DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .build();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer))
                .disableCachingNullValues();
    }
}
