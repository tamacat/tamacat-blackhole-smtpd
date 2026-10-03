# tamacat-blackhole-smtpd

Blackhole SMTP server written in Java
  * Emails cannot be sent.
  * Emails cannot be stored on disk or memory.
  * Emails can write logs only.
  * No external libraries: the server uses `java.base` only (Java 25).

## Log format

One line per event on stdout (logfmt style, UTF-8). Each accepted message is
logged as a `mail` event; the message body is discarded.

```
2026-10-03T03:00:00.123Z INFO  start version=2.0 address=0.0.0.0 port=25 hostname=smtpd max-message-size=10485760 max-connections=100
2026-10-03T03:00:05.456Z INFO  mail id=3f2a9c1e.1 remote=172.17.0.1:50412 helo=client.example from=from@example.com to=to@example.com size=342 subject="テスト mail" message-id=<1@example.com>
```

MIME encoded-word Subjects (`=?ISO-2022-JP?B?...?=` etc.) are decoded. Values
with spaces or control characters are quoted and escaped, so CR/LF in a header
cannot forge log lines. `LOG_LEVEL=DEBUG` also logs connections and every SMTP
command.

## Configuration

Environment variables (or `-D` system properties of the same name):

| Name               | Default        | Description                                |
|--------------------|----------------|--------------------------------------------|
| `BIND_ADDRESS`     | `0.0.0.0`      | Listen address                             |
| `BIND_PORT`        | `25`           | Listen port (the first argument overrides) |
| `SMTP_HOSTNAME`    | local hostname | Host name in the 220 / 250 replies         |
| `MAX_MESSAGE_SIZE` | `10485760`     | Bytes; larger messages are rejected (552)  |
| `MAX_RECIPIENTS`   | `100`          | RCPT TO per message                        |
| `MAX_CONNECTIONS`  | `100`          | Concurrent sessions                        |
| `IDLE_TIMEOUT`     | `300`          | Seconds without input before disconnect    |
| `LOG_LEVEL`        | `INFO`         | `DEBUG` / `INFO` / `WARN` / `ERROR`        |

Supported commands: EHLO, HELO, MAIL, RCPT, DATA, RSET, NOOP, VRFY, HELP, QUIT
(STARTTLS / AUTH are not supported).

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

The multi-stage `Dockerfile` builds the jar and a minimal jlink runtime
(`java.base` + `jdk.charsets`); no prior `mvn package` is needed. The runtime
only needs glibc, so the final image is
[distroless](https://github.com/GoogleContainerTools/distroless)
`base-nossl-debian13:nonroot` — no OpenSSL, no shell, no package manager —
and runs as uid 65532 (`java` has `CAP_NET_BIND_SERVICE` to listen on port 25).

```sh
docker build -t tamacat/tamacat-blackhole-smtpd .
```

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
