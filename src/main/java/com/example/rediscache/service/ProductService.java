package com.example.rediscache.service;

import com.example.rediscache.config.CacheConfig;
import com.example.rediscache.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pretends to be a service talking to a slow database.
 * Every real "DB" access sleeps for 2 seconds so the effect of the cache is obvious.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final Map<Long, Product> fakeDb = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public ProductService() {
        save(new Product(null, "Laptop", new BigDecimal("999.99")));
        save(new Product(null, "Phone", new BigDecimal("599.00")));
        save(new Product(null, "Headphones", new BigDecimal("79.90")));
    }

    /** Cache hit -> method body is skipped. Cache miss -> body runs and result is stored. */
    @Cacheable(cacheNames = CacheConfig.PRODUCTS, key = "#id")
    public Product getById(Long id) {
        log.info("CACHE MISS - loading product {} from the slow 'database'", id);
        slowDown();
        Product product = fakeDb.get(id);
        if (product == null) {
            throw new IllegalArgumentException("Product " + id + " not found");
        }
        return product;
    }

    @Cacheable(cacheNames = CacheConfig.PRODUCT_LIST)
    public List<Product> getAll() {
        log.info("CACHE MISS - loading all products from the slow 'database'");
        slowDown();
        return new ArrayList<>(fakeDb.values());
    }

    /** Always runs the method, then overwrites the cached value. Also clears the list cache. */
    @Caching(
            put = @CachePut(cacheNames = CacheConfig.PRODUCTS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfig.PRODUCT_LIST, allEntries = true))
    public Product update(Long id, Product changes) {
        log.info("Updating product {} and refreshing the cache", id);
        Product existing = fakeDb.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Product " + id + " not found");
        }
        existing.setName(changes.getName());
        existing.setPrice(changes.getPrice());
        return existing;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_LIST, allEntries = true)})
    public void delete(Long id) {
        log.info("Deleting product {} and removing it from the cache", id);
        fakeDb.remove(id);
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCT_LIST, allEntries = true)
    public Product create(Product product) {
        log.info("Creating product '{}'", product.getName());
        return save(product);
    }

    private Product save(Product product) {
        product.setId(idSequence.incrementAndGet());
        fakeDb.put(product.getId(), product);
        return product;
    }

    private void slowDown() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
