# syntax=docker/dockerfile:1
#
# tamacat-blackhole-smtpd — multi-stage build from this directory (no git clone).
#
# The server depends on java.base only, so instead of a full JRE the final
# image carries a jlink'ed runtime: java.base + jdk.charsets (the latter
# decodes ISO-2022-JP / Shift_JIS encoded Subject headers for the log).
#
# That runtime needs only glibc and zlib (libstdc++ is built into the JDK), so
# the final stage is distroless "base-nossl" (glibc, CA certificates, tzdata)
# plus Debian's zlib1g — no OpenSSL, no shell, no package manager.
#
#   docker build -t tamacat/tamacat-blackhole-smtpd .
#   docker run --rm -d -p 1025:25 tamacat/tamacat-blackhole-smtpd

FROM maven:3.9.16-eclipse-temurin-25 AS build

RUN apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates curl gnupg libcap2-bin \
	&& rm -rf /var/lib/apt/lists/*

# The runtime is jlink'ed from the Amazon Corretto JDK package
# (java-25-amazon-corretto-jdk) of Corretto's apt repository rather than from
# the JDK in this image: the official Docker images pick up a new Java security
# release days to weeks later. apt verifies the package against the
# repository signed with Corretto's GPG release key (fingerprint pinned below).
# CI passes the latest Corretto 25 version (see ci-release.yml); the default
# is for local builds.
ARG CORRETTO_VERSION=25.0.5.0.9
ARG CORRETTO_SIGNER_FINGERPRINT=6DC3636DAE534049C8B94623A122542AB04F24E3
ENV CORRETTO_PACKAGE=java-25-amazon-corretto-jdk \
	CORRETTO_HOME=/usr/lib/jvm/java-25-amazon-corretto
RUN set -eu; \
	keyring=/usr/share/keyrings/corretto.gpg; \
	curl -fsSL https://apt.corretto.aws/corretto.key | gpg --batch --dearmor -o "${keyring}"; \
	gpg --batch --show-keys --with-colons "${keyring}" | grep -q "^fpr:::::::::${CORRETTO_SIGNER_FINGERPRINT}:"; \
	echo "deb [signed-by=${keyring}] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list; \
	apt-get update; \
	cd /tmp; \
	apt-get download "${CORRETTO_PACKAGE}=1:${CORRETTO_VERSION}-1"; \
	mkdir -p /opt/corretto; \
	mv "${CORRETTO_PACKAGE}"_*.deb /opt/corretto/corretto.deb; \
	dpkg-deb -x /opt/corretto/corretto.deb /opt/corretto/root; \
	rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Tests run in CI (mvn verify) before the image build; skip them here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# The runtime goes where the Corretto package installs it, so the files can be
# attributed to that package (see the status.d entry below).
# jlink drops IMPLEMENTOR etc. from the release file; without them scanners
# (e.g. Docker Scout, Amazon Inspector) cannot tell Corretto from Oracle Java
# and report "oracle/jre".
RUN set -eu; \
	jdk="/opt/corretto/root${CORRETTO_HOME}"; \
	. "${jdk}/release"; \
	"${jdk}/bin/jlink" \
		--add-modules java.base,jdk.charsets \
		--strip-debug \
		--no-man-pages \
		--no-header-files \
		--compress=zip-6 \
		--release-info "add:IMPLEMENTOR=\"${IMPLEMENTOR}\":IMPLEMENTOR_VERSION=\"${IMPLEMENTOR_VERSION}\":JAVA_RUNTIME_VERSION=\"${JAVA_RUNTIME_VERSION}\"" \
		--output "/rootfs${CORRETTO_HOME}"

# Distrust the roots of the compromised eMudhra intermediate CAs
# (CVE-2026-91403, CVE-2026-114820; https://openjdk.org/emudhra) until a
# runtime that removes them is out; Corretto 25.0.5.0.9 still ships them.
# Matched by subject CN because aliases differ between vendors
# ("cn_emsign_root_ca__g1,..." in Temurin, "emsignrootcag1 [jdk]" in the
# OpenJDK advisory); roots that are not present are simply skipped. This
# server never makes TLS connections.
RUN set -eu; \
	keytool="/opt/corretto/root${CORRETTO_HOME}/bin/keytool"; \
	cacerts="/rootfs${CORRETTO_HOME}/lib/security/cacerts"; \
	distrusted='^Owner: CN=(emSign Root CA - G1|emSign Root CA - G2|emSign ECC Root CA - G3|AAA Certificate Services),'; \
	list() { \
		"${keytool}" -list -v -keystore "${cacerts}" -storepass changeit \
			| awk -v re="${distrusted}" '/^Alias name: /{alias=substr($0, 13)} $0 ~ re {print alias}'; \
	}; \
	list | while IFS= read -r alias; do \
		echo "removing: ${alias}"; \
		"${keytool}" -delete -keystore "${cacerts}" -storepass changeit -alias "${alias}"; \
	done; \
	test -z "$(list)"

# Let the non-root user bind port 25. Docker (20.10+) allows that anyway, but
# Podman and Kubernetes do not, so grant CAP_NET_BIND_SERVICE to java itself
# (the final stage has no shell, so it is set here and kept by COPY).
# A binary with file capabilities runs in secure mode, where glibc ignores the
# $ORIGIN-relative RPATH java uses to find libjli.so; put it on a system path.
RUN set -eu; \
	setcap cap_net_bind_service=+ep "/rootfs${CORRETTO_HOME}/bin/java"; \
	mkdir -p /rootfs/usr/lib; \
	cp "/rootfs${CORRETTO_HOME}/lib/libjli.so" /rootfs/usr/lib/libjli.so

# Register the runtime as the Corretto package in /var/lib/dpkg/status.d, the
# way distroless records its packages, so scanners attribute it to Amazon
# Corretto (and match Corretto's advisories) instead of a bare "oracle/jre".
# The control fields come from the package; Installed-Size and the md5sums
# list describe the jlink'ed subset that is actually in the image.
RUN set -eu; \
	status=/rootfs/var/lib/dpkg/status.d; \
	mkdir -p "${status}"; \
	size="$(du -sk "/rootfs${CORRETTO_HOME}" | cut -f1)"; \
	dpkg-deb -f /opt/corretto/corretto.deb \
		| sed "s/^Installed-Size: .*/Installed-Size: ${size}/" > "${status}/${CORRETTO_PACKAGE}"; \
	(cd /rootfs && find "${CORRETTO_HOME#/}" usr/lib/libjli.so -type f -exec md5sum {} +) \
		> "${status}/${CORRETTO_PACKAGE}.md5sums"; \
	cat "${status}/${CORRETTO_PACKAGE}"

# Corretto's java links the system zlib (libz.so.1), which distroless
# base-nossl does not have. Take zlib1g from Debian 13 (the distroless base)
# and register it in /var/lib/dpkg/status.d the way distroless does, so
# vulnerability scanners keep tracking it like the other OS packages.
FROM debian:13-slim AS zlib
RUN set -eu; \
	apt-get update; \
	cd /tmp; \
	apt-get download zlib1g; \
	mkdir -p /rootfs/var/lib/dpkg/status.d ctrl; \
	dpkg-deb -x zlib1g_*.deb /rootfs; \
	dpkg-deb -e zlib1g_*.deb ctrl; \
	dpkg-deb -f zlib1g_*.deb > /rootfs/var/lib/dpkg/status.d/zlib1g; \
	cp ctrl/md5sums /rootfs/var/lib/dpkg/status.d/zlib1g.md5sums; \
	rm -rf /var/lib/apt/lists/*

FROM gcr.io/distroless/base-nossl-debian13:nonroot

COPY --from=zlib /rootfs/ /
COPY --from=build /rootfs/ /
COPY --from=build /build/target/tamacat-blackhole-smtpd.jar /opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar

ENV BIND_PORT=25

EXPOSE 25

# distroless "nonroot" (uid 65532)
USER nonroot

ENTRYPOINT ["/usr/lib/jvm/java-25-amazon-corretto/bin/java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "/opt/tamacat-blackhole-smtpd/tamacat-blackhole-smtpd.jar"]
