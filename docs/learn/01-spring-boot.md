# Spring Boot, properly

**Who this is for:** you have written Spring Boot code, it works, and you could
not currently explain to an interviewer *why* it works. Two years in, that is
exactly the gap that gets probed.

**How to use it:** every concept is explained in plain language first, with an
analogy, then the mechanism, then code you could type from memory. Most
examples are from ReconPilot, so you can open the real file and see it in
place.

---

## Table of contents

1. [What Spring actually is](#1-what-spring-actually-is)
2. [Beans and the container](#2-beans-and-the-container)
3. [Dependency injection](#3-dependency-injection)
4. [Auto-configuration: the magic, demystified](#4-auto-configuration-the-magic-demystified)
5. [Configuration and profiles](#5-configuration-and-profiles)
6. [Proxies — and the bug everyone hits](#6-proxies--and-the-bug-everyone-hits)
7. [Transactions](#7-transactions)
8. [The web layer](#8-the-web-layer)
9. [Error handling](#9-error-handling)
10. [Validation](#10-validation)
11. [Data access](#11-data-access)
12. [Database migrations with Flyway](#12-database-migrations-with-flyway)
13. [Connection pooling](#13-connection-pooling)
14. [Spring Security](#14-spring-security)
15. [Async work and thread pools](#15-async-work-and-thread-pools)
16. [Kafka](#16-kafka)
17. [Caching and Redis](#17-caching-and-redis)
18. [Actuator](#18-actuator)
19. [Testing](#19-testing)
20. [Application startup and lifecycle](#20-application-startup-and-lifecycle)
21. [The traps, collected](#21-the-traps-collected)

---

## 1. What Spring actually is

### The one-sentence answer

**Spring is a factory that builds your objects for you and wires them together,
so your classes can ask for what they need instead of constructing it.**

That is it. Everything else — Boot, Data, Security, Kafka — is built on that
one idea.

### The analogy

Imagine a restaurant kitchen.

**Without Spring**, every chef who needs a sauce walks to the store, buys
tomatoes, grows basil, and makes it from scratch. If two chefs need the same
sauce, it gets made twice. If the recipe changes, you edit it in fourteen
places. If you want to test one chef, you have to buy real tomatoes.

```java
class OrderService {
    private final PaymentClient payments = new PaymentClient("https://live.psp.com");
    //                                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                         hard-coded, real, and now impossible to test offline
}
```

**With Spring**, there is a pantry. Someone stocks it once. Chefs say "I need
sauce" and it is handed to them. Swap the sauce for a test version and every
chef gets the test version, with no chef changing.

```java
@Service
class OrderService {
    private final PaymentClient payments;

    OrderService(PaymentClient payments) {   // "I need sauce"
        this.payments = payments;            // Spring hands it over
    }
}
```

### The two terms interviewers want

**Inversion of Control (IoC)** — normally *your* code controls when objects are
created. With Spring, the *framework* controls it and calls you. Control has
been inverted. It is the "don't call us, we'll call you" principle.

**Dependency Injection (DI)** — the specific technique that implements IoC.
Dependencies are pushed *into* your object from outside rather than pulled in
from inside.

> **Interview phrasing that lands:** "IoC is the principle — the framework owns
> object lifecycle instead of my code. DI is the implementation of that
> principle: dependencies arrive through the constructor rather than being
> constructed internally. The practical payoff is that my business logic never
> names a concrete collaborator, so it is testable and swappable."

### Why this matters in practice

In ReconPilot, `MdrCalculator` — the class that decides how much money someone
is owed — has **zero Spring annotations**. Its 23 tests run in under 20
milliseconds because no container needs to start.

That is not an accident. It is the pay-off of DI taken seriously: push
framework concerns to the edges and keep the core plain.

---

## 2. Beans and the container

### What a bean is

A **bean** is just an object that Spring created and is managing for you.
Nothing mystical. If Spring made it, it is a bean. If you wrote `new`, it is
not.

The **ApplicationContext** is the container holding them all. Think of it as
the pantry: a big map of name → object, plus the knowledge of how to build
each one.

### The four ways to declare a bean

```java
// 1. @Component — "Spring, manage this"; the generic form
@Component
class FileFingerprinter { }

// 2. @Service — a @Component. Identical behaviour, different INTENT:
//    "this holds business logic"
@Service
class ReconciliationService { }

// 3. @Repository — also a @Component, but with a bonus: Spring translates
//    vendor-specific SQLExceptions into its own DataAccessException hierarchy
@Repository
class BreakRepository { }

// 4. @Bean inside @Configuration — for objects you do NOT own the source of,
//    or that need construction logic
@Configuration
class DomainConfig {
    @Bean
    MdrCalculator mdrCalculator() {
        return new MdrCalculator();   // a plain class, made available to Spring
    }
}
```

> **Interview question:** *"What's the difference between `@Component`,
> `@Service` and `@Repository`?"*
>
> **Weak answer:** "They're the same."
> **Strong answer:** "`@Service` and `@Repository` are both meta-annotated with
> `@Component`, so for scanning they behave identically. The difference is
> intent, which matters for readability and for AOP pointcuts that target a
> stereotype. `@Repository` is the exception: it activates
> `PersistenceExceptionTranslationPostProcessor`, which converts driver-specific
> `SQLException`s into Spring's `DataAccessException` hierarchy — so my service
> layer catches `DuplicateKeyException` rather than parsing a Postgres error
> code."

That last point is not theoretical. ReconPilot catches exactly that:

```java
try {
    admin.update("INSERT INTO app_user ...");
} catch (DuplicateKeyException e) {          // not SQLException, not error code 23505
    throw new ResponseStatusException(HttpStatus.CONFLICT, "...");
}
```

### Component scanning

`@SpringBootApplication` on your main class is three annotations in one:

```java
@SpringBootApplication
// = @Configuration          this class can declare @Bean methods
// + @EnableAutoConfiguration turn on Boot's auto-config
// + @ComponentScan           scan THIS package and everything below it
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
```

**The trap:** scanning starts at the package of the annotated class and goes
*downward only*. If your main class is in `in.reconpilot` and you put a service
in `com.other.thing`, it will never be found, and the error is a confusing
"no qualifying bean" rather than "I didn't look there."

### Bean scopes

| Scope | Meaning | When |
|---|---|---|
| `singleton` | **Default.** One instance for the whole application | Almost always |
| `prototype` | A new instance every time it is injected or fetched | Stateful helpers |
| `request` | One per HTTP request | Web only, rare |
| `session` | One per HTTP session | Web only, rare |

> **The single most important consequence of `singleton`:** your `@Service`
> beans are shared by every thread handling every request, simultaneously.
> **Never put mutable request state in a field of a singleton bean.** It is a
> race condition that only appears under load.

```java
@Service
class BadService {
    private String currentUser;        // ☠️ shared across ALL requests

    void handle(String user) {
        this.currentUser = user;       // thread B overwrites thread A
        doWork();
    }
}
```

The fix is to pass state as parameters, or — when it must cross many layers —
use a `ThreadLocal`, which is what ReconPilot's `TenantContext` does so the
current tenant follows a request through the call stack without being threaded
through every signature.

---

## 3. Dependency injection

### Three ways, and only one you should use

```java
// ✅ CONSTRUCTOR INJECTION — use this
@Service
class ReconciliationService {
    private final MdrCalculator calculator;   // final: cannot be reassigned
    private final JdbcTemplate jdbc;

    ReconciliationService(MdrCalculator calculator, JdbcTemplate jdbc) {
        this.calculator = calculator;
        this.jdbc = jdbc;
    }
}

// ❌ FIELD INJECTION — avoid
@Service
class BadService {
    @Autowired private MdrCalculator calculator;
}

// ⚠️ SETTER INJECTION — only for genuinely optional dependencies
@Service
class OkService {
    private MetricsClient metrics;

    @Autowired(required = false)
    void setMetrics(MetricsClient metrics) { this.metrics = metrics; }
}
```

### Why constructor injection wins — four concrete reasons

**1. The object cannot exist in a broken state.** With field injection, the
constructor finishes before the fields are populated. With constructor
injection, if a dependency is missing the object is never created at all.

**2. The fields can be `final`.** That is compile-time proof nothing reassigns
them, which matters because singletons are shared across threads.

**3. It is testable without Spring, and without Mockito's reflection tricks:**

```java
// With constructor injection — plain Java
var service = new ReconciliationService(new MdrCalculator(), stubJdbc);

// With field injection you need reflection, or @InjectMocks, or a context
```

**4. It makes bad design visible.** A constructor with nine parameters *looks*
wrong. Nine `@Autowired` fields look tidy while hiding the same problem. The
ugliness is a feature.

> **Since Spring 4.3**, if a class has exactly one constructor you can omit
> `@Autowired` entirely. Spring uses it automatically. That is why ReconPilot's
> constructors carry no annotation.

### Resolving ambiguity

When two beans of the same type exist, Spring cannot guess:

```
NoUniqueBeanDefinitionException: expected single matching bean but found 2
```

Three ways out:

```java
// 1. @Primary — "when in doubt, use this one"
@Bean @Primary
DataSource appDataSource() { ... }

@Bean
DataSource adminDataSource() { ... }

// 2. @Qualifier — name the one you want at the injection point
public AuthController(@Qualifier("adminJdbcTemplate") JdbcTemplate admin) { ... }

// 3. Match the parameter name to the bean name (fragile — renaming breaks it)
public AuthController(JdbcTemplate adminJdbcTemplate) { ... }
```

ReconPilot uses **both** of the first two, because it runs two datasources: an
application connection as a least-privilege role (so PostgreSQL row-level
security applies) and an admin connection as the owner (for migrations and
cross-tenant work).

> **A real bug from this project, and a great interview story.** Declaring an
> `adminJdbcTemplate` bean caused Spring Boot's auto-configured `JdbcTemplate`
> to *back off*, because its auto-configuration is guarded by
> `@ConditionalOnMissingBean`. Every query in the application silently began
> using the privileged connection — and row-level security applies to nobody
> when you connect as the owner. Security was disabled by a bean declaration.
> The fix was to declare **both** templates explicitly and mark the app one
> `@Primary`.
>
> The lesson: *auto-configuration backs off when you define your own bean, and
> "backs off" can mean "silently turns off a security control".*

### Circular dependencies

```java
@Service class A { A(B b) {} }
@Service class B { B(A a) {} }   // 💥 BeanCurrentlyInCreationException
```

Spring Boot 2.6+ **fails at startup** rather than tolerating this. That is a
gift: a circular dependency is a design smell saying two classes want to be
one, or that a third class is missing. `@Lazy` will break the cycle, but
treat that as a last resort, not a fix.

---

## 4. Auto-configuration: the magic, demystified

This is the single most common "senior" question in Spring interviews, and
most candidates answer it vaguely.

### What it does

Put `spring-boot-starter-web` on the classpath, and you get an embedded Tomcat,
a `DispatcherServlet`, JSON converters and sensible defaults — without writing
a line of configuration. That is auto-configuration.

### How it actually works

```
1. @EnableAutoConfiguration (inside @SpringBootApplication) triggers
   AutoConfigurationImportSelector.

2. It reads every JAR's
   META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
   (in Boot 2.x this was spring.factories)

3. That file lists candidate configuration classes — hundreds of them.

4. Each is gated by @Conditional annotations. Only those whose conditions
   pass are applied.

5. Your own beans are registered FIRST, so @ConditionalOnMissingBean lets
   auto-config step aside wherever you have taken over.
```

### The conditions worth knowing by name

```java
@ConditionalOnClass(DataSource.class)        // is this class on the classpath?
@ConditionalOnMissingBean(DataSource.class)  // has the user NOT defined one?
@ConditionalOnProperty(
    name = "reconpilot.ai.format-discovery.enabled",
    havingValue = "true")                    // is this property set?
@ConditionalOnWebApplication                 // is this a web app?
@ConditionalOnBean(KafkaTemplate.class)      // does another bean exist?
```

Read that list once and the magic disappears: **auto-configuration is a big
pile of `if` statements over the classpath and your bean definitions.**

### Writing your own conditional bean

```java
@Configuration
class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "reconpilot.ai.format-discovery.enabled",
                           havingValue = "true")
    FormatDiscoveryService formatDiscovery(@Value("${ANTHROPIC_API_KEY:}") String key) {
        return new FormatDiscoveryService(key);
    }
}
```

### Debugging it

```bash
# Prints every auto-configuration, matched and unmatched, WITH the reason
java -jar app.jar --debug
```

The "Negative matches" section answers "why isn't my bean there?" faster than
any amount of staring.

> **Interview question:** *"How would you exclude an auto-configuration?"*
>
> ```java
> @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
> ```
> or in properties:
> ```properties
> spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
> ```
> Real use: a batch job that must not open a datasource, or a test slice you
> want kept narrow.

---

## 5. Configuration and profiles

### The precedence order

Spring resolves a property from many sources. **Later beats earlier:**

```
1. application.properties inside the JAR
2. application-{profile}.properties
3. OS environment variables
4. Java system properties (-D)
5. Command-line arguments (--server.port=9000)
```

This is why ReconPilot's properties look like this:

```properties
# ${ENV_VAR:default} — take the environment variable, or the default if absent
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/reconpilot}
spring.datasource.username=${DB_APP_USER:reconpilot_app}
reconpilot.jwt.secret=${RECONPILOT_JWT_SECRET:local-development-secret-...}
```

The default makes `mvn spring-boot:run` work on a laptop with no setup. The
environment variable makes the container secure. **One file, two environments,
no profile switching.**

### Reading configuration in code

```java
// 1. @Value — fine for one or two values
@Service
class JwtService {
    private final String secret;

    JwtService(@Value("${reconpilot.jwt.secret}") String secret) {
        this.secret = secret;
    }
}

// 2. @ConfigurationProperties — better for a group; type-safe and validated
@ConfigurationProperties(prefix = "reconpilot.ingest")
@Validated
record IngestProperties(
    @NotBlank String stagingDir,
    @Min(1) int queueCapacity
) {}
```

> **Interview question:** *"`@Value` or `@ConfigurationProperties`?"*
>
> "`@Value` for a single value; `@ConfigurationProperties` for a related group.
> The latter gives type safety, IDE completion, relaxed binding — `staging-dir`,
> `stagingDir` and `STAGING_DIR` all bind to the same field — and JSR-380
> validation at startup, so a bad configuration fails fast instead of at the
> first request."

### Profiles

```properties
# application.properties
spring.profiles.active=dev
```

```java
@Bean
@Profile("!prod")           // everything EXCEPT prod
DataSeeder devSeeder() { ... }

@Component
@Profile("prod")
AlertingNotifier realAlerts() { ... }
```

```bash
java -jar app.jar --spring.profiles.active=prod
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```

> **Secrets never go in a profile file.** A committed
> `application-prod.properties` with a real password is one of the most common
> ways credentials leak. ReconPilot instead reads secrets from the environment
> and has a `StartupSecretCheck` that **refuses to boot** if a development
> default is still in use.

---

## 6. Proxies — and the bug everyone hits

**This is the highest-value section in this document.** It explains a whole
family of bugs and is asked constantly.

### What a proxy is

When you annotate a method with `@Transactional`, `@Async`, `@Cacheable` or
`@PreAuthorize`, Spring does **not** modify your class. It cannot — your
bytecode is already compiled.

Instead it wraps your bean in a **proxy**: a generated object that looks like
your class from the outside, but intercepts calls, does extra work, and then
delegates to the real instance.

**The analogy:** a receptionist sitting in front of your office. Visitors talk
to the receptionist, who logs the visit, checks ID, then shows them in. To the
visitor it looks like they walked straight into your office.

```
caller ──▶ [ Proxy ]  ──▶  [ Your real object ]
             │
             ├─ open a transaction
             ├─ call the real method
             └─ commit or roll back
```

### The bug: self-invocation

```java
@Service
public class ReportService {

    public void processAll(List<Batch> batches) {
        for (Batch b : batches) {
            processOne(b);          // ☠️ internal call — NO transaction
        }
    }

    @Transactional
    public void processOne(Batch b) {
        // ... you think this is transactional. It is not.
    }
}
```

**Why it fails:** the proxy only intercepts calls that arrive *from outside*.
`processOne(b)` inside the same class is a plain `this.processOne(b)` — it
never leaves the object, so the receptionist never sees it.

The visitor analogy: once you are *inside* the office, walking between rooms
does not pass the receptionist again.

**This applies identically to `@Async`, `@Cacheable`, `@Retryable`,
`@PreAuthorize`.** Every one of them silently does nothing on a self-call. No
error. No warning. Just absent behaviour.

### The three fixes

```java
// 1. BEST — move the annotated method into a different bean
@Service
class ReportService {
    private final BatchProcessor processor;      // separate bean = real proxy boundary
    ReportService(BatchProcessor processor) { this.processor = processor; }

    public void processAll(List<Batch> batches) {
        batches.forEach(processor::processOne);  // external call — proxy applies ✅
    }
}

// 2. Self-injection — works, but signals the class is doing too much
@Service
class ReportService {
    @Lazy private final ReportService self;      // @Lazy breaks the cycle
    public void processAll(List<Batch> b) { b.forEach(self::processOne); }
}

// 3. AopContext — works; requires exposeProxy = true and reads badly
@EnableAspectJAutoProxy(exposeProxy = true)
// ((ReportService) AopContext.currentProxy()).processOne(b);
```

### The other proxy rules

| Rule | Why |
|---|---|
| `@Transactional` on **private** methods does nothing | A proxy cannot override a private method |
| `@Transactional` on **final** methods/classes does nothing with CGLIB | A subclass cannot override `final` |
| `@Transactional` on **static** methods does nothing | Not dispatched through an instance |

> **Interview question:** *"Why doesn't `@Transactional` work on a private
> method?"*
>
> "Spring's declarative transactions are proxy-based. A JDK dynamic proxy
> implements the interface, and a CGLIB proxy subclasses the target — in both
> cases the interceptor can only apply to methods that are externally
> dispatched and overridable. A private method is neither, so the call never
> passes through the proxy. The same reasoning explains why self-invocation and
> `final` methods also bypass it."

### JDK proxy vs CGLIB

- **JDK dynamic proxy** — used when the bean implements an interface. The proxy
  implements the same interface.
- **CGLIB** — used when there is no interface. The proxy is a generated
  *subclass*, which is why `final` breaks it.

Spring Boot defaults to CGLIB for everything (`spring.aop.proxy-target-class=true`)
because it is more predictable.

---

## 7. Transactions

### What a transaction guarantees — ACID

| Letter | Means | Plain English |
|---|---|---|
| **A**tomicity | All or nothing | Both inserts happen, or neither does |
| **C**onsistency | Constraints hold | No transaction can leave a broken invariant |
| **I**solation | Concurrent work doesn't interfere | Two transactions cannot half-see each other |
| **D**urability | Committed means permanent | Survives the power going out |

### A real bug from this project

```java
// BEFORE — two statements, no transaction
UUID tenantId = UUID.randomUUID();
admin.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, name);
admin.update("INSERT INTO app_user (...) VALUES (...)", ..., email, ...);
//            ^^^ if this violates the unique index on email, the tenant row
//                has ALREADY been committed — an orphan nobody will ever use
```

Two duplicate registrations left two orphaned tenants, and **nothing reported
it**. No error, no log line, no constraint. The rows just accumulated.

Two possible fixes, and the choice is instructive:

```java
// Option A — @Transactional
@Transactional
public void register(...) { insert tenant; insert user; }

// Option B — one statement, atomic on its own (what ReconPilot uses)
admin.update("""
    WITH new_tenant AS (
        INSERT INTO tenant (id, name) VALUES (?, ?) RETURNING id
    )
    INSERT INTO app_user (id, tenant_id, email, password_hash, role)
    SELECT ?, new_tenant.id, ?, ?, 'ADMIN' FROM new_tenant
    """, ...);
```

Option B was chosen because this application runs **two** datasources, and
`@Transactional` would have needed a second `PlatformTransactionManager` wired
to the admin one — a lot of machinery for two rows. **A single statement in
PostgreSQL is atomic by itself.**

> That is a good interview answer, by the way: knowing `@Transactional` is not
> the only tool, and that a CTE gives you atomicity without a transaction
> manager, reads as someone who understands the database and not just the
> framework.

### Propagation

What happens when a transactional method calls another transactional method?

```java
@Transactional(propagation = Propagation.REQUIRED)     // DEFAULT
// Join the caller's transaction, or start one if there is none.

@Transactional(propagation = Propagation.REQUIRES_NEW)
// ALWAYS start a new one; suspend the caller's.
// Use for audit logs that must survive the caller rolling back.

@Transactional(propagation = Propagation.MANDATORY)
// Must already be in a transaction, else throw. Good for internal helpers.

@Transactional(propagation = Propagation.SUPPORTS)
// Join if there is one, run without if not.

@Transactional(propagation = Propagation.NOT_SUPPORTED)  // suspend any transaction
@Transactional(propagation = Propagation.NEVER)          // throw if one exists
@Transactional(propagation = Propagation.NESTED)         // savepoint within the caller
```

**The classic use of `REQUIRES_NEW`:**

```java
@Service
class OrderService {
    @Transactional
    public void placeOrder(Order o) {
        save(o);
        auditLog.record(o);   // if this is REQUIRES_NEW, the audit entry
        riskCheck(o);         // survives even when riskCheck throws
    }
}
```

### Isolation levels

From weakest to strongest:

| Level | Prevents | Still allows |
|---|---|---|
| `READ_UNCOMMITTED` | nothing | dirty reads |
| `READ_COMMITTED` | dirty reads | non-repeatable reads, phantoms |
| `REPEATABLE_READ` | + non-repeatable reads | phantoms |
| `SERIALIZABLE` | everything | (slowest) |

The three anomalies in plain terms:

- **Dirty read** — you read a row another transaction wrote but has not
  committed. It may roll back, so you read something that never existed.
- **Non-repeatable read** — you read the same *row* twice in one transaction
  and get different values, because someone committed in between.
- **Phantom read** — you run the same *query* twice and get a different number
  of rows, because someone inserted in between.

**PostgreSQL's default is `READ_COMMITTED`.** Oracle's too. MySQL/InnoDB
defaults to `REPEATABLE_READ`. Knowing your database's default is a good
interview detail.

### Rollback rules — a genuine trap

```java
@Transactional
public void doWork() throws Exception {
    insert();
    throw new Exception("boom");     // ⚠️ does NOT roll back!
}
```

**By default, Spring rolls back on `RuntimeException` and `Error` — but NOT on
checked exceptions.** This surprises nearly everyone.

```java
@Transactional(rollbackFor = Exception.class)     // now it rolls back
public void doWork() throws Exception { ... }
```

> **Interview question:** *"Does `@Transactional` roll back on a checked
> exception?"* — "No, not by default. Spring's default rollback rule is
> unchecked exceptions and `Error` only, on the reasoning that a checked
> exception is part of the method's declared contract and may be a handled,
> expected outcome. You override it with `rollbackFor`."

### `readOnly`

```java
@Transactional(readOnly = true)
public List<BreakView> findBreaks() { ... }
```

Not just documentation: Hibernate skips dirty-checking (a real performance win
on large result sets), and some databases and routers use the flag to send the
query to a read replica.

---

## 8. The web layer

### The request journey

```
Browser
  │
  ▼
Embedded Tomcat  ──────────── one thread taken from the pool
  │
  ▼
Filter chain (Security lives here)
  │
  ▼
DispatcherServlet ─────────── the "front controller"
  │
  ├─▶ HandlerMapping   : which @RequestMapping matches this URL?
  ├─▶ HandlerAdapter   : invoke the controller method
  │     ├─ ArgumentResolvers  : build @RequestBody, @PathVariable, @RequestParam
  │     └─ your method runs
  ├─▶ HttpMessageConverter : turn the return value into JSON (Jackson)
  └─▶ Response written
```

Being able to name `DispatcherServlet`, `HandlerMapping` and
`HttpMessageConverter` in order is a reliable senior signal.

### Controllers

```java
@RestController                    // = @Controller + @ResponseBody
@RequestMapping("/api/auth")       // class-level prefix
public class AuthController {

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) { ... }

    @GetMapping("/{id}")
    public BreakView one(@PathVariable UUID id) { ... }

    @GetMapping
    public List<BreakView> list(@RequestParam(defaultValue = "50") int limit) { ... }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Submission> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)      // 202
                .location(URI.create("/api/ingest/" + id))
                .body(submission);
    }
}
```

`@Controller` returns view names for server-rendered templates.
`@RestController` returns the object itself, serialised to JSON. For an API you
want `@RestController`.

### Status codes that show judgement

| Code | Meaning | ReconPilot's use |
|---|---|---|
| `200 OK` | Done, here is the result | a normal GET |
| `201 Created` | A new resource exists | register |
| **`202 Accepted`** | **Valid, queued, not done** | **file upload** |
| `400 Bad Request` | Your request is malformed | bad CSV header |
| `401 Unauthorized` | You are not authenticated | missing/expired token |
| `403 Forbidden` | Authenticated, but not allowed | wrong tenant |
| `404 Not Found` | No such thing | unknown batch id |
| `409 Conflict` | Clashes with current state | duplicate email |
| `422 Unprocessable` | Understood, but semantically refused | claiming an undercharge |
| `429 Too Many Requests` | Rate limited | — |
| `503 Service Unavailable` | Temporarily out of capacity | ingestion queue full |

> **202 is the one that impresses.** ReconPilot accepts a 2 GB settlement file
> and returns 202 in half a second; a worker parses it afterwards. Doing the
> work inside the request would hold an HTTP connection open for a minute and
> hit every proxy timeout between the browser and the server. Being able to
> explain *why* you returned 202 is a system-design answer disguised as a
> status code.

### Records as DTOs

```java
public record LoginRequest(String email, String password) {}
public record LoginResponse(String token, long expiresInSeconds, String email, String role) {}
```

Java records give you the constructor, accessors, `equals`, `hashCode` and
`toString` for free, and they are immutable. Jackson supports them natively.
Since Java 16 there is no reason to hand-write a DTO class.

Validation and invariants go in the compact constructor:

```java
public record MdrInput(long amountPaise, TxnType txnType) {
    public MdrInput {
        if (amountPaise < 0) throw new IllegalArgumentException("negative: " + amountPaise);
    }
}
```

---

## 9. Error handling

### Centralised, not scattered

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> conflict(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "That already exists"));
    }

    // Catch-all LAST — and do not leak internals
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled", e);            // full detail in the log
        return ResponseEntity.internalServerError()
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Something went wrong"));   // nothing useful to an attacker
    }
}
```

**`ProblemDetail`** is RFC 7807, built into Spring 6 / Boot 3+. Use it instead
of inventing an error shape.

> **Security point, and a live defect in ReconPilot (D3):** a default error
> response can leak internal package names, which tells an attacker your
> framework, your library versions and your structure. Log the detail; return
> something bland.

---

## 10. Validation

```java
public record RegisterRequest(
    @NotBlank(message = "tenant name is required")
    String tenantName,

    @Email @NotBlank
    String email,

    @Size(min = 12, message = "password must be at least 12 characters")
    String password
) {}

@PostMapping("/register")
public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) { ... }
//                                ^^^^^^ without this, the annotations do nothing
```

**`@Valid` is what triggers it.** The annotations on the record are inert
without it — a very common bug.

Handle the failure centrally:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest()
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
}
```

| Annotation | Applies to |
|---|---|
| `@NotNull` | any — must not be null |
| `@NotEmpty` | String/Collection — not null and not empty |
| `@NotBlank` | String — not null, and not only whitespace |
| `@Size(min, max)` | String/Collection length |
| `@Min` / `@Max` | numbers |
| `@Email`, `@Pattern` | strings |
| `@Past`, `@Future` | dates |

> **`@Valid` vs `@Validated`:** `@Valid` is the JSR-380 standard and works on
> method parameters and for cascading into nested objects. `@Validated` is
> Spring's own and adds **validation groups** — letting one object validate
> differently on create versus update. `@Validated` on a class also enables
> method-level validation.

---

## 11. Data access

### JdbcTemplate vs JPA — when to use which

ReconPilot deliberately uses **JdbcTemplate** for its hot paths. That is worth
being able to defend.

```java
// JdbcTemplate — you write the SQL, you know exactly what runs
List<BreakView> breaks = jdbc.query("""
        SELECT id, break_type, delta_paise
          FROM recon_break
         WHERE tenant_id = ?
         ORDER BY delta_paise DESC
         LIMIT ?
        """,
        (rs, i) -> new BreakView(rs.getObject(1, UUID.class),
                                 rs.getString(2), rs.getLong(3)),
        tenantId, limit);
```

```java
// JPA — the framework writes the SQL
@Entity
class ReconBreak {
    @Id UUID id;
    @Enumerated(EnumType.STRING) BreakType breakType;
    long deltaPaise;
}

interface BreakRepo extends JpaRepository<ReconBreak, UUID> {
    List<ReconBreak> findByTenantIdOrderByDeltaPaiseDesc(UUID tenantId, Pageable p);
}
```

| | JdbcTemplate | JPA / Hibernate |
|---|---|---|
| Boilerplate | more | far less |
| Control over SQL | total | indirect |
| Bulk performance | predictable | needs care |
| Surprises | few | N+1, lazy-loading, flush timing |
| Best for | reporting, bulk, complex SQL | CRUD over an object graph |

> **A strong answer:** "For ingesting a million rows I used JdbcTemplate with
> batched inserts, because I need to know precisely what SQL runs and I want no
> persistence context accumulating entities. For CRUD over an aggregate JPA
> saves real work. They coexist fine — the decision is per use case, not per
> project."

### The N+1 problem — asked constantly

```java
List<Order> orders = orderRepo.findAll();      // 1 query
for (Order o : orders) {
    o.getCustomer().getName();                 // +1 query EACH — 1 + N total
}
```

100 orders becomes **101 queries**. Fixes:

```java
// 1. JOIN FETCH
@Query("SELECT o FROM Order o JOIN FETCH o.customer")
List<Order> findAllWithCustomer();

// 2. Entity graph
@EntityGraph(attributePaths = "customer")
List<Order> findAll();

// 3. Batch fetching
@BatchSize(size = 50)    // turns N queries into N/50
```

**How to detect it:** turn on `spring.jpa.show-sql=true` or add a
`datasource-proxy`, then count. If the query count scales with the row count,
you have it.

### Bulk inserts done properly

```java
jdbc.batchUpdate("INSERT INTO transaction_event (...) VALUES (?,?,?)",
    rows, 1000,                       // batch size
    (ps, row) -> {
        ps.setObject(1, row.id());
        ps.setLong(2, row.amountPaise());
    });
```

And one driver flag that is nearly free:

```properties
spring.datasource.url=jdbc:postgresql://host/db?reWriteBatchedInserts=true
```

This collapses a JDBC batch into **one multi-row INSERT** instead of N
single-row ones. Measured on this project: **70.8s → 51.0s** for a million
rows. No code change.

### Money: never a floating-point type

```java
double price = 0.1 + 0.2;     // 0.30000000000000004
```

**Money is `long` paise everywhere in ReconPilot**, formatted only at the very
edge for display. `BigDecimal` is the alternative when you need fractional
precision and explicit rounding — the fee calculators use it *during*
computation and convert back to `long` at the end.

> **Interview question:** *"How do you store money?"* — "Integer minor units —
> paise or cents — in a `BIGINT`, or `NUMERIC`/`BigDecimal` when fractional
> precision matters. Never `float` or `double`, because binary floating point
> cannot represent 0.1 exactly, so errors accumulate over millions of rows and
> two systems computing the same total disagree."

---

## 12. Database migrations with Flyway

### The problem it solves

Without migrations, the schema is whatever someone ran by hand, and no two
environments match.

```
src/main/resources/db/migration/
  V1__initial_schema.sql
  V2__batch_lifecycle_columns.sql
  ...
  V8__app_role_password_from_environment.sql
```

Flyway keeps a `flyway_schema_history` table, runs anything not yet applied, in
order, exactly once, and records a **checksum** of each file.

### The rule that catches people

**Never edit an applied migration.** The checksum will not match and Flyway
refuses to start:

```
Migration checksum mismatch for migration version 6
```

Fix forward with `V9__...` instead. This is a *feature*: it guarantees every
environment ran the same statements.

ReconPilot hit exactly this. `V6` hard-coded the application role's password.
Because V6 could not be edited, the fix was `V8`, using a placeholder:

```sql
-- V8__app_role_password_from_environment.sql
ALTER ROLE reconpilot_app WITH PASSWORD '${appPassword}';
```

```properties
spring.flyway.placeholders.appPassword=${DB_APP_PASSWORD:localdev_app}
```

### Pairing it with Hibernate

```properties
spring.jpa.hibernate.ddl-auto=validate
```

| Value | Effect |
|---|---|
| `none` | do nothing |
| **`validate`** | **check entities match the schema, change nothing** ✅ |
| `update` | try to alter the schema to fit ⚠️ |
| `create` | drop and recreate at startup ☠️ |
| `create-drop` | ...and drop again at shutdown ☠️ |

**Use `validate` with Flyway.** `update` in production is how columns get
silently added, data gets orphaned, and two environments drift apart.

---

## 13. Connection pooling

Opening a database connection costs a TCP handshake, authentication and session
setup — tens of milliseconds. A pool opens them once and lends them out.

**HikariCP** is Spring Boot's default and the fastest in common use.

```properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000     # wait for a connection
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000         # recycle before the DB does
```

> **Sizing:** bigger is not better. Every connection is a backend process in
> PostgreSQL. A common starting formula is `(core_count * 2) + effective_spindle_count`.
> A pool of 10 serving 200 concurrent requests is usually *correct* — the
> requests queue briefly, rather than overwhelming the database.

### The subtle danger: connection state leaks

A pooled connection is **reused**. Anything you set on it — a session variable,
an unclosed transaction, a temp table — is still there for the next borrower.

ReconPilot sets a PostgreSQL session variable to carry the current tenant so
row-level security can filter on it. That is only safe because it wraps the
pool and clears the variable on **both** checkout and close:

```java
class TenantAwareDataSource extends DelegatingDataSource {
    @Override
    public Connection getConnection() throws SQLException {
        Connection c = super.getConnection();
        setTenant(c, TenantContext.get());   // stamp on borrow
        return wrapSoThatCloseClears(c);     // clear on return
    }
}
```

Double defence, because a leaked tenant id means one customer sees another's
data.

---

## 14. Spring Security

### The filter chain

Security is a chain of servlet filters running **before** the
`DispatcherServlet`.

```
Request
  │
  ▼
SecurityFilterChain
  ├─ CsrfFilter
  ├─ YourJwtAuthFilter            ← reads the token, sets the SecurityContext
  ├─ AuthorizationFilter          ← checks the rules below
  └─ ExceptionTranslationFilter   ← turns AccessDenied into 401/403
  │
  ▼
DispatcherServlet ──▶ your controller
```

### Configuring it

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwt) throws Exception {
        return http
            // Stateless API with a bearer token: there is no session cookie for
            // a browser to send automatically, so CSRF does not apply.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // Error dispatches re-enter the chain after the context is
                // cleared. Without this a downstream 500 is reported as a 401,
                // which sends you debugging authentication for hours.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // adaptive; salts automatically
    }
}
```

### Authentication vs authorization

- **Authentication** — *who are you?* (login, token validation)
- **Authorization** — *what may you do?* (roles, permissions)

### Password hashing

```java
String hash = encoder.encode(rawPassword);              // store this
boolean ok  = encoder.matches(rawPassword, storedHash); // never decrypt
```

BCrypt is a **one-way hash**, deliberately slow, with a per-password salt built
into the output. Never MD5 or SHA-256 for passwords — they are fast, which is
exactly wrong.

### JWT

```
header.payload.signature      (three base64url parts, dot separated)
```

- The payload is **signed, not encrypted** — anyone can read it. Never put a
  secret in a JWT.
- Validation is local: recompute the signature with the secret. No database
  hit, which is why JWTs scale.
- **They cannot be revoked.** Once issued, a JWT is valid until it expires.
  That is the fundamental trade-off: short expiry limits the damage, a denylist
  gives you revocation but reintroduces the lookup you were avoiding.

> **Interview question:** *"How do you log a user out with JWT?"* — "You can't,
> not really. The token stays valid until expiry. In practice you delete it
> client-side, keep expiry short — minutes to a couple of hours — and add a
> refresh token that *is* stored server-side and can be revoked. For immediate
> revocation you need a denylist in Redis, which trades away the statelessness."

### Method security

```java
@EnableMethodSecurity
class Config {}

@PreAuthorize("hasRole('ADMIN')")
public void deleteEverything() { }

@PreAuthorize("#tenantId == authentication.principal.tenantId")
public List<Break> forTenant(UUID tenantId) { }
```

> **A live defect in ReconPilot (D18):** roles are stored and turned into Spring
> authorities, but **nothing checks them** — there is no `@PreAuthorize`
> anywhere. Knowing that authorities exist is not the same as enforcing them.
> Recording it as a defect is better than pretending.

---

## 15. Async work and thread pools

```java
@Configuration
@EnableAsync                      // without this, @Async does nothing
public class AsyncConfig {

    @Bean("ingestionExecutor")
    ThreadPoolTaskExecutor ingestionExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("ingest-");
        // Reject rather than queue without limit. An unbounded queue turns
        // overload into an OutOfMemoryError instead of a clear 503.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return ex;
    }
}

@Service
class IngestionService {
    @Async("ingestionExecutor")
    public void process(UUID batchId) { ... }
}
```

### The counter-intuitive rule

**A `ThreadPoolExecutor` fills the queue BEFORE it grows past the core pool
size.**

```
corePoolSize=4, queueCapacity=10, maxPoolSize=8

tasks 1-4    → run on 4 core threads
tasks 5-14   → QUEUED (the pool does NOT grow yet)
task 15      → now a 5th thread starts
tasks 15-18  → threads grow to max of 8
task 19      → REJECTED
```

So the total capacity is `queueCapacity + maxPoolSize` = 18, and with a large
queue the extra threads may *never* be created. ReconPilot measured this: with
a capacity of 14, exactly 14 were accepted and 2 rejected with 503.

> **Interview question:** *"You set `maxPoolSize=50` but never see more than 10
> threads. Why?"* — "Because the queue fills first. `ThreadPoolExecutor` only
> creates threads beyond `corePoolSize` once the queue is full. With a large or
> unbounded queue, the maximum is effectively unreachable. An unbounded queue
> also converts overload into an `OutOfMemoryError` rather than backpressure."

### Rejection policies

| Policy | Behaviour |
|---|---|
| `AbortPolicy` | throw `RejectedExecutionException` (default) — becomes a 503 |
| `CallerRunsPolicy` | the submitting thread runs it — natural backpressure |
| `DiscardPolicy` | silently drop ☠️ |
| `DiscardOldestPolicy` | drop the oldest queued task ☠️ |

And remember: **`@Async` is proxy-based, so it does nothing on a self-call.**
The method also cannot be `private` or `final`.

---

## 16. Kafka

### Why a queue at all

Before Kafka, ReconPilot held work in an in-memory executor queue. Kill the
process and the queued work vanished, with nothing recorded. Kafka makes the
work **durable**: it is written to disk before it is acknowledged, so a crash
loses nothing.

```java
@Service
class Publisher {
    private final KafkaTemplate<String, IngestionRequested> template;

    void publish(IngestionRequested event) {
        template.send("reconpilot.ingestion.requested", event.batchId().toString(), event);
        //                                              ^^^^^^^^^^ the KEY
    }
}

@Component
class Worker {
    @KafkaListener(topics = "reconpilot.ingestion.requested",
                   groupId = "reconpilot-ingestion")
    void onMessage(IngestionRequested event) { ... }
}
```

### The concepts

- **Topic** — a named log of messages.
- **Partition** — a topic is split into partitions; each is an ordered,
  append-only sequence. **Ordering is guaranteed within a partition, never
  across partitions.**
- **Key** — decides the partition (`hash(key) % partitions`). Same key → same
  partition → ordered relative to each other. Using the batch id as the key
  keeps one batch's events in order.
- **Consumer group** — each partition is consumed by exactly one member of a
  group. More consumers than partitions means idle consumers.
- **Offset** — the consumer's bookmark.

### The single most important setting

```properties
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=record
```

**With auto-commit on, Kafka commits offsets on a timer whether or not the
message was actually processed.** Crash between the commit and the work and the
message is gone: never processed, never redelivered, no error anywhere. Turning
it off makes Spring commit only after the listener returns normally — which is
what makes redelivery-on-crash work at all.

### Producer durability

```properties
spring.kafka.producer.acks=all                       # every in-sync replica must have it
spring.kafka.producer.properties.enable.idempotence=true   # no duplicates on retry
spring.kafka.consumer.auto-offset-reset=earliest     # a new group reads from the start
```

`acks=all` with one broker is the same as `acks=1`, but it is what makes a
multi-node cluster durable — and defaulting it correctly now avoids a silent
data-loss window later.

### Delivery semantics

| | Meaning |
|---|---|
| At-most-once | commit before processing — may lose |
| **At-least-once** | **process, then commit — may duplicate** ← the normal choice |
| Exactly-once | transactions + idempotent producer; costly and narrow |

Because at-least-once can redeliver, **consumers must be idempotent.**
ReconPilot does this with `ON CONFLICT ... DO UPDATE ... RETURNING`, so
processing the same batch twice produces the same result rather than double
counting.

---

## 17. Caching and Redis

```java
@EnableCaching
@Configuration
class CacheConfig {}

@Service
class FormatMappingService {

    @Cacheable(value = "formats", key = "#fingerprint")
    public Mapping lookup(String fingerprint) { ... }   // runs only on a miss

    @CacheEvict(value = "formats", key = "#fingerprint")
    public void invalidate(String fingerprint) { }

    @CachePut(value = "formats", key = "#m.fingerprint")
    public Mapping save(Mapping m) { return m; }        // always runs, updates cache
}
```

| Annotation | Behaviour |
|---|---|
| `@Cacheable` | check cache first; run the method only on a miss |
| `@CachePut` | **always** run the method, then update the cache |
| `@CacheEvict` | remove an entry (`allEntries = true` clears the cache) |

Yes — **`@Cacheable` is proxy-based too, so a self-call bypasses it.**

### Cache patterns worth naming

- **Cache-aside (lazy loading)** — application checks the cache, falls back to
  the database, then populates. What `@Cacheable` does.
- **Write-through** — write to cache and database together. Consistent, slower.
- **Write-behind** — write to cache, flush to the database later. Fast, risks
  loss.

### The hard parts

- **Invalidation** — the classic joke ("two hard things: naming, cache
  invalidation, and off-by-one errors") is about this. Prefer a short TTL over
  clever invalidation.
- **Stampede** — a popular key expires and a thousand requests all miss at
  once. Mitigate with a short random jitter on the TTL, or a lock so one caller
  refreshes.
- **What not to cache** — anything where a stale answer is *wrong* rather than
  merely old. Account balances, permission checks, anything a regulator reads.

---

## 18. Actuator

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

| Endpoint | Gives you |
|---|---|
| `/actuator/health` | UP / DOWN, plus per-component detail |
| `/actuator/info` | build and version info |
| `/actuator/metrics` | JVM, HTTP, datasource metrics |
| `/actuator/prometheus` | the same, in Prometheus format |
| `/actuator/env` | resolved configuration ⚠️ **sensitive** |
| `/actuator/loggers` | read *and change* log levels at runtime |

> **Expose deliberately.** `include=*` on a public port hands out configuration,
> beans and heap dumps. ReconPilot exposes `health,info` only, and the
> container healthcheck uses `/actuator/health`.

Custom health indicator:

```java
@Component
class KafkaLagHealth implements HealthIndicator {
    public Health health() {
        long lag = consumerLag();
        return lag < 1000
            ? Health.up().withDetail("lag", lag).build()
            : Health.down().withDetail("lag", lag).build();
    }
}
```

---

## 19. Testing

### The pyramid

```
        ╱ E2E ╲          few, slow, brittle
      ╱ Integration ╲    some, real DB via Testcontainers
   ╱   Unit tests     ╲  many, fast, no Spring
```

### Unit tests — no Spring at all

```java
class MarketplaceFeeCalculatorTest {
    private final MarketplaceFeeCalculator calc = new MarketplaceFeeCalculator();

    @Test
    void gstIsOnFeesNotItemPrice() {
        var r = calc.calculate(amazon(20_000_00L, MOBILE_PHONES, someDate));
        assertEquals(18_000, r.gstPaise());       // 18% of Rs 1,000 commission
        assertNotEquals(360_000, r.gstPaise());   // NOT 18% of the Rs 20,000 item
    }
}
```

16 tests, **74 milliseconds**, because there is no context to start. This is the
reward for keeping business logic free of annotations.

### Test slices — load only what you need

```java
@WebMvcTest(AuthController.class)      // controller + MVC only; no DB
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService service;  // @MockBean in older versions

    @Test
    void returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/breaks"))
           .andExpect(status().isUnauthorized());
    }
}

@DataJpaTest       // JPA + an embedded or configured database, nothing else
@JsonTest          // Jackson serialisation only
@SpringBootTest    // the whole context — slowest, use sparingly
```

### Testcontainers — a real database, disposable

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection            // auto-wires spring.datasource.* — no manual props
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }
}
```

> **A real, expensive bug from this project.** `@ServiceConnection` only
> overrides `spring.datasource.*`. ReconPilot has a *second* datasource,
> `app.datasource.admin.*`, which still pointed at `localhost:5432` — so the
> test suite's `TRUNCATE` ran against the **developer's real database** and
> destroyed a million rows. The fix was a `DynamicPropertyRegistrar` for the
> second datasource *and* a guard that refuses to run unless the connection URL
> is the container's:
>
> ```java
> @BeforeEach
> void refuseToRunAgainstAnythingButAContainer() {
>     String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
>     if (!url.equals(postgres.getJdbcUrl())) {
>         throw new IllegalStateException("Refusing to run: not the test container");
>     }
> }
> ```
>
> **A destructive operation should verify what it is about to destroy, not
> trust that the wiring is right.** That is a sentence worth saying out loud in
> an interview.

### Surefire vs Failsafe — the silent skip

Maven runs **unit** tests with Surefire and **integration** tests with Failsafe,
and they match different file names:

| Plugin | Phase | Matches |
|---|---|---|
| Surefire | `test` | `*Test`, `Test*`, `*Tests` |
| Failsafe | `verify` | `*IT`, `IT*`, `*ITCase` |

**`mvn test` does not run `*IT` classes.** This project silently skipped 36
integration tests for a whole session because of exactly that. **Use `mvn
verify`.**

### Context caching

`@SpringBootTest` caches the `ApplicationContext` between test classes **keyed
on the configuration**. Change a property, a profile or a `@MockBean` and you
get a *new* context and another slow startup. Sharing one abstract base class
keeps the cache working — which is why ReconPilot has
`AbstractIntegrationTest`.

### Mockito essentials

```java
@ExtendWith(MockitoExtension.class)
class ServiceTest {
    @Mock  PaymentClient client;
    @InjectMocks OrderService service;

    @Test
    void retriesOnFailure() {
        when(client.charge(any())).thenThrow(new TimeoutException())
                                  .thenReturn(Receipt.ok());
        service.pay(order);
        verify(client, times(2)).charge(any());
    }
}
```

`when/thenReturn` stubs behaviour; `verify` asserts an interaction happened.
Do not verify everything — that produces tests that break on every refactor
while proving nothing.

### The discipline that matters most

**Prove the test would fail.** After fixing a bug, restore the bug and watch
the new test go red. ReconPilot did this for the registration fix: 3 tests run,
2 fail against the old code. A regression test that has never failed is a
hypothesis, not a test.

---

## 20. Application startup and lifecycle

### Order of execution

```
1. SpringApplication.run()
2. Environment prepared (properties, profiles resolved)
3. ApplicationContext created
4. Bean definitions loaded (scanning + auto-config)
5. BeanFactoryPostProcessors
6. Beans instantiated → dependencies injected
7. @PostConstruct   ← per bean, during context refresh
8. InitializingBean.afterPropertiesSet()
9. Context refresh completes
10. Embedded server STARTS LISTENING            ← note where this is
11. ApplicationRunner / CommandLineRunner
12. ApplicationReadyEvent
```

### Why that order matters — a real bug

ReconPilot has a `StartupSecretCheck` that refuses to start when a development
secret is still in use. It was written as an `ApplicationRunner`, and the logs
showed:

```
Started BackendApplication in 2.727 seconds
... then ...
IllegalStateException: Refusing to start: development secrets are in use.
```

**Tomcat had already bound its port and was accepting requests** before the
check ran. A guard that fires after the door is open is not a guard.

Moving it to `@PostConstruct` made the failure abort the context refresh, so
the server never starts at all. Verified by the *absence* of a "Tomcat started"
line.

```java
@Component
class StartupSecretCheck {
    @PostConstruct                 // step 7 — before the server listens
    void check() {
        if (usingDevelopmentSecret()) {
            throw new IllegalStateException("Refusing to start: ...");
        }
    }
}
```

### Graceful shutdown

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

Stops accepting new requests, lets in-flight ones finish, then exits. Without
it a deploy kills requests mid-flight.

```java
@PreDestroy
void cleanup() { ... }     // runs on orderly shutdown (not on kill -9)
```

---

## 21. The traps, collected

Quick revision. Every one of these is a real interview question.

| # | Trap | The answer |
|---|---|---|
| 1 | `@Transactional` on a self-invoked method | Proxy is bypassed; move it to another bean |
| 2 | `@Transactional` on private/final/static | A proxy cannot intercept it |
| 3 | Checked exception doesn't roll back | Default is unchecked only; use `rollbackFor` |
| 4 | `@Async` does nothing | Missing `@EnableAsync`, or a self-call |
| 5 | Thread pool never exceeds core size | The queue fills before the pool grows |
| 6 | `@Valid` missing | Validation annotations are inert without it |
| 7 | Defining a bean silently disables auto-config | `@ConditionalOnMissingBean` backs off |
| 8 | `mvn test` skips integration tests | Surefire matches `*Test`; use `mvn verify` |
| 9 | Mutable field in a singleton | Shared across all threads — race condition |
| 10 | `ddl-auto=update` in production | Silent schema drift; use `validate` + Flyway |
| 11 | Editing an applied migration | Checksum mismatch; fix forward |
| 12 | N+1 queries | `JOIN FETCH` / `@EntityGraph` / `@BatchSize` |
| 13 | `double` for money | Use `long` minor units or `BigDecimal` |
| 14 | Kafka auto-commit | Loses messages on crash; commit after processing |
| 15 | JWT cannot be revoked | Short expiry + refresh tokens, or a denylist |
| 16 | Pooled connection keeps session state | Clear it on borrow *and* return |
| 17 | Guard runs after the port opens | `@PostConstruct`, not `ApplicationRunner` |
| 18 | Circular dependency | A design smell; `@Lazy` is a plaster |
| 19 | Component scan misses a package | Scanning starts at the main class, downward |
| 20 | Error response leaks internals | Log the detail, return something bland |

---

## What to do next

1. **Open the real files.** Every example here exists in `backend/src/main/java/in/reconpilot/`.
   Reading a concept then seeing it in place is what makes it stick.
2. **Break something on purpose.** Add `@Transactional` to a private method and
   watch it do nothing. Set `enable-auto-commit=true` and kill the consumer
   mid-message. You will never forget a bug you caused deliberately.
3. **Say the answers out loud.** Reading fluently is not the same as speaking
   fluently under pressure, and the interview tests the second one.

The companion document `02-spring-boot-interview-questions.md` has the
questions and full answers.
