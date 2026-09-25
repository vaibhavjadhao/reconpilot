# Learning ReconPilot

Documents that explain every technology used in this project, in plain language
with working code, written for someone with roughly two years of experience who
is preparing for interviews at companies that will not go easy on them.

Each concept is explained three times over: an analogy, the mechanism, and code
you could type from memory. Where this project hit a real bug, the bug is
included — a mistake you have seen someone make is worth ten paragraphs of
theory.

## Documents

| # | Document | Status |
|---|---|---|
| 01 | [Spring Boot, properly](01-spring-boot.md) | ✅ done |
| 02 | Spring Boot interview questions | planned |
| 03 | [Java, the language](03-java.md) | ✅ done |
| 04 | [PostgreSQL and SQL](06-postgresql.md) | ✅ done |
| 05 | [Docker and containers](05-docker.md) | ✅ done |
| 06 | [Kafka](07-kafka.md) | ✅ done |
| 07 | [Redis and caching](12-redis.md) | ✅ done |
| 08 | [React, Redux and TypeScript](08-react-redux-typescript.md) | ✅ done |
| 09 | [JavaScript, the language](09-javascript.md) | ✅ done |
| 10 | [Testing: JUnit, Mockito, Testcontainers](10-testing.md) | ✅ done |
| 11 | [System design for a two-year engineer](11-system-design.md) | ✅ done |
| 12 | [Angular](13-angular.md) | ✅ done |

### Interview questions

| # | Document | Status |
|---|---|---|
| 04 | [Java interview questions](04-java-interview-questions.md) (66 + code) | ✅ done |
| 02 | [Spring Boot interview questions](02-spring-boot-interview-questions.md) (72) | ✅ done |
| 14 | [System design interview questions](14-system-design-interview-questions.md) (13 iconic problems) | ✅ done |

## How to read these

1. Read a section.
2. **Open the real file it refers to** in `backend/src/main/java/in/reconpilot/`
   or `frontend/src/`. Seeing a concept in place is what makes it stick.
3. **Break it deliberately.** Add `@Transactional` to a private method and
   watch it do nothing. You will never forget a bug you caused on purpose.
4. **Say the answer out loud.** Reading fluently and speaking fluently under
   pressure are different skills, and only the second one is tested.
