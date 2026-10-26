# syntax=docker/dockerfile:1
#
# tamacat-blackhole-smtpd — multi-stage build from this directory (no git clone).
#
# The server depends on java.base only, so instead of a full JRE the final
# image carries a jlink'ed runtime: java.base + jdk.charsets (the latter
# decodes ISO-2022-JP / Shift_JIS encoded Subject headers for the log).
#
# That runtime needs nothing but glibc (zlib and libstdc++ are built into the
# JDK), so the final stage is distroless "base-nossl": glibc, CA certificates
# and tzdata only — no OpenSSL, no shell, no package manager.
#
#   docker build -t tamacat/tamacat-blackhole-smtpd .
#   docker run --rm -d -p 1025:25 tamacat/tamacat-blackhole-smtpd

FROM maven:3.9.16-eclipse-temurin-25 AS build

RUN apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates curl libcap2-bin \
	&& rm -rf /var/lib/apt/lists/*

# The runtime is jlink'ed from a Temurin JDK downloaded from Adoptium rather
# than from the JDK in this image: the official Docker images pick up a new
# Java security release days to weeks later. CI passes the latest Temurin 25
# GA release name (see ci-release.yml); the default is for local builds.
ARG JDK_RELEASE=jdk-25.0.5+8
ARG TARGETARCH
RUN set -eu; \
	case "${TARGETARCH:-amd64}" in \
		amd64) arch=x64 ;; \
		arm64) arch=aarch64 ;; \
		*) echo "unsupported arch: ${TARGETARCH}" >&2; exit 1 ;; \
	esac; \
	version="${JDK_RELEASE#jdk-}"; \
	file="OpenJDK25U-jdk_${arch}_linux_hotspot_$(echo "${version}" | tr + _).tar.gz"; \
	url="https://github.com/adoptium/temurin25-binaries/releases/download/$(echo "${JDK_RELEASE}" | sed 's/+/%2B/')/${file}"; \
	curl -fsSL -o "/tmp/${file}" "${url}"; \
	curl -fsSL -o "/tmp/${file}.sha256.txt" "${url}.sha256.txt"; \
	(cd /tmp && sha256sum -c "${file}.sha256.txt"); \
	mkdir -p /opt/jdk; \
	tar -xzf "/tmp/${file}" -C /opt/jdk --strip-components=1; \
	rm -f /tmp/OpenJDK25U-*

WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Tests run in CI (mvn verify) before the image build; skip them here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# jlink drops IMPLEMENTOR etc. from the release file; without them scanners
# (e.g. Docker Scout) cannot tell Temurin from Oracle Java and report "oracle/jre".
RUN . /opt/jdk/release; \
	/opt/jdk/bin/jlink \
		--add-modules java.base,jdk.charsets \
		--strip-debug \
		--no-man-pages \
		--no-header-files \
		--compress=zip-6 \
		--release-info "add:IMPLEMENTOR=\"${IMPLEMENTOR}\":IMPLEMENTOR_VERSION=\"${IMPLEMENTOR_VERSION}\":JAVA_RUNTIME_VERSION=\"${JAVA_RUNTIME_VERSION}\"" \
		--output /opt/jre

# Let the non-root user bind port 25. Docker (20.10+) allows that anyway, but
# Podman and Kubernetes do not, so grant CAP_NET_BIND_SERVICE to java itself
# (the final stage has no shell, so it is set here and kept by COPY).
RUN setcap cap_net_bind_service=+ep /opt/jre/bin/java

FROM gcr.io/distroless/base-nossl-debian13:nonroot

COPY --from=build /opt/jre /opt/jre
# A binary with file capabilities runs in secure mode, where glibc ignores the
# $ORIGIN-relative RPATH java uses to find libjli.so; put it on a system path.
COPY --from=build /opt/jre/lib/libjli.so /usr/lib/libjli.so
COPY --from=build /build/target/tamacat-blackhole-smtpd.jar /opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar

ENV BIND_PORT=25

EXPOSE 25

# distroless "nonroot" (uid 65532)
USER nonroot

ENTRYPOINT ["/opt/jre/bin/java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "/opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar"]
