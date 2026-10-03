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

FROM maven:3-eclipse-temurin-24 AS build

RUN apt-get update \
	&& apt-get install -y --no-install-recommends libcap2-bin \
	&& rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Tests run in CI (mvn verify) before the image build; skip them here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

RUN jlink \
		--add-modules java.base,jdk.charsets \
		--strip-debug \
		--no-man-pages \
		--no-header-files \
		--compress=zip-6 \
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
