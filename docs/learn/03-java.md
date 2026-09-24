# Java, the language

**Why this document matters more than the Spring one.** For a Java backend role
at a serious package, the Spring questions are usually the *easy* half.
Interviewers separate candidates on core Java: collections internals,
concurrency, the memory model, and whether you understand what the JVM is
actually doing. Two years in, "I use `HashMap`" is expected; "I know what
happens when two keys collide" is what gets you through.

Everything here is Java 21, which is what this project targets.

---

## Table of contents

1. [How Java actually runs](#1-how-java-actually-runs)
2. [Memory: stack, heap, and what the GC does](#2-memory-stack-heap-and-what-the-gc-does)
3. [Object fundamentals](#3-object-fundamentals)
4. [equals and hashCode — the contract](#4-equals-and-hashcode--the-contract)
5. [Strings](#5-strings)
6. [Collections, properly](#6-collections-properly)
7. [HashMap internals](#7-hashmap-internals)
8. [Generics and type erasure](#8-generics-and-type-erasure)
9. [Exceptions](#9-exceptions)
10. [Concurrency](#10-concurrency)
11. [The Java Memory Model](#11-the-java-memory-model)
12. [Executors and CompletableFuture](#12-executors-and-completablefuture)
13. [Virtual threads](#13-virtual-threads)
14. [Streams and lambdas](#14-streams-and-lambdas)
15. [Optional](#15-optional)
16. [Modern Java: records, sealed, pattern matching](#16-modern-java-records-sealed-pattern-matching)
17. [Immutability](#17-immutability)
18. [Numbers and money](#18-numbers-and-money)
19. [The traps, collected](#19-the-traps-collected)

---

## 1. How Java actually runs

### JDK, JRE, JVM

| | What it is |
|---|---|
| **JVM** | The engine that executes bytecode. Platform-specific. |
| **JRE** | JVM + the standard library. Enough to *run* a program. |
| **JDK** | JRE + compiler (`javac`), debugger, tools. Enough to *build* one. |

```
Foo.java  ──javac──▶  Foo.class      ──JVM──▶  runs
(source)             (bytecode,               (interprets, then
                      platform-neutral)        JIT-compiles hot paths)
```

**"Write once, run anywhere"** works because `javac` does not produce machine
code. It produces bytecode, and each platform has its own JVM that understands
it. The portability lives in the JVM, not in your code.

### Interpretation, then JIT

The JVM starts by **interpreting** bytecode — simple, slow. It counts how often
each method runs, and once a method is "hot" the **JIT (Just-In-Time)
compiler** compiles it to native machine code and optimises it aggressively:
inlining, dead code elimination, loop unrolling, escape analysis.

This is why:
- Java is slow for the first few seconds and fast afterwards — **warm-up**.
- Microbenchmarks that run a loop 10 times measure nothing useful.
- A long-running server eventually beats naive expectations.

> **Interview question:** *"Is Java compiled or interpreted?"* — "Both. `javac`
> compiles source to platform-neutral bytecode ahead of time. At runtime the
> JVM interprets that bytecode, profiles it, and the JIT compiles hot methods
> to native code with runtime information the static compiler never had —
> actual branch frequencies, actual types at call sites. That is why a
> long-running JVM can outperform an ahead-of-time compiled language on some
> workloads, and why benchmarks must include a warm-up phase."

---

## 2. Memory: stack, heap, and what the GC does

### The two regions you must be able to describe

```
┌─────────────────────────────┐
│           STACK             │   one PER THREAD
│  ─────────────────────────  │   - local variables
│  method frames              │   - references (not objects)
│  primitives                 │   - primitives
│  references → heap          │   - freed automatically on return
└─────────────────────────────┘

┌─────────────────────────────┐
│            HEAP             │   SHARED by all threads
│  ─────────────────────────  │   - every object, every array
│  Young gen: Eden, S0, S1    │   - garbage collected
│  Old gen (tenured)          │
└─────────────────────────────┘

┌─────────────────────────────┐
│          METASPACE          │   class metadata (off-heap since Java 8)
└─────────────────────────────┘
```

```java
void example() {
    int count = 5;                  // primitive → on the STACK
    String name = "recon";          // reference on stack, object on HEAP
    var list = new ArrayList<>();   // reference on stack, ArrayList on HEAP
}                                   // stack frame gone; heap objects await GC
```

> **The sentence to remember:** *the stack holds the reference; the heap holds
> the object.* Almost every "where does X live" question is answered by it.

### Garbage collection

Java frees memory for you. The GC finds objects that are no longer
**reachable** from a *GC root* (a live thread's stack, static fields, JNI
references) and reclaims them.

**Generational hypothesis:** most objects die young. So the heap is split:

```
New object → Eden
   survives a collection → Survivor space (S0 ⇄ S1)
   survives enough collections → Old generation
```

- **Minor GC** — collects the young generation. Frequent, fast.
- **Major/Full GC** — collects the old generation. Rarer, slower, and the one
  that causes a noticeable pause.

**Collectors worth naming:**

| Collector | Character |
|---|---|
| **Serial** | one thread; tiny heaps, containers with 1 CPU |
| **Parallel** | throughput-oriented; longer pauses |
| **G1** | **default since Java 9**; region-based, targets a pause goal |
| **ZGC / Shenandoah** | sub-millisecond pauses, very large heaps |

> **You cannot force a GC.** `System.gc()` is a *suggestion* the JVM may
> ignore. Saying "I call `System.gc()` to free memory" in an interview is a red
> flag.

### Memory leaks in a garbage-collected language

Yes, they happen — whenever something stays *reachable* that shouldn't be:

```java
// 1. A static collection that only grows
static final Map<String, Session> CACHE = new HashMap<>();   // never evicted

// 2. Unclosed resources
var stream = Files.lines(path);     // holds a file handle until closed

// 3. Listeners never removed
bus.register(this);                 // and never unregister(this)

// 4. ThreadLocal in a pooled thread
threadLocal.set(bigObject);         // the thread is reused; the value survives
```

That last one is the subtle, production-grade answer. ReconPilot uses a
`ThreadLocal` for the current tenant, and clears it explicitly after every
request — because Tomcat reuses threads, so a value left behind would be picked
up by the *next* user's request. That is not just a leak, it is a data breach.

### Container awareness

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
```

Modern JVMs read the **container's** memory limit, not the host's. Setting a
percentage rather than a fixed `-Xmx` means changing the container limit
changes the heap, with no rebuild. `ExitOnOutOfMemoryError` makes the process
die rather than limp — so the orchestrator restarts it instead of leaving a
zombie serving errors.

### OutOfMemoryError vs StackOverflowError

- **`OutOfMemoryError: Java heap space`** — the heap is full of reachable
  objects.
- **`StackOverflowError`** — a thread's stack is exhausted, almost always
  infinite recursion.

---

## 3. Object fundamentals

### The four pillars, stated usefully

**Encapsulation** — hide internal state, expose behaviour. The point is that
you can change the internals without breaking callers.

**Inheritance** — `extends`. Reuse and specialise. Use sparingly; see below.

**Polymorphism** — one interface, many implementations. Resolved at runtime.

**Abstraction** — expose *what*, hide *how*.

### Composition over inheritance

```java
// ❌ Inheritance abused: a Stack is not really a Vector
class Stack extends Vector<Integer> { }   // now Stack has insertAt(), get(i)...

// ✅ Composition: the Stack HAS a list, and exposes only stack operations
class Stack<T> {
    private final List<T> items = new ArrayList<>();
    void push(T t) { items.add(t); }
    T pop() { return items.remove(items.size() - 1); }
}
```

> **The rule:** inheritance is an "is-a" relationship *and* a promise that
> every subclass can be substituted for the parent (Liskov). If you find
> yourself overriding a method to throw `UnsupportedOperationException`, the
> relationship was wrong.

### Abstract class vs interface

| | Abstract class | Interface |
|---|---|---|
| Multiple inheritance | ❌ one only | ✅ many |
| State (fields) | ✅ | only `static final` |
| Constructors | ✅ | ❌ |
| Default methods | ✅ | ✅ since Java 8 |
| Use when | classes share state and identity | classes share a capability |

> **Interview question:** *"Since Java 8 interfaces have default methods —
> what's left of the difference?"* — "State and construction. An abstract class
> can hold instance fields and run a constructor, so it can enforce invariants
> during creation. An interface cannot. Default methods solved API evolution —
> adding a method without breaking implementers — not shared state. And
> interfaces still allow multiple inheritance of *type*, which is the deeper
> difference."

### Overloading vs overriding

```java
class Parent {
    void greet(String s) { }
}

class Child extends Parent {
    @Override
    void greet(String s) { }          // OVERRIDE  — same signature, runtime dispatch
    void greet(Integer i) { }         // OVERLOAD  — different params, compile-time
}
```

- **Overriding** is resolved at **runtime** by the object's actual type.
- **Overloading** is resolved at **compile time** by the declared types.

Always write `@Override`. It is a compile-time check that you actually
overrode something rather than silently creating an overload — which is a
genuinely nasty bug when the signature drifts.

### `static`

Belongs to the class, not an instance. One copy, shared.

```java
class Counter {
    static int total;        // shared by every instance — and every thread ⚠️
    int mine;                // one per instance
}
```

A `static` mutable field is global mutable state. In a web application that
means shared across every request. Treat with suspicion.

---

## 4. equals and hashCode — the contract

**This is asked in almost every Java interview.**

### The rules

1. If `a.equals(b)` then `a.hashCode() == b.hashCode()`. **Mandatory.**
2. If `a.hashCode() == b.hashCode()`, `a.equals(b)` may still be false —
   that is a *collision*, and it is legal.
3. `equals` must be reflexive, symmetric, transitive, consistent, and
   `x.equals(null)` must be false.

### Why breaking it is catastrophic

```java
class Point {
    int x, y;
    @Override public boolean equals(Object o) { /* compares x and y */ }
    // hashCode NOT overridden → inherits Object's identity hash
}

var set = new HashSet<Point>();
set.add(new Point(1, 1));
set.contains(new Point(1, 1));     // false! 😱
```

`HashSet` looks in the bucket given by `hashCode()`. Two equal objects with
different hash codes land in **different buckets**, so the lookup never even
reaches `equals`. The object is in the set and cannot be found.

**Override both, always, together.**

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Point p)) return false;   // pattern matching, Java 16+
    return x == p.x && y == p.y;
}

@Override
public int hashCode() {
    return Objects.hash(x, y);
}
```

### Or just use a record

```java
record Point(int x, int y) {}    // equals, hashCode, toString — all correct, free
```

This is one of the strongest arguments for records, and why ReconPilot uses
them for every DTO and value object.

### The mutable-key trap

```java
var key = new ArrayList<String>();
map.put(key, "value");
key.add("mutated");          // hashCode has now CHANGED
map.get(key);                // null — it is in the wrong bucket forever
```

**Keys must be immutable.** This is why `String` and the boxed primitives make
good keys and a mutable list does not.

---

## 5. Strings

### Immutability

A `String` can never change. Every "modification" creates a new object.

```java
String s = "hello";
s.toUpperCase();             // returns a NEW string; s is unchanged
s = s.toUpperCase();         // now s points at the new one
```

**Why immutable?** Safe to share between threads with no synchronisation,
safe to cache the hash code, safe to use as a map key, and safe to pass to code
you do not trust (it cannot alter your string under you — which matters for
file paths and SQL).

### The string pool

```java
String a = "recon";                    // goes in the pool
String b = "recon";                    // same pooled object
String c = new String("recon");        // NEW object on the heap, not pooled

a == b        // true   — same reference
a == c        // false  — different objects
a.equals(c)   // true   — same content
c.intern() == a  // true — intern() returns the pooled instance
```

> **Always compare strings with `.equals()`.** `==` compares references. It
> *happens* to work for literals because of the pool, which makes the bug
> intermittent — the worst kind.

### Concatenation in a loop

```java
// ❌ O(n²): each += copies the whole string
String s = "";
for (String part : parts) s += part;

// ✅ O(n)
var sb = new StringBuilder();
for (String part : parts) sb.append(part);
String s = sb.toString();
```

The compiler optimises simple `a + b` into `StringBuilder` automatically, but
**not across loop iterations** — each iteration creates a fresh builder. For a
10,000-element loop that is 10,000 array copies.

- **`StringBuilder`** — not thread-safe, fast. Use this.
- **`StringBuffer`** — synchronised, slower. Essentially legacy.

### Text blocks (Java 15+)

```java
String sql = """
        SELECT id, break_type, delta_paise
          FROM recon_break
         WHERE tenant_id = ?
         ORDER BY delta_paise DESC
        """;
```

Used throughout ReconPilot for SQL. Readable, no escaping, incidental
indentation stripped automatically.

---

## 6. Collections, properly

### The hierarchy

```
Iterable
  └─ Collection
       ├─ List      ordered, duplicates allowed
       │    ├─ ArrayList        resizable array
       │    ├─ LinkedList       doubly-linked list
       │    └─ CopyOnWriteArrayList   thread-safe, read-heavy
       ├─ Set       no duplicates
       │    ├─ HashSet          unordered, backed by HashMap
       │    ├─ LinkedHashSet    insertion-ordered
       │    └─ TreeSet          sorted, backed by TreeMap
       └─ Queue / Deque
            ├─ ArrayDeque       the right default for a stack or queue
            ├─ PriorityQueue    heap-ordered
            └─ LinkedBlockingQueue  thread-safe, blocking

Map (NOT a Collection)
  ├─ HashMap            unordered
  ├─ LinkedHashMap      insertion (or access) ordered — LRU caches
  ├─ TreeMap            sorted by key; NavigableMap
  └─ ConcurrentHashMap  thread-safe, no global lock
```

Note **`Map` is not a `Collection`.** It is a separate interface. People get
this wrong.

### ArrayList vs LinkedList

| Operation | ArrayList | LinkedList |
|---|---|---|
| `get(i)` | **O(1)** | O(n) |
| `add` at end | O(1) amortised | O(1) |
| `add`/`remove` at front | O(n) | **O(1)** |
| `add`/`remove` in middle | O(n) | O(n) — the *walk* is O(n) |
| Memory | compact | +2 references per element |

> **In practice use `ArrayList` almost always.** `LinkedList`'s theoretical
> advantage is undone by cache locality: an `ArrayList` is one contiguous block
> the CPU prefetches, while a `LinkedList` chases pointers all over the heap.
> Even for middle insertions `ArrayList` frequently wins, because `System.arraycopy`
> is extremely fast. If you need fast insert/remove at *both ends*, use
> `ArrayDeque`, not `LinkedList`.

### TreeMap and NavigableMap — genuinely useful

ReconPilot uses this for fee slabs:

```java
NavigableMap<Long, Long> slabs = new TreeMap<>();
slabs.put(300_00L, 20_00L);     // up to Rs 300  -> Rs 20
slabs.put(500_00L, 26_00L);     // Rs 300-500    -> Rs 26

slabs.ceilingEntry(250_00L);    // the first slab whose ceiling is >= 250 → Rs 20
slabs.floorEntry(250_00L);      // the greatest key <= 250 → null here
slabs.higherKey(300_00L);       // strictly greater → 500
```

**Any "price band", "tier", "rate slab" or "nearest match" problem is a
`TreeMap` problem.** Knowing `ceilingEntry` / `floorEntry` exists saves you
writing a loop, and it is an O(log n) lookup rather than O(n).

### Fail-fast iterators

```java
for (String s : list) {
    if (s.isEmpty()) list.remove(s);     // 💥 ConcurrentModificationException
}
```

Collections track a `modCount`. The iterator remembers it, and throws if the
collection changed underneath. The correct ways:

```java
list.removeIf(String::isEmpty);                       // best

var it = list.iterator();                             // explicit iterator
while (it.hasNext()) { if (it.next().isEmpty()) it.remove(); }
```

Note the exception name is misleading: it usually has nothing to do with
threads — it is a *single-threaded* structural modification during iteration.

### Choosing quickly

| I need... | Use |
|---|---|
| Indexed access, general list | `ArrayList` |
| Stack or queue | `ArrayDeque` |
| Uniqueness | `HashSet` |
| Uniqueness + insertion order | `LinkedHashSet` |
| Sorted, or range/nearest queries | `TreeMap` / `TreeSet` |
| Key→value, general | `HashMap` |
| Key→value, concurrent | `ConcurrentHashMap` |
| LRU cache | `LinkedHashMap` with `removeEldestEntry` |

---

## 7. HashMap internals

**This is the deep-dive question that separates candidates.** Be able to draw
it.

### The structure

```
        buckets (an array, always a power of two)
        ┌───┬───┬───┬───┬───┬───┬───┬───┐
index   │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │
        └───┴─┬─┴───┴───┴─┬─┴───┴───┴───┘
              │           │
              ▼           ▼
           ("a",1)     ("c",3) ──▶ ("k",9)      ← a collision chain
```

### put(key, value), step by step

1. `h = key.hashCode()`
2. **Spread:** `h = h ^ (h >>> 16)` — mixes the high bits into the low bits,
   because the index only uses the low bits.
3. `index = h & (n - 1)` — equivalent to `h % n`, but a bitmask, and only
   correct **because `n` is a power of two**. That is why capacity is always
   16, 32, 64...
4. If the bucket is empty, place the entry.
5. If not, walk the chain comparing with `equals`. Replace on a match, else
   append.
6. If the chain in one bucket reaches **8** entries *and* the table is at least
   64 buckets, it is **treeified** into a red-black tree — O(n) worst case
   becomes O(log n). It untreeifies below 6.
7. If `size > capacity * loadFactor` (default **0.75**), **resize**: double the
   capacity and rehash everything.

### Why 0.75

A trade-off between space and collisions. Lower means more memory and fewer
collisions; higher means less memory and longer chains. 0.75 is empirically
about right for a roughly uniform hash.

### Sizing it up front

```java
// Default capacity 16. Adding 1,000 entries triggers ~6 resizes,
// each rehashing everything.
var map = new HashMap<String, String>();

// Sized once: no resize at all
var sized = new HashMap<String, String>(1_400);   // expected / 0.75
```

### The interview answers

> *"What happens on a hash collision?"* — "Both entries live in the same
> bucket, chained in a linked list, and lookup compares with `equals` along the
> chain. Since Java 8, if one bucket's chain reaches eight entries and the
> table has at least 64 buckets, that bucket converts to a red-black tree so
> worst-case lookup is O(log n) instead of O(n). This was added to blunt hash
> collision denial-of-service attacks."

> *"Why must the capacity be a power of two?"* — "So the bucket index can be
> computed as `hash & (n-1)`, a single bitmask, instead of a modulo. It also
> makes resizing cheap: when capacity doubles, an entry either stays at its
> index or moves to `index + oldCapacity`, decided by one bit."

> *"What happens if you use a HashMap from multiple threads?"* — "Undefined
> behaviour. Lost updates at minimum. In Java 7 a concurrent resize could
> create a circular reference in a bucket chain and spin a CPU at 100% forever.
> Java 8 changed the resize to preserve order and made that specific infinite
> loop much harder to hit, but it is still not thread-safe — use
> `ConcurrentHashMap`."

### ConcurrentHashMap

Not one big lock. It locks **per bucket** (via CAS and synchronisation on the
bucket head), so different threads writing different buckets never contend.
Reads are lock-free.

```java
map.computeIfAbsent(key, k -> expensiveLoad(k));   // atomic
map.putIfAbsent(key, value);                        // atomic
map.merge(key, 1, Integer::sum);                    // atomic counter
```

**These compound operations are atomic; a `get` followed by a `put` is not.**
That check-then-act gap is a real race, and ReconPilot hit exactly that shape
at the database level — a check-then-insert failed 12 of 14 concurrent batches
until it became a single `INSERT ... ON CONFLICT ... RETURNING`.

`Collections.synchronizedMap` wraps every method in one lock — correct, but
serialises everything and still needs external synchronisation for iteration.

---

## 8. Generics and type erasure

### Why generics exist

```java
List list = new ArrayList();      // raw
list.add("hello");
Integer i = (Integer) list.get(0);  // compiles; ClassCastException at runtime

List<String> typed = new ArrayList<>();
typed.add(42);                     // ❌ compile error — caught at build time
```

Generics move type errors from runtime to compile time.

### Type erasure — the thing to understand

**Generics exist only at compile time.** The compiler checks types, then
*erases* them and inserts casts. At runtime, `List<String>` and
`List<Integer>` are both just `List`.

Consequences you will be asked about:

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
a.getClass() == b.getClass();        // true! Both are ArrayList

// ❌ Cannot overload on erased types — same signature after erasure
void process(List<String> s) { }
void process(List<Integer> i) { }    // compile error

// ❌ Cannot create a generic array
T[] array = new T[10];               // compile error

// ❌ Cannot use instanceof with a type argument
if (obj instanceof List<String>) { } // compile error

// ❌ Cannot instantiate a type parameter
T t = new T();                       // compile error
```

**Why erasure?** Backward compatibility. Generics arrived in Java 5, and
erasure let generic and pre-generic code interoperate on the same JVM.

### Wildcards and PECS

```java
List<? extends Number> producer;   // you can READ Numbers out
List<? super Integer>  consumer;   // you can WRITE Integers in
```

**PECS — Producer Extends, Consumer Super.**

```java
// Reads FROM src (producer), writes TO dest (consumer)
static <T> void copy(List<? extends T> src, List<? super T> dest) {
    for (T t : src) dest.add(t);
}
```

Why you cannot write into `? extends`:

```java
List<? extends Number> list = new ArrayList<Integer>();
list.add(3.14);      // ❌ — the actual list might be List<Integer>
```

The compiler knows only that it is *some* subtype of `Number`, so no value is
provably safe to add.

---

## 9. Exceptions

### The hierarchy

```
Throwable
 ├─ Error                 ☠️ do not catch: OutOfMemoryError, StackOverflowError
 └─ Exception
      ├─ RuntimeException  UNCHECKED — NullPointer, IllegalArgument, IllegalState
      └─ everything else   CHECKED  — IOException, SQLException
```

- **Checked** — the compiler forces you to catch or declare. Meant for
  recoverable conditions.
- **Unchecked** — no compiler ceremony. Meant for programming errors.

### try-with-resources

```java
// ✅ closes in reverse order, even on exception; suppressed exceptions attached
try (var in = Files.newInputStream(path);
     var out = Files.newOutputStream(target)) {
    in.transferTo(out);
}
```

Anything implementing `AutoCloseable` works. This replaced the old
`finally { try { x.close(); } catch (IOException ignored) {} }` dance, and it
handles the case where both the body *and* `close()` throw — the close
exception is attached as *suppressed* rather than hiding the real one.

### finally, and how to lose an exception

```java
try {
    return compute();       // throws
} finally {
    return fallback();      // ☠️ swallows the exception entirely
}
```

**Never `return` from `finally`.** It discards both exceptions and earlier
return values.

### Practices that read as senior

```java
// ❌ swallowing
try { risky(); } catch (Exception e) { }

// ❌ losing the cause
catch (SQLException e) { throw new AppException("failed"); }

// ✅ chain the cause
catch (SQLException e) { throw new AppException("saving break", e); }

// ❌ catching what you cannot handle
catch (Throwable t) { }

// ✅ fail fast on programmer error
if (amountPaise < 0) throw new IllegalArgumentException("negative: " + amountPaise);
```

That last pattern is everywhere in ReconPilot's records — validating in the
compact constructor means an invalid object cannot exist at all, rather than
causing a confusing failure three layers later.

> **Interview question:** *"Checked or unchecked for your own exceptions?"* —
> "Unchecked by default. Checked exceptions force every intermediate caller to
> declare or wrap them, which pollutes signatures and pushes people to catch
> and swallow. I use checked only where the caller genuinely has a recovery
> path they would otherwise forget. Spring's entire data access hierarchy is
> unchecked for exactly this reason."

---

## 10. Concurrency

### Thread basics

```java
Thread t = new Thread(() -> doWork());
t.start();       // starts a new thread and calls run()
t.run();         // ⚠️ just a method call — SAME thread, no concurrency
t.join();        // wait for it to finish
```

`start()` vs `run()` is a classic screening question.

### The race condition

```java
class Counter {
    private int count = 0;
    void increment() { count++; }     // NOT atomic
}
```

`count++` is three operations: read, add, write. Two threads interleave and one
update is lost.

### synchronized

```java
class Counter {
    private int count = 0;

    synchronized void increment() { count++; }       // locks on `this`

    void alsoFine() {
        synchronized (lock) { count++; }             // locks on a chosen object
    }
    private final Object lock = new Object();
}
```

A `synchronized` block does two things:
1. **Mutual exclusion** — one thread at a time.
2. **Visibility** — establishes happens-before, so changes are seen by the next
   thread to acquire the lock.

`static synchronized` locks on the `Class` object, not on an instance.

### volatile

```java
private volatile boolean running = true;
```

**`volatile` guarantees visibility, not atomicity.**

- ✅ Writes are immediately visible to other threads; reads are never cached in
  a register or CPU cache.
- ❌ `volatile int i; i++;` is still a race — it is still read-modify-write.

Use it for a flag that one thread sets and others read, which is exactly the
shutdown-flag case:

```java
private volatile boolean shutdown = false;   // without volatile, the loop
while (!shutdown) { ... }                    // may never see the change
```

### Atomics

```java
var counter = new AtomicLong();
counter.incrementAndGet();                   // atomic, lock-free
counter.compareAndSet(expected, newValue);   // CAS
```

Built on **compare-and-swap**, a CPU instruction: "if the value is still what I
read, replace it; otherwise tell me and I will retry." Faster than locking
under moderate contention because no thread ever blocks.

### Deadlock

```java
// Thread 1: synchronized(A) { synchronized(B) { } }
// Thread 2: synchronized(B) { synchronized(A) { } }   💥
```

Four conditions must all hold: mutual exclusion, hold-and-wait, no preemption,
circular wait. Break any one:

- **Always acquire locks in a consistent global order** — the simplest fix.
- Use `tryLock(timeout)` so a thread gives up instead of waiting forever.
- Reduce lock scope, or avoid locks with immutable data.

**Related terms:** *livelock* (threads keep responding to each other and make
no progress), *starvation* (a thread never gets the lock).

### Other locks

```java
var lock = new ReentrantLock();
lock.lock();
try { ... } finally { lock.unlock(); }      // ALWAYS unlock in finally

var rw = new ReentrantReadWriteLock();       // many readers OR one writer
```

`ReentrantLock` over `synchronized` when you need `tryLock`, a timeout,
fairness, or interruptible acquisition. Otherwise `synchronized` is simpler and
the JVM optimises it well.

---

## 11. The Java Memory Model

The JMM defines when one thread's write becomes visible to another. This sounds
academic until you hit a bug that only appears on a multi-core machine under
load.

### Why a write might never be seen

Without synchronisation, the compiler, the JIT and the CPU may all **reorder**
instructions, and each core has its own cache. A write to a field may sit in a
core's store buffer indefinitely.

```java
// Thread A
config = new Config();      // 1
ready = true;               // 2   — may be reordered BEFORE 1

// Thread B
if (ready) config.use();    // may see ready==true and config==null 💥
```

### happens-before

If action X *happens-before* Y, then X's effects are visible to Y. The
guarantees:

1. **Program order** — within one thread, statements happen in order.
2. **Monitor lock** — unlocking happens-before any later lock of the same
   monitor.
3. **volatile** — a write happens-before every subsequent read of that field.
4. **Thread start** — `t.start()` happens-before everything in `t`.
5. **Thread join** — everything in `t` happens-before `t.join()` returning.
6. **final fields** — correctly-constructed final fields are visible without
   synchronisation.

> **Interview question:** *"What does `volatile` actually guarantee?"* —
> "Visibility and ordering, not atomicity. A volatile write happens-before any
> subsequent volatile read of that field, which prevents both caching and
> reordering across it. It does *not* make compound operations atomic, so
> `volatile int i; i++` is still a race — for that you need an `Atomic` type or
> a lock."

---

## 12. Executors and CompletableFuture

### Never create threads by hand

```java
// ❌ unbounded thread creation — one bad day away from OOM
new Thread(task).start();

// ✅ a pool with a bounded queue
ExecutorService pool = Executors.newFixedThreadPool(4);
pool.submit(task);
pool.shutdown();
```

| Factory | Character |
|---|---|
| `newFixedThreadPool(n)` | n threads, **unbounded queue** ⚠️ |
| `newCachedThreadPool()` | grows without limit ⚠️ |
| `newSingleThreadExecutor()` | serialised execution |
| `newVirtualThreadPerTaskExecutor()` | Java 21, one virtual thread per task |

> **The warning matters.** `newFixedThreadPool` uses an unbounded
> `LinkedBlockingQueue`. Under overload it queues forever and you get an
> `OutOfMemoryError` instead of backpressure. For anything real, construct a
> `ThreadPoolExecutor` directly with a bounded queue and a rejection policy —
> which is what ReconPilot does, so an overloaded ingestion queue returns
> **503** rather than dying.

### CompletableFuture

```java
CompletableFuture
    .supplyAsync(() -> fetchRates(), pool)        // run async
    .thenApply(rates -> calculate(rates))          // transform the result
    .thenCompose(r -> saveAsync(r))                // chain another future (flatMap)
    .thenCombine(otherFuture, (a, b) -> merge(a, b))  // join two
    .exceptionally(ex -> fallback())               // recover
    .thenAccept(result -> log.info("{}", result)); // consume

CompletableFuture.allOf(f1, f2, f3).join();        // wait for all
```

- `thenApply` — map (returns a value)
- `thenCompose` — flatMap (returns another future; avoids `Future<Future<T>>`)
- `thenCombine` — zip two independent futures

**Always pass your own executor.** The default is the common `ForkJoinPool`,
which is shared process-wide and sized to your CPU count — one blocking task
there can starve everything else.

---

## 13. Virtual threads

Java 21's headline feature, and a very likely interview topic right now.

### The problem they solve

A platform thread maps 1:1 to an OS thread: ~1 MB of stack, expensive to
create, and a few thousand is the practical ceiling. Most server threads spend
their life **blocked on I/O** — doing nothing while holding an expensive
resource.

### What a virtual thread is

A lightweight thread managed by the JVM, not the OS. Millions can exist. When
one blocks on I/O, the JVM **unmounts** it from its carrier (a platform thread)
and runs something else. The carrier is never idle.

```java
// One virtual thread per task — cheap
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (var task : tasks) executor.submit(task);
}

Thread.startVirtualThread(() -> handle(request));
```

In Spring Boot 3.2+:

```properties
spring.threads.virtual.enabled=true
```

### What they do NOT do

- They do **not** speed up CPU-bound work. There are still only so many cores.
- **Do not pool them.** They are cheap to create; a pool defeats the purpose.
- `synchronized` blocks could **pin** a virtual thread to its carrier in early
  versions — prefer `ReentrantLock` in hot paths. (This is progressively being
  fixed in later JDKs.)
- `ThreadLocal` still works but is memory-hungry when you have a million
  threads.

> **Interview question:** *"When would virtual threads help you?"* — "I/O-bound
> request handling, which is most web servers. The classic 'thread per request'
> model is simple to read and debug but caps out at a few thousand threads
> because each one is an OS thread. Virtual threads keep the simple blocking
> style while scaling to hundreds of thousands, because a blocked virtual
> thread releases its carrier. They give you reactive-like scalability without
> reactive-style code. They do nothing for CPU-bound work."

---

## 14. Streams and lambdas

### Functional interfaces

An interface with exactly one abstract method, so a lambda can implement it.

```java
Supplier<String>          s = () -> "value";           // nothing in, one out
Consumer<String>          c = v -> print(v);           // one in, nothing out
Function<String, Integer> f = String::length;          // one in, one out
Predicate<String>         p = String::isEmpty;         // one in, boolean out
BiFunction<A, B, C>       b = (a, x) -> combine(a, x); // two in, one out
UnaryOperator<String>     u = String::toUpperCase;     // Function<T,T>
```

### Method references

```java
String::length          // an instance method of an arbitrary object
System.out::println     // an instance method of a specific object
Integer::parseInt       // a static method
ArrayList::new          // a constructor
```

### Streams

```java
List<String> names = breaks.stream()
        .filter(b -> b.deltaPaise() > 0)           // intermediate (lazy)
        .sorted(comparing(Break::deltaPaise).reversed())
        .limit(10)
        .map(Break::externalTxnId)                  // intermediate
        .toList();                                   // TERMINAL — now it runs
```

**Nothing happens until the terminal operation.** Intermediate operations build
a pipeline; the terminal one drives it.

### Collectors worth knowing

```java
.collect(Collectors.toList())                       // or .toList() since 16
.collect(Collectors.toSet())
.collect(Collectors.joining(", ", "[", "]"))
.collect(Collectors.groupingBy(Break::breakType))
.collect(Collectors.groupingBy(Break::breakType, Collectors.counting()))
.collect(Collectors.partitioningBy(b -> b.deltaPaise() > 0))
.collect(Collectors.toMap(Break::id, b -> b))       // ⚠️ throws on duplicate keys
.collect(Collectors.summingLong(Break::deltaPaise))
```

That duplicate-key trap is worth remembering — supply a merge function:

```java
.collect(Collectors.toMap(Break::id, b -> b, (existing, replacement) -> existing))
```

### The rules that keep streams honest

```java
// ❌ side effects in a stream
list.stream().forEach(x -> total += x);       // broken, especially in parallel

// ✅ reduce to a value
long total = list.stream().mapToLong(X::value).sum();

// ❌ a stream can only be consumed once
var s = list.stream();
s.count();
s.findFirst();        // 💥 IllegalStateException: stream has already been operated upon
```

### parallelStream — usually not

```java
list.parallelStream().map(this::expensive).toList();
```

Only worth it when: the dataset is large, the per-element work is genuinely
expensive, the operation is stateless and side-effect free, and the source
splits well (an `ArrayList` does; a `LinkedList` does not). It uses the shared
common `ForkJoinPool`, so a blocking operation inside one can stall unrelated
parts of your application.

> **When not to use streams at all:** ReconPilot's ingestion processes a million
> rows in constant memory by streaming lines and flushing JDBC batches. A
> `.collect(toList())` there would hold a million objects on the heap. Streams
> are for *transforming*, not for *accumulating everything*.

---

## 15. Optional

```java
Optional<Break> found = repo.findById(id);

found.map(Break::deltaPaise)                 // transform if present
     .filter(d -> d > 0)
     .orElse(0L);                            // default

found.orElseThrow(() -> new NotFoundException(id));
found.ifPresentOrElse(this::handle, this::handleMissing);
```

### Rules

```java
// ❌ this is just a null check with extra steps
if (opt.isPresent()) { use(opt.get()); }

// ✅
opt.ifPresent(this::use);

// ❌ never a field or a parameter
class Break { Optional<String> note; }        // not serialisable, pointless
void process(Optional<String> maybe) { }      // just overload the method

// ✅ Optional is for RETURN TYPES: "this may legitimately find nothing"
Optional<Break> findById(UUID id);

// ❌ eagerly builds the default even when present
opt.orElse(expensiveDefault());

// ✅ lazy
opt.orElseGet(() -> expensiveDefault());
```

> **The intent:** `Optional` exists to make "might be absent" part of the
> *return type*, so the caller cannot forget. It is not a general-purpose null
> replacement.

---

## 16. Modern Java: records, sealed, pattern matching

### Records (Java 16+)

```java
public record MdrInput(long amountPaise, TxnType txnType, PaymentRail rail) {

    // Compact constructor: validate, or normalise, before the fields are set
    public MdrInput {
        if (amountPaise < 0) {
            throw new IllegalArgumentException("must not be negative: " + amountPaise);
        }
    }

    // You can add behaviour
    public boolean isExempt() { return txnType == TxnType.P2P; }
}
```

You get: a canonical constructor, accessors (`amountPaise()`, not
`getAmountPaise()`), `equals`, `hashCode` and `toString` — all correct.

Records are **implicitly final** and their fields **final**. They are for
*data carriers*, not for entities with identity and lifecycle.

> ⚠️ Records give a *shallow* copy. `record Team(List<String> members)` still
> exposes a mutable list. If it must be immutable, copy defensively in the
> compact constructor — which is exactly what ReconPilot's `FeeSchedule` does
> with `Set.copyOf`, `Map.copyOf`.

### Sealed types (Java 17+)

```java
public sealed interface ReconOutcome
        permits Matched, Overcharged, Undercharged { }

public record Matched(UUID txnId) implements ReconOutcome { }
public record Overcharged(UUID txnId, long deltaPaise) implements ReconOutcome { }
public record Undercharged(UUID txnId, long deltaPaise) implements ReconOutcome { }
```

You control the full set of subtypes, so the compiler can check exhaustiveness.

### Pattern matching for switch (Java 21)

```java
String describe(ReconOutcome o) {
    return switch (o) {
        case Matched m            -> "correct";
        case Overcharged c when c.deltaPaise() > 30_000 -> "large overcharge";
        case Overcharged c        -> "overcharged by " + c.deltaPaise();
        case Undercharged u       -> "undercharged";
        // no default needed — sealed, so the compiler knows this is exhaustive
    };
}
```

**This combination — sealed + records + switch patterns — is how modern Java
models a closed set of outcomes.** Add a fourth outcome and every switch that
does not handle it stops compiling. That is the compiler enforcing your domain
model, and it is a genuinely strong thing to demonstrate in an interview.

### instanceof pattern (Java 16+)

```java
// old
if (o instanceof String) { String s = (String) o; use(s); }

// new
if (o instanceof String s) { use(s); }
if (!(o instanceof Point p)) return false;     // works with negation too
```

---

## 17. Immutability

### Making a class immutable

1. `final` class (no subclass can add mutability)
2. All fields `private final`
3. No setters
4. **Defensive copy on the way in and on the way out** for mutable fields

```java
public final class Schedule {
    private final List<String> steps;

    public Schedule(List<String> steps) {
        this.steps = List.copyOf(steps);      // copy IN — caller can't mutate ours
    }

    public List<String> steps() {
        return steps;                          // already immutable, safe to return
    }
}
```

Without the copy:

```java
var input = new ArrayList<>(List.of("a"));
var s = new Schedule(input);
input.add("b");           // ☠️ the "immutable" Schedule just changed
```

### Why it is worth the effort

- **Thread-safe with no synchronisation.** Nothing can change, so there is
  nothing to coordinate.
- **Safe as a map key** — the hash cannot drift.
- **Easier to reason about.** A value that cannot change cannot be changed by
  code you have not read.

`List.of`, `Map.of`, `Set.of`, `List.copyOf` all return genuinely immutable
collections — they throw `UnsupportedOperationException` on modification.
`Collections.unmodifiableList` only wraps: the *underlying* list can still be
changed through the original reference.

---

## 18. Numbers and money

### Floating point cannot represent decimals

```java
0.1 + 0.2 == 0.3        // false
0.1 + 0.2               // 0.30000000000000004
```

Binary floating point cannot represent 0.1 exactly, for the same reason decimal
cannot represent 1/3. Over a million rows the error accumulates and two systems
computing the same total will disagree.

### The two correct options

```java
// 1. Integer minor units — what ReconPilot uses everywhere
long amountPaise = 133_846_00L;        // Rs 1,33,846.00
// exact, fast, and a BIGINT column in the database

// 2. BigDecimal — when you need fractional precision and explicit rounding
BigDecimal rate = new BigDecimal("0.004");           // ⚠️ the STRING constructor
long fee = BigDecimal.valueOf(amountPaise)
        .multiply(rate)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();                            // throws rather than truncating
```

```java
new BigDecimal(0.1)        // 0.1000000000000000055511151231257827 — the double's error
new BigDecimal("0.1")      // exactly 0.1  ✅
```

**Always construct `BigDecimal` from a `String`.**

And compare with `compareTo`, not `equals`:

```java
new BigDecimal("1.0").equals(new BigDecimal("1.00"));      // false — scale differs
new BigDecimal("1.0").compareTo(new BigDecimal("1.00"));   // 0 — equal in value ✅
```

### Rounding is a business decision

ReconPilot pins `RoundingMode.HALF_UP` in one place with a comment marking it
**provisional**, because no regulator published the rule. That single unknown
paise produced **8,174 false breaks** in a million-row run.

| Mode | 2.5 → | -2.5 → |
|---|---|---|
| `HALF_UP` | 3 | -3 |
| `HALF_DOWN` | 2 | -2 |
| `HALF_EVEN` (banker's) | 2 | -2 |
| `CEILING` | 3 | -2 |
| `FLOOR` | 2 | -3 |

**`HALF_EVEN` is the financial default** in many systems because it has no
systematic upward bias across many roundings. Python's built-in `round()` uses
it, which is exactly how a Python test generator once disagreed with this Java
code by one paise.

### Integer overflow is silent

```java
int max = Integer.MAX_VALUE;
max + 1;                        // -2147483648 — wraps silently ☠️
Math.addExact(max, 1);          // throws ArithmeticException ✅
```

Use `Math.addExact` / `multiplyExact` where a wrong number would be worse than
a crash. Money is exactly that case.

---

## 19. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `equals` without `hashCode` | Object vanishes from `HashSet`/`HashMap` |
| 2 | Mutable map key | Hash changes; entry is unreachable |
| 3 | `==` on strings | Compares references; works by accident via the pool |
| 4 | `+=` on String in a loop | O(n²); use `StringBuilder` |
| 5 | Removing during for-each | `ConcurrentModificationException`; use `removeIf` |
| 6 | `t.run()` instead of `t.start()` | Runs on the same thread; no concurrency |
| 7 | `volatile` for a counter | Visibility only; `count++` is still a race |
| 8 | `newFixedThreadPool` | Unbounded queue → OOM instead of backpressure |
| 9 | `return` inside `finally` | Swallows the exception |
| 10 | Catching `Exception` and ignoring | Hides failures forever |
| 11 | `double` for money | Use `long` minor units or `BigDecimal` |
| 12 | `new BigDecimal(0.1)` | Carries the double's error; use the String form |
| 13 | `BigDecimal.equals` | Compares scale too; use `compareTo` |
| 14 | Silent integer overflow | `Math.addExact` where it matters |
| 15 | `Optional` as a field or parameter | It is for return types |
| 16 | `opt.orElse(expensive())` | Always evaluated; use `orElseGet` |
| 17 | Reusing a consumed stream | `IllegalStateException` |
| 18 | `Collectors.toMap` with duplicates | Throws; supply a merge function |
| 19 | `parallelStream` by default | Shared `ForkJoinPool`; rarely faster |
| 20 | `HashMap` across threads | Undefined; use `ConcurrentHashMap` |
| 21 | `get` then `put` on a concurrent map | Check-then-act race; use `computeIfAbsent` |
| 22 | `ThreadLocal` in a pooled thread | Leaks into the next request — a data breach |
| 23 | `System.gc()` | A suggestion, not a command |
| 24 | Records are deeply immutable | They are shallow; copy defensively |
| 25 | Pooling virtual threads | Defeats the point; create one per task |

---

## What to do next

1. **Draw a `HashMap`** on paper — buckets, a collision chain, the treeify
   threshold. If you can draw it you can explain it.
2. **Write the deadlock** in a scratch file, run it, watch it hang, then fix it
   by ordering the locks. Ten minutes, and you will never forget the four
   conditions.
3. **Open `MarketplaceFeeCalculator`** in this repo and find the `BigDecimal`
   usage, the `longValueExact`, and the pinned rounding mode. Every rule in
   section 18 is in that one file.

Next: `04-java-interview-questions.md`.
