# Testing

**Why this is worth a document of its own.** Nearly every candidate says "I
write unit tests." Very few can explain what a test actually *proves*, or
describe a time a test lied to them. That gap is where the interesting
interview questions live — and this project has unusually good material,
because several of its tests were wrong in instructive ways.

---

## Table of contents

1. [What a test is for](#1-what-a-test-is-for)
2. [The pyramid](#2-the-pyramid)
3. [JUnit 5](#3-junit-5)
4. [Writing assertions people can read](#4-writing-assertions-people-can-read)
5. [Mockito](#5-mockito)
6. [Spring test slices](#6-spring-test-slices)
7. [Testcontainers](#7-testcontainers)
8. [Surefire vs Failsafe — the silent skip](#8-surefire-vs-failsafe--the-silent-skip)
9. [Testing concurrency](#9-testing-concurrency)
10. [The independent-implementation technique](#10-the-independent-implementation-technique)
11. [Proving a test would fail](#11-proving-a-test-would-fail)
12. [Testing in CI](#12-testing-in-ci)
13. [Frontend testing](#13-frontend-testing)
14. [Coverage, and why it lies](#14-coverage-and-why-it-lies)
15. [The traps, collected](#15-the-traps-collected)

---

## 1. What a test is for

Not "to increase coverage". A test exists to let you **change code without
fear**. It is a machine that answers one question: *did I just break
something?*

That framing has consequences:

- A test that never fails proves nothing. It is a hypothesis you have not
  tested.
- A test that fails for reasons unrelated to the bug it guards is worse than no
  test — it trains people to ignore red.
- A test asserting what the code does, rather than what it should do, will
  happily lock in a bug forever.

> **The sentence worth carrying into an interview:** *a regression test that has
> never been seen to fail is a hypothesis, not a test.*

---

## 2. The pyramid

```
        ╱  E2E  ╲          few    — slow, brittle, highest confidence
      ╱ Integration ╲      some   — real DB, real broker
   ╱   Unit tests     ╲    many   — fast, no framework
```

| Level | Speed | What it proves | ReconPilot |
|---|---|---|---|
| Unit | ms | one class behaves | 99 tests |
| Integration | seconds | components work together, against real infrastructure | 39 tests |
| End-to-end | minutes | the deployed system works | the CI stack job |

The shape matters because of feedback speed. If your fast tests catch most
problems, you run them constantly. If only the slow ones catch anything, you
stop running tests.

### The rules-engine example

`MarketplaceFeeCalculator` — the class that decides how much money someone is
owed — has **zero Spring annotations**. Sixteen tests run in **74
milliseconds**, with no context, no database, no broker.

That is not an accident. It is the reason the money logic is easy to test, and
it is a design decision you can defend:

> "I kept the rules engine free of framework annotations so its tests are plain
> JUnit and run in milliseconds. A test suite you are reluctant to run is a test
> suite that stops being run."

---

## 3. JUnit 5

```java
class MarketplaceFeeCalculatorTest {

    private final MarketplaceFeeCalculator calc = new MarketplaceFeeCalculator();

    @Test
    @DisplayName("Amazon charges no commission below Rs 1,000")
    void belowThreshold() {
        var r = calc.calculate(amazon(99_900, APPAREL, AFTER_CHANGE));
        assertEquals(0, r.commissionPaise());
    }

    @Nested
    @DisplayName("Exemptions, which is where the real overcharges hide")
    class Exemptions { ... }
}
```

### The lifecycle annotations

| Annotation | Runs |
|---|---|
| `@BeforeAll` | once, before everything (must be `static`) |
| `@BeforeEach` | before each test |
| `@AfterEach` | after each test |
| `@AfterAll` | once, at the end (`static`) |
| `@Disabled` | skip, with a reason |

### Parameterised tests

```java
@ParameterizedTest
@CsvSource({
    "99900,  0",        // below Rs 1,000 — exempt
    "100000, 5000",     // exactly Rs 1,000 — 5%
    "2000000, 100000",  // Rs 20,000 — 5%
})
void commission(long pricePaise, long expected) {
    assertEquals(expected, calc.calculate(amazon(pricePaise, MOBILE_PHONES, D)).commissionPaise());
}

@ParameterizedTest
@EnumSource(BreakType.class)
void everyBreakTypeHasALabel(BreakType type) { ... }
```

`@EnumSource` is quietly excellent: **add an enum constant and the test
automatically covers it**, so a new break type cannot be forgotten.

### @Nested for structure

Nested classes group related cases and let you share setup. They also produce
readable output:

```
Exemptions, which is where the real overcharges hide
  ✓ Amazon charges no commission below Rs 1,000
  ✓ exactly Rs 1,000 is NOT exempt -- the band is strictly below
  ✓ a category rate we cannot cite is reported unverifiable, never as zero
```

> ⚠️ **A gotcha this project hit:** Maven's report prints `Tests run: 0` for the
> *outer* class when all its tests live in `@Nested` classes. The tests ran
> fine. Check the XML report (`tests="16"`) before concluding you have a
> problem — I briefly did.

---

## 4. Writing assertions people can read

```java
// ❌ tells you nothing when it fails
assertTrue(result.commissionPaise() == 5000);

// ✅ shows expected vs actual
assertEquals(5_000, result.commissionPaise());

// ✅✅ says WHY, in the failure message
assertEquals(5_000, result.commissionPaise(), "5% of Rs 1,000 = Rs 50");
```

```java
assertThrows(UnpublishedRateException.class, () -> calc.calculate(tooEarly));

assertAll("fee breakdown",                       // reports ALL failures, not the first
    () -> assertEquals(5_000, r.commissionPaise()),
    () -> assertEquals(900,   r.gstPaise()),
    () -> assertTrue(r.verified().contains(COMMISSION)));
```

### Test names are documentation

```java
@Test void test1() { }                                    // ❌
@Test void testCalculate() { }                            // ❌

@Test
@DisplayName("Rs 300 itself falls in the first slab, not the second")
void slabBoundaryIsInclusive() { }                        // ✅
```

That name records a **decision** — the slab reads "up to ₹300" — so anyone who
changes it later knows they are changing a rule, not fixing a typo.

### Assert on behaviour, not implementation

```java
// ❌ breaks when you refactor, proves nothing about correctness
verify(repository).save(any());

// ✅ asserts the outcome the user cares about
assertEquals(7_300, reconciliationResult.breakCount());
```

---

## 5. Mockito

```java
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock  KafkaPublisher publisher;
    @Mock  BatchRepository repository;
    @InjectMocks IngestionService service;

    @Test
    void publishesAfterStaging() {
        when(repository.insert(any())).thenReturn(BATCH_ID);

        service.accept(upload);

        verify(publisher).publish(argThat(e -> e.batchId().equals(BATCH_ID)));
    }
}
```

### Stubbing

```java
when(client.charge(any())).thenReturn(Receipt.ok());
when(client.charge(any())).thenThrow(new TimeoutException());
when(client.charge(any())).thenThrow(new TimeoutException()).thenReturn(Receipt.ok()); // then retry
doThrow(new IllegalStateException()).when(service).voidMethod();    // for void
```

### Verifying

```java
verify(client).charge(any());                 // exactly once
verify(client, times(2)).charge(any());
verify(client, never()).refund(any());
verify(client, atLeastOnce()).charge(any());
verifyNoMoreInteractions(client);             // use sparingly — brittle
```

### mock vs spy

```java
var mock = mock(Service.class);     // everything stubbed; real code never runs
var spy  = spy(new Service());      // real object; stub selected methods
doReturn(1).when(spy).slowMethod(); // note: doReturn, not when(), for spies
```

**Prefer mocks.** A spy is a signal that the class under test is doing too much
— you are trying to test half of it while the other half runs.

### When not to mock

```java
// ❌ mocking a value object
var input = mock(MdrInput.class);
when(input.amountPaise()).thenReturn(500_000L);

// ✅ just build one
var input = new MdrInput(500_000L, P2M, UPI_QR, STANDARD);
```

**Do not mock what you own and can construct cheaply.** Mock the things that are
slow, non-deterministic, or outside your control: network clients, clocks,
message brokers, payment gateways.

> **Interview question:** *"When do you mock a database?"* — "Rarely. Mocking a
> repository tests that I called a method, not that the SQL is right, the
> constraint fires, or the transaction rolls back. Those are exactly the things
> that break. I use Testcontainers for a real PostgreSQL in integration tests
> and keep mocks for genuinely external systems."

---

## 6. Spring test slices

Load only what you need. Context startup dominates test time.

```java
@WebMvcTest(AuthController.class)     // MVC layer only: no DB, no Kafka
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService service;          // @MockBean in older versions

    @Test
    void rejectsUnauthenticated() throws Exception {
        mvc.perform(get("/api/breaks"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsConflictOnDuplicate() throws Exception {
        when(service.register(any())).thenThrow(new DuplicateKeyException("x"));
        mvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content("""{"email":"a@b.c","password":"x","tenantName":"t"}"""))
           .andExpect(status().isConflict());
    }
}
```

| Slice | Loads |
|---|---|
| `@WebMvcTest` | controllers, converters, filters |
| `@DataJpaTest` | JPA repositories + a database |
| `@JdbcTest` | `JdbcTemplate` + a database |
| `@JsonTest` | Jackson only |
| `@SpringBootTest` | everything — slowest |

### Context caching

Spring **caches the ApplicationContext across test classes**, keyed on the
configuration. Change a property, a profile or a mock bean and you get a
different key — and another slow startup.

**Sharing one base class keeps the cache working.** That is why ReconPilot has
`AbstractIntegrationTest`: the PostgreSQL container and the Spring context start
**once for the whole suite**, not once per class.

---

## 7. Testcontainers

Real infrastructure, disposable, defined in code.

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection            // auto-wires spring.datasource.* — no manual properties
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    }
}
```

An H2 in-memory database is faster, and it is **a different database**. It does
not have row-level security, `ON CONFLICT ... RETURNING`, `JSONB`, or
PostgreSQL's locking behaviour. A test against H2 that passes proves your code
works against H2.

### The most expensive bug in this project

The test suite **truncated the developer's real database and destroyed a
million rows.**

**Why:** `@ServiceConnection` overrides `spring.datasource.*`. ReconPilot has a
*second* datasource — `app.datasource.admin.*` — for migrations and
cross-tenant work. That one still pointed at `localhost:5432`. So
`@BeforeEach ... TRUNCATE` ran against the real development database, and from
Spring's point of view the configuration was perfectly valid.

**Two fixes, and the second is the important one:**

```java
// 1. Point the second datasource at the container too
@Bean
DynamicPropertyRegistrar adminDatasourceProperties(PostgreSQLContainer<?> postgres) {
    return registry -> {
        registry.add("app.datasource.admin.url", postgres::getJdbcUrl);
        registry.add("app.datasource.admin.username", postgres::getUsername);
        registry.add("app.datasource.admin.password", postgres::getPassword);
    };
}

// 2. Refuse to run if the connection is not the container
@BeforeEach
void refuseToRunAgainstAnythingButAContainer() {
    String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
    if (!url.equals(postgres.getJdbcUrl())) {
        throw new IllegalStateException(
            "Refusing to run: the admin connection is not the test container.%n"
          + "  expected: %s%n  actual:   %s".formatted(postgres.getJdbcUrl(), url));
    }
}
```

> **The lesson, and it generalises well beyond tests:** *a destructive operation
> should verify what it is about to destroy, rather than trust that the wiring
> is correct.* Configuration can be wrong. A guard cannot be wrong in the same
> way.

That is a genuinely strong interview answer, because it is a story about a
mistake, the fix, and the principle extracted from it.

---

## 8. Surefire vs Failsafe — the silent skip

| Plugin | Maven phase | Matches |
|---|---|---|
| **Surefire** | `test` | `*Test`, `Test*`, `*Tests` |
| **Failsafe** | `verify` | `*IT`, `IT*`, `*ITCase` |

**`mvn test` does not run `*IT` classes.** It reports success having run none of
them.

This project silently skipped **36 integration tests for an entire session**
because of exactly that. The build was green. Nothing was being tested.

```xml
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
  </executions>
</plugin>
```

**Always `mvn verify`.** And CI should run `verify`, never `test` — which is
why ReconPilot's workflow does, with a comment explaining why.

---

## 9. Testing concurrency

Concurrency bugs are invisible to sequential tests. You must actually run
things at the same time.

```java
@Test
void concurrentUploadsOfTheSameFileProduceOneBatch() throws Exception {
    int threads = 14;
    var start = new CountDownLatch(1);          // release them all together
    var done  = new CountDownLatch(threads);
    var results = Collections.synchronizedList(new ArrayList<UUID>());

    var pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            try {
                start.await();                   // everyone waits here
                results.add(service.accept(sameFile).batchId());
            } catch (Exception e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        });
    }

    start.countDown();                           // 💥 all at once
    assertTrue(done.await(30, TimeUnit.SECONDS));

    assertEquals(1, Set.copyOf(results).size(), "every caller must get the same batch");
}
```

**The `CountDownLatch` is the technique.** Submitting tasks to a pool does not
make them collide — the first may finish before the last starts. A latch holds
every thread at the gate and releases them together, which is what actually
provokes the race.

This test is how ReconPilot found that a check-then-act insert **failed 12 of 14
concurrent batches**. A sequential test passed every time.

> Concurrency tests are inherently probabilistic — a passing run does not prove
> absence. Run them repeatedly, and fix the design (an atomic
> `INSERT ... ON CONFLICT`) rather than trying to test the race away.

---

## 10. The independent-implementation technique

**The most valuable testing idea in this project.**

The normal way to test a calculator is to compute the expected answer yourself
and assert it. The problem: *you* wrote both, so you make the same mistake
twice, and the test agrees with the bug.

Instead, ReconPilot has a **Python generator** that produces settlement rows
with a known number of deliberately wrong ones, computing the correct fee **from
the published rules** — not by calling the Java engine.

```python
def correct_mdr(amount, txn_type, rail, category):
    if txn_type == "P2P":  return 0
    if rail == "UPI_AUTOPAY": return 0
    if txn_type == "P2PM": return 0
    if amount <= THRESHOLD_PAISE: return 0
    raw = (Decimal(amount) * RATE[category]).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return min(int(raw), CAP_PAISE)
```

Then the assertion is simple and powerful:

```
planted 150, found 150 — every planted break found, and no others
```

### Why it earned its place

An early version of the generator **truncated** where the Java engine rounds
`HALF_UP`. One paise of disagreement surfaced as **8,174 false breaks** across a
million rows.

No test written by the engine's author would have caught that, because the
author's mental model *was* the bug. It took a second implementation, built
from the same source rules but by different reasoning, to disagree.

> **This is double-entry bookkeeping applied to software.** You do not check the
> ledger by re-reading the ledger.

### Where else the technique applies

- A parser, checked against a generator that produces the format.
- An encoder, checked by round-tripping through an independent decoder.
- A cache, checked against the uncached path.
- Any migration: run the old and new code on the same input and diff.

---

## 11. Proving a test would fail

**Write the test, fix the bug, then put the bug back and watch the test go
red.** Otherwise you have a test that passes, not a test that works.

ReconPilot did this for the registration fix:

```
With the bug restored:
  Tests run: 3, Failures: 2
    RegistrationIT.isAConflict    -- expected ResponseStatusException, got DuplicateKeyException
    RegistrationIT.writesNothing  -- a failed registration must not commit the tenant row

With the fix:
  Tests run: 39, Failures: 0
```

And for the backups, the same discipline in a different shape — three corrupted
backups were **deliberately planted** to prove the restore drill rejects them:

| Planted fault | Caught by |
|---|---|
| A byte altered after writing | checksum mismatch |
| Truncated mid-write, checksum regenerated | `pg_restore --exit-on-error` |
| Globals file missing | the roles check |
| Dump silently one row short | the manifest comparison |

The second is the instructive one: `pg_restore --list` — the integrity check the
backup script itself runs — **passed** on the truncated file, because the table
of contents sits at the front of a custom-format dump. Only a full restore found
the missing data.

> **A write-time integrity check proves a file is structurally sane. It does not
> prove the file is complete.** That distinction is exactly the sort of thing
> senior interviewers are listening for.

---

## 12. Testing in CI

CI runs the tests on a machine that has **never seen your project**. That is the
whole value: it catches everything that depends on your laptop.

This project's `mvn verify` once passed locally and failed nowhere else because
of a shell environment variable. CI would have caught it on day one.

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: '21'          # matching the Dockerfile — test what ships
    distribution: temurin
    cache: maven

- name: mvn verify              # verify, not test — see section 8
  run: ./mvnw --batch-mode --no-transfer-progress verify
```

### The green build that proved nothing

ReconPilot's CI has a job that starts the whole production stack and asserts the
backup restores. Its **first green run reported `ok` for all thirteen tables —
every one of them holding zero rows.** The job registered a user and ingested
nothing, so the backup was of an empty database and the manifest agreed with it
perfectly.

**Green CI that asserts nothing is worse than red CI, because red gets
investigated.** A smoke alarm with no battery is worse than no smoke alarm: same
silence, plus false confidence.

The job now generates 20,000 rows with exactly 150 wrong ones, ingests them,
reconciles, asserts 150 breaks were found, and only then takes the backup the
drill restores.

---

## 13. Frontend testing

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

test('shows a message when the upload is a duplicate', async () => {
  render(<Upload />, { wrapper: Providers })

  await userEvent.upload(screen.getByLabelText(/settlement csv/i), file)

  expect(await screen.findByText(/already ingested/i)).toBeInTheDocument()
})
```

### The Testing Library philosophy

**Query the way a user would** — by role, label, and visible text, not by CSS
class or component internals.

```tsx
screen.getByRole('button', { name: /reconcile/i })      // ✅ how a user finds it
screen.getByLabelText(/email/i)
screen.getByText(/2,000/)

container.querySelector('.MuiButton-root')              // ❌ breaks on restyling
```

A test bound to class names fails when you change the styling and passes when
you break the behaviour. That is backwards.

| Query | When it fails |
|---|---|
| `getBy` | throws immediately if not found |
| `queryBy` | returns null — **use this to assert absence** |
| `findBy` | async, retries — **use for anything after a fetch** |

### Mocking the network

```ts
import { setupServer } from 'msw/node'
const server = setupServer(
  http.get('/api/breaks', () => HttpResponse.json([{ id: '1', deltaPaise: 9863 }]))
)
```

MSW intercepts at the network layer, so your component and its RTK Query
hooks run **unmodified**. Mocking the `fetch` function tests your mock;
intercepting the request tests your code.

---

## 14. Coverage, and why it lies

```bash
mvn verify jacoco:report        # target/site/jacoco/index.html
```

Coverage tells you which lines **ran**. It says nothing about whether anything
was **checked**.

```java
@Test
void coversEverythingProvesNothing() {
    calculator.calculate(input);     // 100% coverage of calculate()
}                                    // zero assertions
```

Use it to find code that **no** test touches — that is genuinely useful. Do not
use it as a target. **Once a percentage becomes a goal, people write tests that
raise the percentage**, which is not the same as tests that catch bugs.

The honest measure is different and harder: *when you introduce a bug on
purpose, does something go red?*

---

## 15. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `mvn test` skipping `*IT` | Surefire vs Failsafe; use `mvn verify` |
| 2 | A test that has never failed | Restore the bug and watch it go red |
| 3 | Green CI asserting nothing | Check what it actually compared |
| 4 | H2 instead of the real database | Different SQL, no RLS, no `ON CONFLICT` |
| 5 | Truncating a database the test doesn't own | Guard on the connection URL |
| 6 | `@ServiceConnection` and a second datasource | Only `spring.datasource.*` is wired |
| 7 | Testing your own expected answer | Use an independent implementation |
| 8 | Sequential test for a concurrency bug | `CountDownLatch` to release together |
| 9 | Mocking value objects | Construct them |
| 10 | Mocking the repository | You test the mock, not the SQL |
| 11 | `verify(repo).save(any())` as the assertion | Assert the outcome |
| 12 | Querying by CSS class in the UI | Query by role and text |
| 13 | `getBy` for async content | Use `findBy` |
| 14 | Coverage as a target | It measures execution, not checking |
| 15 | A different Java version in CI | Test what actually ships |
| 16 | Slow tests nobody runs | Keep the domain free of framework annotations |
| 17 | Shared mutable state between tests | Reset in `@BeforeEach`; never rely on order |
| 18 | `Thread.sleep` in a test | Flaky; use latches or Awaitility |
| 19 | `@Nested` classes reporting 0 tests | A Maven display quirk; read the XML |
| 20 | Asserting on implementation details | Breaks on every refactor |

---

## What to do next

1. **Pick your best test and break the code it guards.** If it does not fail,
   it was never a test.
2. **Write one concurrency test** with a `CountDownLatch`. It is twenty lines,
   and it finds a class of bug nothing else will.
3. **Read `MarketplaceFeeCalculatorTest`** in this repo and notice that every
   `@DisplayName` states a *rule*, not a method name. That is what makes a suite
   readable two years later.

Next: `11-system-design.md`.
