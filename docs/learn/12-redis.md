# Redis

**Be honest about this one in interviews.** ReconPilot runs Redis in its stack
but leans on it lightly — it is there for caching and as the obvious home for
rate limiting and distributed locks as the system grows. Saying *"it is in the
stack, here is what I would use it for, and here is why I have not needed it
yet"* is a much better answer than pretending to deep production experience.

What you do need is to understand **what it is good at, what it is bad at, and
the handful of traps that cause real incidents.**

---

## Table of contents

1. [What Redis is](#1-what-redis-is)
2. [The data structures](#2-the-data-structures)
3. [Expiry and eviction](#3-expiry-and-eviction)
4. [Persistence](#4-persistence)
5. [Redis with Spring Boot](#5-redis-with-spring-boot)
6. [Caching patterns and their failure modes](#6-caching-patterns-and-their-failure-modes)
7. [Distributed locks](#7-distributed-locks)
8. [Rate limiting](#8-rate-limiting)
9. [Atomicity: transactions and Lua](#9-atomicity-transactions-and-lua)
10. [Scaling: replication, Sentinel, Cluster](#10-scaling-replication-sentinel-cluster)
11. [When not to use Redis](#11-when-not-to-use-redis)
12. [The traps, collected](#12-the-traps-collected)

---

## 1. What Redis is

**An in-memory data-structure server.** Not "a cache" — caching is its most
common use, not its definition.

- Data lives **in RAM**, which is why it is fast (sub-millisecond).
- It is **single-threaded** for command execution.
- It speaks a simple protocol and exposes rich data types, not just strings.

### Why single-threaded is a feature, not a limitation

Every command runs to completion before the next begins. That gives you
**atomicity for free** — no locks, no race conditions between commands — and it
avoids context switching entirely.

The bottleneck for Redis is almost never CPU; it is memory and network. (Redis
6+ does use threads for I/O, but **command execution is still serialised**.)

The consequence you must remember: **one slow command blocks everything.**
`KEYS *` on a million keys, or `FLUSHALL`, stops the entire server for every
client. That is the single most common Redis incident.

---

## 2. The data structures

This is what separates Redis from a hash map with a network port.

### Strings

```bash
SET user:42:name "Vaibhav"
GET user:42:name
SET session:abc "..." EX 3600        # expires in an hour
INCR page:views                      # atomic counter
INCRBY balance 100
SETNX lock:job "owner"               # set only if not exists
```

Any binary blob up to 512 MB. `INCR` is atomic — it is the simplest correct
counter in a distributed system.

### Hashes — an object in one key

```bash
HSET user:42 name "Vaibhav" role "ADMIN" tenant "3f14..."
HGET user:42 role
HGETALL user:42
HINCRBY user:42 loginCount 1
```

Better than a JSON string when you update individual fields, because you avoid
read-modify-write of the whole object.

### Lists — a deque

```bash
LPUSH queue:jobs "job1"
RPOP queue:jobs
BRPOP queue:jobs 30          # BLOCKING pop, waits up to 30s
LRANGE recent:events 0 9     # last 10
```

`BRPOP` gives you a simple job queue with no polling. **But see section 11 —
for real work queues, Redis lists lose messages.**

### Sets — unique, unordered

```bash
SADD tenant:1:features "ai" "backups"
SISMEMBER tenant:1:features "ai"
SINTER online:users premium:users      # set intersection, server-side
SCARD tenant:1:features                # count
```

Server-side set algebra is genuinely useful — "users who are online *and*
premium" is one round trip.

### Sorted sets — the most underrated type

```bash
ZADD leaderboard 4638455 "tenant:1"
ZREVRANGE leaderboard 0 9 WITHSCORES   # top 10
ZRANGEBYSCORE events 1700000000 1700003600   # a time window
ZREMRANGEBYSCORE events 0 1699999999         # trim old entries
```

Every member has a **score**, and the set stays sorted by it. This single
structure gives you leaderboards, priority queues, time-series windows, and
**sliding-window rate limiting** (section 8).

### The others, briefly

- **Streams** (`XADD`, `XREADGROUP`) — an append-only log with consumer groups.
  Kafka-like, at a much smaller scale. The right choice if you want durable
  queue semantics *within* Redis.
- **HyperLogLog** — approximate unique counts in 12 KB regardless of
  cardinality. ~0.8% error. For "unique visitors" where exactness does not
  matter.
- **Bitmaps**, **Geospatial** (`GEOADD`, `GEOSEARCH`) — niche but occasionally
  perfect.

### Key naming

```
tenant:{id}:breaks:summary
format:mapping:{sha256}
ratelimit:{userId}:{minute}
```

Colon-separated namespacing is a convention, not a feature — but it makes keys
readable and lets you reason about what to expire together.

---

## 3. Expiry and eviction

### TTL

```bash
SET key value EX 300         # 300 seconds
EXPIRE key 300
TTL key                      # seconds left; -1 = no expiry, -2 = gone
PERSIST key                  # remove the expiry
```

> ⚠️ **Most write commands clear the TTL.** `SET key newvalue` drops the
> expiry unless you set it again (`SET key v KEEPTTL` preserves it). This is a
> classic source of keys that were supposed to expire and never do.

Redis expires keys **lazily** (on access) and with a **background sampler**, so
a key can live slightly past its TTL without being read. Memory is not freed at
the exact second.

### Eviction — what happens when memory fills

```
maxmemory 2gb
maxmemory-policy allkeys-lru
```

| Policy | Behaviour |
|---|---|
| `noeviction` | **writes fail with an error** (the default!) |
| `allkeys-lru` | evict least-recently-used from all keys ← usual choice for a cache |
| `allkeys-lfu` | least-*frequently*-used; better for skewed popularity |
| `volatile-lru` | only evict keys that have a TTL |
| `volatile-ttl` | evict the keys expiring soonest |

> **The trap:** the default is `noeviction`. If you use Redis as a cache and
> never set `maxmemory-policy`, a full instance starts **rejecting writes**
> rather than making room — and your application starts failing for a reason
> that looks nothing like "the cache is full".
>
> The mirror-image trap: `allkeys-lru` on an instance that also holds
> **locks or sessions** will happily evict those. Keep caches and durable-ish
> data on separate instances or databases.

---

## 4. Persistence

Redis is in-memory, but it can survive a restart.

| Mode | How | Trade-off |
|---|---|---|
| **RDB** | periodic point-in-time snapshot | compact, fast restart; **loses everything since the last snapshot** |
| **AOF** | append every write to a log | far less loss (`everysec` ≈ 1s); larger, slower restart |
| **Both** | AOF for durability, RDB for fast restore | the usual production choice |
| **None** | pure cache | fine when the data is rebuildable |

```
save 900 1                  # RDB: snapshot if ≥1 key changed in 900s
appendonly yes
appendfsync everysec        # fsync once a second — the sane default
```

> **The honest framing:** even with AOF `everysec` you can lose a second of
> writes. **Redis is not a system of record.** If losing it would be a
> correctness problem rather than a performance problem, it belongs in
> PostgreSQL.

---

## 5. Redis with Spring Boot

```properties
spring.data.redis.host=redis
spring.data.redis.port=6379
spring.cache.type=redis
spring.cache.redis.time-to-live=600000
```

### The caching abstraction

```java
@EnableCaching
@Configuration
class CacheConfig {

    @Bean
    RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()          // avoid caching "not found"
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
    }
}

@Service
class FormatMappingService {

    @Cacheable(value = "formats", key = "#fingerprint")
    public Mapping lookup(String fingerprint) { ... }      // runs only on a miss

    @CacheEvict(value = "formats", key = "#fingerprint")
    public void invalidate(String fingerprint) { }
}
```

**Remember these are proxy-based**, so a self-call inside the same class
bypasses the cache entirely — exactly like `@Transactional` and `@Async`.

### Serialization matters

The default `JdkSerializationRedisSerializer` produces opaque binary that only
Java can read, breaks when a class changes, and is a deserialisation risk.
**Use JSON.** You can then inspect cache contents with `redis-cli`, which you
will want at 2am.

### RedisTemplate for direct use

```java
@Service
class RateLimiter {
    private final StringRedisTemplate redis;

    boolean allow(String userId, int limitPerMinute) {
        String key = "rl:" + userId + ":" + Instant.now().getEpochSecond() / 60;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofMinutes(2));
        }
        return count != null && count <= limitPerMinute;
    }
}
```

> Note the shape: `INCR` first, then set the expiry only on the first
> increment. Setting the TTL every time would slide the window forever.
> (This still has a small race — see section 9 for the Lua fix.)

---

## 6. Caching patterns and their failure modes

### Cache-aside (the default)

```
read:  check cache → miss → read DB → write cache → return
write: write DB → invalidate cache
```

**Invalidate rather than update** on write. Updating from two places races; the
next read repopulates correctly.

### The three failures worth naming

**1. Cache stampede (thundering herd).** A popular key expires and a thousand
requests miss simultaneously, all hitting the database at once.

> Fix: **jitter the TTL** (`600 + random(0..60)` seconds) so keys do not expire
> together, or use a short lock so one caller refreshes while others serve
> slightly stale data.

**2. Cache penetration.** Requests for a key that does **not exist** in the
database bypass the cache every time — and an attacker can do this deliberately.

> Fix: cache the negative result with a short TTL, or use a Bloom filter.

**3. Cache avalanche.** A large set of keys expires at the same moment — or the
Redis instance restarts empty — and the database takes the full load.

> Fix: jittered TTLs, and warming the cache before taking traffic.

### What not to cache

Anything where stale means **wrong** rather than merely **old**: account
balances, permission and authorisation checks, anything a regulator reads.

That principle is why ReconPilot caches **format mappings** — keyed on a
SHA-256 of the header row, where a hit is *still correct* because the same
header implies the same mapping — and caches **no computed fee**, ever.

---

## 7. Distributed locks

The use case: several instances run the same scheduled job and only one should
actually do it.

```java
Boolean acquired = redis.opsForValue()
        .setIfAbsent("lock:nightly-drill", ownerId, Duration.ofMinutes(5));

if (Boolean.TRUE.equals(acquired)) {
    try { runJob(); }
    finally { releaseIfStillMine("lock:nightly-drill", ownerId); }
}
```

### The three rules

1. **Always set a TTL.** A holder that crashes without a TTL leaves the lock
   held forever, and the job never runs again.
2. **Store a unique owner token** and check it on release — otherwise you can
   delete a lock that a *different* instance acquired after yours expired.
3. **Release atomically**, with Lua (section 9), because check-then-delete is
   itself a race.

### The honest caveat

**A Redis lock is not a correctness guarantee.** If a holder pauses (a long GC,
a network partition) past the TTL, two processes can believe they hold it. The
Redlock algorithm exists and is genuinely contested among distributed-systems
people.

> **Say this in an interview:** "A Redis lock is fine for *efficiency* — 'let us
> usually not do this work twice'. It is not safe for *correctness*. If doing
> the work twice would corrupt data, I would put the guarantee in the database
> instead — a unique constraint, or `pg_advisory_lock`, which is transactional
> and cannot be held past a connection loss."

That answer separates people who have read about Redlock from people who have
thought about it.

---

## 8. Rate limiting

### Fixed window — simple, with a known flaw

```
key = ratelimit:{user}:{minute}
INCR key; EXPIRE key 120
allow if count <= limit
```

**The flaw:** a client can send the full limit at 11:00:59 and again at
11:01:00 — double the intended rate across a one-second boundary.

### Sliding window with a sorted set — correct

```bash
ZREMRANGEBYSCORE rl:user 0 <now - 60s>     # drop anything older than the window
ZCARD rl:user                              # how many remain
ZADD rl:user <now> <uniqueId>              # record this request
EXPIRE rl:user 60
```

The sorted set holds a timestamp per request, so the window genuinely slides.
Costs more memory; it is the right answer when the limit matters.

### Token bucket — allows bursts deliberately

Tokens refill at a fixed rate up to a cap; each request takes one. Good when
you want to permit a short burst but bound the sustained rate — which is
usually what an API actually wants.

**All of these must be a single atomic operation**, which is what Lua is for.

---

## 9. Atomicity: transactions and Lua

### MULTI/EXEC is not a database transaction

```bash
MULTI
INCR a
INCR b
EXEC
```

Commands are **queued and executed together without interleaving** — but there
is **no rollback**. If one command fails, the others still applied. It gives
you isolation, not atomicity in the ACID sense.

`WATCH key` adds optimistic locking: if the key changes before `EXEC`, the
transaction aborts and you retry.

### Lua scripts — the real tool

```lua
-- release a lock only if we still own it
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end
```

```java
var script = new DefaultRedisScript<Long>(LUA, Long.class);
redis.execute(script, List.of(lockKey), ownerId);
```

**A Lua script runs atomically** — Redis is single-threaded, so nothing
interleaves. Any read-then-conditionally-write pattern (locks, rate limits,
atomic counters with a cap) should be a script, not two round trips.

This is the same lesson as `INSERT ... ON CONFLICT` in PostgreSQL:
**check-then-act across a network is a race; make it one operation.**

---

## 10. Scaling: replication, Sentinel, Cluster

| | What it gives | What it does not |
|---|---|---|
| **Replication** | read scaling, a warm standby | automatic failover |
| **Sentinel** | monitoring + automatic failover | sharding |
| **Cluster** | sharding across nodes + failover | cross-slot multi-key commands |

**Replication is asynchronous**, so a failover can lose recent writes. Again:
not a system of record.

**Cluster** splits the keyspace into 16,384 hash slots. Multi-key commands only
work when the keys are in the same slot, which is what **hash tags** are for:

```
user:{42}:name
user:{42}:email       # the {42} part decides the slot — both land together
```

---

## 11. When not to use Redis

Being able to say this is worth as much as knowing the commands.

**Do not use Redis as:**

- **A system of record.** Asynchronous replication and `everysec` fsync mean
  data loss is possible by design.
- **A durable work queue with lists.** `BRPOP` removes the item; if the worker
  dies before finishing, it is gone. Use Redis **Streams** with consumer groups,
  or a real broker, or a database table with `SELECT ... FOR UPDATE SKIP
  LOCKED`.
- **A primary store for relational data.** No joins, no constraints, no
  transactions in the ACID sense.
- **A large blob store.** Values up to 512 MB are *possible* and a terrible
  idea — everything is in RAM, and one big value blocks the single thread while
  it is transferred.
- **A correctness-critical lock.** See section 7.

> **A good interview answer:** "We have Redis in the stack for caching and it is
> the obvious place for rate limiting and a scheduling lock as we add
> instances. I would not put anything there that we could not rebuild from
> PostgreSQL, because Redis is fast and lossy by design and our whole product
> is a claim to be right about money."

---

## 12. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `KEYS *` in production | O(n) and blocks the server; use `SCAN` |
| 2 | `FLUSHALL` / `FLUSHDB` | Blocks, and deletes everything |
| 3 | Default `maxmemory-policy` is `noeviction` | A full cache starts failing writes |
| 4 | `allkeys-lru` on an instance holding locks | Your lock gets evicted |
| 5 | `SET` clearing the TTL | Use `KEEPTTL` or re-set the expiry |
| 6 | A lock with no TTL | A crashed holder blocks the job forever |
| 7 | Deleting a lock without checking ownership | You release someone else's lock |
| 8 | Check-then-act over two round trips | Use a Lua script |
| 9 | `MULTI`/`EXEC` assumed to roll back | It does not |
| 10 | Redis as a durable queue via lists | `BRPOP` loses the item on a crash |
| 11 | Redis as a system of record | Async replication, `everysec` fsync |
| 12 | JDK serialization for cache values | Opaque, brittle, unsafe — use JSON |
| 13 | `@Cacheable` on a self-invoked method | Proxy-based; bypassed |
| 14 | Caching `null` results | Either cache them deliberately or disable it |
| 15 | Uniform TTLs | Stampede and avalanche; add jitter |
| 16 | Fixed-window rate limiting | Double rate across the boundary |
| 17 | Large values | Blocks the single thread |
| 18 | Multi-key commands in Cluster | Different slots; use hash tags |
| 19 | Caching data where stale = wrong | Balances, permissions, regulated figures |
| 20 | No `maxmemory` set at all | The OOM killer takes the process |

---

## What to do next

1. **Open `redis-cli` in the running stack** — `docker compose exec redis
   redis-cli` — and try `SET`, `INCR`, `ZADD`, `ZREVRANGE`. Ten minutes gives
   you the feel that reading cannot.
2. **Build the sliding-window rate limiter** as a Lua script. It is fifteen
   lines and it demonstrates atomicity, sorted sets and scripting at once.
3. **Run `INFO memory` and `INFO stats`.** Look at `keyspace_hits` versus
   `keyspace_misses` — that ratio is the only honest measure of whether a cache
   is earning its keep.

Next: `13-angular.md`.
