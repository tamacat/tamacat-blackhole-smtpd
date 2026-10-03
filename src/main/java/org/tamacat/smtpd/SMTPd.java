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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Blackhole SMTP server: accepts every message, never delivers or stores it,
 * and writes one log line per message.
 */
public class SMTPd implements AutoCloseable {

	private final Config config;
	private final ServerSocket serverSocket;
	private final Semaphore connections;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** Binds the listen socket; call {@link #serve()} to accept connections. */
	public SMTPd(Config config) throws IOException {
		this.config = config;
		this.connections = new Semaphore(config.maxConnections());
		this.serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		serverSocket.bind(new InetSocketAddress(config.bindAddress(), config.port()));
	}

	public int getPort() {
		return serverSocket.getLocalPort();
	}

	/** Accepts connections until {@link #close()} is called. */
	public void serve() throws IOException {
		Log.info("start", "version", version(), "address", config.bindAddress(), "port", getPort(),
				"hostname", config.hostname(), "max-message-size", config.maxMessageSize(),
				"max-connections", config.maxConnections());
		while (!serverSocket.isClosed()) {
			Socket socket;
			try {
				socket = serverSocket.accept();
			} catch (SocketException e) {
				if (serverSocket.isClosed()) {
					break;
				}
				throw e;
			}
			if (!connections.tryAcquire()) {
				Log.warn("refuse", "remote", socket.getRemoteSocketAddress(), "reason", "too many connections");
				try (socket; OutputStream out = socket.getOutputStream()) {
					out.write(("421 4.3.2 " + config.hostname() + " Too many connections, try again later\r\n")
							.getBytes(StandardCharsets.US_ASCII));
				} catch (IOException ignore) {
					// client already gone
				}
				continue;
			}
			executor.execute(() -> {
				try {
					new SmtpSession(socket, config).run();
				} finally {
					connections.release();
				}
			});
		}
	}

	@Override
	public void close() throws IOException {
		serverSocket.close();
		executor.shutdownNow();
	}

	static String version() {
		String version = SMTPd.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
	}

	public static void main(String[] args) throws IOException {
		SMTPd server = new SMTPd(Config.load(args));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			Log.info("stop");
			try {
				server.close();
			} catch (IOException ignore) {
				// shutting down
			}
		}));
		server.serve();
	}
}
