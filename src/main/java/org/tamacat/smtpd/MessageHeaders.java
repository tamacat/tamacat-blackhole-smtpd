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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough RFC 5322 / RFC 2047 parsing to log a few header fields
 * (Subject, Message-ID, ...) of a received message.
 */
final class MessageHeaders {

	private static final Pattern ENCODED_WORD =
			Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");

	private final Map<String, String> fields = new LinkedHashMap<>();

	/** @param headerSection header lines separated by LF, without the trailing blank line. */
	MessageHeaders(String headerSection) {
		String name = null;
		StringBuilder value = new StringBuilder();
		for (String line : headerSection.split("\n")) {
			if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
				if (name != null) {
					value.append(line); // folded continuation line
				}
				continue;
			}
			put(name, value);
			int colon = line.indexOf(':');
			name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : null;
			value.setLength(0);
			if (name != null) {
				value.append(line, colon + 1, line.length());
			}
		}
		put(name, value);
	}

	private void put(String name, CharSequence value) {
		if (name != null) {
			fields.putIfAbsent(name, value.toString().trim());
		}
	}

	/** First occurrence of the field, encoded-words decoded, or null. */
	String get(String name) {
		String value = fields.get(name.toLowerCase(Locale.ROOT));
		return value == null ? null : decode(value);
	}

	/**
	 * Decodes RFC 2047 encoded-words. Adjacent words of the same charset are
	 * joined before decoding because a multibyte character may be split across them.
	 */
	static String decode(String text) {
		Matcher m = ENCODED_WORD.matcher(text);
		StringBuilder out = new StringBuilder(text.length());
		ByteArrayOutputStream pending = new ByteArrayOutputStream();
		String pendingCharset = null;
		int last = 0;
		while (m.find()) {
			String between = text.substring(last, m.start());
			boolean adjacent = pendingCharset != null && between.isBlank();
			String charset = m.group(1);
			int star = charset.indexOf('*'); // RFC 2231 language suffix
			if (star >= 0) {
				charset = charset.substring(0, star);
			}
			if (!adjacent || !charset.equalsIgnoreCase(pendingCharset)) {
				flush(out, pending, pendingCharset);
				if (!adjacent) {
					out.append(between);
				}
				pendingCharset = charset;
			}
			byte[] bytes = decodeWord(m.group(2), m.group(3));
			if (bytes == null) { // undecodable: keep it verbatim
				flush(out, pending, pendingCharset);
				out.append(m.group());
				pendingCharset = null;
			} else {
				pending.writeBytes(bytes);
			}
			last = m.end();
		}
		flush(out, pending, pendingCharset);
		return out.append(text, last, text.length()).toString();
	}

	private static byte[] decodeWord(String encoding, String encoded) {
		try {
			if (encoding.equalsIgnoreCase("B")) {
				return Base64.getMimeDecoder().decode(encoded);
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
			for (int i = 0; i < encoded.length(); i++) {
				char c = encoded.charAt(i);
				if (c == '_') {
					bytes.write(' ');
				} else if (c == '=' && i + 2 < encoded.length()) {
					bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
					i += 2;
				} else {
					bytes.write(c);
				}
			}
			return bytes.toByteArray();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void flush(StringBuilder out, ByteArrayOutputStream pending, String charset) {
		if (pending.size() == 0) {
			return;
		}
		Charset cs;
		try {
			cs = Charset.forName(charset);
		} catch (IllegalArgumentException e) {
			cs = StandardCharsets.UTF_8;
		}
		out.append(pending.toString(cs));
		pending.reset();
	}
}
