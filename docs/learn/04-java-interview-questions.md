# Java interview questions

**60 questions with full answers**, pitched at a candidate with two years of
experience interviewing for a role that pays well. The bar at that level is not
"do you know the syntax" — it is "do you know what the JVM is doing, and have
you been burned by it."

**How to use this:** cover the answer, say yours out loud, then compare. Reading
fluently and speaking fluently under pressure are different skills, and only the
second one is tested.

Legend: 🟢 screening · 🟡 standard · 🔴 separator

---

## Contents

- [Language fundamentals](#language-fundamentals) (1–8)
- [OOP](#oop) (9–16)
- [equals, hashCode and objects](#equals-hashcode-and-objects) (17–21)
- [Strings](#strings) (22–26)
- [Collections](#collections) (27–36)
- [Generics](#generics) (37–40)
- [Exceptions](#exceptions) (41–45)
- [Concurrency](#concurrency) (46–55)
- [JVM and memory](#jvm-and-memory) (56–60)
- [Modern Java](#modern-java) (61–66)
- [Code-writing questions](#code-writing-questions)

---

## Language fundamentals

### 1. 🟢 Is Java compiled or interpreted?

**Both.** `javac` compiles source into platform-neutral **bytecode** ahead of
time. At runtime the JVM interprets that bytecode, profiles which methods run
hot, and the **JIT compiler** compiles those to native machine code.

The JIT has information a static compiler never has — real branch frequencies,
the actual types arriving at each call site — so it can inline aggressively and
speculate. That is why a long-running JVM can beat naive expectations, and why
any benchmark needs a **warm-up phase**.

---

### 2. 🟢 JDK, JRE, JVM?

- **JVM** — the engine that executes bytecode. Platform-specific.
- **JRE** — JVM + standard library. Enough to *run* a program.
- **JDK** — JRE + `javac`, debugger, tools. Enough to *build* one.

"Write once, run anywhere" works because the portability lives in the JVM, not
in your code.

---

### 3. 🟢 Is Java pass-by-value or pass-by-reference?

**Always pass-by-value.** The confusion is that for objects, *the value being
passed is the reference*.

```java
void mutate(List<String> list) { list.add("x"); }   // caller SEES this
void reassign(List<String> list) { list = new ArrayList<>(); }  // caller does NOT
```

You get a copy of the reference. Following it reaches the same object, so
mutation is visible. Reassigning the local copy changes nothing for the caller.

---

### 4. 🟢 `==` vs `.equals()`?

`==` compares **references** for objects (and values for primitives).
`.equals()` compares **content**, if the class overrides it — `Object`'s default
is identity.

```java
new String("a") == new String("a")        // false
new String("a").equals(new String("a"))  // true
Integer.valueOf(127) == Integer.valueOf(127)   // true  — cached
Integer.valueOf(128) == Integer.valueOf(128)   // false — outside the cache
```

That last pair is a favourite: Java caches boxed `Integer` values from **-128 to
127**, so `==` accidentally works in that range and silently stops at 128.

---

### 5. 🟡 `final`, `finally`, `finalize`?

- **`final`** — a variable cannot be reassigned, a method cannot be overridden,
  a class cannot be extended.
- **`finally`** — a block that runs whether or not an exception was thrown.
- **`finalize`** — a deprecated `Object` method the GC *might* have called before
  collection. **Deprecated since Java 9, removed in 18.** Never use it; use
  `try-with-resources` or a `Cleaner`.

---

### 6. 🟡 `static` — what does it actually mean?

The member belongs to the **class**, not an instance. One copy, shared by
everything, initialised when the class is loaded.

The consequence that matters: **a `static` mutable field is global mutable
state, shared across every thread and every request in a web application.**
Treat every one with suspicion.

---

### 7. 🟡 Can you override a `static` method?

**No.** You can *hide* it by declaring one with the same signature in a
subclass, but the call is resolved at **compile time** by the declared type, not
at runtime by the object.

```java
Parent p = new Child();
p.staticMethod();     // calls Parent's — no polymorphism
```

---

### 8. 🔴 What is the difference between `int` and `Integer`, and when does it bite?

`int` is a primitive — 4 bytes on the stack, cannot be null.
`Integer` is an object — heap-allocated, nullable, cached in [-128, 127].

Where it bites:

```java
Map<String, Integer> counts = new HashMap<>();
int n = counts.get("missing");     // NullPointerException on unboxing 💥
```

Also: autoboxing in a loop creates an object per iteration. `long sum` versus
`Long sum` over a million additions is a million allocations.

---

## OOP

### 9. 🟢 The four pillars?

**Encapsulation** (hide state, expose behaviour), **Inheritance** (reuse and
specialise), **Polymorphism** (one interface, many implementations, resolved at
runtime), **Abstraction** (expose what, hide how).

The useful follow-up is *why* encapsulation matters: you can change internals
without breaking callers.

---

### 10. 🟢 Overloading vs overriding?

**Overloading** — same name, different parameters, resolved at **compile time**
by declared types. **Overriding** — same signature in a subclass, resolved at
**runtime** by the actual object.

Always write `@Override`: it is a compile-time check that you actually overrode
something rather than silently creating an overload when a signature drifts.

---

### 11. 🟡 Abstract class vs interface — since Java 8, what's left?

Default methods gave interfaces behaviour, so the remaining differences are:

- **State.** An abstract class can hold instance fields; an interface can only
  hold `static final` constants.
- **Construction.** An abstract class has a constructor and can enforce
  invariants at creation.
- **Multiple inheritance of type.** A class implements many interfaces but
  extends one class.

Default methods solved **API evolution** — adding a method without breaking
every implementer — not shared state.

---

### 12. 🟡 Why "composition over inheritance"?

Inheritance couples you to a superclass's implementation, and every change there
ripples down. It also forces an "is-a" relationship that is often not true —
`Stack extends Vector` means a stack exposes `insertElementAt`.

**The test:** if you override a method to throw `UnsupportedOperationException`,
the relationship was wrong. Liskov substitution says every subclass must be
usable wherever the parent is.

---

### 13. 🟡 What is the diamond problem and how does Java handle it?

Two interfaces provide the same default method; a class implements both. Java
**refuses to compile** until you resolve it explicitly:

```java
class C implements A, B {
    public void hello() { A.super.hello(); }     // choose
}
```

Java avoids the classical diamond entirely by forbidding multiple *class*
inheritance.

---

### 14. 🔴 What is the Liskov Substitution Principle, with a real example?

**Any subclass must be usable wherever the parent is, without the caller
knowing.**

The classic violation: `Square extends Rectangle`. A rectangle lets you set
width and height independently; a square cannot. Code that does
`r.setWidth(5); r.setHeight(4); assert area == 20` breaks when handed a square.

Mathematically a square *is* a rectangle. Behaviourally it is not substitutable
— which is why "is-a" is about behaviour, not taxonomy.

---

### 15. 🟡 What are the SOLID principles?

- **S**ingle responsibility — one reason to change.
- **O**pen/closed — open to extension, closed to modification.
- **L**iskov substitution — subclasses are substitutable.
- **I**nterface segregation — many small interfaces beat one fat one.
- **D**ependency inversion — depend on abstractions, not concretions.

Dependency inversion is what Spring's DI implements: your service depends on an
interface, and the container supplies the implementation.

---

### 16. 🔴 How would you make a class immutable?

1. Mark the class `final`, so nobody adds mutability by subclassing.
2. All fields `private final`.
3. No setters.
4. **Defensive copies** on the way in *and* out for any mutable field.

```java
public final class Schedule {
    private final List<String> steps;
    public Schedule(List<String> steps) { this.steps = List.copyOf(steps); }
    public List<String> steps() { return steps; }     // already immutable
}
```

Without the copy, the caller keeps a reference to the list you stored and can
change your "immutable" object afterwards.

**Records give you most of this — but shallowly.** A `record Team(List<String>
members)` still exposes a mutable list unless you copy in the compact
constructor.

---

## equals, hashCode and objects

### 17. 🟢 What is the equals/hashCode contract?

1. If `a.equals(b)`, then `a.hashCode() == b.hashCode()`. **Mandatory.**
2. Equal hash codes do **not** imply equality — that is a legal collision.
3. `equals` must be reflexive, symmetric, transitive, consistent, and
   `x.equals(null)` must be false.

---

### 18. 🟡 What happens if you override `equals` but not `hashCode`?

```java
var set = new HashSet<Point>();
set.add(new Point(1, 1));
set.contains(new Point(1, 1));     // false 😱
```

`HashSet` looks in the bucket given by `hashCode()`. Two equal objects with
different hash codes land in **different buckets**, so the lookup never reaches
`equals`. The object is in the set and cannot be found.

---

### 19. 🟡 Why must map keys be immutable?

```java
var key = new ArrayList<String>();
map.put(key, "value");
key.add("mutated");          // hashCode changed
map.get(key);                // null — it is in the wrong bucket, forever
```

The entry was filed under the old hash. Nothing re-files it.

---

### 20. 🟡 What does `Objects.hash()` do, and is it fast?

It boxes the arguments into an array and computes a combined hash. Convenient
and correct, but it **allocates** — for a hot-path class you would hand-roll:

```java
int result = 31 * Long.hashCode(amountPaise) + txnType.hashCode();
```

31 is used because it is an odd prime and `31 * i` optimises to `(i << 5) - i`.

---

### 21. 🔴 When would you *not* use a record for a value object?

When you need:
- **Deep immutability** of mutable components (records copy shallowly).
- **Lazy or cached derived fields** — records have no instance fields beyond
  their components.
- **Inheritance** — records are implicitly final.
- **A different `equals` semantic** — for example, identity by a single id field
  while carrying other data.

For everything else, a record is the right default: the constructor, accessors,
`equals`, `hashCode` and `toString` are all generated correctly.

---

## Strings

### 22. 🟢 Why is `String` immutable?

Safe to share across threads without synchronisation; safe to cache the hash
code (which is why it is a good map key); safe to intern in a pool; and safe to
pass to untrusted code, which matters for file paths, class names and SQL.

---

### 23. 🟡 Explain the string pool.

String literals are interned in a pool, so identical literals share one object.
`new String("x")` deliberately creates a separate heap object.

```java
String a = "recon", b = "recon", c = new String("recon");
a == b            // true
a == c            // false
a == c.intern()   // true
```

This is why `==` on strings *sometimes* works — the worst kind of bug, because
it is intermittent.

---

### 24. 🟡 `String` vs `StringBuilder` vs `StringBuffer`?

- `String` — immutable; concatenation creates a new object.
- `StringBuilder` — mutable, **not** thread-safe, fast. The default.
- `StringBuffer` — mutable, synchronised, slower. Effectively legacy.

```java
String s = "";
for (String p : parts) s += p;      // O(n²) — copies the whole string each time
```

The compiler converts simple `a + b` into `StringBuilder`, but **not across loop
iterations** — each iteration builds a fresh one.

---

### 25. 🔴 What does `intern()` do, and why be careful?

It returns the pooled instance for that content, adding it if absent. Since Java
7 the pool lives on the heap rather than in PermGen.

Be careful because interning user-supplied strings in bulk grows the pool
indefinitely, and pooled strings behave differently from ordinary ones in
identity comparisons — which encourages exactly the `==` bug you want to avoid.

---

### 26. 🟡 How do you compare strings ignoring case, and what's the trap?

`equalsIgnoreCase`, or `toLowerCase(Locale.ROOT)`.

**The trap is the locale.** In Turkish, `"I".toLowerCase()` is `"ı"`, not `"i"`.
Code that lowercases an identifier with the default locale breaks on a Turkish
machine — a genuine, famous production bug. Always pass an explicit `Locale` for
non-display text.

---

## Collections

### 27. 🟢 `ArrayList` vs `LinkedList` — and which do you actually use?

`ArrayList` is a resizable array: **O(1)** indexed access, O(n) insert at the
front. `LinkedList` is doubly-linked: O(1) insert at either end, O(n) access.

**In practice, `ArrayList` almost always.** `LinkedList`'s theoretical advantage
is destroyed by cache locality — an array is one contiguous block the CPU
prefetches, while a linked list chases pointers around the heap. Even middle
insertions often favour `ArrayList`, because `System.arraycopy` is extremely
fast. If you need both ends, use `ArrayDeque`.

---

### 28. 🔴 Explain `HashMap` internals.

An array of buckets whose length is always a **power of two**.

On `put`:
1. `h = key.hashCode()`
2. **Spread:** `h ^= (h >>> 16)` — mixes high bits into low bits, because only
   the low bits are used for the index.
3. `index = h & (n - 1)` — a bitmask instead of a modulo; correct **only**
   because `n` is a power of two.
4. Empty bucket → place it. Otherwise walk the chain comparing with `equals`.
5. If a bucket's chain reaches **8** entries *and* the table has at least **64**
   buckets, that bucket becomes a **red-black tree** — O(n) worst case becomes
   O(log n). It reverts below 6.
6. When `size > capacity × 0.75`, resize: double and rehash.

Resizing is cheap because with a doubled capacity an entry either stays at its
index or moves to `index + oldCapacity`, decided by a single bit.

---

### 29. 🔴 Why was treeification added?

To blunt **hash-collision denial of service**. An attacker who can submit keys
that all hash to one bucket turns every lookup into an O(n) chain walk. Trees
bound that at O(log n).

---

### 30. 🟡 What happens if you use a `HashMap` from multiple threads?

Undefined behaviour — lost updates at minimum.

In **Java 7**, a concurrent resize could create a **cycle in a bucket chain**,
making a later `get` spin at 100% CPU forever. Java 8 changed resize to preserve
order, which makes that specific infinite loop much harder to hit — but
`HashMap` is still not thread-safe. Use `ConcurrentHashMap`.

---

### 31. 🔴 How does `ConcurrentHashMap` achieve thread safety without one big lock?

It locks **per bucket**, using CAS for empty buckets and synchronising on the
bucket head otherwise. Threads writing to different buckets never contend.
**Reads are lock-free.**

(Java 7 used fixed "segments"; Java 8 replaced that with per-bucket locking,
which is finer-grained.)

Its compound operations are atomic:

```java
map.computeIfAbsent(k, this::load);
map.merge(k, 1, Integer::sum);
```

A `get` followed by a `put` is **not** atomic — that check-then-act gap is a
real race.

---

### 32. 🟡 `HashMap` vs `Hashtable` vs `Collections.synchronizedMap` vs `ConcurrentHashMap`?

| | Thread-safe | Locking | Nulls |
|---|---|---|---|
| `HashMap` | ❌ | — | one null key, many null values |
| `Hashtable` | ✅ | one lock, legacy | none |
| `synchronizedMap` | ✅ | one lock on every method | as the wrapped map |
| `ConcurrentHashMap` | ✅ | **per bucket** | none |

`ConcurrentHashMap` forbids nulls deliberately: `get` returning null would be
ambiguous between "absent" and "present but null", which cannot be resolved
atomically.

---

### 33. 🟡 What is `ConcurrentModificationException` and when does it occur?

Thrown when a collection is **structurally modified while being iterated**.
Collections keep a `modCount`; the iterator remembers it and throws on
mismatch.

Despite the name it usually has nothing to do with threads — it is
single-threaded modification during a for-each. Use `removeIf` or an explicit
`Iterator.remove()`.

---

### 34. 🟡 When would you use a `TreeMap` over a `HashMap`?

When you need **ordering or range queries**. `TreeMap` is a red-black tree,
O(log n), sorted by key, and implements `NavigableMap`:

```java
slabs.ceilingEntry(price);   // the first slab whose ceiling is >= price
slabs.floorEntry(price);
slabs.headMap(k), tailMap(k), subMap(a, b);
```

**Any "price band", "rate slab", "tier" or "nearest match" problem is a
`TreeMap` problem** — ReconPilot uses exactly this for marketplace closing-fee
slabs, and it turns a loop into an O(log n) lookup.

---

### 35. 🟡 How do you build an LRU cache in Java?

`LinkedHashMap` with access order and an eviction hook:

```java
new LinkedHashMap<K, V>(16, 0.75f, true) {          // true = access order
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) {
        return size() > capacity;
    }
};
```

Not thread-safe — wrap it, or use Caffeine in production.

---

### 36. 🟡 `Comparable` vs `Comparator`?

`Comparable` is the type's **natural order**, implemented by the class itself
(`compareTo`). `Comparator` is an **external** ordering, so you can have many.

```java
list.sort(Comparator.comparingLong(Break::deltaPaise).reversed()
                    .thenComparing(Break::externalTxnId));
```

**The contract matters:** a comparator must be transitive and consistent, or
`TimSort` throws "Comparison method violates its general contract!" — which is
the JDK catching *your* bug, not a JDK bug.

---

## Generics

### 37. 🟡 What is type erasure?

Generics exist only at **compile time**. The compiler checks types, then erases
them and inserts casts. At runtime `List<String>` and `List<Integer>` are both
just `List`.

```java
new ArrayList<String>().getClass() == new ArrayList<Integer>().getClass()   // true
```

**Why:** backward compatibility. Generics arrived in Java 5, and erasure let
generic and pre-generic code run on the same JVM.

---

### 38. 🟡 What can't you do because of erasure?

```java
T[] a = new T[10];                      // ❌ cannot create a generic array
if (o instanceof List<String>) {}       // ❌ cannot test a type argument
T t = new T();                          // ❌ cannot instantiate a type parameter
void f(List<String> s) {}
void f(List<Integer> i) {}              // ❌ same signature after erasure
catch (MyException<String> e) {}        // ❌ generic exceptions
```

---

### 39. 🔴 Explain PECS.

**Producer Extends, Consumer Super.**

```java
static <T> void copy(List<? extends T> src, List<? super T> dst) { ... }
```

`? extends T` — you can **read** `T` out, but cannot write, because the actual
list might be of a narrower subtype. `? super T` — you can **write** `T` in, but
reads only give you `Object`.

---

### 40. 🔴 What is a bounded type parameter and why use one?

```java
static <T extends Comparable<T>> T max(List<T> list) { ... }
```

It constrains what `T` can be, so you can call methods on it. Without the bound
you could only use `Object` methods.

Also allows multiple bounds: `<T extends Number & Comparable<T>>`.

---

## Exceptions

### 41. 🟢 Checked vs unchecked?

**Checked** (`Exception`, not `RuntimeException`) — the compiler forces you to
catch or declare. Intended for recoverable conditions.
**Unchecked** (`RuntimeException`, `Error`) — no ceremony. Intended for
programming errors.

---

### 42. 🟡 Which do you use for your own exceptions?

**Unchecked, by default.** Checked exceptions force every intermediate caller to
declare or wrap them, which pollutes signatures and pressures people into
catching and swallowing. I use checked only where the caller has a genuine
recovery path they would otherwise forget.

**Spring's entire data-access hierarchy is unchecked** for exactly this reason —
`SQLException` became `DataAccessException`.

---

### 43. 🟡 What does try-with-resources do that `finally` didn't?

It closes resources in **reverse order**, automatically, and — critically —
handles the case where **both the body and `close()` throw**. The close
exception is attached as **suppressed** rather than replacing the real one.

```java
try (var in = Files.newInputStream(p); var out = Files.newOutputStream(q)) {
    in.transferTo(out);
}
```

Retrieve suppressed exceptions with `e.getSuppressed()`.

---

### 44. 🟡 What happens if you `return` from a `finally` block?

It **discards** any in-flight exception and any earlier return value.

```java
try { return compute(); }      // throws
finally { return fallback(); } // the exception vanishes silently ☠️
```

Never return, break or throw from `finally`.

---

### 45. 🔴 What's wrong with `catch (Exception e) { log.error(e); }`?

Several things:

1. It catches things you cannot handle, including `RuntimeException`s that
   indicate bugs.
2. Logging and continuing leaves the program in an unknown state.
3. It loses the stack trace if logged as `log.error(e)` rather than
   `log.error("context", e)`.
4. It swallows `InterruptedException`, breaking cancellation — if you catch
   that, **restore the flag**: `Thread.currentThread().interrupt()`.

Catch what you can act on; let the rest reach a boundary that knows what to do.

---

## Concurrency

### 46. 🟢 `start()` vs `run()`?

`start()` asks the JVM to create a **new thread** which then calls `run()`.
Calling `run()` directly is an ordinary method call on the current thread — no
concurrency at all.

---

### 47. 🟡 What exactly does `volatile` guarantee?

**Visibility and ordering. Not atomicity.**

A volatile write *happens-before* every subsequent volatile read of that field,
which prevents both caching in a register and reordering across it.

It does **not** make compound operations atomic:

```java
volatile int i;
i++;            // still a race — read, add, write
```

The correct use is a flag one thread sets and others read:

```java
private volatile boolean shutdown = false;
while (!shutdown) { ... }      // without volatile this may never terminate
```

---

### 48. 🟡 `synchronized` — what are the two guarantees?

1. **Mutual exclusion** — one thread at a time holds the monitor.
2. **Visibility** — unlocking happens-before the next lock of the same monitor,
   so changes made inside are visible to the next holder.

`synchronized` on an instance method locks `this`; on a `static` method it locks
the `Class` object. **Those are different locks**, which surprises people.

---

### 49. 🔴 Explain happens-before.

The Java Memory Model's ordering guarantee: if X *happens-before* Y, X's effects
are visible to Y.

The edges worth naming:
1. Program order within a thread.
2. Monitor unlock → subsequent lock of the same monitor.
3. Volatile write → subsequent volatile read of that field.
4. `Thread.start()` → everything in that thread.
5. Everything in a thread → a successful `join()`.
6. Correctly-constructed `final` fields are visible without synchronisation.

Without such an edge, the compiler, JIT and CPU may reorder freely, and a write
may sit in a core's store buffer indefinitely.

---

### 50. 🔴 What is a deadlock and how do you prevent it?

Four conditions must hold simultaneously: mutual exclusion, hold-and-wait, no
preemption, circular wait. Break any one.

**In practice: acquire locks in a consistent global order.** That eliminates
circular wait and is the simplest fix. Also: `tryLock(timeout)` so a thread
gives up, shorter critical sections, and immutable data so no lock is needed.

Related: **livelock** (threads keep reacting to each other, no progress) and
**starvation** (a thread never gets the lock).

---

### 51. 🔴 Why doesn't `Executors.newFixedThreadPool` scale safely?

It uses an **unbounded `LinkedBlockingQueue`**. Under overload it queues
indefinitely until you get an `OutOfMemoryError` — the failure is a heap crash
rather than backpressure.

For anything real, construct a `ThreadPoolExecutor` directly with a **bounded
queue** and a rejection policy, so overload produces a visible 503 rather than a
dead process.

---

### 52. 🔴 You set `maxPoolSize=50` but never see more than 10 threads. Why?

**`ThreadPoolExecutor` fills the queue before it grows past `corePoolSize`.**

```
core=10, queue=1000, max=50
tasks 1–10      → run on the core threads
tasks 11–1010   → QUEUED — the pool does not grow
task 1011       → now an 11th thread starts
```

With a large queue, `maxPoolSize` is effectively unreachable. Total capacity is
`queueCapacity + maxPoolSize`.

---

### 53. 🟡 `Callable` vs `Runnable`?

`Runnable.run()` returns void and cannot throw a checked exception.
`Callable.call()` returns a value and can throw. Submit a `Callable` to an
`ExecutorService` and you get a `Future`.

---

### 54. 🟡 `thenApply` vs `thenCompose` on `CompletableFuture`?

`thenApply` is **map** — the function returns a value.
`thenCompose` is **flatMap** — the function returns another `CompletableFuture`,
and compose flattens it so you do not get `CompletableFuture<CompletableFuture<T>>`.

Also: **always pass your own executor.** The default is the shared common
`ForkJoinPool`, sized to your CPU count, where one blocking task starves
everything else in the process.

---

### 55. 🔴 What are virtual threads and when do they help?

Java 21. Lightweight threads managed by the JVM rather than the OS. A platform
thread costs ~1 MB of stack and a few thousand is the ceiling; virtual threads
number in the millions. When one blocks on I/O, the JVM **unmounts** it from its
carrier platform thread, so the carrier is never idle.

**They help with I/O-bound work** — most web request handling. You keep the
simple blocking style and get reactive-like scalability.

**They do not help CPU-bound work** — there are still only so many cores. **Do
not pool them** — they are cheap to create, and pooling defeats the purpose.
Early versions could **pin** a virtual thread to its carrier inside
`synchronized`, so `ReentrantLock` was preferred in hot paths.

---

## JVM and memory

### 56. 🟢 Stack vs heap?

**Stack** — one per thread; holds method frames, local primitives and
*references*; freed automatically on return.
**Heap** — shared by all threads; holds every object and array; garbage
collected.

The sentence to remember: *the stack holds the reference, the heap holds the
object.*

---

### 57. 🟡 How does garbage collection decide what to free?

**Reachability** from GC roots — live thread stacks, static fields, JNI
references. Anything unreachable is collectable. Reference counting is not used,
because it cannot reclaim cycles.

**The generational hypothesis** — most objects die young — is why the heap is
split into Eden, two survivor spaces and an old generation. Minor GCs are
frequent and cheap; full GCs are rare and cause the pause you notice.

**G1** is the default since Java 9: region-based, targeting a configurable pause
goal. ZGC and Shenandoah give sub-millisecond pauses on very large heaps.

---

### 58. 🟡 Can you have a memory leak in Java?

Yes — whenever something stays **reachable** that shouldn't be:

1. A `static` collection that only grows.
2. Unclosed resources holding native handles.
3. Listeners registered and never removed.
4. **A `ThreadLocal` in a pooled thread** — the thread is reused, so the value
   outlives the request.

That last one is the production-grade answer, and it is worse than a leak: in a
web application a `ThreadLocal` left set can be read by the **next user's
request**. ReconPilot clears its tenant `ThreadLocal` explicitly for exactly
this reason.

---

### 59. 🔴 How do you size the JVM heap in a container?

**Never hard-code `-Xmx`.** Modern JVMs read the cgroup limit, so use a
percentage:

```
-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError
```

Leave 25–30% headroom: metaspace, thread stacks, the JIT code cache and direct
byte buffers all live **outside** the heap. Set the heap to the whole container
and the kernel OOM-killer terminates the process with **no stack trace and no
heap dump** — just exit code 137.

`ExitOnOutOfMemoryError` makes the process die rather than limp along serving
errors while passing its healthcheck.

---

### 60. 🟡 `OutOfMemoryError` vs `StackOverflowError`?

`OutOfMemoryError: Java heap space` — the heap is full of reachable objects.
`StackOverflowError` — a thread's stack is exhausted, almost always infinite
recursion.

Neither should be caught. Both are `Error`, not `Exception`.

---

## Modern Java

### 61. 🟢 What do you get from a record?

A canonical constructor, accessors named after the components (`amountPaise()`,
not `getAmountPaise()`), and correct `equals`, `hashCode` and `toString`.
Records are implicitly `final` with `final` fields.

Validation goes in the compact constructor:

```java
public record MdrInput(long amountPaise) {
    public MdrInput {
        if (amountPaise < 0) throw new IllegalArgumentException();
    }
}
```

---

### 62. 🟡 What are sealed types for?

```java
public sealed interface ReconOutcome permits Matched, Overcharged, Undercharged {}
```

They let you close a type hierarchy, so the compiler knows the full set of
subtypes and can check a `switch` for **exhaustiveness**.

Combined with records and pattern matching, adding a fourth outcome makes every
switch that does not handle it **stop compiling** — the compiler enforcing your
domain model.

---

### 63. 🟡 What does `Optional` exist for, and how is it misused?

It makes "might be absent" part of the **return type**, so callers cannot
forget.

Misuses: as a **field** (not serialisable, pointless), as a **parameter** (just
overload the method), `isPresent()` + `get()` (a null check with extra steps),
and `orElse(expensive())` where `orElseGet` should be used — `orElse` evaluates
its argument even when the value is present.

---

### 64. 🟡 Streams: what is lazy, and when does work happen?

Intermediate operations (`map`, `filter`, `sorted`, `limit`) build a pipeline
and do nothing. **Work starts at the terminal operation** (`collect`, `forEach`,
`reduce`, `findFirst`, `count`).

A stream can be consumed **once**; reusing it throws `IllegalStateException`.

---

### 65. 🔴 When would you use `parallelStream()` — and when not?

Only when: the dataset is large, the per-element work is genuinely expensive,
the operation is stateless and side-effect free, and the source splits well (an
`ArrayList` does; a `LinkedList` does not).

**Why usually not:** it uses the shared common `ForkJoinPool`, so a blocking
operation inside one can stall unrelated parts of the application. And most
pipelines are memory-bound, where parallelism adds coordination cost for no
gain. Measure.

---

### 66. 🔴 How do you store money in Java, and why?

**Integer minor units** — `long` paise — or `BigDecimal` when fractional
precision is genuinely needed. **Never `float` or `double`**: binary floating
point cannot represent 0.1 exactly, so errors accumulate over millions of rows
and two systems computing the same total disagree.

With `BigDecimal`:
- Construct from a **String**: `new BigDecimal(0.1)` carries the double's error.
- Compare with `compareTo`, not `equals` — `equals` compares scale, so
  `1.0` ≠ `1.00`.
- Choose the rounding mode **explicitly**, and treat it as a business decision.

> A real example worth telling: in this project a Python test generator
> truncated where the Java engine rounds `HALF_UP`. **One paise of disagreement
> produced 8,174 false breaks across a million rows.**

---

## Code-writing questions

These get asked at a whiteboard or in a shared editor. Practise typing them
without an IDE.

### A. Reverse a string without a library method

```java
static String reverse(String s) {
    char[] c = s.toCharArray();
    for (int i = 0, j = c.length - 1; i < j; i++, j--) {
        char t = c[i]; c[i] = c[j]; c[j] = t;
    }
    return new String(c);
}
```

### B. Find the first non-repeating character

```java
static char firstUnique(String s) {
    Map<Character, Integer> counts = new LinkedHashMap<>();   // insertion order
    for (char c : s.toCharArray()) counts.merge(c, 1, Integer::sum);
    return counts.entrySet().stream()
            .filter(e -> e.getValue() == 1)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("all characters repeat"));
}
```

`LinkedHashMap` is the point — a `HashMap` loses the ordering the question
requires.

### C. Group and sum with streams

```java
Map<BreakType, Long> recoverableByType = breaks.stream()
        .filter(b -> b.deltaPaise() > 0)
        .collect(Collectors.groupingBy(
                Break::breakType,
                Collectors.summingLong(Break::deltaPaise)));
```

### D. Find duplicates in a list

```java
static <T> Set<T> duplicates(List<T> list) {
    Set<T> seen = new HashSet<>();
    return list.stream().filter(x -> !seen.add(x)).collect(Collectors.toSet());
}
```

`Set.add` returns false when the element is already present — neat, and worth
explaining rather than leaving as a trick.

### E. A thread-safe counter, three ways

```java
// 1. synchronized
private int count;
synchronized void inc() { count++; }

// 2. atomic — lock-free CAS, usually fastest under moderate contention
private final AtomicLong count = new AtomicLong();
void inc() { count.incrementAndGet(); }

// 3. high contention — striped, faster than AtomicLong when many threads write
private final LongAdder count = new LongAdder();
void inc() { count.increment(); }
```

Knowing **`LongAdder`** exists, and that it trades a slightly slower read for
much faster concurrent writes, is a genuine differentiator.

### F. Implement a simple LRU cache

```java
class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;
    LruCache(int capacity) {
        super(16, 0.75f, true);        // accessOrder = true
        this.capacity = capacity;
    }
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;
    }
}
```

### G. Check if two strings are anagrams

```java
static boolean anagrams(String a, String b) {
    if (a.length() != b.length()) return false;      // cheap early exit
    int[] counts = new int[256];
    for (int i = 0; i < a.length(); i++) { counts[a.charAt(i)]++; counts[b.charAt(i)]--; }
    for (int c : counts) if (c != 0) return false;
    return true;
}
```

O(n) time, O(1) space. Sorting both strings is O(n log n) — say so, then give
this.

---

## How to prepare from here

1. **Answer out loud, timed.** Two minutes per question. Rambling is the most
   common reason a correct answer scores badly.
2. **For every 🔴, have a story.** "We hit this" beats "the documentation says"
   every time — and you genuinely have stories for the HashMap contract, the
   thread pool, rounding, and `ThreadLocal`.
3. **Say the trade-off.** Nearly every answer above improves if you finish with
   "which costs you X."

Next: `02-spring-boot-interview-questions.md`.
