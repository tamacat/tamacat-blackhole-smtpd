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
 * One SMTP connection (RFC 5321 subset). This is a stand-in for a real mail
 * server, so every command gets a success reply, whatever the client sends
 * and in whatever order: any AUTH is accepted, nothing is too large, and a
 * failure inside the server (even in logging) never turns into an error
 * reply. Messages are discarded; only the envelope and a few header fields
 * are logged. The connection itself still ends on the idle and session
 * timeouts.
 */
final class SmtpSession implements Runnable {

	static final String SOFTWARE = "tamacat-blackhole-smtpd";

	/** RFC 5321 allows 512 octets; leave room for extension parameters. */
	private static final int MAX_COMMAND_LINE = 2048;
	/** Longest data line kept in memory; longer lines are counted but truncated. */
	private static final int MAX_DATA_LINE = 8192;
	/** Header bytes kept for logging; the body is never stored. */
	private static final int MAX_HEADER_BYTES = 64 * 1024;
	private static final int LINGER_MILLIS = 1000;
	private static final int MAX_LINGER_BYTES = 1024 * 1024;
	private static final String OK = "250 2.0.0 Ok";

	private final Socket socket;
	private final Config config;
	private final String id = String.format("%08x", ThreadLocalRandom.current().nextInt());
	private final String remote;

	private InputStream in;
	private OutputStream out;

	private String helo;
	private String mailFrom;
	/** Logged recipients, at most {@link Config#maxRecipients()}; the rest are only counted. */
	private final List<String> recipients = new ArrayList<>();
	private int omittedRecipients;
	private int messageCount;

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
		// The timeout handlers reply before the socket is closed, so they are
		// inside the try-with-resources (its own catch runs after the close).
		try (socket) {
			try {
				in = new BufferedInputStream(new DeadlineInputStream(socket, config));
				out = new BufferedOutputStream(socket.getOutputStream());
				session();
			} catch (SessionExpiredException e) {
				Log.debug("session-timeout", "session", id, "remote", remote);
				reply("421 4.4.2 " + config.hostname() + " Session time limit exceeded, closing connection");
				lingeringClose();
			} catch (SocketTimeoutException e) {
				Log.debug("timeout", "session", id, "remote", remote);
				reply("421 4.4.2 " + config.hostname() + " Idle timeout, closing connection");
			}
		} catch (IOException e) {
			Log.debug("io-error", "session", id, "remote", remote, "error", e.toString());
		} catch (RuntimeException e) {
			Log.error("session-error", "session", id, "remote", remote, "error", e.toString());
		}
		Log.debug("disconnect", "session", id, "remote", remote, "messages", messageCount);
	}

	private void session() throws IOException {
		reply("220 " + config.hostname() + " ESMTP " + SOFTWARE);
		ByteArrayOutputStream line = new ByteArrayOutputStream(256);
		while (true) {
			line.reset();
			// an over-long line is handled truncated, like any other command
			if (readLine(line, MAX_COMMAND_LINE) < 0) {
				return;
			}
			try {
				if (!command(line.toString(StandardCharsets.UTF_8))) {
					return;
				}
			} catch (RuntimeException e) {
				// a bug in the server must not fail the client
				Log.error("command-error", "session", id, "remote", remote, "error", e.toString());
				reply(OK);
			}
		}
	}

	/**
	 * The client may still be sending when the session is cut off. Closing a
	 * socket with unread input sends a TCP RST, which can make the client drop
	 * the 421 reply, so half-close and discard input for a short, bounded time.
	 */
	private void lingeringClose() throws IOException {
		socket.shutdownOutput();
		socket.setSoTimeout(LINGER_MILLIS);
		InputStream raw = socket.getInputStream();
		byte[] buf = new byte[8192];
		long end = System.nanoTime() + LINGER_MILLIS * 1_000_000L;
		long total = 0;
		int n;
		while (total < MAX_LINGER_BYTES && System.nanoTime() < end && (n = raw.read(buf)) >= 0) {
			total += n;
		}
	}

	/** @return false to close the connection */
	private boolean command(String line) throws IOException {
		int sp = line.indexOf(' ');
		String verb = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
		String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
		if (Log.isDebugEnabled()) {
			// never log credentials (AUTH PLAIN <base64>)
			Log.debug("command", "session", id, "line",
					verb.equals("AUTH") ? "AUTH " + arg.split("\\s+", 2)[0] + " ..." : line);
		}
		switch (verb) {
			case "EHLO" -> {
				helo = arg;
				resetTransaction();
				reply("250-" + config.hostname() + " Hello " + arg,
						"250-8BITMIME",
						"250-SMTPUTF8",
						"250-AUTH PLAIN LOGIN",
						"250-ENHANCEDSTATUSCODES",
						"250 PIPELINING");
			}
			case "HELO" -> {
				helo = arg;
				resetTransaction();
				reply("250 " + config.hostname());
			}
			case "MAIL" -> {
				resetTransaction(); // a nested MAIL starts over
				mailFrom = address(arg, "FROM:");
				reply("250 2.1.0 Ok");
			}
			case "RCPT" -> {
				if (recipients.size() < config.maxRecipients()) {
					recipients.add(address(arg, "TO:"));
				} else {
					omittedRecipients++;
				}
				reply("250 2.1.5 Ok");
			}
			case "DATA" -> data();
			case "AUTH" -> auth(arg);
			case "RSET" -> {
				resetTransaction();
				reply(OK);
			}
			case "VRFY" -> reply("252 2.5.2 Cannot VRFY user, but will accept message");
			case "HELP" -> reply("214 2.0.0 Commands: EHLO HELO MAIL RCPT DATA AUTH RSET NOOP VRFY HELP QUIT");
			// The one refusal: a 220 here would make the client start a TLS
			// handshake that cannot succeed. STARTTLS is not advertised, so
			// only a client that insists on TLS sends it.
			case "STARTTLS" -> reply("454 4.7.0 TLS not available");
			case "QUIT" -> {
				reply("221 2.0.0 " + config.hostname() + " Bye");
				return false;
			}
			default -> reply(OK); // NOOP, empty lines and anything unknown
		}
		return true;
	}

	/** Accepts any credentials; they are read and discarded, never logged. */
	private void auth(String arg) throws IOException {
		String[] parts = arg.split("\\s+", 2);
		String mechanism = parts[0].toUpperCase(Locale.ROOT);
		boolean initialResponse = parts.length > 1 && !parts[1].isEmpty();
		if (mechanism.equals("LOGIN")) {
			if (!initialResponse) {
				reply("334 VXNlcm5hbWU6"); // "Username:"
				skipLine();
			}
			reply("334 UGFzc3dvcmQ6"); // "Password:"
			skipLine();
		} else if (mechanism.equals("PLAIN") && !initialResponse) {
			reply("334 ");
			skipLine();
		}
		Log.debug("auth", "session", id, "remote", remote, "mechanism", mechanism);
		reply("235 2.7.0 Authentication successful");
	}

	private void skipLine() throws IOException {
		if (readLine(new ByteArrayOutputStream(256), MAX_COMMAND_LINE) < 0) {
			throw new EOFException("connection closed in AUTH");
		}
	}

	/** The address of {@code FROM:<addr>} / {@code TO:<addr>}, or the raw argument if it does not parse. */
	private static String address(String arg, String prefix) {
		String[] path = parsePath(arg, prefix);
		return path == null ? arg : path[0];
	}

	private void data() throws IOException {
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
		try {
			MessageHeaders headers = new MessageHeaders(header.toString(StandardCharsets.UTF_8));
			Log.info("mail",
					"id", messageId,
					"remote", remote,
					"helo", helo,
					"from", mailFrom,
					"to", String.join(",", recipients),
					"to-omitted", omittedRecipients > 0 ? omittedRecipients : null,
					"size", size,
					"subject", headers.get("Subject"),
					"message-id", headers.get("Message-ID"));
		} catch (RuntimeException e) {
			Log.error("log-error", "id", messageId, "remote", remote, "error", e.toString());
		}
		reply("250 2.0.0 Ok: queued as " + messageId);
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
		omittedRecipients = 0;
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

	/** Thrown when the session has used up {@link Config#sessionTimeoutSeconds()}. */
	static final class SessionExpiredException extends SocketTimeoutException {
		private static final long serialVersionUID = 1L;

		SessionExpiredException() {
			super("session time limit exceeded");
		}
	}

	/**
	 * Socket input that enforces both timeouts: each read may wait up to the
	 * idle timeout, but never past the end of the session. Checking on every
	 * read (BufferedInputStream refills in chunks) also stops a client that
	 * keeps a session alive by trickling bytes or streaming endless DATA.
	 */
	static final class DeadlineInputStream extends InputStream {
		private final Socket socket;
		private final InputStream in;
		private final long idleMillis;
		private final long deadline; // System.nanoTime(), or 0 = no limit

		DeadlineInputStream(Socket socket, Config config) throws IOException {
			this.socket = socket;
			this.in = socket.getInputStream();
			this.idleMillis = config.idleTimeoutSeconds() * 1000L;
			this.deadline = config.sessionTimeoutSeconds() > 0
					? System.nanoTime() + config.sessionTimeoutSeconds() * 1_000_000_000L
					: 0;
		}

		@Override
		public int read() throws IOException {
			byte[] b = new byte[1];
			return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			long timeout = idleMillis;
			// Which limit ends this wait is decided here, not by reading the
			// clock after the timeout: the socket timeout has millisecond
			// resolution and may fire just before the deadline.
			boolean sessionLimited = false;
			if (deadline != 0) {
				long remaining = (deadline - System.nanoTime()) / 1_000_000;
				if (remaining <= 0) {
					throw new SessionExpiredException();
				}
				if (timeout <= 0 || remaining < timeout) {
					timeout = remaining;
					sessionLimited = true;
				}
			}
			socket.setSoTimeout((int) Math.min(timeout, Integer.MAX_VALUE));
			try {
				return in.read(b, off, len);
			} catch (SocketTimeoutException e) {
				if (sessionLimited) {
					throw new SessionExpiredException();
				}
				throw e;
			}
		}

		@Override
		public int available() throws IOException {
			return in.available();
		}
	}
}
