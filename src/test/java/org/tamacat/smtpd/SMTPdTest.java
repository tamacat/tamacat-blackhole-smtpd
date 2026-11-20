package org.tamacat.smtpd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SMTPdTest {

	final List<String> logs = new CopyOnWriteArrayList<>();
	SMTPd server;

	/** maxRecipients 2, maxConnections 10, no per-IP limit, idle 5 s, no session limit. */
	static Config config() {
		return new Config("127.0.0.1", 0, "test.local", 2, 10, 0, 5, 0);
	}

	@BeforeEach
	void start() throws IOException {
		Log.sink = logs::add;
		Log.level = Log.Level.INFO;
		start(config());
	}

	void start(Config config) throws IOException {
		if (server != null) {
			server.close();
		}
		server = new SMTPd(config);
		Thread.ofVirtual().start(server::serve);
	}

	@AfterEach
	void stop() throws IOException {
		server.close();
		Log.level = Log.Level.INFO;
	}

	@Test
	void acceptsAndLogsMessage() throws IOException {
		try (Client c = new Client(server.getPort())) {
			assertTrue(c.read().startsWith("220 test.local ESMTP"));
			c.send("EHLO client.example");
			List<String> ehlo = c.readMultiline();
			assertTrue(ehlo.contains("250-AUTH PLAIN LOGIN"), ehlo.toString());
			assertFalse(ehlo.stream().anyMatch(l -> l.contains("SIZE")), ehlo.toString());
			c.send("MAIL FROM:<from@example.com> SIZE=100");
			assertEquals("250 2.1.0 Ok", c.read());
			c.send("RCPT TO:<to1@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("RCPT TO:<to2@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			// beyond MAX_RECIPIENTS: still accepted, only counted in the log
			c.send("RCPT TO:<to3@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("DATA");
			assertTrue(c.read().startsWith("354 "));
			c.send("Subject: =?UTF-8?B?44OG44K544OI?=\r\n =?UTF-8?Q?_mail?=\r\nMessage-ID: <1@example.com>\r\n\r\n..dot\r\nbody\r\n.");
			assertTrue(c.read().startsWith("250 2.0.0 Ok: queued as "));
			c.send("QUIT");
			assertTrue(c.read().startsWith("221 "));
		}
		String mail = logs.stream().filter(l -> l.contains(" mail ")).findFirst().orElseThrow();
		assertTrue(mail.contains(" from=from@example.com "), mail);
		assertTrue(mail.contains(" to=to1@example.com,to2@example.com to-omitted=1 "), mail);
		assertTrue(mail.contains(" subject=\"テスト mail\""), mail);
		assertTrue(mail.contains(" message-id=<1@example.com>"), mail);
	}

	@Test
	void repliesSuccessToAnythingAClientSends() throws IOException {
		try (Client c = new Client(server.getPort())) {
			c.read();
			// out of order: RCPT and DATA without MAIL, no HELO at all
			c.send("RCPT TO:<to@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("DATA");
			assertTrue(c.read().startsWith("354 "));
			c.send("Subject: no MAIL\r\n\r\nbody\r\n.");
			assertTrue(c.read().startsWith("250 2.0.0 Ok: queued as "));
			// DATA with no recipient at all
			c.send("DATA");
			assertTrue(c.read().startsWith("354 "));
			c.send("\r\n.");
			assertTrue(c.read().startsWith("250 "));
			// syntax errors, unknown commands, empty and over-long lines
			c.send("EHLO");
			assertTrue(c.read().startsWith("250"));
			c.readMultilineRest();
			c.send("MAIL garbage");
			assertEquals("250 2.1.0 Ok", c.read());
			c.send("MAIL FROM:<nested@example.com> SIZE=99999999999");
			assertEquals("250 2.1.0 Ok", c.read());
			c.send("RCPT TO:<unterminated");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("XYZZY");
			assertEquals("250 2.0.0 Ok", c.read());
			c.send("");
			assertEquals("250 2.0.0 Ok", c.read());
			c.send("NOOP " + "x".repeat(5000));
			assertEquals("250 2.0.0 Ok", c.read());
			// no size limit
			c.send("DATA");
			c.read();
			c.send("Subject: big\r\n\r\n" + ("x".repeat(9000) + "\r\n").repeat(20) + ".");
			assertTrue(c.read().startsWith("250 2.0.0 Ok: queued as "));
			// many errors in a row do not close the session
			for (int i = 0; i < 20; i++) {
				c.send("BAD" + i);
				assertEquals("250 2.0.0 Ok", c.read());
			}
			c.send("QUIT");
			assertTrue(c.read().startsWith("221 "));
		}
	}

	@Test
	void acceptsAnyCredentialsWithoutLoggingThem() throws IOException {
		Log.level = Log.Level.DEBUG;
		try (Client c = new Client(server.getPort())) {
			c.read();
			c.send("EHLO client");
			c.readMultiline();
			// AUTH PLAIN with initial response: "\0user\0secret-1"
			c.send("AUTH PLAIN AHVzZXIAc2VjcmV0LTE=");
			assertEquals("235 2.7.0 Authentication successful", c.read());
			// AUTH PLAIN, credentials on the next line
			c.send("AUTH PLAIN");
			assertEquals("334 ", c.read());
			c.send("AHVzZXIAc2VjcmV0LTI=");
			assertEquals("235 2.7.0 Authentication successful", c.read());
			// AUTH LOGIN: user, then "secret-3"
			c.send("AUTH LOGIN");
			assertEquals("334 VXNlcm5hbWU6", c.read());
			c.send("dXNlcg==");
			assertEquals("334 UGFzc3dvcmQ6", c.read());
			c.send("c2VjcmV0LTM=");
			assertEquals("235 2.7.0 Authentication successful", c.read());
			// a mechanism that is not advertised
			c.send("AUTH XOAUTH2 dG9rZW4=");
			assertEquals("235 2.7.0 Authentication successful", c.read());
			c.send("QUIT");
			c.read();
		}
		String all = String.join("\n", logs);
		assertTrue(all.contains("AUTH PLAIN ..."), all);
		for (String secret : List.of("AHVzZXIAc2VjcmV0LTE=", "AHVzZXIAc2VjcmV0LTI=", "c2VjcmV0LTM=", "dG9rZW4=")) {
			assertFalse(all.contains(secret), secret);
		}
	}

	@Test
	void repliesSuccessEvenWhenLoggingFails() throws IOException {
		Log.sink = line -> {
			throw new IllegalStateException("log sink broken");
		};
		try (Client c = new Client(server.getPort())) {
			c.read();
			c.send("MAIL FROM:<a@example.com>");
			c.read();
			c.send("RCPT TO:<b@example.com>");
			c.read();
			c.send("DATA");
			c.read();
			c.send("Subject: x\r\n\r\nbody\r\n.");
			assertTrue(c.read().startsWith("250 2.0.0 Ok: queued as "));
		}
	}

	@Test
	void waitsForAFreeSlotInsteadOfRefusing() throws Exception {
		start(new Config("127.0.0.1", 0, "test.local", 2, 1, 0, 5, 0));
		Client first = new Client(server.getPort());
		assertTrue(first.read().startsWith("220 "));
		try (Client second = new Client(server.getPort())) {
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(500);
					first.send("QUIT");
					first.read();
					first.close();
				} catch (IOException | InterruptedException ignore) {
					// test fails below
				}
			});
			assertTrue(second.read().startsWith("220 "));
		}
	}

	@Test
	void refusesTooManyConnectionsFromOneAddress() throws Exception {
		start(new Config("127.0.0.1", 0, "test.local", 2, 10, 2, 5, 0));
		try (Client c1 = new Client(server.getPort()); Client c2 = new Client(server.getPort())) {
			assertTrue(c1.read().startsWith("220 "));
			assertTrue(c2.read().startsWith("220 "));
			try (Client c3 = new Client(server.getPort())) {
				assertTrue(c3.read().startsWith("421 "));
			}
			c1.send("QUIT");
			c1.read();
		}
		// the slots are freed once the sessions end
		String greeting = null;
		for (int i = 0; i < 50 && (greeting == null || !greeting.startsWith("220 ")); i++) {
			Thread.sleep(100);
			try (Client c = new Client(server.getPort())) {
				greeting = c.read();
			}
		}
		assertTrue(greeting.startsWith("220 "), greeting);
		assertTrue(logs.stream().anyMatch(l -> l.contains("reason=\"too many connections from this address\"")));
	}

	@Test
	void endsSessionAtTimeLimitEvenWhileClientIsSending() throws IOException {
		start(new Config("127.0.0.1", 0, "test.local", 2, 10, 0, 5, 1));
		try (Client c = new Client(server.getPort())) {
			c.read();
			// trickle one byte at a time, far below the idle timeout
			Thread.ofVirtual().start(() -> {
				try {
					for (int i = 0; i < 50; i++) {
						c.out.write('N');
						c.out.flush();
						Thread.sleep(100);
					}
				} catch (IOException | InterruptedException ignore) {
					// closed by the server
				}
			});
			long start = System.nanoTime();
			String reply = c.read();
			assertTrue(reply.startsWith("421 4.4.2 test.local Session time limit exceeded"), reply);
			assertTrue(System.nanoTime() - start < 4_000_000_000L);
		}
	}

	@Test
	void repliesBeforeClosingAnIdleSession() throws IOException {
		start(new Config("127.0.0.1", 0, "test.local", 2, 10, 0, 1, 0));
		try (Client c = new Client(server.getPort())) {
			c.read();
			String reply = c.read();
			assertTrue(reply.startsWith("421 4.4.2 test.local Idle timeout"), reply);
		}
	}

	@Test
	void badSettingsFallBackToDefaults() {
		assertEquals(25, Config.parse("BIND_PORT", "2x5", 25, 0, 65535));
		assertEquals(25, Config.parse("BIND_PORT", "70000", 25, 0, 65535));
		assertEquals(100, Config.parse("MAX_CONNECTIONS", "0", 100, 1, Integer.MAX_VALUE));
		assertEquals(1025, Config.parse("BIND_PORT", " 1025 ", 25, 0, 65535));
		assertEquals(Log.Level.INFO, Log.level("verbose"));
		assertEquals(Log.Level.DEBUG, Log.level("debug"));
	}

	@Test
	void escapesLogValues() {
		StringBuilder sb = new StringBuilder();
		Log.appendValue(sb, "a\r\nINFO forged\" x");
		assertEquals("\"a\\r\\nINFO forged\\\" x\"", sb.toString());
	}

	@Test
	void decodesEncodedWordsSplitAcrossCharacters() {
		// "日本語" in ISO-2022-JP, split into two base64 words
		assertEquals("件名: 日本語",
				MessageHeaders.decode("件名: =?ISO-2022-JP?B?GyRCRnxLXBsoQg==?= =?ISO-2022-JP?B?GyRCOGwbKEI=?="));
		assertEquals("plain text", MessageHeaders.decode("plain text"));
		assertEquals("bad =?UTF-8?Q?=ZZ?=", MessageHeaders.decode("bad =?UTF-8?Q?=ZZ?="));
		assertEquals("unknown charset", MessageHeaders.decode("=?x-bogus?Q?unknown_charset?="));
	}

	static class Client implements AutoCloseable {
		final Socket socket;
		final BufferedReader in;
		final OutputStream out;

		Client(int port) throws IOException {
			socket = new Socket("127.0.0.1", port);
			socket.setSoTimeout(5000);
			in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out = socket.getOutputStream();
		}

		void send(String line) throws IOException {
			out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}

		String read() throws IOException {
			return in.readLine();
		}

		List<String> readMultiline() throws IOException {
			List<String> lines = new ArrayList<>();
			String line;
			do {
				line = read();
				lines.add(line);
			} while (line.charAt(3) == '-');
			return lines;
		}

		/** Reads the remaining lines of a multiline reply whose first line was already read. */
		void readMultilineRest() throws IOException {
			readMultiline();
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
