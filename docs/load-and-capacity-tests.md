# Load and capacity tests

Two different tests live in this repo. They answer different questions.

| | `k6/load-test.js` | `scripts/capacity-test.sh` |
|---|---|---|
| Question | Does the system behave correctly under a realistic mixed load? | How much read traffic can it sustain before it stops keeping up? |
| Run | `k6 run k6/load-test.js` | `scripts/capacity-test.sh` |
| Load | Fixed: 500 reads/s plus 5 bookings/s for 1 minute | Stepped: several constant read rates, 30s each |
| Checks | Errors, read latency, read-your-writes | Achieved rate, errors, latency, replica load, CPU and memory |
| Result | Thresholds pass or fail the whole run | PASS or FAIL per rate level |

There is no `load-test.sh`; the first test is the k6 script `k6/load-test.js`.

## 1. `k6/load-test.js` (correctness under load)

### What it does

Setup creates 20 events (5 cities x 4) with a large ticket quantity, then waits 6 seconds (`SEED_WAIT_SECONDS`) so the
replica has them. Two scenarios then run at constant arrival rates for 1 minute:

| Scenario | Rate | What each iteration does |
|---|---|---|
| `browse` | 500 requests/s (`READ_RPS`) | Anonymous read, no token: 60% list events of a random city, 40% view a random event |
| `book` | 5 bookings/s (`WRITE_RPS`) | Book 1 ticket, then immediately read that booking **with the `X-Read-After` token** |

### Criteria (k6 thresholds)

The run fails if any of these is false:

| Criterion | Meaning |
|---|---|
| `http_req_failed < 1%` | Fewer than 1% of all requests error (non-2xx/3xx, or connection failure) |
| `http_req_duration{scenario:browse} p(95) < 200 ms` | 95% of anonymous reads finish within 200 ms |
| `stale_reads_after_write count == 0` | **Zero** booking reads returned anything but 200 right after the booking. This is the read-after-write guarantee: with the token, the read goes to the primary, so a 404 from a lagging replica must never happen |

It also records per-request checks (`list 200`, `detail 200`, `booked 201`) that appear in the summary.

### What it does not check

- Cache behaviour. It never looks at `X-Cache` or `X-Served-By`.
- Freshness of event availability after bookings. The read-your-writes check covers the booking read only.
- Maximum throughput. 500 reads/s is far below what the app can do.

## 2. `scripts/capacity-test.sh` (how much can it take)

### What it does

For each rate in `LEVELS` (default `2000 4000 6000 8000 12000`) it runs `k6/capacity-test.js` once at that constant
request rate for `DURATION` (default 30s) and prints one row. Each run first seeds events, waits 8 seconds for the
replica and second invalidation to settle, and warms the cache.

| Setting | Values |
|---|---|
| `TARGET=hot` (default) | Every request reads the same event: one hot key |
| `TARGET=mixed` | 20 events plus city listings (many keys) |
| `LEVELS="2000 5000 10000"` | Rates to try (requests per second) |
| `DURATION=30s` | Time at each rate |

k6 uses a constant-arrival-rate executor: it tries to start the target number of requests every second no matter how
slow the system is. When the system cannot keep up, k6 counts the requests it could not start as
`dropped_iterations`.

### Criteria for PASS / FAIL

A level is **PASS** only if all three hold, otherwise **FAIL**:

| # | Criterion | How it is computed |
|---|---|---|
| 1 | Achieved rate >= 97% of target | `achieved = target x (1 - dropped_iterations / (target x seconds))` |
| 2 | Error rate < 1% | `http_req_failed` |
| 3 | p95 latency < 200 ms | `http_req_duration` p(95) |

These thresholds are my choice for this project, not an industry standard. "FAIL" means the system stopped keeping
up with the requested rate, not that requests started to error. In every run so far the error rate was 0%.

### Columns it prints

| Column | Meaning |
|---|---|
| `target_rps`, `achieved` | Requested and delivered requests per second |
| `p95_ms`, `p99_ms` | Latency percentiles |
| `errors`, `dropped` | Failed requests (%) and requests k6 could not start |
| `replica_xacts` | Transactions committed on the replica during the run: a proxy for database load. A hot key should stay near the background level (about 16 per run) however high the rate is |
| `result` | PASS or FAIL by the three criteria |

After the table it prints resource use per level, sampled about every 2 seconds by `scripts/resource-sampler.py`
after the first 10 seconds of the run (the seeding phase):

| Column | Meaning |
|---|---|
| `app_cpu`, `redis_cpu` | Average/peak CPU in cores (1.0 = one fully busy core) |
| `app_mem`, `redis_mem` | Peak memory in MiB |
| `replica_cpu`, `primary_cpu` | Peak CPU in cores |
| `k6_cpu` | Average cores used by the load generator |
| `dproxy_cpu` | Average cores used by Docker's port proxy (`docker-proxy`), which forwards `localhost:8080` into the app container. It is outside every container, so `docker stats` never shows it |
| `host_cpu` | Whole-machine CPU busy % (this machine has 20 cores) |

**Results below were taken before the in-process cache and the caching proxy were added. For the results after those
changes (50,000 RPS direct, 60,000+ through the proxy) see [cache-advanche.md](../cache-advanche.md).** Set
`BASE_URL=http://localhost:8088` to test through the proxy.

## 3. Results so far (this laptop)

Everything runs on one machine: the app, Redis, Postgres, Kafka and the load generator. The app container has no CPU
or memory limit.

### Hot key, with resource sampling

| Target | Achieved | p95 | Result | App CPU avg/peak (cores) | App mem (MiB) | Redis CPU | k6 CPU | docker-proxy CPU | Host CPU |
|---|---|---|---|---|---|---|---|---|---|
| 4,000 | 4,000 | 4.3 ms | PASS | 3.9 / 4.6 | 1,926 | 0.30 | 2.9 | 1.1 | 59% |
| 8,000 | 7,971 | 13.8 ms | PASS | 7.0 / 7.4 | 1,812 | 0.35 | 5.2 | 2.1 | 88% |
| 10,000 | 9,921 | 21.7 ms | PASS | 7.1 / 7.6 | 1,814 | 0.34 | 5.9 | 2.3 | 92% |
| 12,000 | 11,191 | 106.8 ms | FAIL (93% of target) | 6.9 / 7.5 | 1,814 | 0.36 | 6.7 | 2.4 | 96% |

Redis memory was 7 to 9 MiB, and replica and primary CPU stayed under 0.25 cores at every level. `replica_xacts` stayed
at 16. An earlier sampled run (a different, noisier run) failed at 10,000 and at the cold-start 2,000 level; it also
under-reported k6 CPU (see section 5), so it is not shown.

### Hot key, earlier run without sampling

| Target | Achieved | p95 | Result |
|---|---|---|---|
| 8,000 | 7,940 | 15 ms | PASS |
| 10,000 | 9,871 | 24 ms | PASS |
| 11,000 | 10,726 | 37 ms | PASS |
| 12,000 | 11,306 | 79 ms | FAIL (94% of target) |

### Many keys (mixed), without sampling

| Target | Achieved | p95 | Result |
|---|---|---|---|
| 4,000 | 3,987 | 7 ms | PASS |
| 8,000 | 7,762 | 49 ms | PASS |
| 10,000 | 9,033 | 223 ms | FAIL (90% of target and p95 over 200 ms) |

## 4. What the numbers say about the bottleneck

- **Memory is not the bottleneck.** The app used about 1.8 GB (it settles there once the JVM heap has grown), Redis
  under 10 MiB, and the machine has about 15.6 GB. I did not measure JVM heap limits or GC pauses, so I cannot
  rule out GC cost.
- **CPU is the limit, and it is the whole machine's CPU, shared between the system under test and the load
  generator.** Host CPU went from 59% to 96% across the levels, and the first level to miss the target (12,000) is
  the one at 96%.
- **Redis and the databases are not the limit.** Redis peaked at about 0.45 cores (it is single-threaded) and the
  replica and primary stayed idle.

### Where the 20 cores go at 12,000 requests per second

| Consumer | Cores |
|---|---|
| App (Spring Boot, in its container) | 6.9 |
| k6 (load generator, running `nice`d at lower priority) | 6.7 |
| docker-proxy (forwards `localhost:8080` into the container) | 2.4 |
| Redis, replica, primary | about 0.8 |
| Everything else (desktop, browser, IDE, Docker daemon, kernel networking) | about 2 |
| **Total** | **about 19 of 20 (96% busy)** |

The "missing 10 cores" from the first sampled run were not missing. Two things hid them:

1. The sampler measured k6 with `ps %cpu`, which is a lifetime average and so included the idle seeding phase. It
   showed about 3.5 cores when k6 was really using 5 to 7. The sampler now measures CPU between samples.
2. `docker-proxy` uses 1 to 2.4 cores and is not part of any container, so `docker stats` does not show it. Testing
   through `localhost:8080` pays for this.

What this means: on this machine, roughly 9 of the 19 busy cores go to the load generator and Docker's port proxy
rather than to the app. The app itself needs about 7 cores for 10,000 requests per second, about 0.7 cores per 1,000.
A separate load-generator machine would therefore raise the measured ceiling.

I also tried sending the load straight to the container's IP address, bypassing `docker-proxy`. That did not clearly
raise the ceiling: 10,000 achieved 9,707 (p95 35 ms) and 12,000 achieved 10,801 (p95 143 ms), within run-to-run
noise of the numbers above.

## 5. Caveats

- **Results are noisy.** At a target of 10,000, three runs achieved 9,871 (PASS), 9,157 (FAIL) and 9,921 (PASS). The
  ceiling on this laptop is therefore about 10,000 requests per second, give or take a couple of thousand, not an
  exact number.
- **The sampler adds load.** `docker stats` polling uses CPU itself, which can make a sampled run look slightly worse
  than an unsampled one.
- **The first level can be a cold start.** In one earlier run the 2,000 level failed with an app CPU peak of 14.6
  cores while higher levels passed. Warm-up (JIT compilation) is the likely cause, but I did not confirm it. Treat the
  first level as a warm-up, or run it twice.
- **Load generator on the same host.** The result is a floor for the app, not its ceiling. Run k6 from another machine
  for a truer number.
- **Reads only.** The capacity test measures cache-served reads. It says nothing about write throughput.
