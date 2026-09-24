# Spring Boot interview questions

**60 questions with full answers.** Same format as the Java set: cover the
answer, say yours out loud, compare.

At two years, Spring questions usually start easy and then probe one thing
deeply — almost always **proxies**, **transactions**, or **auto-configuration**.
Those three sections are where the interview is decided.

Legend: 🟢 screening · 🟡 standard · 🔴 separator

---

## Contents

- [Core Spring and DI](#core-spring-and-di) (1–12)
- [Auto-configuration and Boot](#auto-configuration-and-boot) (13–19)
- [Configuration and profiles](#configuration-and-profiles) (20–24)
- [Proxies and AOP](#proxies-and-aop) (25–30)
- [Transactions](#transactions) (31–38)
- [The web layer](#the-web-layer) (39–46)
- [Data access](#data-access) (47–52)
- [Security](#security) (53–57)
- [Async, Kafka and scheduling](#async-kafka-and-scheduling) (58–62)
- [Testing and operations](#testing-and-operations) (63–68)
- [Architecture](#architecture) (69–72)

---

## Core Spring and DI

### 1. 🟢 What is Spring, in one sentence?

A container that constructs your objects and wires them together, so your
classes declare what they need rather than building it. Everything else — Boot,
Data, Security — is built on that.

---

### 2. 🟢 IoC and DI — what's the difference?

**IoC** is the *principle*: the framework controls object creation and lifecycle
instead of your code. **DI** is the *implementation* of that principle:
dependencies are pushed in from outside rather than constructed inside.

The practical payoff is that business logic never names a concrete collaborator,
so it is testable and swappable.

---

### 3. 🟢 `@Component`, `@Service`, `@Repository` — any real difference?

`@Service` and `@Repository` are meta-annotated with `@Component`, so for
scanning they are identical. The difference is **intent**, which matters for
readability and for AOP pointcuts that target a stereotype.

**`@Repository` is the real exception:** it activates
`PersistenceExceptionTranslationPostProcessor`, which converts driver-specific
`SQLException`s into Spring's `DataAccessException` hierarchy. That is why you
can catch `DuplicateKeyException` instead of parsing PostgreSQL error code
23505.

---

### 4. 🟡 Constructor, field, or setter injection — and why?

**Constructor**, for four concrete reasons:

1. The object cannot exist in a half-built state — a missing dependency means
   it is never created.
2. Fields can be `final`, which is compile-time proof nothing reassigns them —
   and singletons are shared across threads.
3. It is testable with plain `new`, no reflection and no container.
4. **It makes bad design visible.** A nine-parameter constructor looks wrong;
   nine `@Autowired` fields look tidy while hiding the same problem.

Since Spring 4.3, a class with exactly one constructor needs no `@Autowired`.

---

### 5. 🟢 What is a bean?

An object the Spring container created and manages. If Spring made it, it is a
bean; if you wrote `new`, it is not.

---

### 6. 🟡 What bean scopes exist, and which one bites people?

`singleton` (default), `prototype`, and the web scopes `request`, `session`,
`application`, `websocket`.

**`singleton` is the one that bites.** Your `@Service` beans are shared by every
thread handling every request. **A mutable field on a singleton is a race
condition** that only appears under load. State belongs in parameters, or in a
`ThreadLocal` if it must cross many layers.

---

### 7. 🟡 How do you resolve two beans of the same type?

`@Primary` on the default one, `@Qualifier("name")` at the injection point, or
matching the parameter name to the bean name (fragile — renaming breaks it).

ReconPilot uses `@Primary` *and* `@Qualifier`, because it runs two datasources:
an application connection as a least-privilege role so row-level security
applies, and an admin connection as the owner for migrations.

---

### 8. 🔴 What happens if you have a circular dependency?

Spring Boot 2.6+ **fails at startup** with `BeanCurrentlyInCreationException`
rather than tolerating it.

That is a gift. A cycle means either two classes want to be one, or a third
class is missing. `@Lazy` breaks it and `spring.main.allow-circular-references`
re-enables the old behaviour, but both are plasters over a design problem.

---

### 9. 🟡 What does `@SpringBootApplication` actually do?

Three annotations:

- `@Configuration` — the class may declare `@Bean` methods
- `@EnableAutoConfiguration` — turns on Boot's conditional configuration
- `@ComponentScan` — scans **this package and downward only**

That last point is a common bug: a bean in a sibling package is never found, and
the error says "no qualifying bean" rather than "I didn't look there."

---

### 10. 🟡 `@Bean` vs `@Component` — when do you need `@Bean`?

`@Component` is for classes you own and can annotate. `@Bean` in a
`@Configuration` class is for **third-party classes** you cannot annotate, or
when construction needs logic.

```java
@Bean
MdrCalculator mdrCalculator() { return new MdrCalculator(); }
```

ReconPilot does exactly this so the rules engine stays annotation-free and
testable without a context.

---

### 11. 🔴 What is a `BeanPostProcessor`?

A hook that runs **for every bean**, before and after initialisation, and can
wrap or replace it. It is the mechanism behind AOP proxies:
`AnnotationAwareAspectJAutoProxyCreator` is a `BeanPostProcessor` that returns a
proxy in place of your object.

`BeanFactoryPostProcessor` is different — it runs earlier, on bean *definitions*
rather than instances. `PropertySourcesPlaceholderConfigurer` (which resolves
`${...}`) is one.

---

### 12. 🟡 What is the bean lifecycle?

```
definition loaded → instantiated → dependencies injected
→ *Aware interfaces → BeanPostProcessor.before
→ @PostConstruct → afterPropertiesSet() → custom init
→ BeanPostProcessor.after  [proxy created here]
→ ... in use ...
→ @PreDestroy → destroy()
```

The place this matters is **`@PostConstruct` versus `ApplicationRunner`** — see
question 68.

---

## Auto-configuration and Boot

### 13. 🔴 How does auto-configuration actually work?

1. `@EnableAutoConfiguration` triggers `AutoConfigurationImportSelector`.
2. It reads every JAR's
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   (in Boot 2.x this was `spring.factories`).
3. That lists hundreds of candidate configuration classes.
4. Each is gated by `@Conditional` annotations; only those whose conditions pass
   are applied.
5. **Your beans are registered first**, so `@ConditionalOnMissingBean` lets
   auto-configuration step aside where you have taken over.

**Auto-configuration is a large pile of `if` statements over the classpath and
your bean definitions.** Once you say that, the magic disappears.

---

### 14. 🟡 Name the conditional annotations.

`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnBean`,
`@ConditionalOnProperty`, `@ConditionalOnWebApplication`,
`@ConditionalOnResource`, `@ConditionalOnExpression`.

---

### 15. 🔴 Tell me about a time auto-configuration surprised you.

> "We added a second `JdbcTemplate` bean for an admin datasource. Spring Boot's
> auto-configured `JdbcTemplate` is guarded by `@ConditionalOnMissingBean`, so
> it **backed off** — and every query in the application silently started using
> the privileged admin connection. PostgreSQL row-level security doesn't apply
> to the table owner, so multi-tenant isolation was disabled by a bean
> declaration. No error, tests still passed.
>
> The fix was to declare both templates explicitly and mark the application one
> `@Primary`. The lesson I took is that 'auto-configuration backs off' can mean
> 'silently turns off a security control', so I now look at what I am displacing
> whenever I define a bean Boot would otherwise provide."

This is the single best Spring story to have ready.

---

### 16. 🟡 How do you exclude an auto-configuration?

```java
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
```

or `spring.autoconfigure.exclude=...` in properties. Real use: a batch job that
must not open a datasource.

---

### 17. 🟡 How do you debug why a bean isn't there?

Run with `--debug`. Boot prints a **conditions evaluation report** with positive
and negative matches **and the reason** for each. The "Negative matches" section
answers the question faster than any amount of reading.

---

### 18. 🟡 What is a starter?

A curated dependency aggregator — `spring-boot-starter-web` pulls in Spring MVC,
Jackson, validation and embedded Tomcat with versions that are known to work
together. It brings no code of its own.

Version alignment comes from `spring-boot-dependencies`, which is why you
usually omit `<version>` from Spring dependencies.

---

### 19. 🔴 How would you write your own starter?

Create an autoconfiguration class with `@Conditional` guards, register it in
`META-INF/spring/...AutoConfiguration.imports`, expose settings with
`@ConfigurationProperties`, and always guard beans with
`@ConditionalOnMissingBean` so consumers can override them.

---

## Configuration and profiles

### 20. 🟡 What is the property precedence order?

Later wins: JAR `application.properties` → profile-specific files → OS
environment variables → Java system properties → command-line arguments. (Plus
`@TestPropertySource` and `spring-boot-devtools` in their own places.)

---

### 21. 🟡 `@Value` or `@ConfigurationProperties`?

`@Value` for one or two values. `@ConfigurationProperties` for a related group —
it gives type safety, IDE completion, **relaxed binding** (`staging-dir`,
`stagingDir` and `STAGING_DIR` all bind to the same field), and JSR-380
validation **at startup**, so bad configuration fails fast.

---

### 22. 🟡 How do you manage secrets?

Never in a properties file in the repository. Read from the environment, with a
harmless default only so local development works:

```properties
reconpilot.jwt.secret=${RECONPILOT_JWT_SECRET:local-development-value}
```

And then **verify it**. ReconPilot has a `StartupSecretCheck` that refuses to
boot if any credential is still a development default — configuration alone is
not a control, because configuration can be wrong.

---

### 23. 🟡 How do profiles work?

`spring.profiles.active=prod` selects `application-prod.properties` and
activates `@Profile("prod")` beans. `@Profile("!prod")` is everything else.

---

### 24. 🔴 How do you configure the same app for laptop and container without profiles?

Environment variables with defaults, as in question 22 — **one file, two
environments**. The default makes `mvn spring-boot:run` work with no setup; the
environment variable makes the container correct. No profile files to keep in
sync and none to accidentally commit a password into.

---

## Proxies and AOP

### 25. 🔴 Why doesn't `@Transactional` work when I call the method from the same class?

Because Spring's declarative features are **proxy-based**. Your bean is wrapped
by a proxy that intercepts calls arriving **from outside**. An internal call is
a plain `this.method()` — it never leaves the object, so the proxy never sees
it.

The analogy: a receptionist logs every visitor, but once you are inside the
office, walking between rooms does not pass reception again.

**The same applies to `@Async`, `@Cacheable`, `@Retryable` and
`@PreAuthorize`** — and there is no error. The behaviour is simply absent.

**Fixes:** move the annotated method to another bean (best), self-inject with
`@Lazy`, or `AopContext.currentProxy()` with `exposeProxy = true`.

---

### 26. 🟡 Why doesn't `@Transactional` work on a private method?

A proxy can only intercept methods that are externally dispatched and
overridable. A JDK proxy implements an interface; a CGLIB proxy subclasses the
target. A `private` method is neither implemented nor overridable, so the call
never passes through the proxy.

The same reasoning covers `final` methods and classes (CGLIB cannot subclass
them) and `static` methods (not dispatched through an instance).

---

### 27. 🟡 JDK dynamic proxy vs CGLIB?

**JDK** — used when the bean implements an interface; the proxy implements the
same interface. **CGLIB** — no interface, so the proxy is a generated
**subclass**, which is why `final` breaks it.

Spring Boot defaults to CGLIB for everything
(`spring.aop.proxy-target-class=true`) because it behaves consistently whether
or not an interface exists.

---

### 28. 🟡 What are the AOP terms?

**Aspect** (the cross-cutting module), **Join point** (a point where advice can
apply — in Spring, always a method execution), **Pointcut** (an expression
selecting join points), **Advice** (the code: `@Before`, `@After`,
`@AfterReturning`, `@AfterThrowing`, `@Around`), **Weaving** (applying it — at
runtime, via proxies, in Spring).

---

### 29. 🟡 Write an aspect that logs slow methods.

```java
@Aspect
@Component
public class SlowCallLogger {

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object timed(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 500) log.warn("{} took {} ms", pjp.getSignature(), ms);
        }
    }
}
```

---

### 30. 🔴 What is the performance cost of AOP?

A proxy adds one indirection and, for CGLIB, a generated subclass per bean —
negligible for I/O-bound work and measurable in a tight loop. The bigger cost is
**debuggability**: stack traces gain proxy frames and behaviour depends on the
call path, which is exactly why self-invocation surprises people.

---

## Transactions

### 31. 🟢 What does `@Transactional` do?

Wraps the method in a database transaction: begin before, commit on normal
return, roll back on a runtime exception.

---

### 32. 🔴 Does it roll back on a checked exception?

**No, not by default.** Spring rolls back on `RuntimeException` and `Error`
only, on the reasoning that a checked exception is part of the declared contract
and may be an expected outcome.

```java
@Transactional(rollbackFor = Exception.class)
```

---

### 33. 🟡 Explain the propagation levels.

`REQUIRED` (default — join or start), `REQUIRES_NEW` (always a new one, suspend
the caller's), `MANDATORY` (must already be in one), `SUPPORTS` (join if
present), `NOT_SUPPORTED` (suspend), `NEVER` (throw if present), `NESTED` (a
savepoint inside the caller's).

`REQUIRES_NEW` is for audit records that must survive the caller rolling back.

---

### 34. 🟡 What are the isolation levels and the anomalies?

`READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`,
preventing progressively: dirty reads, non-repeatable reads, phantom reads.

**PostgreSQL and Oracle default to `READ_COMMITTED`; MySQL/InnoDB to
`REPEATABLE_READ`.** PostgreSQL never permits dirty reads at all, and its
`REPEATABLE_READ` also prevents phantoms because it is snapshot-based.

---

### 35. 🟡 What does `readOnly = true` actually do?

Hibernate skips dirty-checking, which is a real saving on large result sets; the
driver may set the connection read-only; and some routers use the flag to send
the query to a read replica. It is not merely documentation.

---

### 36. 🔴 How do you handle a transaction spanning two datasources?

You do not, safely, without a distributed transaction coordinator (JTA/XA),
which is heavy and widely avoided.

The practical answers are: **make it one datasource**, use the **outbox
pattern** (write intent to the same database, publish asynchronously), or make
the second operation **idempotent and retried**.

> ReconPilot has exactly this problem — a database write plus a Kafka publish —
> and records it honestly as a known dual-write defect rather than pretending
> `@Transactional` covers it.

---

### 37. 🔴 Give an example where you avoided `@Transactional` deliberately.

> "Registration inserted a tenant and then a user. With no transaction, a
> duplicate email violated the unique index *after* the tenant row had already
> committed — so every duplicate registration left an orphaned tenant, and
> nothing reported it.
>
> `@Transactional` would have fixed it, but that code runs on our admin
> datasource, which has no transaction manager — adding one was a lot of
> machinery for two rows. Instead I made it a single statement with a CTE:
>
> ```sql
> WITH new_tenant AS (INSERT INTO tenant ... RETURNING id)
> INSERT INTO app_user ... SELECT ... FROM new_tenant;
> ```
>
> A single statement in PostgreSQL is atomic by itself, so the tenant insert is
> undone if the user insert fails. And I changed the duplicate response from 500
> to 409, because 'that address is taken' is a fact about the request, not a
> server failure."

---

### 38. 🟡 What is the transaction manager?

`PlatformTransactionManager` — the abstraction Spring drives.
`DataSourceTransactionManager` for JDBC, `JpaTransactionManager` for JPA,
`KafkaTransactionManager` for Kafka. Boot auto-configures one. **With two
datasources you get two, and `@Transactional` must name the right one.**

---

## The web layer

### 39. 🟡 Walk me through a request.

```
Tomcat thread → Filter chain (Security) → DispatcherServlet
  → HandlerMapping    : which handler matches?
  → HandlerAdapter    : invoke it
      → ArgumentResolvers  build @RequestBody / @PathVariable / @RequestParam
  → HttpMessageConverter : serialise the return value (Jackson)
  → response written
```

Naming `DispatcherServlet`, `HandlerMapping` and `HttpMessageConverter` in order
is a reliable signal.

---

### 40. 🟢 `@Controller` vs `@RestController`?

`@RestController` = `@Controller` + `@ResponseBody`. The former returns view
names for server-rendered templates; the latter returns the object, serialised.

---

### 41. 🟡 `@RequestParam` vs `@PathVariable` vs `@RequestBody`?

Query string (`?limit=50`), path segment (`/breaks/{id}`), and request body
(JSON) respectively.

---

### 42. 🔴 When do you return 202, and why?

When the request is **valid and scheduled but not done**.

> "Our upload accepts files up to 2 GB. Parsing inside the request would hold an
> HTTP connection open for a minute and hit every proxy timeout between browser
> and server — and a retry would re-upload two gigabytes. So the endpoint stages
> the file, publishes a message and returns **202 in about half a second**, with
> a `Location` header pointing at where the outcome will appear. The client
> polls that."

---

### 43. 🟡 How do you handle exceptions globally?

`@RestControllerAdvice` with `@ExceptionHandler` methods, returning
**`ProblemDetail`** (RFC 7807, built into Spring 6).

Order matters: specific handlers first, `Exception.class` last. **Log the
detail, return something bland** — a default error response can leak internal
package names, which tells an attacker your framework and structure.

---

### 44. 🟡 How does validation work?

Bean Validation annotations on the DTO, plus **`@Valid` on the parameter** —
without which the annotations are inert, which is a very common bug. Failures
arrive as `MethodArgumentNotValidException`, handled centrally.

`@Validated` is Spring's variant, adding validation **groups** and enabling
method-level validation on a class.

---

### 45. 🟡 What is CORS and how did you handle it?

A browser rule: a page from origin A may not read a response from origin B
unless B opts in with headers.

> "I didn't have to handle it. In development Vite proxies `/api` to Spring; in
> production nginx proxies the same path to the backend container. The browser
> only ever sees one origin, so there is no cross-origin request to permit. CORS
> is needed when you *choose* to serve the frontend from a different origin —
> it is a deployment decision, not an inevitability."

---

### 46. 🔴 How do you stream a large file without exhausting the heap?

Never buffer it. `spring.servlet.multipart.file-size-threshold=0` forces Spring
to spool every upload straight to disk rather than keeping small ones in memory.
Then read with a `BufferedReader` and flush JDBC batches as you go, so memory is
constant regardless of file size.

> "Collecting rows into a list first put a million objects on the heap — the
> difference between working and dying at 251 MB of a 256 MB limit."

---

## Data access

### 47. 🟡 JdbcTemplate or JPA?

Per use case, not per project. **JdbcTemplate** where I need to know exactly
what SQL runs — bulk ingestion, reporting, complex queries — and no persistence
context accumulating entities. **JPA** for CRUD over an object graph, where it
saves real work.

---

### 48. 🔴 What is the N+1 problem and how do you fix it?

One query fetches N rows, then accessing a lazy association issues one query per
row — 1 + N total.

Fixes: `JOIN FETCH` in JPQL, `@EntityGraph`, `@BatchSize` (turning N queries
into N/batchSize), or a projection that fetches only what is needed.

**Detect it** by enabling SQL logging and checking whether the query count
scales with the row count.

---

### 49. 🟡 What does `spring.jpa.hibernate.ddl-auto` do, and what should it be?

`none`, `validate`, `update`, `create`, `create-drop`.

**`validate`, with Flyway owning the schema.** `update` causes silent schema
drift — columns get added, nothing gets removed, and two environments diverge
without anyone noticing.

---

### 50. 🟡 Why use Flyway, and what is the rule everyone breaks?

Versioned, ordered, exactly-once migrations with a checksum per file, so every
environment provably ran the same statements.

**The rule: never edit an applied migration.** The checksum mismatch stops
startup. Fix forward with a new version.

> "V6 hard-coded a role password. I couldn't edit it, so V8 set it from a Flyway
> placeholder bound to an environment variable."

---

### 51. 🟡 How does HikariCP sizing work?

Bigger is not better — each connection is a backend process in PostgreSQL. A
common starting point is `(cores × 2) + effective_spindles`. A pool of 10
serving 200 concurrent requests is usually correct: requests queue briefly
rather than overwhelming the database.

Also set `max-lifetime` below any database or proxy idle timeout, or you will
hand out connections the server has already closed.

---

### 52. 🔴 What is the danger of connection pooling with session state?

A pooled connection is **reused**, and anything set on it — a session variable,
an open transaction, a temp table — persists for the next borrower.

> "We set a PostgreSQL session variable per connection to carry the current
> tenant, so row-level security can filter on it. That is only safe because we
> wrap the pool and set it on checkout *and clear it on close*. Double defence,
> because a leaked tenant id means one customer sees another's data."

---

## Security

### 53. 🟡 How does Spring Security work?

A chain of **servlet filters** running before the `DispatcherServlet`. Each
filter can authenticate, authorise, or translate exceptions. Authentication
lands in the `SecurityContext` (a `ThreadLocal`); `AuthorizationFilter` then
applies the rules.

---

### 54. 🟡 Authentication vs authorization?

*Who are you* versus *what may you do*. Two filters, two failure codes: **401**
for unauthenticated, **403** for authenticated but not permitted.

---

### 55. 🟡 Why BCrypt and not SHA-256?

BCrypt is **deliberately slow** and **adaptive** (a configurable work factor),
with a per-password salt built into the output. SHA-256 is fast, which is
exactly wrong for passwords — fast means an attacker can try billions per
second.

---

### 56. 🔴 What are the trade-offs of JWT?

**For:** stateless validation — no session store, no sticky sessions, any
instance can serve any request.

**Against:** a JWT **cannot be revoked** before it expires; the payload is
signed but **not encrypted**, so never put secrets in it; and it is larger than
a session id on every request.

**Logout with JWT:** you can't, really. Delete it client-side, keep expiry
short, and add a refresh token that *is* stored server-side and can be revoked.
Immediate revocation needs a denylist, which reintroduces the lookup you were
avoiding.

---

### 57. 🔴 Tell me about a security bug you found.

> "Two, both about controls that existed and did nothing.
>
> First, row-level security was enabled and completely inert. Three things all
> had to be true: `FORCE ROW LEVEL SECURITY`, because the table owner is exempt
> without it; connecting as a **non-superuser**, because superusers bypass RLS
> entirely; and `WITH CHECK` as well as `USING`, because otherwise reads are
> filtered while writes are not — a tenant could insert a row belonging to
> someone else. We had one of the three.
>
> Second, a downstream 500 was being reported as a 401, because the error
> dispatch re-enters the security filter chain after the context has been
> cleared. Fixed with `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`.
>
> What I took from both: a security control nobody has watched *refuse* is a
> hypothesis, not a control. We now have a test that connects as the
> least-privileged role and asserts another tenant's rows are invisible."

---

## Async, Kafka and scheduling

### 58. 🟡 How does `@Async` work and what breaks it?

`@EnableAsync` installs a proxy that submits the call to a `TaskExecutor` and
returns immediately (or a `CompletableFuture`).

It does nothing if: `@EnableAsync` is missing, the method is called from the
same class, or the method is `private`/`final`. And **always supply your own
executor** — the default `SimpleAsyncTaskExecutor` creates a new thread per
task, without limit.

---

### 59. 🔴 Explain your thread pool configuration.

```java
core = 4; max = 8; queueCapacity = 10; AbortPolicy
```

> "The counter-intuitive part is that `ThreadPoolExecutor` **fills the queue
> before growing past the core size**, so total capacity is `queue + max` and a
> large queue makes `maxPoolSize` effectively unreachable. We chose a small
> bounded queue with `AbortPolicy` so overload surfaces as a **503** the caller
> can retry. An unbounded queue converts overload into an `OutOfMemoryError`,
> which is a worse failure and a harder one to diagnose. Measured: capacity 14,
> sixteen concurrent uploads, 14 accepted and 2 rejected."

---

### 60. 🔴 What is the single most important Kafka consumer setting?

```properties
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=record
```

> "With auto-commit on — which is Kafka's **default** — offsets are committed on
> a five-second timer whether or not the message was processed. Crash in that
> window and the message is gone: never processed, never redelivered, no
> exception, no log line, no metric. Turning it off makes Spring commit only
> after the listener returns normally, which is what makes
> redelivery-on-crash work — and that was the entire reason we moved to Kafka."

---

### 61. 🟡 How do you make a consumer idempotent?

Because at-least-once delivery redelivers on crash, processing twice must equal
processing once. In the database:

```sql
INSERT ... ON CONFLICT (tenant_id, batch_id, external_txn_id) DO UPDATE ...
```

A unique key plus an upsert, in **one atomic statement** — never check-then-act,
which has a race window that concurrency will find.

---

### 62. 🟡 How does `@Scheduled` behave with multiple instances?

**It runs on every instance**, which is usually not what you want. Options: a
leader election, a database lock (`pg_advisory_lock`), ShedLock, or moving the
job to an external scheduler. Proxy rules apply here too.

---

## Testing and operations

### 63. 🟡 What test slices exist and why use them?

`@WebMvcTest`, `@DataJpaTest`, `@JdbcTest`, `@JsonTest` — each loads only the
relevant part of the context, which dominates test time. `@SpringBootTest` loads
everything and should be used sparingly.

---

### 64. 🔴 Why does my test suite start Spring twenty times?

Spring **caches the context across test classes, keyed on the configuration**.
Any difference — a property, a profile, a `@MockBean` — produces a new key and
another slow startup.

**Sharing one abstract base class keeps the cache working**, which is why we
have a single `AbstractIntegrationTest` and the PostgreSQL container starts once
for the whole suite.

---

### 65. 🔴 Testcontainers or H2?

Testcontainers. **H2 is a different database** — no row-level security, no
`ON CONFLICT ... RETURNING`, no `JSONB`, different locking. A test that passes
against H2 proves your code works against H2.

> "And one warning from experience: `@ServiceConnection` only overrides
> `spring.datasource.*`. Our second datasource still pointed at localhost, so a
> `TRUNCATE` in test setup destroyed a million rows in the real development
> database. We fixed the wiring *and* added a guard that refuses to run unless
> the connection URL is the container's — because a destructive operation
> should verify what it is about to destroy rather than trust the configuration."

---

### 66. 🟡 Why `mvn verify` rather than `mvn test`?

Surefire runs `*Test` in the `test` phase; Failsafe runs `*IT` in `verify`.
**`mvn test` silently runs none of your integration tests and reports success.**
This project skipped 36 of them for an entire session that way.

---

### 67. 🟡 What does Actuator give you, and what's the risk?

`/health`, `/info`, `/metrics`, `/prometheus`, `/loggers`, `/env`, plus custom
`HealthIndicator`s.

**The risk is exposure.** `include=*` on a public port hands out configuration,
beans and heap dumps. Expose the minimum — we expose `health,info` and use
`/actuator/health` for the container healthcheck.

---

### 68. 🔴 `@PostConstruct` or `ApplicationRunner` for a startup check?

**`@PostConstruct`, if the check must prevent startup.**

> "We had a guard that refuses to boot with development secrets, written as an
> `ApplicationRunner`. The logs showed `Started BackendApplication in 2.727
> seconds` and *then* the exception — Tomcat had already bound its port and was
> accepting requests. A guard that fires after the door is open is not a guard.
> `@PostConstruct` runs during context refresh, so the failure aborts it and the
> server never listens. We verified it by the **absence** of the 'Tomcat
> started' line."

---

## Architecture

### 69. 🔴 Monolith or microservices for your project — defend it.

> "A modular monolith, deliberately. We measured roughly 0.4 writes per second
> with million-row bursts — that is a *correctness* problem, not a *scale*
> problem. Microservices would have bought independent deployment we don't need
> and cost us distributed transactions, network failure modes, and tracing
> across services, for one team.
>
> The modules have clean boundaries — ingestion, reconciliation, disputes — so
> if one genuinely needs separate scaling it can be extracted. **Starting as a
> monolith and splitting later is far easier than the reverse.**"

---

### 70. 🟡 How do you make an API idempotent?

A client-supplied or natural idempotency key, a **unique constraint in the
database**, and one atomic upsert that returns the stored result on a repeat.

The critical part is that uniqueness is enforced by the database, not by an
application lookup — otherwise two concurrent retries both see "not found" and
both act.

---

### 71. 🟡 How would you add caching, and what would you not cache?

`@EnableCaching` with `@Cacheable`, backed by Redis, with a TTL and jitter to
avoid stampedes.

**Not cached:** anything where stale means *wrong* rather than *old* — balances,
permission checks, computed fees. We cache format mappings keyed on a SHA-256 of
the header row, because a hit there is still *correct*.

---

### 72. 🔴 What would you do differently if you rebuilt this?

> "Three things. I'd put the outbox pattern in from the start rather than
> accepting a dual write between the database and Kafka. I'd enforce roles with
> method security from day one — we store roles and turn them into authorities
> but nothing checks them, which was harmless until we published demo
> credentials. And I'd have set up CI before writing the second feature: our
> first CI run found a bug no local test could have found, because every laptop
> database had been migrated days earlier."

**Having a real answer to this question is worth more than most of the technical
ones.** It shows you evaluate your own work.

---

## How to prepare from here

1. **Master three sections**: proxies (25–30), transactions (31–38), and
   auto-configuration (13–19). Interviews concentrate there.
2. **Learn four stories cold**: the bean that disabled RLS, the startup guard
   that fired too late, the auto-commit setting, and the test suite that
   truncated production. Each one answers several questions.
3. **Always finish with the trade-off.** "This buys X and costs Y" is what turns
   a correct answer into a senior one.

Next: PostgreSQL, React/JavaScript, and system-design question sets.
