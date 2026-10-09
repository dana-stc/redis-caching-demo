# Redis Caching Demo (Spring Boot)

A small Spring Boot project that shows how to add **caching with Redis** to a REST API.

The "database" in this project is fake and deliberately slow (every read takes 2 seconds).
That way you can **see** the cache working: the first request is slow, the next ones are instant.

---

## 1. Caching basics

### What is a cache?
A cache is a fast storage place where we keep a copy of data that is slow to get
(a database query, a call to another service, a heavy calculation).
Next time someone asks for the same data, we return the copy instead of doing the slow work again.

### Cache hit and cache miss
- **Cache hit**: the data is in the cache -> returned immediately.
- **Cache miss**: the data is not in the cache -> we do the slow work, save the result in the cache, then return it.

```
Request --> Is it in the cache?
              |-- yes (HIT)  --> return it (fast)
              |-- no  (MISS) --> read from DB (slow) --> save in cache --> return it
```

### Why Redis?
Redis is an in-memory key-value store. It is very fast (data lives in RAM) and it is
a separate server, so **all instances of your app share the same cache**.
(A simple in-memory cache inside the app is not shared between instances.)

### TTL (Time To Live)
Cached data can become old (stale). A TTL says how long an entry may live.
After that time Redis deletes it automatically and the next request refreshes it.

### Cache invalidation
When the real data changes (update/delete), the cached copy is wrong.
We must either **update** it or **remove (evict)** it. This is famously the hardest part of caching,
so keep your rules simple.

### Common caching patterns
| Pattern | How it works |
|---|---|
| **Cache-aside** (used here) | App checks the cache first; on a miss it loads from the DB and fills the cache. |
| Write-through | App writes to cache and DB at the same time. |
| Write-behind | App writes to the cache; the DB is updated later. |

---

## 2. How the implementation works

Spring has a built-in caching abstraction. You put **annotations** on methods and Spring
does the cache work for you, using Redis behind the scenes.

### Dependencies (`pom.xml`)
- `spring-boot-starter-cache` - the caching annotations
- `spring-boot-starter-data-redis` - the Redis client (Lettuce)

### Turn it on (`config/CacheConfig.java`)
- `@EnableCaching` activates the annotations.
- A `RedisCacheManager` bean configures:
  - **Default TTL**: 60 seconds.
  - **Per-cache TTL**: `products` = 5 minutes, `productList` = 30 seconds.
  - **JSON serialization**: values are saved as readable JSON, not Java binary.
  - **No null caching**: `null` results are not stored.

### The annotations (`service/ProductService.java`)

| Annotation | Used on | What it does |
|---|---|---|
| `@Cacheable` | `getById`, `getAll` | If the key is in the cache, return it and **skip the method**. Otherwise run the method and store the result. |
| `@CachePut` | `update` | **Always runs** the method and replaces the cached value with the new result. |
| `@CacheEvict` | `create`, `delete` | Removes entries from the cache so the next read loads fresh data. |
| `@Caching` | `update`, `delete` | Lets you combine several cache operations on one method. |

Example:

```java
@Cacheable(cacheNames = "products", key = "#id")
public Product getById(Long id) { ... }
```

This stores the result in Redis under the key `demo::products::<id>` (e.g. `demo::products::1`).

### Behaviour in this project
- `GET /products/{id}` -> cached per id.
- `GET /products` -> cached as one list.
- `PUT /products/{id}` -> updates the product cache entry and clears the list cache.
- `POST /products` -> clears the list cache (the list changed).
- `DELETE /products/{id}` -> clears that product and the list cache.

> Note: Spring caching works through a proxy, so a method calling another cached method
> **in the same class** will not use the cache. Call it from another bean.

---

## 3. Run it

**Requirements:** Java 17+, Maven, and a Redis server (Docker is the easiest way).

```bash
# 1. start Redis
docker compose up -d

# 2. start the app
mvn spring-boot:run
```

### Try it

```bash
# First call: slow (~2s), log shows "CACHE MISS"
time curl localhost:8080/products/1

# Second call: instant, no log line, because it came from Redis
time curl localhost:8080/products/1

# Update -> cache entry is refreshed
curl -X PUT localhost:8080/products/1 -H 'Content-Type: application/json' \
     -d '{"name":"Laptop Pro","price":1299.99}'

# Create / delete
curl -X POST localhost:8080/products -H 'Content-Type: application/json' \
     -d '{"name":"Keyboard","price":49.90}'
curl -X DELETE localhost:8080/products/3
```

### Look inside Redis

```bash
docker exec -it redis-caching-demo redis-cli
> KEYS *
> GET "demo::products::1"
> TTL "demo::products::1"     # seconds left before it expires
> FLUSHALL                    # clear everything
```

---

## 4. Project structure

```
src/main/java/com/example/rediscache
├── RedisCachingDemoApplication.java   # app entry point
├── config/CacheConfig.java            # Redis cache manager, TTLs, JSON
├── model/Product.java                 # simple data class
├── service/ProductService.java        # fake slow DB + cache annotations
└── controller/ProductController.java  # REST endpoints
```

## 5. Good practices (next steps)
- Cache data that is **read often and changes rarely**.
- Always set a **TTL** so stale data cannot live forever.
- Keep cache **keys** clear and unique (`prefix::cacheName::id`).
- Do not cache sensitive data without thinking about security.
- Consider what happens if Redis is down (add a `CacheErrorHandler` to fall back to the DB).
