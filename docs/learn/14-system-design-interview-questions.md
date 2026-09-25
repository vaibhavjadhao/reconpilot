# System design interview questions

**The iconic problems, worked through with diagrams.** These are the questions
that actually get asked — at Flipkart, Paytm, Swiggy, Zomato, and at every
product company that pays well.

**Read `11-system-design.md` first** for the framework and the fundamentals.
This document applies them.

**Three things have changed by 2026** and are worth knowing before you walk in:
the passing bar is higher than it was, AI and LLM infrastructure questions have
moved into general engineering loops, and **interviewers now grade cost** — "we
would add a cache" invites "what does that cost, and what does it save?"

---

## Contents

**How to run the hour**
- [The script](#the-script)
- [The estimation numbers](#the-estimation-numbers)

**Warm-ups** — asked at screening, and as a first 20 minutes
1. [URL shortener (TinyURL, bit.ly)](#1-url-shortener)
2. [API rate limiter](#2-api-rate-limiter)
3. [Distributed cache](#3-distributed-cache)
4. [Notification service](#4-notification-service)
5. [Web crawler](#5-web-crawler)

**The big ones**
6. [E-commerce (Flipkart, Amazon)](#6-e-commerce-flipkart)
7. [Ticket booking (BookMyShow, Ticketmaster)](#7-ticket-booking-bookmyshow)
8. [Payment system (Paytm, Stripe, UPI)](#8-payment-system-paytm-upi)
9. [Chat (WhatsApp)](#9-chat-whatsapp)
10. [Ride hailing (Uber, Ola)](#10-ride-hailing-uber-ola)
11. [Food delivery (Swiggy, Zomato)](#11-food-delivery-swiggy-zomato)
12. [News feed (Instagram, Twitter)](#12-news-feed-instagram-twitter)
13. [Video streaming (Netflix, YouTube)](#13-video-streaming-netflix-youtube)

**Closing**
- [The patterns, collected](#the-patterns-collected)
- [Questions they ask about YOUR project](#questions-they-ask-about-your-project)

---

## The script

Say these out loud. You are graded on process, not on the drawing.

```
 0–5 min   CLARIFY      Ask. Never start designing immediately.
 5–10 min  ESTIMATE     Numbers decide the architecture.
10–15 min  API + DATA   What operations exist? What is stored?
15–30 min  HIGH LEVEL   Boxes and arrows. Talk while you draw.
30–45 min  DEEP DIVE    They pick one. Go deep. Name trade-offs.
45–50 min  BOTTLENECKS  What breaks at 10×? What did you not build?
```

**The five questions to ask every time:**

1. How many users, and how much data?
2. Read-heavy or write-heavy, and what ratio?
3. How fresh must the data be — is stale acceptable?
4. What is the latency budget?
5. What must never be lost or double-counted?

---

## The estimation numbers

| | |
|---|---|
| Seconds/day | ~86,400 (≈10⁵) |
| Seconds/month | ~2.6 million |
| 1M requests/day | ~12/second |
| 1M requests/day, peak | ~30–60/second (3–5× average) |
| Memory read | 100 ns |
| SSD random read | 100 µs |
| Network, same DC | 0.5 ms |
| India ↔ US round trip | ~150 ms |
| One commodity server | ~10k QPS simple reads |
| One PostgreSQL node | ~5–10k simple TPS |
| One Redis node | ~100k ops/second |

**Storage shortcuts:** a UUID is 16 bytes, a timestamp 8, a typical row with a
few columns 200–500 bytes, a small JSON document ~1 KB, a photo ~2 MB, a minute
of 1080p video ~50 MB.

---

## 1. URL shortener

> *"Design TinyURL."* The classic warm-up. If you cannot do this cleanly, the
> interview ends early.

### Clarify

Custom aliases? Expiry? Analytics? Who can create?

### Estimate

```
100M new URLs/month  →  100M / 2.6M s  ≈  40 writes/second
Read:write ratio 100:1                 ≈  4,000 reads/second
Storage: 100M × 500 bytes/month        ≈  50 GB/month  →  600 GB/year
```

**Conclusion: read-heavy, tiny writes, and the working set fits in memory.**
This is a caching problem, not a sharding problem.

### The key insight — how to generate the short code

```
Option A  Hash the URL (MD5/SHA) and take 7 chars
          + stateless, same URL → same code
          − collisions must be detected and retried

Option B  Auto-increment ID, base62-encode it        ← usually the answer
          + no collisions ever, shortest codes
          − sequential = guessable = enumerable
          − a single counter is a bottleneck

Option C  Pre-generate keys into a "key store", hand them out
          + no collision, no hot counter, no enumeration
          − an extra service to run
```

**Base62** = `[a-zA-Z0-9]`, so 62⁷ ≈ **3.5 trillion** codes in 7 characters.

For the counter bottleneck: give each application server a **range**
(server A gets 1–10,000, B gets 10,001–20,000) from a coordination service. No
per-write coordination.

### Design

```
                    ┌──────────────┐
   POST /shorten ──▶│              │──▶ counter range ──▶ base62 ──┐
                    │   App tier   │                               │
   GET /{code}  ───▶│  (stateless) │◀──────────────────────────────┘
                    └──────┬───────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
        ┌──────────┐            ┌───────────────┐
        │  Redis   │  miss ───▶ │   Database    │
        │  cache   │            │  code → url   │
        │  (LRU)   │◀───────────│  (sharded by  │
        └──────────┘            │   code hash)  │
                                └───────────────┘
```

### The details that score points

- **Redirect with 302, not 301.** A 301 is cached by the browser forever, so
  you never see the second click — and analytics is usually the business model.
- **Cache-aside with LRU**, sized to the hot set. The 80/20 rule is extreme
  here: a tiny fraction of links take almost all traffic.
- **Shard by hash of the short code**, because every lookup is a point query on
  that key. No range queries, so hashing is free.
- **Expiry** via a TTL column plus a background sweeper — deleting on read leaves
  dead rows forever.

---

## 2. API rate limiter

> *"Limit each user to 100 requests per minute."*

### The four algorithms

```
FIXED WINDOW          |####    |####    |     simple, but 2× burst at the edge
                      12:00    12:01

SLIDING LOG           keep a timestamp per request — exact, most memory

SLIDING WINDOW        weighted blend of the last two windows — good compromise
COUNTER

TOKEN BUCKET          tokens refill at a fixed rate, up to a cap
                      ●●●●●○○○  allows a burst, bounds the sustained rate
```

**The fixed-window flaw is the thing to name:** 100 requests at 11:00:59 and 100
more at 11:01:00 is 200 in one second, double the intended rate.

### Implementation with Redis

```lua
-- sliding window, atomic because Redis is single-threaded
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])  -- drop old
local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
    redis.call('EXPIRE', KEYS[1], ARGV[2])
    return 1
end
return 0
```

### The points that matter

- **It must be one atomic operation.** `GET` then `INCR` across the network is a
  race, and under load the race is the normal case.
- **Distributed counting.** A per-instance counter lets N× through. Redis is the
  shared counter — at the cost of a network hop on every request.
- **Return `429` with `Retry-After`**, and include `X-RateLimit-Remaining`.
- **Fail open or closed?** If Redis is down, do you block everyone or let
  everyone through? For a public API, usually fail *open* — a rate limiter
  outage should not be an API outage. **Say which you chose and why.**

---

## 3. Distributed cache

> *"Design Redis / memcached."*

### The core idea: consistent hashing

Naive `hash(key) % N` means **adding one node remaps almost every key** — a
total cache miss storm.

```
Consistent hashing: nodes and keys both map onto a ring

        Node A
          ●
     k1 ○   ○ k2
   ●            ●  Node B
 Node D    ○ k3
          ●
        Node C

Adding a node only remaps the keys between it and its predecessor: ~1/N.
```

**Virtual nodes** (each physical node placed at ~150 points on the ring) fix the
uneven distribution you get with few nodes.

### Round it out with

- **Eviction:** LRU by default, LFU for skewed popularity.
- **Replication** for availability; **write-through vs cache-aside** for
  consistency.
- **Stampede protection:** jittered TTLs, or a lock so one caller refreshes.
- **Hot key problem:** one celebrity key overwhelms one node. Mitigate by
  replicating that key to several nodes with a suffix, or caching it locally in
  the application.

---

## 4. Notification service

> *"Send push, SMS and email to millions of users."*

```
Services ──▶ [ API ] ──▶ [ Kafka ] ──┬──▶ [ Push worker ]  ──▶ APNS / FCM
                             │        ├──▶ [ SMS worker ]   ──▶ Twilio
                             │        └──▶ [ Email worker ] ──▶ SES
                             │
                    ┌────────┴────────┐
                    │  Template svc   │
                    │  Preference svc │  ← opt-outs, quiet hours
                    │  Dedup store    │  ← idempotency key
                    └─────────────────┘
```

### What they are testing

- **Fan-out and decoupling** — the sender must not wait for a third-party API.
- **Retries with exponential backoff and jitter**, plus a **dead-letter queue**.
- **Idempotency** — an at-least-once queue means the same notification can be
  delivered twice. A dedup key per (user, event) prevents "you have 5 new
  messages" arriving five times.
- **Rate limiting per provider** — APNS and Twilio have their own quotas.
- **Preferences and quiet hours** — the product requirement people forget.
- **Priority queues** — an OTP must not queue behind a marketing blast. Use
  separate topics, not a priority field.

---

## 5. Web crawler

> *"Crawl the web."* Tests BFS at scale, politeness, and deduplication.

```
[ Seed URLs ] ──▶ [ URL frontier ]  ← priority + politeness queues
                        │
                        ▼
                  [ Fetcher pool ] ──▶ DNS cache
                        │
                        ▼
                  [ Parser ] ──▶ extract links ──▶ back to frontier
                        │
                        ▼
                  [ Dedup: Bloom filter on URL + content hash ]
                        │
                        ▼
                  [ Storage / index ]
```

**The points:**
- **Politeness** — one domain must not be hammered. A per-domain queue with a
  delay, honouring `robots.txt`.
- **Dedup** — a Bloom filter for "have I seen this URL" (a small false-positive
  rate is fine; you skip a page). A content hash catches mirrored pages.
- **Traps** — infinite calendars, session ids in URLs. Bound the depth and
  normalise URLs.
- **Freshness** — recrawl frequency proportional to how often a page changes.

---

## 6. E-commerce (Flipkart)

> *"Design Flipkart."* The most common deep-dive at Indian product companies.
> Too big for one hour — **scope it immediately.**

### Scope it out loud

> "Flipkart is a dozen systems. Let me focus on the **catalogue, cart and
> checkout with inventory**, since that is where the interesting consistency
> problems are, and treat search, recommendations and logistics as services I
> call. Does that work?"

**That sentence alone separates candidates.**

### High level

```
                              ┌─────────────┐
   Browser / App ──▶ CDN ──▶  │ API Gateway │
                              └──────┬──────┘
          ┌──────────┬───────────────┼───────────────┬──────────┐
          ▼          ▼               ▼               ▼          ▼
     ┌────────┐ ┌────────┐     ┌──────────┐    ┌────────┐ ┌─────────┐
     │Catalog │ │ Search │     │   Cart   │    │ Order  │ │ Payment │
     │        │ │(Elastic│     │ (Redis)  │    │        │ │         │
     └───┬────┘ │ search)│     └────┬─────┘    └───┬────┘ └────┬────┘
         │      └────────┘          │              │           │
         ▼                          ▼              ▼           ▼
     ┌────────┐               ┌──────────┐   ┌─────────┐  ┌─────────┐
     │ Product│               │Inventory │   │ Orders  │  │ Ledger  │
     │   DB   │               │   DB     │   │   DB    │  │         │
     └────────┘               └──────────┘   └─────────┘  └─────────┘
                                    ▲
                                    │  reserve / release
                              ┌─────┴──────┐
                              │   Kafka    │──▶ warehouse, notifications,
                              └────────────┘    analytics, recommendations
```

### The hard part: inventory

**Do not decrement stock at add-to-cart.** People abandon carts, and you would
leak inventory.

```
1. Add to cart        → no inventory change
2. Checkout starts    → RESERVE with a TTL (10 minutes)
3. Payment succeeds   → CONFIRM (convert the reservation to a sale)
4. Payment fails/TTL  → RELEASE
```

```sql
-- The reservation, atomically. This is the whole answer.
UPDATE inventory
   SET available = available - 1,
       reserved  = reserved  + 1
 WHERE sku_id = ? AND available > 0;
-- 0 rows updated  →  out of stock. No read-then-write, no race.
```

> **The mistake to avoid:** `SELECT available` then `UPDATE`. Between the two,
> a thousand other checkouts happen. **Make it one conditional statement** and
> let the database arbitrate.

### Flash sale (the follow-up you will get)

A million people want 10,000 units at 12:00:00.

```
Queue/token  →  admit N users per second into checkout
Pre-load stock into Redis, DECR atomically, reconcile to the DB asynchronously
Shard the hot SKU counter into 10 buckets of 1,000 to avoid one hot key
Reject fast and cheaply at the edge — do not let 990,000 people reach the DB
```

### Database choice per service

| Service | Store | Why |
|---|---|---|
| Catalogue | document store + CDN | read-heavy, variable attributes per category |
| Search | Elasticsearch | full-text, facets, typo tolerance |
| Cart | Redis with TTL | ephemeral, fast, loss is tolerable |
| **Inventory** | **SQL** | **must be correct; needs atomic conditional updates** |
| **Orders** | **SQL** | **money, transactions, audit** |
| Analytics | columnar / warehouse | different access pattern entirely |

**Being able to say "SQL for inventory and orders, because correctness beats
throughput there" is the point.** Candidates who put everything in a document
store for "scale" fail this question.

---

## 7. Ticket booking (BookMyShow)

> *"Design BookMyShow / Ticketmaster."* The concurrency question. The entire
> interview is: **how do you not sell the same seat twice?**

### The core problem

```
Seat map for one show

  A  [1][2][3][4][5][6][7][8]
  B  [1][2][3][4][5][6][7][8]      ← 10,000 people want B4
  C  [1][2][3][4][5][6][7][8]         at the same moment
```

### The three-state seat

```
     AVAILABLE ──select──▶ HELD ──pay──▶ BOOKED
         ▲                  │
         └──── TTL 8 min ───┘
```

### Making the hold atomic

```sql
-- Pessimistic: lock the row, then check
BEGIN;
SELECT status FROM seat WHERE show_id = ? AND seat_id = ? FOR UPDATE;
UPDATE seat SET status = 'HELD', held_until = now() + interval '8 minutes',
                held_by = ?
 WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE';
COMMIT;

-- Or one conditional statement — no explicit lock needed
UPDATE seat SET status = 'HELD', held_until = now() + interval '8 minutes'
 WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE';
-- 0 rows updated → somebody else got it
```

### Optimistic vs pessimistic — and the right answer

| | Pessimistic (`FOR UPDATE`) | Optimistic (version check) |
|---|---|---|
| Good when | contention is **high** | contention is **low** |
| Cost | holds a lock, can block | retries under contention |

**A popular show is high contention**, so a short pessimistic lock — or the
single conditional `UPDATE`, which is effectively the same thing — wins.
**Explain that you chose based on the contention profile**, not by preference.

### Releasing expired holds

Do not rely only on a sweeper job.

```sql
-- Treat an expired hold as available in the WHERE clause itself
UPDATE seat SET status = 'HELD', held_until = now() + interval '8 min', held_by = ?
 WHERE seat_id = ?
   AND (status = 'AVAILABLE'
        OR (status = 'HELD' AND held_until < now()));
```

Correct even if the sweeper is late or dead. **Self-healing beats a cron job.**

### The full flow

```
User ──▶ [ Seat map: Redis, eventually consistent, refreshed every few seconds ]
   │            ⚠️ display only — NEVER trust it for the booking decision
   ▼
[ Hold: SQL, atomic conditional UPDATE ]  ← the source of truth
   │
   ▼
[ Payment (external, slow, can time out) ]
   │
   ├── success → CONFIRM, emit ticket, notify
   └── failure/timeout → release, and make it idempotent (the payment may
                          still land afterwards)
```

> **The mature point:** the seat map the user sees is a cache and will be
> slightly stale. That is fine — you must simply never *decide* from it. Two
> people seeing "available" and one of them losing at the database is correct
> behaviour, not a bug.

---

## 8. Payment system (Paytm, UPI)

> Paytm asks this and goes deep on **idempotency, locking and isolation**. It is
> also closest to what you have actually built.

### The double-entry ledger — the thing they want to hear

```
Never "update a balance". Append immutable entries that must sum to zero.

  txn_id   account          amount
  ───────────────────────────────────
  t1       user:alice       -50000     (debit, in paise)
  t1       merchant:bob     +50000     (credit)
                            ───────
                            0    ✅ every transaction sums to zero

Balance = SUM(amount) over the account, optionally snapshotted for speed.
```

**Why:** an audit trail that cannot be edited, a balance that is always
derivable and provable, and corrections that are new entries rather than
rewrites. This is how real financial systems work, and saying "double-entry
ledger" immediately signals you know that.

### Idempotency — the question they will actually drill

```
POST /payments
Idempotency-Key: 8f3c-...           ← supplied by the CLIENT
```

```sql
INSERT INTO payment (idempotency_key, user_id, amount_paise, status)
VALUES (?, ?, ?, 'PENDING')
ON CONFLICT (idempotency_key) DO UPDATE SET status = payment.status
RETURNING id, status, (xmax = 0) AS was_inserted;
```

**The critical sentence:** *uniqueness is enforced by the database, not by an
application lookup.* Check-then-insert leaves a window where two concurrent
retries both see "not found" and both charge.

### The state machine

```
 INITIATED ──▶ AUTHORIZED ──▶ CAPTURED ──▶ SETTLED
      │             │              │
      ▼             ▼              ▼
   FAILED       CANCELLED      REFUNDED

Rules: transitions are one-way, every change is an appended event,
       and an unknown state is resolved by RECONCILIATION, never by guessing.
```

### The three-way problem, and reconciliation

A payment can time out with the money already moved. You cannot know from your
side.

```
         Your ledger        PSP statement       Bank statement
              │                   │                   │
              └────────┬──────────┴─────────┬─────────┘
                       ▼                    ▼
                [ Reconciliation job, runs every cycle ]
                       │
              ┌────────┴────────┐
              ▼                 ▼
        matched            BREAKS → investigation queue
```

> **This is your project.** ReconPilot is a reconciliation engine, and you can
> talk about break types, idempotent ingestion, an append-only event log, and
> why money is `BIGINT` paise and never a float. **Steer this question towards
> what you have built** — you will be the only candidate that day with a
> measured answer.

### Also mention

- **Money as integer minor units.** `0.1 + 0.2 != 0.3` in binary floating point,
  and across a million rows the error is real money.
- **Exactly-once does not exist end to end.** You get at-least-once delivery plus
  idempotent processing, which is *effectively* once. Say that honestly.
- **PCI-DSS / tokenisation** — you store a token, never a card number.
- **The outbox pattern** for "write to the database and publish an event"
  without a distributed transaction.

---

## 9. Chat (WhatsApp)

> Tests WebSockets, delivery guarantees, fan-out and ordering.

```
 Phone A                                              Phone B
    │  WebSocket                            WebSocket  │
    ▼                                                  ▼
┌──────────────┐                              ┌──────────────┐
│ Chat server  │                              │ Chat server  │
│      1       │                              │      7       │
└──────┬───────┘                              └──────▲───────┘
       │                                             │
       ▼                                             │
┌───────────────────────────────────────────────────────────┐
│  Session registry (Redis): userId → which server holds     │
│                            their live connection           │
└───────────────────────────────────────────────────────────┘
       │                                             │
       ▼                                             │
┌──────────────┐        ┌──────────────┐             │
│ Message store│        │    Kafka     │─────────────┘
│  (Cassandra) │        │  (delivery)  │
└──────────────┘        └──────────────┘
                               │
                               ▼  B is offline
                        ┌──────────────┐
                        │ Push (APNS / │
                        │     FCM)     │
                        └──────────────┘
```

### The points that score

- **WebSocket, not polling.** A persistent connection per device; the session
  registry maps a user to the server holding it.
- **The two ticks.** ✓ = stored on the server. ✓✓ = delivered to the device.
  Blue = read. Three separate acknowledgements, each a separate message.
- **Offline delivery.** Store in an inbox with an `undelivered` flag; flush on
  reconnect. Push notification as the fallback.
- **Ordering.** Per-conversation sequence numbers. **Not wall-clock
  timestamps** — clocks on phones are wrong, and two devices disagree.
- **Group messages.** Fan-out on write for small groups. For a 100,000-member
  group, fan-out on read instead, or the single write becomes 100,000.
- **Storage.** Cassandra suits this: partition by `conversation_id`, cluster by
  sequence number, so "the last 50 messages" is one sequential read. It is
  write-heavy with a known access pattern — the case where NoSQL genuinely wins.
- **End-to-end encryption.** Keys live on devices; the server stores ciphertext
  it cannot read. Which means **no server-side search**, a real product
  trade-off worth naming.

---

## 10. Ride hailing (Uber, Ola)

> Tests geospatial indexing and real-time matching.

```
Driver app ──every 4s──▶ [ Location ingest ] ──▶ [ Redis GEO / QuadTree ]
                                                        │
Rider requests ──▶ [ Matching service ] ──nearby────────┘
                          │
                          ├──▶ [ Pricing (surge) ]
                          ├──▶ [ ETA service ]
                          └──▶ [ Trip service ] ──▶ SQL (trips, money)
                                     │
                                     ▼
                              [ Kafka ] ──▶ analytics, receipts, payouts
```

### The geospatial question

**How do you find "drivers within 3 km" without scanning every driver?**

```
GEOHASH — encode lat/long into a string; a shared prefix means nearby

  tdr1y  ┐
  tdr1z  ├── all share "tdr1" → same ~150 m cell
  tdr1w  ┘

Find neighbours: query your cell + the 8 surrounding cells.
Redis does this natively: GEOADD / GEOSEARCH.
```

Alternatives worth naming: a **QuadTree** (splits dense regions further — good
because drivers cluster in cities) and **Uber's H3** (hexagons, so every
neighbour is equidistant, unlike a square grid where diagonals are further).

### The rest

- **Write volume is the real challenge.** A million drivers pinging every 4
  seconds is **250,000 writes/second**. Keep them in memory (Redis), not in a
  relational database. A stale location is acceptable; a slow one is not.
- **Matching** is not simply "nearest" — it weighs ETA, driver rating,
  acceptance rate, and direction of travel.
- **Surge** is computed per geohash cell over a rolling window of
  demand-to-supply ratio.
- **The trip itself is SQL** — it is money, and it needs transactions.
- **Idempotency** on trip creation, or a double-tap creates two rides.

---

## 11. Food delivery (Swiggy, Zomato)

> Uber plus inventory plus a three-sided marketplace. Zomato also asks the
> **restaurant table booking** variant.

```
                         ┌──────────────────┐
  Customer ──▶ Search ──▶│ Restaurant svc   │ menu, availability, timings
                         └────────┬─────────┘
                                  │
  Order ──▶ [ Cart ] ──▶ [ Order service ] ──▶ SQL (orders, money)
                                  │
                                  ▼
                          [ Kafka: order.placed ]
                     ┌────────────┼────────────┐
                     ▼            ▼            ▼
             ┌────────────┐ ┌──────────┐ ┌──────────┐
             │ Restaurant │ │ Delivery │ │ Customer │
             │  terminal  │ │ matching │ │ tracking │
             └────────────┘ └────┬─────┘ └──────────┘
                                 ▼
                          [ Rider location: Redis GEO ]
```

### What makes it different from Uber

- **Three parties, not two** — customer, restaurant, rider. Any one can fail.
- **Two-stage ETA** — food preparation time *plus* travel time, and the
  preparation estimate is the noisy one. Predict it per restaurant, per dish,
  per hour.
- **Batching** — one rider carrying two nearby orders is the unit economics of
  the business, and it is a constrained optimisation, not a nearest-match.
- **Menu availability** changes constantly; cache it, but revalidate at order
  placement — the same "display is stale, the decision is not" rule as seat
  booking.
- **Surge and peak** — 7–9 pm is most of the day's volume. Capacity planning is
  for the peak, not the average.

### The table-booking variant (Zomato asks this)

Match a party size and time against opening hours, table inventory, and
existing reservations. **It is the seat-booking problem again** — atomic hold,
TTL, conditional update — with the wrinkle that tables can be **combined** for
larger parties, which makes the allocation a bin-packing problem.

---

## 12. News feed (Instagram, Twitter)

> The classic fan-out question.

### The central trade-off

```
FAN-OUT ON WRITE (push)          FAN-OUT ON READ (pull)
─────────────────────            ──────────────────────
On post: write the post id       On read: query all the people
into every follower's feed       you follow and merge

+ reads are instant              + writes are cheap
− a celebrity post = 100M        − reads are expensive
  writes ("the celebrity            and get slower with
  problem")                         how many you follow
```

**The answer is a hybrid, and saying so is the point:**

```
Normal user posts  →  fan out on write to followers' feeds (Redis lists)
Celebrity posts    →  do NOT fan out; store once
Feed read          →  merge (precomputed feed) + (recent celebrity posts)
```

```
   Post ──▶ [ Post service ] ──▶ [ Kafka ] ──▶ [ Fan-out worker ]
                                                      │
                          follower count > 10,000? ───┤
                                    │                 │
                                   YES               NO
                                    │                 │
                              skip fan-out    write into each
                                    │         follower's feed (Redis)
                                    ▼                 ▼
   Read feed ──▶ [ Feed service ] ◀─── merge ─────────┘
```

### Also cover

- **Ranking** — chronological is simplest; engagement-ranked needs a scoring
  service and is where the ML sits.
- **Pagination must be keyset**, not `OFFSET` — new posts arrive while a user
  scrolls, so offsets shift and they see duplicates.
- **Media** goes to object storage plus a CDN, never through your API.
- **The read:write ratio is enormous** — maybe 1000:1 — which is what justifies
  paying a large write cost to make reads cheap.

---

## 13. Video streaming (Netflix, YouTube)

> Tests CDN strategy, and mostly it tests whether you know video does not flow
> through your servers.

```
Upload ──▶ [ Ingest ] ──▶ [ Transcode pipeline ]
                               │  one source → many renditions
                               │  240p 360p 480p 720p 1080p 4K
                               │  chunked into ~4-second segments
                               ▼
                       [ Object storage (S3) ]
                               │
                               ▼
                       [ CDN edge caches ]  ← the video is served from HERE
                               │
   Player ──manifest──▶ ───────┘
     │
     └─ adaptive bitrate: measure bandwidth, request the next segment
        at a quality the connection can sustain
```

### The points

- **Transcoding is a batch pipeline**, parallelised per segment, and it is the
  expensive part.
- **HLS / DASH**: a manifest lists segments at each quality; the *player*
  decides which to fetch next. Adaptation is client-side.
- **The CDN is the system.** 95%+ of bytes never touch your origin. Netflix goes
  further and puts appliances (Open Connect) inside ISP networks.
- **Metadata, recommendations and playback position** are your services and are
  small. **The video is not your traffic.**
- **Pre-positioning** — push a new release to edges *before* launch, rather than
  letting the first million users all miss.

---

## The patterns, collected

The same ideas keep reappearing. Learn these once and you can attack any
question.

| Pattern | Where it shows up |
|---|---|
| **Atomic conditional update** | inventory, seats, wallet balance |
| **Hold with TTL** | seats, inventory reservation, tables |
| **Idempotency key + unique constraint** | payments, orders, notifications |
| **Double-entry ledger** | any money movement |
| **Outbox pattern** | DB write + event publish, without XA |
| **Fan-out write vs read** | feeds, group chat, notifications |
| **Geohash / QuadTree / H3** | anything "nearby" |
| **Consistent hashing** | caches, shards |
| **CDN + object storage** | any large file or media |
| **Keyset pagination** | any infinite scroll |
| **Bounded queue + 503** | any ingestion path |
| **Event log + projections** | audit, replay, rebuilding state |
| **Read replica** | read-heavy, staleness tolerable |
| **CQRS** | very different read and write shapes |

---

## Questions they ask about YOUR project

**This is where you should be strongest**, because you have measured answers.
Practise these out loud.

### "Walk me through your system."

Five minutes, timed, spoken not read. Problem → requirements → estimation →
diagram → three decisions with their costs. §14 of `11-system-design.md` has the
shape.

### "Why a monolith?"

> "We measured roughly 0.4 writes per second with million-row bursts. That is a
> **correctness** problem, not a **scale** problem. Microservices would have
> bought independent deployment we didn't need and cost distributed
> transactions and cross-service tracing, for one team. The modules have clean
> boundaries, so one can be extracted if it ever genuinely needs separate
> scaling — and starting as a monolith and splitting later is far easier than
> the reverse."

### "How do you handle a 2 GB upload?"

> "Return **202 Accepted** in about half a second. The file is streamed to disk
> and hashed in one pass, never buffered, so memory is constant regardless of
> size. Only the batch id goes through Kafka — the claim-check pattern. Parsing
> inside the request would hold an HTTP connection open past every proxy
> timeout, and a retry would re-upload two gigabytes."

### "What happens under overload?"

> "A bounded queue and `AbortPolicy`, so it returns **503** rather than queueing
> until the heap dies. Measured: capacity 14, sixteen concurrent uploads, 14
> accepted and 2 rejected. An unbounded queue turns overload into an
> `OutOfMemoryError`, which is a worse failure and harder to diagnose."

### "How is multi-tenancy isolated?"

> "Shared tables with a `tenant_id`, but the filter is enforced by **PostgreSQL
> row-level security**, not by application code. A forgotten `WHERE` clause
> returns nothing instead of someone else's data. It needed three things to
> actually work — `FORCE`, a non-superuser role, and `WITH CHECK` as well as
> `USING` — and we shipped it with one of the three, so it was enabled and
> completely inert while the tests passed."

### "What would you do differently?"

> "The outbox pattern from the start instead of accepting a dual write between
> the database and Kafka. Method security enforced from day one — we store roles
> and turn them into authorities, but nothing checks them. And CI before the
> second feature: our first CI run found a bug no local test could have found,
> because every laptop database had been migrated days earlier."

### "What is the biggest weakness of your design?"

> "Single node. One Kafka broker at replication factor 1, so broker loss is data
> loss. Backups that never leave the host. No alerting on consumer lag. All four
> are recorded as a known defect rather than discovered by an interviewer — and
> the reason they are acceptable today is that this is a reconciliation tool
> handling a few hundred files, not a payment authorisation path."

---

## How to prepare from here

1. **Do one question a week, on paper, timed at 45 minutes.** Start with the
   URL shortener and the rate limiter; they teach the rhythm.
2. **Always estimate before designing.** It is the habit interviewers notice
   most, and it is the one candidates skip.
3. **For every box you draw, say what it costs.** 2026 interviewers grade cost.
   "Add a cache" now invites "how much, and what does it save?"
4. **Rehearse your own project answers out loud.** They are your strongest
   material and the only ones where you have real numbers.
