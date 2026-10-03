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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One SMTP connection (RFC 5321 subset). Every message is accepted and
 * discarded; only the envelope and a few header fields are logged.
 */
final class SmtpSession implements Runnable {

	static final String SOFTWARE = "tamacat-blackhole-smtpd";

	/** RFC 5321 allows 512 octets; leave room for extension parameters. */
	private static final int MAX_COMMAND_LINE = 2048;
	/** Longest data line kept in memory; longer lines are counted but truncated. */
	private static final int MAX_DATA_LINE = 8192;
	/** Header bytes kept for logging; the body is never stored. */
	private static final int MAX_HEADER_BYTES = 64 * 1024;
	private static final int MAX_ERRORS = 10;

	private final Socket socket;
	private final Config config;
	private final String id = String.format("%08x", ThreadLocalRandom.current().nextInt());
	private final String remote;

	private InputStream in;
	private OutputStream out;

	private String helo;
	private String mailFrom;
	private final List<String> recipients = new ArrayList<>();
	private int messageCount;
	private int errors;

	SmtpSession(Socket socket, Config config) {
		this.socket = socket;
		this.config = config;
		this.remote = socket.getRemoteSocketAddress() instanceof InetSocketAddress a
				? a.getAddress().getHostAddress() + ":" + a.getPort()
				: String.valueOf(socket.getRemoteSocketAddress());
	}

	@Override
	public void run() {
		Log.debug("connect", "session", id, "remote", remote);
		try (socket) {
			socket.setSoTimeout(config.idleTimeoutSeconds() * 1000);
			in = new BufferedInputStream(socket.getInputStream());
			out = new BufferedOutputStream(socket.getOutputStream());
			reply("220 " + config.hostname() + " ESMTP " + SOFTWARE);
			ByteArrayOutputStream line = new ByteArrayOutputStream(256);
			while (true) {
				line.reset();
				long length = readLine(line, MAX_COMMAND_LINE);
				if (length < 0) {
					break;
				}
				if (length > MAX_COMMAND_LINE) {
					error("500 5.5.6 Line too long");
				} else if (!command(line.toString(StandardCharsets.UTF_8))) {
					break;
				}
				if (errors >= MAX_ERRORS) {
					reply("421 4.7.0 Too many errors, closing connection");
					break;
				}
			}
		} catch (SocketTimeoutException e) {
			Log.debug("timeout", "session", id, "remote", remote);
			try {
				reply("421 4.4.2 " + config.hostname() + " Idle timeout, closing connection");
			} catch (IOException ignore) {
				// connection is being dropped anyway
			}
		} catch (IOException e) {
			Log.debug("io-error", "session", id, "remote", remote, "error", e.toString());
		} catch (RuntimeException e) {
			Log.error("session-error", "session", id, "remote", remote, "error", e.toString());
		}
		Log.debug("disconnect", "session", id, "remote", remote, "messages", messageCount);
	}

	/** @return false to close the connection */
	private boolean command(String line) throws IOException {
		Log.debug("command", "session", id, "line", line);
		int sp = line.indexOf(' ');
		String verb = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
		String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
		switch (verb) {
			case "EHLO" -> ehlo(arg);
			case "HELO" -> helo(arg);
			case "MAIL" -> mail(arg);
			case "RCPT" -> rcpt(arg);
			case "DATA" -> data();
			case "RSET" -> {
				resetTransaction();
				reply("250 2.0.0 Ok");
			}
			case "NOOP" -> reply("250 2.0.0 Ok");
			case "VRFY" -> reply("252 2.5.2 Cannot VRFY user, but will accept message");
			case "HELP" -> reply("214 2.0.0 Commands: EHLO HELO MAIL RCPT DATA RSET NOOP VRFY HELP QUIT");
			case "QUIT" -> {
				reply("221 2.0.0 " + config.hostname() + " Bye");
				return false;
			}
			case "" -> error("500 5.5.2 Error: bad syntax");
			default -> error("502 5.5.2 Error: command not implemented");
		}
		return true;
	}

	private void ehlo(String arg) throws IOException {
		if (arg.isEmpty()) {
			error("501 5.5.4 Syntax: EHLO hostname");
			return;
		}
		helo = arg;
		resetTransaction();
		reply("250-" + config.hostname() + " Hello " + arg,
				"250-SIZE " + config.maxMessageSize(),
				"250-8BITMIME",
				"250-SMTPUTF8",
				"250-ENHANCEDSTATUSCODES",
				"250 PIPELINING");
	}

	private void helo(String arg) throws IOException {
		if (arg.isEmpty()) {
			error("501 5.5.4 Syntax: HELO hostname");
			return;
		}
		helo = arg;
		resetTransaction();
		reply("250 " + config.hostname());
	}

	private void mail(String arg) throws IOException {
		if (mailFrom != null) {
			error("503 5.5.1 Error: nested MAIL command");
			return;
		}
		String[] path = parsePath(arg, "FROM:");
		if (path == null) {
			error("501 5.5.4 Syntax: MAIL FROM:<address>");
			return;
		}
		for (String param : path[1].split("\\s+")) {
			if (param.regionMatches(true, 0, "SIZE=", 0, 5)) {
				try {
					if (Long.parseLong(param.substring(5)) > config.maxMessageSize()) {
						reply("552 5.3.4 Message size exceeds fixed limit");
						return;
					}
				} catch (NumberFormatException e) {
					error("501 5.5.4 Bad SIZE parameter");
					return;
				}
			}
		}
		mailFrom = path[0];
		reply("250 2.1.0 Ok");
	}

	private void rcpt(String arg) throws IOException {
		if (mailFrom == null) {
			error("503 5.5.1 Error: need MAIL command");
			return;
		}
		String[] path = parsePath(arg, "TO:");
		if (path == null || path[0].isEmpty()) {
			error("501 5.5.4 Syntax: RCPT TO:<address>");
			return;
		}
		if (recipients.size() >= config.maxRecipients()) {
			reply("452 4.5.3 Error: too many recipients");
			return;
		}
		recipients.add(path[0]);
		reply("250 2.1.5 Ok");
	}

	private void data() throws IOException {
		if (recipients.isEmpty()) {
			error("503 5.5.1 Error: need RCPT command");
			return;
		}
		reply("354 End data with <CR><LF>.<CR><LF>");

		ByteArrayOutputStream header = new ByteArrayOutputStream(1024);
		ByteArrayOutputStream line = new ByteArrayOutputStream(256);
		boolean inHeader = true;
		long size = 0;
		while (true) {
			line.reset();
			long length = readLine(line, MAX_DATA_LINE);
			if (length < 0) {
				throw new EOFException("connection closed in DATA");
			}
			byte[] bytes = line.toByteArray();
			int offset = 0;
			if (length > 0 && bytes[0] == '.') {
				if (length == 1) {
					break;
				}
				offset = 1; // dot-stuffing (RFC 5321 4.5.2)
			}
			size += length - offset + 2;
			if (inHeader) {
				int kept = bytes.length - offset;
				if (length == offset || header.size() + kept + 1 > MAX_HEADER_BYTES) {
					inHeader = false;
				} else {
					header.write(bytes, offset, kept);
					header.write('\n');
				}
			}
		}

		String messageId = id + "." + (++messageCount);
		if (size > config.maxMessageSize()) {
			Log.warn("reject", "id", messageId, "remote", remote, "from", mailFrom,
					"to", String.join(",", recipients), "size", size, "reason", "message too large");
			reply("552 5.3.4 Message size exceeds fixed limit");
		} else {
			MessageHeaders headers = new MessageHeaders(header.toString(StandardCharsets.UTF_8));
			Log.info("mail",
					"id", messageId,
					"remote", remote,
					"helo", helo,
					"from", mailFrom,
					"to", String.join(",", recipients),
					"size", size,
					"subject", headers.get("Subject"),
					"message-id", headers.get("Message-ID"));
			reply("250 2.0.0 Ok: queued as " + messageId);
		}
		resetTransaction();
	}

	/**
	 * Parses {@code FROM:<addr> params} / {@code TO:<addr> params}.
	 * @return {address, parameters} or null on syntax error
	 */
	static String[] parsePath(String arg, String prefix) {
		if (!arg.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return null;
		}
		String rest = arg.substring(prefix.length()).trim();
		if (rest.startsWith("<")) {
			int end = rest.indexOf('>');
			if (end < 0) {
				return null;
			}
			return new String[] { rest.substring(1, end).trim(), rest.substring(end + 1).trim() };
		}
		if (rest.isEmpty()) {
			return null;
		}
		int sp = rest.indexOf(' ');
		return sp < 0
				? new String[] { rest, "" }
				: new String[] { rest.substring(0, sp), rest.substring(sp + 1).trim() };
	}

	private void resetTransaction() {
		mailFrom = null;
		recipients.clear();
	}

	private void error(String response) throws IOException {
		errors++;
		reply(response);
	}

	private void reply(String... lines) throws IOException {
		for (String line : lines) {
			out.write(line.getBytes(StandardCharsets.UTF_8));
			out.write('\r');
			out.write('\n');
		}
		out.flush();
	}

	/**
	 * Reads one line terminated by CRLF (a bare LF is tolerated) into buf,
	 * keeping at most max bytes.
	 * @return the full line length without the terminator, or -1 at end of stream
	 */
	private long readLine(ByteArrayOutputStream buf, int max) throws IOException {
		long count = 0;
		boolean cr = false;
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\n') {
				return count;
			}
			if (cr) {
				count++;
				if (buf.size() < max) {
					buf.write('\r');
				}
			}
			cr = c == '\r';
			if (!cr) {
				count++;
				if (buf.size() < max) {
					buf.write(c);
				}
			}
		}
		return -1;
	}
}
