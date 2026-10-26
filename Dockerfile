# syntax=docker/dockerfile:1
#
# tamacat-blackhole-smtpd — multi-stage build from this directory (no git clone).
#
# The runtime is the official Amazon Corretto image. Java is installed there
# as the apk package amazon-corretto-25 (from apk.corretto.aws), so
# vulnerability scanners such as Amazon Inspector judge it with Corretto's
# advisories; a jlink'ed runtime that belongs to no package is reported as
# "oracle/jre" and matched against Oracle Java's affected versions instead.
#
#   docker build -t tamacat/tamacat-blackhole-smtpd .
#   docker run --rm -d -p 1025:25 tamacat/tamacat-blackhole-smtpd

FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Tests run in CI (mvn verify) before the image build; skip them here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM amazoncorretto:25-alpine3.24

# apk upgrade picks up Alpine security fixes and, through the apk.corretto.aws
# repository the image already has, a new Corretto release before the image
# tag itself is refreshed. CI passes the latest Corretto version so that a new
# release invalidates the cached layer and this step runs again.
ARG CORRETTO_VERSION=unknown
RUN echo "latest Corretto: ${CORRETTO_VERSION}" \
	&& apk upgrade --no-cache \
	&& apk info -v amazon-corretto-25

# Distrust the roots of the compromised eMudhra intermediate CAs
# (CVE-2026-91403, CVE-2026-114820; https://openjdk.org/emudhra) until a
# Corretto release that removes them is out; Corretto 25.0.5.0.9 still ships
# them. Matched by subject CN because aliases differ between vendors and
# versions; roots that are not present are simply skipped. This server never
# makes TLS connections.
RUN set -eu; \
	cacerts="${JAVA_HOME}/lib/security/cacerts"; \
	distrusted='^Owner: CN=(emSign Root CA - G1|emSign Root CA - G2|emSign ECC Root CA - G3|AAA Certificate Services),'; \
	list() { \
		keytool -list -v -keystore "${cacerts}" -storepass changeit \
			| awk -v re="${distrusted}" '/^Alias name: /{alias=substr($0, 13)} $0 ~ re {print alias}'; \
	}; \
	list | while IFS= read -r alias; do \
		echo "removing: ${alias}"; \
		keytool -delete -keystore "${cacerts}" -storepass changeit -alias "${alias}"; \
	done; \
	test -z "$(list)"

# Let the non-root user bind port 25. Docker (20.10+) allows that anyway, but
# Podman and Kubernetes do not, so grant CAP_NET_BIND_SERVICE to java itself.
# A binary with file capabilities runs in secure mode, where musl ignores the
# $ORIGIN-relative RPATH java uses to find libjli.so, so list the JDK library
# directories in the musl search path explicitly.
RUN set -eu; \
	jdk="$(readlink -f "${JAVA_HOME}")"; \
	apk add --no-cache --virtual .setcap libcap-utils; \
	setcap cap_net_bind_service=+ep "${jdk}/bin/java"; \
	apk del --no-cache .setcap; \
	echo "/lib:/usr/local/lib:/usr/lib:${jdk}/lib:${jdk}/lib/server" > "/etc/ld-musl-$(uname -m).path"

COPY --from=build /build/target/tamacat-blackhole-smtpd.jar /opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar

ENV BIND_PORT=25

EXPOSE 25

USER 65532:65532

ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "/opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar"]
