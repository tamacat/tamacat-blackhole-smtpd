# tamacat-blackhole-smtpd

Blackhole SMTP server written in Java: a stand-in mail server for
applications that need "the mail was sent" to succeed.
  * Emails cannot be sent.
  * Emails cannot be stored on disk or memory.
  * Emails can write logs only.
  * No external libraries: the server uses `java.base` only (Java 25).

## Always a success reply

Every command gets a success reply, so the sending application never sees an
SMTP error:

* Commands in any order (RCPT or DATA without MAIL, DATA without RCPT, a
  second MAIL), syntax errors, unknown commands and over-long lines are all
  answered `250` (`354` for DATA).
* No message size limit (`SIZE` is not advertised) and no recipient limit.
* `AUTH PLAIN` / `AUTH LOGIN` (and any other mechanism) accept any user and
  password. Credentials are never logged, not even with `LOG_LEVEL=DEBUG`.
* Logging is best effort: if a header cannot be parsed or a log line cannot
  be written, the message is still answered `250`, and nothing that goes wrong
  in the server ends the process.

What is still not a success, because it cannot be:

* `STARTTLS` gets `454` (TLS is not supported). Clients send it only when
  they are configured to require TLS; configure them not to.
* A session ends with `421 4.4.2` after `IDLE_TIMEOUT` without input or
  `SESSION_TIMEOUT` in total. Clients reconnect.
* With `MAX_CONNECTIONS_PER_IP` set, connections over that limit get `421`.

## Log format

One line per event on stdout (logfmt style, UTF-8). Each accepted message is
logged as a `mail` event; the message body is discarded.

```
2026-10-03T03:00:00.123Z INFO  start version=2.0 java=25.0.4.1+1-LTS address=0.0.0.0 port=25 hostname=smtpd max-connections=100 max-connections-per-ip=0 idle-timeout=300 session-timeout=600
2026-10-03T03:00:05.456Z INFO  mail id=3f2a9c1e.1 remote=172.17.0.1:50412 helo=client.example from=from@example.com to=to@example.com size=342 subject="テスト mail" message-id=<1@example.com>
```

MIME encoded-word Subjects (`=?ISO-2022-JP?B?...?=` etc.) are decoded. Values
with spaces or control characters are quoted and escaped, so CR/LF in a header
cannot forge log lines. A message with more than `MAX_RECIPIENTS` recipients
logs the first ones and `to-omitted=<count>`. `LOG_LEVEL=DEBUG` also logs
connections and every SMTP command.

## Configuration

Environment variables (or `-D` system properties of the same name). A value
that is not a number or is out of range is replaced by its default (with a
`config` warning in the log), so a typo does not keep the server from
starting.

| Name               | Default        | Description                                |
|--------------------|----------------|--------------------------------------------|
| `BIND_ADDRESS`     | `0.0.0.0`      | Listen address                             |
| `BIND_PORT`        | `25`           | Listen port (the first argument overrides) |
| `SMTP_HOSTNAME`    | local hostname | Host name in the 220 / 250 replies         |
| `MAX_RECIPIENTS`   | `100`          | Recipients logged per message; more are accepted and counted |
| `MAX_CONNECTIONS`  | `100`          | Concurrent sessions; further connections wait until one ends |
| `MAX_CONNECTIONS_PER_IP` | `0`      | Concurrent sessions from one address, `0` = no limit |
| `IDLE_TIMEOUT`     | `300`          | Seconds without input before disconnect, `0` = no limit |
| `SESSION_TIMEOUT`  | `600`          | Seconds a session may last in total, even while the client keeps sending, `0` = no limit |
| `LOG_LEVEL`        | `INFO`         | `DEBUG` / `INFO` / `WARN` / `ERROR`        |

`SESSION_TIMEOUT` keeps a client that sends very slowly from holding a session
forever. `MAX_CONNECTIONS_PER_IP` also stops one client from taking every
session, but it is off by default because it refuses connections: behind NAT
or a published container port all clients may share one address (for example
the container network's gateway when they connect from the Docker / Podman
host).

This is a plain-text SMTP server that accepts any message and any
credentials, and its log holds addresses and subjects. Keep it on a test or
internal network; do not expose port 25 to the Internet.

Supported commands: EHLO, HELO, MAIL, RCPT, DATA, AUTH, RSET, NOOP, VRFY,
HELP, QUIT (STARTTLS is not supported).

## How to run tamacat-blackhole-smtpd in Docker

### DockerHub: tamacat/tamacat-blackhole-smtpd
* https://hub.docker.com/r/tamacat/tamacat-blackhole-smtpd

Tags: `<version>-b<YYYYMMDD>` (e.g. `2.0-b20261003`), `<version>-latest`, `latest`.

### Docker run (0.0.0.0:1025->25/tcp)

```sh
docker run --rm -it -d -p 1025:25 -t tamacat/tamacat-blackhole-smtpd
```

### Send a test mail

```sh
cd src/test/resources
sh testmail.sh 1025
```

## Build from Source Code

```sh
git clone https://github.com/tamacat/tamacat-blackhole-smtpd.git
cd tamacat-blackhole-smtpd
```

### Maven build and run

```sh
mvn package
java -jar target/tamacat-blackhole-smtpd.jar 1025
```

### Docker build

The multi-stage `Dockerfile` builds the jar (no prior `mvn package` is
needed) and runs it on the official
[Amazon Corretto](https://aws.amazon.com/corretto/) image
`amazoncorretto:25-alpine3.24`, as uid 65532 (`java` has
`CAP_NET_BIND_SERVICE` to listen on port 25).

Java is installed there as the apk package `amazon-corretto-25`, so
vulnerability scanners such as Amazon Inspector judge it with Corretto's
advisories. (A jlink'ed runtime that belongs to no package is reported as
`oracle/jre` and matched against Oracle Java's affected versions.)
`apk upgrade` picks up Alpine fixes and new Corretto releases from
apk.corretto.aws before the image tag is refreshed; CI passes the latest
Corretto version as `CORRETTO_VERSION` so that a new release invalidates the
cached layer.

```sh
docker build -t tamacat/tamacat-blackhole-smtpd .
```

Packages the server does not use are removed at the end of the build:
`apk-tools` (with `libapk`, `ca-certificates-bundle`), OpenSSL
(`libssl3`, `libcrypto3`, `ssl_client`) and `musl-utils` (with `scanelf`).
Java uses its own TLS implementation and `cacerts`, and no `openssl` command
is installed. apk removes them itself, so the package database stays
accurate for vulnerability scanners. The image therefore has no package
manager; extend it from an earlier build stage if you need one.

The roots of the compromised eMudhra CAs (CVE-2026-91403, CVE-2026-114820,
https://openjdk.org/emudhra) are removed from the runtime's `cacerts`.

When a Java security release is out (quarterly, the third Tuesday of
January / April / July / October), run the workflow manually to publish
without waiting for the weekly build.

## CI / Release (GitHub Actions)

`.github/workflows/ci-release.yml` runs on push / pull request to `master`,
weekly (Monday 03:00 UTC, full rebuild without layer cache to pick up base
image fixes) and on manual dispatch:

1. **verify** — `mvn verify`, gitleaks, image build (GitHub Actions layer
   cache), Trivy scan (fails on fixable HIGH/CRITICAL; exceptions go to
   `.trivyignore`), CycloneDX SBOM, smoke test that sends a mail with curl
   and checks the log.
2. **publish** (not on pull requests) — waits for approval on the
   `production` environment, pushes the exact image verified above to Docker
   Hub, signs it with cosign (keyless) and attaches the SBOM.

One-time setup in the GitHub repository settings:

* Environments → create `production` and add yourself as a required reviewer
  (the self-review gate before publishing).
* Secrets → `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a Docker Hub access
  token with Read & Write scope).

Verify a published image:

```sh
cosign verify tamacat/tamacat-blackhole-smtpd:latest \
  --certificate-identity-regexp 'https://github.com/tamacat/tamacat-blackhole-smtpd/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```
