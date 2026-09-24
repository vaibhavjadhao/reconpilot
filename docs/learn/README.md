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
| 04 | PostgreSQL and SQL | planned |
| 05 | [Docker and containers](05-docker.md) | ✅ done |
| 06 | Kafka | planned |
| 07 | Redis and caching | planned |
| 08 | React, Redux and TypeScript | planned |
| 09 | JavaScript, the language | planned |
| 10 | Testing: JUnit, Mockito, Testcontainers | planned |
| 11 | System design for a two-year engineer | planned |
| 12 | Angular | planned |

Interview-question documents accompany each stack, with at least fifty
questions and full answers.

## How to read these

1. Read a section.
2. **Open the real file it refers to** in `backend/src/main/java/in/reconpilot/`
   or `frontend/src/`. Seeing a concept in place is what makes it stick.
3. **Break it deliberately.** Add `@Transactional` to a private method and
   watch it do nothing. You will never forget a bug you caused on purpose.
4. **Say the answer out loud.** Reading fluently and speaking fluently under
   pressure are different skills, and only the second one is tested.
