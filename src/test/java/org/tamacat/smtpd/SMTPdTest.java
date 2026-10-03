package org.tamacat.smtpd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SMTPdTest {

	final List<String> logs = new CopyOnWriteArrayList<>();
	SMTPd server;

	@BeforeEach
	void start() throws IOException {
		Log.sink = logs::add;
		Config config = new Config("127.0.0.1", 0, "test.local", 1024, 2, 10, 5);
		server = new SMTPd(config);
		Thread.ofVirtual().start(() -> {
			try {
				server.serve();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	@AfterEach
	void stop() throws IOException {
		server.close();
	}

	@Test
	void acceptsAndLogsMessage() throws IOException {
		try (Client c = new Client(server.getPort())) {
			assertTrue(c.read().startsWith("220 test.local ESMTP"));
			c.send("EHLO client.example");
			List<String> ehlo = c.readMultiline();
			assertTrue(ehlo.contains("250-SIZE 1024"));
			c.send("MAIL FROM:<from@example.com> SIZE=100");
			assertEquals("250 2.1.0 Ok", c.read());
			c.send("RCPT TO:<to1@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("RCPT TO:<to2@example.com>");
			assertEquals("250 2.1.5 Ok", c.read());
			c.send("RCPT TO:<to3@example.com>");
			assertTrue(c.read().startsWith("452 "));
			c.send("DATA");
			assertTrue(c.read().startsWith("354 "));
			c.send("Subject: =?UTF-8?B?44OG44K544OI?=\r\n =?UTF-8?Q?_mail?=\r\nMessage-ID: <1@example.com>\r\n\r\n..dot\r\nbody\r\n.");
			assertTrue(c.read().startsWith("250 2.0.0 Ok: queued as "));
			c.send("QUIT");
			assertTrue(c.read().startsWith("221 "));
		}
		String mail = logs.stream().filter(l -> l.contains(" mail ")).findFirst().orElseThrow();
		assertTrue(mail.contains(" from=from@example.com "), mail);
		assertTrue(mail.contains(" to=to1@example.com,to2@example.com "), mail);
		assertTrue(mail.contains(" subject=\"テスト mail\""), mail);
		assertTrue(mail.contains(" message-id=<1@example.com>"), mail);
	}

	@Test
	void rejectsOversizedMessage() throws IOException {
		try (Client c = new Client(server.getPort())) {
			c.read();
			c.send("HELO client");
			c.read();
			c.send("MAIL FROM:<> SIZE=2048");
			assertTrue(c.read().startsWith("552 "));
			c.send("MAIL FROM:<>");
			c.read();
			c.send("RCPT TO:<to@example.com>");
			c.read();
			c.send("DATA");
			c.read();
			c.send("Subject: big\r\n\r\n" + "x".repeat(2000) + "\r\n.");
			assertTrue(c.read().startsWith("552 "));
			// session continues after a rejected message
			c.send("NOOP");
			assertEquals("250 2.0.0 Ok", c.read());
		}
	}

	@Test
	void enforcesCommandOrder() throws IOException {
		try (Client c = new Client(server.getPort())) {
			c.read();
			c.send("RCPT TO:<to@example.com>");
			assertTrue(c.read().startsWith("503 "));
			c.send("DATA");
			assertTrue(c.read().startsWith("503 "));
			c.send("STARTTLS");
			assertTrue(c.read().startsWith("502 "));
		}
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
			List<String> lines = new java.util.ArrayList<>();
			String line;
			do {
				line = read();
				lines.add(line);
			} while (line.charAt(3) == '-');
			return lines;
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
