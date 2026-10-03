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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Minimal logfmt-style logger writing one line per event to stdout (UTF-8):
 *
 * <pre>
 * 2026-10-03T03:00:00.123Z INFO  mail id=3f2a9c1e.1 remote=172.17.0.1:50412 from=a@example.com to=b@example.com size=342 subject="Hello"
 * </pre>
 *
 * Values are quoted when needed and control characters are escaped, so a
 * client cannot forge extra log lines (e.g. with CR/LF in a Subject).
 */
public final class Log {

	public enum Level { DEBUG, INFO, WARN, ERROR }

	private static final PrintStream STDOUT =
			new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

	static volatile Level level = Level.valueOf(Config.value("LOG_LEVEL", "INFO").toUpperCase(Locale.ROOT));
	static volatile Consumer<String> sink = STDOUT::println;

	private Log() {
	}

	public static boolean isDebugEnabled() {
		return level == Level.DEBUG;
	}

	public static void debug(String event, Object... keyValues) {
		log(Level.DEBUG, event, keyValues);
	}

	public static void info(String event, Object... keyValues) {
		log(Level.INFO, event, keyValues);
	}

	public static void warn(String event, Object... keyValues) {
		log(Level.WARN, event, keyValues);
	}

	public static void error(String event, Object... keyValues) {
		log(Level.ERROR, event, keyValues);
	}

	/** @param keyValues alternating keys and values; pairs with a null value are omitted. */
	static void log(Level lv, String event, Object... keyValues) {
		if (lv.compareTo(level) < 0) {
			return;
		}
		StringBuilder sb = new StringBuilder(128)
				.append(Instant.now().truncatedTo(ChronoUnit.MILLIS))
				.append(' ').append(String.format("%-5s", lv))
				.append(' ').append(event);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			if (keyValues[i + 1] != null) {
				sb.append(' ').append(keyValues[i]).append('=');
				appendValue(sb, String.valueOf(keyValues[i + 1]));
			}
		}
		sink.accept(sb.toString());
	}

	static void appendValue(StringBuilder sb, String value) {
		boolean quote = value.isEmpty();
		for (int i = 0; i < value.length() && !quote; i++) {
			char c = value.charAt(i);
			quote = c <= ' ' || c == '"' || c == '=' || c == '\\' || c == 0x7f;
		}
		if (!quote) {
			sb.append(value);
			return;
		}
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\r' -> sb.append("\\r");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < ' ' || c == 0x7f) {
						sb.append(String.format("\\x%02x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}
}
