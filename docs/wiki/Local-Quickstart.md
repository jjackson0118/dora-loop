# Local quickstart

You can build, test and exercise the API without a GitHub account, Tailscale,
a deployment VM, or access to the author's infrastructure.

## Prerequisites

The commands below were reproduced on Linux x86_64 with JDK 21.0.12,
Docker Engine 29.1.3 and Python 3.12.3. They use Bash, Git, curl, OpenSSL,
and standard Linux utilities. WSL2 with a connected Docker daemon is a
reasonable Windows route, but has not been independently reproduced here.

- JDK **21**, available as `java` and to Gradle.
- A running Docker daemon accessible to your user. Full API tests start real
  PostgreSQL containers through Testcontainers; Docker CLI installation alone
  is insufficient. Run in a disposable development environment: Docker daemon
  access is privileged.
- Internet access to GitHub, Gradle/Maven repositories and Docker Hub on first
  use. The Gradle wrapper downloads Gradle; Testcontainers pulls its images.
- Python 3, curl and OpenSSL for the local API example; no pip packages.
- A free loopback port 18080 for the example, or choose `PORT=18081`.

## Clone and test

```bash
git clone https://github.com/jjackson0118/dora-loop.git
cd dora-loop
java -version
docker version
./gradlew build
```

Expect `BUILD SUCCESSFUL`. This compiles both modules, runs the Java tests,
and creates the executable API jar. Results are under
`core/build/reports/tests/test/` and `api/build/reports/tests/test/`.

For just the dependency-light metric library, `./gradlew :core:test` needs
JDK 21 and the first-use downloads, but no Docker daemon.

The deployment helpers have separate tests, also run by CI:

```bash
bash deploy/decide-test.sh
bash deploy/orchestrate-test.sh
bash deploy/ci-deploy-test.sh
python3 -m unittest discover -s deploy -p '*_test.py'
```

These use temporary fixtures and local HTTP servers. They do not provision,
deploy to, or fault-inject a real host. Run these commands from the repository
root. Successful commands exit zero; Python prints `OK`.

## Exercise the running API

Read [examples/local-demo.sh](https://github.com/jjackson0118/dora-loop/blob/main/examples/local-demo.sh)
for the complete Docker, application-start and curl commands, then run:

```bash
bash examples/local-demo.sh
# If 18080 is occupied:
# PORT=18081 bash examples/local-demo.sh
```

The example starts a disposable PostgreSQL 16 container on a random **loopback**
port and the built jar on loopback. It generates a fresh database password and
ingest token for this run, keeps the HTTP token in a private temporary header
file, and does not print either credential. It does not read your deployment
configuration. Do not enable shell tracing while handling credentials.

It queries an empty service report, submits a timestamped sample deployment
using `X-Dora-Ingest-Token`, replays that same event, and prints the final
report. The sample uses `environment: production` because the calculator
filters production events; this is synthetic local data, not a real release.

Expected observations:

| Step | Result |
|---|---|
| Readiness | HTTP 200, `{"status":"UP"}` |
| Empty report | Metrics and quality signals are `UNOBSERVED`, with explicit null values |
| First POST | HTTP 201, `STORED` |
| Identical POST | HTTP 200, `DUPLICATE` |
| Deployment frequency | One observation; roughly 0.03 deploys/day over the default 30-day window |
| Lead time | One observation; roughly 0.98 hours for the generated sample |
| Unverified deployments | `OK`, zero unverified out of one observation |
| Time to restore | `UNOBSERVED`: no incident/recovery was supplied |

One successful sample does not establish sustained delivery performance, so
deployment frequency can correctly remain `DEGRADED`. The script asserts the
empty-report semantics, single deployment count after replay, verification
signal and absent recovery evidence.

## Cleanup and troubleshooting

The script's exit trap stops its own Java process, removes its named database
container and anonymous volume, and deletes its private temporary files—even
when a command fails. The application is therefore not left running afterward.
Downloaded Docker images and Gradle caches remain for reuse. Remove the clone
when finished; do not run broad Docker prune commands on a shared machine.

If the full build reports no valid Docker environment, verify `docker version`
shows both client and server and your user can start containers. If the demo
reports a busy port, choose another `PORT`. A failed startup leaves application
diagnostics in the temporary log only until cleanup; inspect the script before
changing its cleanup behavior, because its temporary directory also contains
the generated credential header.

For actual VM provisioning, CI credentials, smoke decisions and replay of
retained deployment evidence, see [Deployment](Deployment.md). That operational
setup is optional and is separate from this local reproduction path.
