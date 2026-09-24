# Docker and containers

**Why this matters for you.** Every deployment in this project is a container,
and "can you containerise a Spring Boot app" is now a standard backend
interview question. More importantly, the *reasoning* — layers, caching, image
size, networking, healthchecks — is where people who have only copy-pasted a
Dockerfile get found out.

Everything here is from ReconPilot's real `Dockerfile` and
`docker-compose.prod.yml`.

---

## Table of contents

1. [What a container actually is](#1-what-a-container-actually-is)
2. [Images, containers, layers](#2-images-containers-layers)
3. [Writing a Dockerfile](#3-writing-a-dockerfile)
4. [Layer caching — the skill that saves minutes](#4-layer-caching--the-skill-that-saves-minutes)
5. [Multi-stage builds](#5-multi-stage-builds)
6. [The image-size bug that doubled ours](#6-the-image-size-bug-that-doubled-ours)
7. [Running the JVM in a container](#7-running-the-jvm-in-a-container)
8. [Data: volumes and bind mounts](#8-data-volumes-and-bind-mounts)
9. [Networking](#9-networking)
10. [Healthchecks and dependencies](#10-healthchecks-and-dependencies)
11. [Docker Compose](#11-docker-compose)
12. [Secrets and configuration](#12-secrets-and-configuration)
13. [Security](#13-security)
14. [Debugging containers](#14-debugging-containers)
15. [The traps, collected](#15-the-traps-collected)

---

## 1. What a container actually is

### The one-sentence answer

**A container is a normal process on the host, which has been lied to about
what it can see.**

There is no virtual machine, no guest operating system, no emulation. The Linux
kernel provides two features and Docker combines them:

- **Namespaces** — control what a process can *see*: its own process tree, its
  own network interfaces, its own filesystem root, its own hostname.
- **cgroups** (control groups) — control what a process can *use*: how much
  memory, how much CPU.

That is essentially the whole idea.

### Container vs virtual machine

```
     VIRTUAL MACHINES                    CONTAINERS
 ┌──────────┬──────────┐            ┌──────────┬──────────┐
 │  App A   │  App B   │            │  App A   │  App B   │
 ├──────────┼──────────┤            ├──────────┴──────────┤
 │ Guest OS │ Guest OS │  ← heavy   │    Docker Engine    │
 ├──────────┴──────────┤            ├─────────────────────┤
 │      Hypervisor     │            │      Host OS        │  ← ONE kernel
 ├─────────────────────┤            ├─────────────────────┤
 │       Host OS       │            │      Hardware       │
 └─────────────────────┘            └─────────────────────┘
```

| | VM | Container |
|---|---|---|
| Boots | a whole OS | a process |
| Startup | tens of seconds | milliseconds |
| Size | gigabytes | tens of megabytes |
| Isolation | strong (separate kernel) | weaker (shared kernel) |
| Overhead | significant | almost none |

> **Interview question:** *"Why is a container smaller and faster than a VM?"*
> — "Because it shares the host kernel instead of booting its own. A container
> image only contains the application and its userland dependencies, not an
> operating system kernel, so it is megabytes rather than gigabytes and starts
> as fast as a process rather than as slow as a boot. The trade-off is
> isolation: a kernel vulnerability affects every container on the host, which
> is why you do not rely on containers alone to separate hostile tenants."

### The macOS and Windows catch

**The Linux kernel is doing the work — so on a Mac there must be a Linux
kernel somewhere.** Docker Desktop, Colima, or similar quietly run a Linux
virtual machine, and your containers run inside it.

This project uses **Colima**, which is why the day starts with:

```bash
colima start
```

It also explains a whole class of confusing behaviour on a Mac: file-sharing
performance, which host paths are visible inside containers (Colima mounts your
home directory, not `/private/tmp`), and why Colima does not survive a reboot.

---

## 2. Images, containers, layers

### The three nouns

| Term | What it is | Analogy |
|---|---|---|
| **Image** | A read-only template | A class |
| **Container** | A running instance of an image | An object |
| **Volume** | Storage that outlives containers | A database file on disk |

```bash
docker build -t reconpilot-backend .      # source  → image
docker run reconpilot-backend             # image   → container
docker ps                                 # list running containers
docker images                             # list images
```

### Layers

An image is a **stack of read-only layers**, one per instruction. Each layer
records only the *difference* from the layer below.

```dockerfile
FROM eclipse-temurin:21-jre-alpine    # layer 1: base
WORKDIR /app                          # layer 2: metadata
COPY app.jar app.jar                  # layer 3: +114 MB
```

Two things follow:

1. **Layers are shared.** Ten images built `FROM eclipse-temurin:21-jre-alpine`
   store that base once on disk.
2. **Layers are immutable and additive.** Deleting a file in a later layer does
   not shrink the image — the file is still in the earlier layer, merely hidden.

```dockerfile
COPY secret.txt .           # layer 3: the secret is now in the image FOREVER
RUN rm secret.txt           # layer 4: hides it. Does NOT remove it.
```

**Anyone with the image can extract that file.** This is why a private key must
never be `COPY`ed into an image — deleting it later does not un-publish it, and
the image has already been pushed to a registry and cached on every machine
that pulled it. ReconPilot's Dockerfile therefore installs `openssl` to
*generate* a development certificate at runtime, and copies none.

### The writable layer

A running container gets a thin **writable layer** on top. Everything it writes
goes there, and it is **deleted when the container is removed**. That is what
volumes are for.

---

## 3. Writing a Dockerfile

Here is ReconPilot's backend image, which is worth reading line by line.

```dockerfile
# ---------------------------------------------------------------- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, in their own layer. See section 4.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# -------------------------------------------------------------- runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user. See section 13.
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# --chown on the COPY itself, NOT a separate chown -R. See section 6.
COPY --from=build --chown=app:app /build/target/*.jar app.jar
RUN mkdir -p /app/staging && chown app:app /app/staging

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

### The instructions

| Instruction | Does |
|---|---|
| `FROM` | the base image; starts a build stage |
| `WORKDIR` | sets the directory (and creates it) |
| `COPY` | copy files from the build context into the image |
| `ADD` | like `COPY`, but also unpacks archives and fetches URLs — **prefer `COPY`** |
| `RUN` | run a command **at build time**, creating a layer |
| `ENV` | an environment variable, available at build and run time |
| `ARG` | a build-time-only variable |
| `EXPOSE` | documentation only — it publishes nothing |
| `USER` | which user subsequent instructions and the container run as |
| `ENTRYPOINT` | the executable |
| `CMD` | default arguments, or the command if there is no entrypoint |

### ENTRYPOINT vs CMD

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]   # always runs
CMD ["--spring.profiles.active=prod"]    # default args, overridable at run time
```

```bash
docker run myimage                              # java -jar app.jar --spring.profiles.active=prod
docker run myimage --server.port=9000           # java -jar app.jar --server.port=9000
```

### Shell form vs exec form — and why `exec` matters

```dockerfile
ENTRYPOINT java -jar app.jar              # SHELL form: runs via /bin/sh -c
ENTRYPOINT ["java", "-jar", "app.jar"]    # EXEC form:  runs directly as PID 1
```

In shell form, the shell is PID 1 and **your process is a child**. Signals like
`SIGTERM` from `docker stop` go to the shell, not to Java — so graceful
shutdown never happens and the container is killed after the timeout.

ReconPilot needs the shell to expand `$JAVA_OPTS`, so it uses shell form *with*
`exec`:

```dockerfile
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
#                        ^^^^ replaces the shell process, so Java becomes PID 1
```

`exec` means "replace this process rather than forking a child". That one word
is the difference between graceful shutdown working and not.

### `.dockerignore`

```
target/
node_modules/
.git/
.env
```

The **build context** — everything in the directory — is sent to the Docker
daemon before the build starts. Without a `.dockerignore`, that includes your
`.git` history and `node_modules`, which is slow and risks copying secrets into
the image.

---

## 4. Layer caching — the skill that saves minutes

Docker caches each layer. On a rebuild it reuses cached layers until it finds
one whose inputs changed — and then **every layer after that is rebuilt.**

### The wrong way

```dockerfile
COPY . .                        # any source change invalidates this layer...
RUN mvn package                 # ...so dependencies are downloaded again. Every time.
```

Change one character in one Java file and Maven re-downloads the internet.

### The right way

```dockerfile
COPY pom.xml .                  # changes rarely
RUN mvn dependency:go-offline   # cached until pom.xml changes

COPY src ./src                  # changes constantly
RUN mvn package                 # only this re-runs
```

**The rule: order instructions from least-likely-to-change to
most-likely-to-change.**

The same pattern in the frontend:

```dockerfile
COPY package.json package-lock.json* ./
RUN npm ci                      # cached until the lockfile changes
COPY . .
RUN npm run build
```

### `npm ci` vs `npm install`

```dockerfile
RUN npm install     # ⚠️ resolves version ranges freshly; may update the lockfile
RUN npm ci          # ✅ installs exactly the lockfile, fails if it disagrees
```

`npm ci` is what makes a build **reproducible**. Without it, an image built
today and one built in six months from the same commit can contain different
dependencies — and the difference only shows up in production.

---

## 5. Multi-stage builds

### The problem

To build a Spring Boot app you need the JDK, Maven, and the whole dependency
cache — roughly 700 MB of tooling. To *run* it you need a JRE and one jar.

### The solution

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build      # stage 1: fat, has the toolchain
RUN mvn package

FROM eclipse-temurin:21-jre-alpine AS runtime   # stage 2: slim, JRE only
COPY --from=build /build/target/*.jar app.jar   # take ONLY the artefact
```

**Only the final stage becomes the image.** Everything in the build stage —
Maven, the JDK, the `.m2` cache, your source code — is discarded.

This matters for security as well as size: your source code and any build-time
credentials are not in the shipped image at all.

The frontend does the same: Node builds the React bundle, and the runtime image
is nginx with static files. **No Node at runtime**, because nothing needs it.
Result: **77.5 MB**.

---

## 6. The image-size bug that doubled ours

A genuinely good interview story, because the cause is not obvious.

The original Dockerfile:

```dockerfile
COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app                    # ☠️
```

**Image size: 721 MB.** For a 114 MB jar on a ~180 MB base.

### Why

Layers are copy-on-write. `chown -R` **rewrites the metadata of every file**,
and in a copy-on-write filesystem a rewritten file is stored **again** in the
new layer.

```
layer 3:  COPY app.jar        +114 MB
layer 4:  RUN chown -R        +114 MB   ← the same jar, stored a second time
```

The jar is in the image twice. Deleting it from layer 4 would not help —
layers are additive.

### The fix

```dockerfile
COPY --from=build --chown=app:app /build/target/*.jar app.jar
```

`--chown` on the `COPY` sets ownership **as the file is written**, so there is
one layer and one copy.

**721 MB → 503 MB. One line.**

> **The general lesson:** any `RUN` that touches many existing files — `chown
> -R`, `chmod -R`, `find -exec`, even `mv` of a large tree — duplicates them
> into a new layer. Do the work in the same instruction that creates the files,
> or combine it into one `RUN`.

Same principle with package managers:

```dockerfile
# ❌ two layers; the apt cache is stored permanently in the first
RUN apt-get update
RUN apt-get install -y curl

# ✅ one layer, cache removed before the layer closes
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
```

---

## 7. Running the JVM in a container

### Do not hard-code the heap

```dockerfile
# ❌ ignores the container limit; change the limit and this is now wrong
ENV JAVA_OPTS="-Xmx1g"

# ✅ a percentage of whatever the container is given
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
```

Modern JVMs are **container-aware**: they read the cgroup memory limit rather
than the host's total RAM. With `MaxRAMPercentage`, changing the compose limit
changes the heap, with no rebuild:

```yaml
deploy:
  resources:
    limits:
      memory: 2G          # → roughly a 1.4 GB heap
```

### Why not 100%

The JVM needs memory *outside* the heap: metaspace, thread stacks, JIT code
cache, direct byte buffers, and the GC's own structures. Set the heap to the
whole container and the kernel's OOM killer terminates the process — and
because that is a `SIGKILL` from outside, you get **no stack trace, no heap
dump, nothing**, just exit code 137. 70–75% leaves room.

### `ExitOnOutOfMemoryError`

Without it, an `OutOfMemoryError` may kill one thread and leave the process
limping — accepting requests and failing them, passing its healthcheck,
helping nobody. With it the process dies immediately and the orchestrator
restarts it. **Crash cleanly rather than degrade invisibly.**

---

## 8. Data: volumes and bind mounts

**A container's filesystem dies with the container.** Anything that must
survive lives outside it.

```yaml
volumes:
  - pgdata:/var/lib/postgresql/data     # named volume  — Docker manages it
  - ./ops/backup:/ops:ro                # bind mount    — a host path, read-only
```

| | Named volume | Bind mount |
|---|---|---|
| Location | managed by Docker | a path you choose |
| Portable | ✅ | ❌ host-specific |
| Performance on macOS | good | slower |
| Use for | databases, persistent state | source code in dev, config, certificates |

```bash
docker volume ls
docker volume inspect reconpilot-prod_pgdata
docker compose down          # removes containers, KEEPS named volumes
docker compose down -v       # ☠️ removes the volumes too — deletes your database
```

> **`docker compose down -v` is the command that deletes production data.**
> Know the difference cold. To stop for the night, `docker compose stop`.

`:ro` makes a mount read-only. ReconPilot mounts its backup scripts `:ro`
because the container has no business modifying them.

---

## 9. Networking

### Service names, not localhost

**Inside a container, `localhost` means *that container*.** Not the host, not
another container. This is the single most common Docker mistake.

```yaml
services:
  backend:
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/reconpilot
      #                         ^^^^^^^^ the SERVICE NAME
      KAFKA_BOOTSTRAP: kafka:9092
```

Compose creates a network and registers each service name in DNS. Containers
reach each other by service name.

### `ports` vs `expose`

```yaml
ports:
  - "8443:443"      # HOST:CONTAINER — reachable from outside the machine
expose:
  - "8080"          # documentation only; other containers could reach it anyway
```

**Only publish what must be reachable from outside.** ReconPilot's production
compose publishes only the frontend. Postgres, Kafka and Redis have no `ports`
at all — they are reachable on the internal network and from nowhere else.

> A database that answers the internet is a database that gets found. Shodan
> indexes open PostgreSQL ports continuously.

### Advertised listeners — a Kafka-specific trap

```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
```

Kafka tells clients *where to reconnect*. If it advertises `localhost`, a
client in another container will try to connect to **itself**. In development,
when Spring runs on the host, this must be `localhost`; in compose, where the
backend is a container, it must be the service name. Getting this wrong
produces a connection that succeeds and then mysteriously times out.

---

## 10. Healthchecks and dependencies

### A healthcheck tells the orchestrator the truth

```yaml
backend:
  healthcheck:
    test: ["CMD-SHELL", "wget -q -O - http://localhost:8080/actuator/health | grep -q UP"]
    interval: 10s
    timeout: 5s
    retries: 12
    start_period: 40s      # failures during startup do not count
```

`start_period` matters for the JVM: a Spring Boot app takes tens of seconds to
start, and without it the container is marked unhealthy and restarted before it
ever finishes booting — a restart loop caused entirely by impatience.

### depends_on: started is not ready

```yaml
# ❌ waits only for the container to START
depends_on:
  - postgres

# ✅ waits for it to be HEALTHY
depends_on:
  postgres: { condition: service_healthy }
```

PostgreSQL's container is "started" long before it accepts connections. Without
`service_healthy`, the backend starts, fails to connect, and crash-loops until
the database happens to be ready.

> **A real bug from this project.** Even `service_healthy` was not enough for
> the backup container. PostgreSQL reports healthy as soon as it *accepts
> connections* — which is before the application has run its Flyway migrations.
> So the very first backup of a fresh deployment was taken while the
> `reconpilot_app` role did not exist yet, producing a dump that restores fine
> on the original server and **fails on a fresh one** — the only place a backup
> is ever actually needed. CI caught it on its first run. The fix was for the
> backup to wait for the thing it actually needs, not for a proxy for it.

---

## 11. Docker Compose

One file describing a whole stack.

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: ${DB_NAME:?set DB_NAME in .env}      # :? = fail if unset
      POSTGRES_PASSWORD: ${DB_OWNER_PASSWORD:?set it}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${DB_OWNER_USER}"]
      #                                 ^^ escaped: $$ means "not compose's variable"
    restart: unless-stopped

volumes:
  pgdata:
```

### Variable syntax worth knowing

| Syntax | Meaning |
|---|---|
| `${VAR}` | substitute, empty if unset |
| `${VAR:-default}` | substitute, or use the default |
| `${VAR:?message}` | **fail with this message if unset** |
| `$$VAR` | a literal `$VAR` — for the container's own shell |

`${VAR:?message}` is excellent: a missing secret fails immediately with a clear
message instead of starting a broken container.

### Useful commands

```bash
docker compose up -d --build          # build and start detached
docker compose ps                     # status, with health
docker compose logs -f backend        # follow one service
docker compose exec backend sh        # shell into a RUNNING container
docker compose run --rm backup /ops/restore-drill.sh   # one-off container
docker compose stop                   # stop, keep containers and volumes
docker compose down                   # remove containers, keep volumes
docker compose down -v                # ☠️ remove volumes too
docker compose config                 # print the resolved file — great for debugging
```

`exec` runs inside an existing container; `run` starts a new one. Using `exec`
when the container is not running is a common confusion.

### Overlay files

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml up -d
```

Later files merge over earlier ones. ReconPilot uses this to add a real TLS
certificate on a public host without touching the base file — the laptop cannot
use Let's Encrypt anyway, so that configuration does not belong in the shared
file.

---

## 12. Secrets and configuration

### The rules

```dockerfile
# ❌ baked into a layer — extractable by anyone with the image, forever
ENV API_KEY=sk-ant-12345

# ❌ visible in `docker history`
ARG DB_PASSWORD

# ✅ supplied at run time
```

```yaml
environment:
  RECONPILOT_JWT_SECRET: ${RECONPILOT_JWT_SECRET:?set it in .env}
```

```bash
# .env — gitignored, never committed
RECONPILOT_JWT_SECRET=<generated with: openssl rand -base64 48>
```

### Defence in depth

Configuration alone is not a control, because configuration can be wrong.
ReconPilot adds a `StartupSecretCheck` that **refuses to boot** if any
credential is still a development default:

```
Refusing to start: development secrets are in use.
  - RECONPILOT_JWT_SECRET is still the development value...
```

And it runs in `@PostConstruct`, not as an `ApplicationRunner`, so the failure
aborts the context refresh and **the server never binds a port**. A guard that
fires after the door is open is not a guard.

---

## 13. Security

### Do not run as root

By default, a container's process runs as **root inside the container**, and
with a bind mount that is root on files it can reach.

```dockerfile
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
USER app
```

### Pin your base images

```dockerfile
FROM eclipse-temurin:21-jre-alpine        # reasonable: major version pinned
FROM eclipse-temurin:latest               # ☠️ changes under you without warning
```

`latest` means your build is not reproducible and a base image update can break
or silently change your application.

### Smaller is safer

| Base | Size | Notes |
|---|---|---|
| `eclipse-temurin:21` | ~450 MB | full Debian userland |
| `eclipse-temurin:21-jre-alpine` | ~180 MB | Alpine + JRE |
| `distroless` | ~90 MB | no shell at all — hard to exploit, hard to debug |

Fewer packages means fewer CVEs and less for an attacker to use. Alpine uses
**musl** rather than glibc, which very occasionally matters for native
libraries — worth knowing as a caveat.

### Scan

```bash
docker scout cves reconpilot-backend
trivy image reconpilot-backend
```

---

## 14. Debugging containers

```bash
docker compose logs -f --tail 100 backend      # what is it saying
docker compose exec backend sh                 # get inside a running container
docker inspect <container>                     # full configuration, mounts, network
docker stats                                   # live CPU and memory per container
docker compose config                          # the resolved compose file
```

### When a container exits immediately

```bash
docker run -d --name t myimage
docker ps -a                       # it is there, Exited (1)
docker logs t                      # the reason
```

Note: with `--rm` the container is **removed on exit**, so `docker logs` finds
nothing. Drop `--rm` when debugging a crash — a real trap when you are chasing
a startup failure.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | clean exit |
| 1 | application error |
| 125 | the docker command itself failed |
| 126 | the command is not executable |
| 127 | command not found |
| **137** | **SIGKILL — usually the OOM killer** |
| 143 | SIGTERM — a normal `docker stop` |

**137 is the one to recognise.** It almost always means the container exceeded
its memory limit and the kernel killed it. Check `MaxRAMPercentage` and the
compose limit.

---

## 15. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `localhost` inside a container | Means that container; use the service name |
| 2 | `RUN rm secret` after `COPY secret` | The file is still in the earlier layer |
| 3 | `chown -R` after `COPY` | Duplicates every file; use `COPY --chown` |
| 4 | `COPY . .` before installing deps | Destroys layer caching |
| 5 | `npm install` in a Dockerfile | Not reproducible; use `npm ci` |
| 6 | Hard-coded `-Xmx` | Ignores the container limit; use `MaxRAMPercentage` |
| 7 | Heap set to 100% of the limit | OOM-killed with no diagnostics; leave headroom |
| 8 | `ENTRYPOINT` shell form without `exec` | The shell is PID 1; SIGTERM never reaches the app |
| 9 | `depends_on` without a condition | "Started" is not "ready" |
| 10 | No `start_period` on a JVM healthcheck | Restart loop during normal startup |
| 11 | `docker compose down -v` | Deletes your volumes and your data |
| 12 | Publishing a database port | It will be found and scanned |
| 13 | `FROM ...:latest` | Builds are not reproducible |
| 14 | Running as root | Unnecessary privilege, especially with bind mounts |
| 15 | `ENV SECRET=...` | Baked into the image and `docker history` |
| 16 | No `.dockerignore` | Slow builds; `.git` and secrets in the context |
| 17 | Kafka advertising `localhost` | Clients reconnect to themselves |
| 18 | Expecting `docker logs` after `--rm` | The container is gone; drop `--rm` to debug |
| 19 | `exec` on a stopped container | Use `run` for a one-off |
| 20 | Exit 137 read as an app bug | It is the OOM killer |

---

## What to do next

1. **Run `docker history reconpilot-prod-backend`.** You will see every layer
   and its size. Find the jar. That output is the whole of section 2 made
   concrete.
2. **Break the cache on purpose.** Move `COPY src ./src` above the
   `dependency:go-offline` line and rebuild. Watch Maven re-download
   everything. Then put it back.
3. **Try `docker compose exec backend sh`** and run `ping postgres`. It
   resolves. Then `ping localhost` and notice it is the backend itself.

Next: `06-postgresql.md`.
