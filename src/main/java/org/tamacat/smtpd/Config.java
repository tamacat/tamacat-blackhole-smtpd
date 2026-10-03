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
 *
 * <pre>
 * BIND_ADDRESS      listen address              (default 0.0.0.0)
 * BIND_PORT         listen port                 (default 25, the first program argument overrides it)
 * SMTP_HOSTNAME     host name in 220/250 lines  (default local host name)
 * MAX_MESSAGE_SIZE  bytes, advertised as SIZE   (default 10485760)
 * MAX_RECIPIENTS    RCPT TO per message         (default 100)
 * MAX_CONNECTIONS   concurrent sessions         (default 100)
 * IDLE_TIMEOUT      seconds without input       (default 300)
 * LOG_LEVEL         DEBUG | INFO | WARN | ERROR (default INFO)
 * </pre>
 */
public record Config(
		String bindAddress,
		int port,
		String hostname,
		long maxMessageSize,
		int maxRecipients,
		int maxConnections,
		int idleTimeoutSeconds) {

	public static Config load(String... args) {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : intValue("BIND_PORT", 25);
		return new Config(
				value("BIND_ADDRESS", "0.0.0.0"),
				port,
				value("SMTP_HOSTNAME", localHostName()),
				longValue("MAX_MESSAGE_SIZE", 10L * 1024 * 1024),
				intValue("MAX_RECIPIENTS", 100),
				intValue("MAX_CONNECTIONS", 100),
				intValue("IDLE_TIMEOUT", 300));
	}

	/** Same settings listening on another port (0 = any free port). */
	public Config withPort(int port) {
		return new Config(bindAddress, port, hostname, maxMessageSize, maxRecipients, maxConnections, idleTimeoutSeconds);
	}

	static String value(String name, String defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			value = System.getenv(name);
		}
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	static int intValue(String name, int defaultValue) {
		return Integer.parseInt(value(name, String.valueOf(defaultValue)));
	}

	static long longValue(String name, long defaultValue) {
		return Long.parseLong(value(name, String.valueOf(defaultValue)));
	}

	static String localHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "localhost";
		}
	}
}
