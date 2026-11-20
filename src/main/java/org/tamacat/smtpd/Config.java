/*
 * Copyright tamacat.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tamacat.smtpd;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Server settings. Each value is read from a JVM system property first
 * ({@code -DBIND_PORT=1025}), then from the environment variable of the same name.
 * A value that is not a number or out of range falls back to its default
 * (with a warning), so a typo never keeps the server from starting.
 *
 * <pre>
 * BIND_ADDRESS      listen address              (default 0.0.0.0)
 * BIND_PORT         listen port                 (default 25, the first program argument overrides it)
 * SMTP_HOSTNAME     host name in 220/250 lines  (default local host name)
 * MAX_RECIPIENTS    recipients logged per message; more are accepted and counted (default 100)
 * MAX_CONNECTIONS   concurrent sessions; more connections wait for a free one (default 100)
 * MAX_CONNECTIONS_PER_IP  concurrent sessions from one address (default 0 = no limit)
 * IDLE_TIMEOUT      seconds without input       (default 300, 0 = no limit)
 * SESSION_TIMEOUT   seconds a session may last  (default 600, 0 = no limit)
 * LOG_LEVEL         DEBUG | INFO | WARN | ERROR (default INFO)
 * </pre>
 */
public record Config(
		String bindAddress,
		int port,
		String hostname,
		int maxRecipients,
		int maxConnections,
		int maxConnectionsPerIp,
		int idleTimeoutSeconds,
		int sessionTimeoutSeconds) {

	public static Config load(String... args) {
		int port = args.length > 0
				? parse("port argument", args[0], intValue("BIND_PORT", 25, 0, 65535), 0, 65535)
				: intValue("BIND_PORT", 25, 0, 65535);
		return new Config(
				value("BIND_ADDRESS", "0.0.0.0"),
				port,
				value("SMTP_HOSTNAME", localHostName()),
				intValue("MAX_RECIPIENTS", 100, 0, Integer.MAX_VALUE),
				intValue("MAX_CONNECTIONS", 100, 1, Integer.MAX_VALUE),
				intValue("MAX_CONNECTIONS_PER_IP", 0, 0, Integer.MAX_VALUE),
				intValue("IDLE_TIMEOUT", 300, 0, Integer.MAX_VALUE),
				intValue("SESSION_TIMEOUT", 600, 0, Integer.MAX_VALUE));
	}

	/** Same settings listening on another port (0 = any free port). */
	public Config withPort(int port) {
		return new Config(bindAddress, port, hostname, maxRecipients,
				maxConnections, maxConnectionsPerIp, idleTimeoutSeconds, sessionTimeoutSeconds);
	}

	static String value(String name, String defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			value = System.getenv(name);
		}
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	static int intValue(String name, int defaultValue, int min, int max) {
		return parse(name, value(name, null), defaultValue, min, max);
	}

	static int parse(String name, String value, int defaultValue, int min, int max) {
		if (value == null) {
			return defaultValue;
		}
		try {
			int n = Integer.parseInt(value.trim());
			if (n >= min && n <= max) {
				return n;
			}
		} catch (NumberFormatException e) {
			// fall through to the default
		}
		Log.warn("config", "name", name, "value", value, "using", defaultValue);
		return defaultValue;
	}

	static String localHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "localhost";
		}
	}
}
