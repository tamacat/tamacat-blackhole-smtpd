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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Blackhole SMTP server: accepts every message, never delivers or stores it,
 * and writes one log line per message.
 */
public class SMTPd implements AutoCloseable {

	private static final int BACKLOG = 1024;

	private final Config config;
	private final ServerSocket serverSocket;
	private final Semaphore connections;
	private final Map<InetAddress, Integer> connectionsPerAddress = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** Binds the listen socket; call {@link #serve()} to accept connections. */
	public SMTPd(Config config) throws IOException {
		this.config = config;
		this.connections = new Semaphore(config.maxConnections());
		this.serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		// connections beyond MAX_CONNECTIONS wait here (capped by the OS, e.g. somaxconn)
		serverSocket.bind(new InetSocketAddress(config.bindAddress(), config.port()), BACKLOG);
	}

	public int getPort() {
		return serverSocket.getLocalPort();
	}

	/**
	 * Accepts connections until {@link #close()} is called. When every session
	 * slot is taken, new connections wait in the listen backlog for a free one
	 * instead of being refused. Errors from accept (such as running out of file
	 * descriptors) are logged and retried; they never stop the server.
	 */
	public void serve() {
		Log.info("start", "version", version(), "java", Runtime.version(),
				"address", config.bindAddress(), "port", getPort(),
				"hostname", config.hostname(),
				"max-connections", config.maxConnections(),
				"max-connections-per-ip", config.maxConnectionsPerIp(),
				"idle-timeout", config.idleTimeoutSeconds(),
				"session-timeout", config.sessionTimeoutSeconds());
		while (!serverSocket.isClosed()) {
			try {
				if (!connections.tryAcquire()) {
					Log.warn("busy", "max-connections", config.maxConnections());
					connections.acquire();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			Socket socket;
			try {
				socket = serverSocket.accept();
			} catch (IOException | RuntimeException e) {
				connections.release();
				if (serverSocket.isClosed()) {
					break;
				}
				Log.error("accept-error", "error", e.toString());
				pause();
				continue;
			}
			InetAddress address = socket.getInetAddress();
			if (!acquireAddress(address)) {
				connections.release();
				refuse(socket, "too many connections from this address");
				continue;
			}
			try {
				executor.execute(() -> {
					try {
						new SmtpSession(socket, config).run();
					} finally {
						releaseAddress(address);
						connections.release();
					}
				});
			} catch (RuntimeException e) { // only after close()
				releaseAddress(address);
				connections.release();
				closeQuietly(socket);
			}
		}
	}

	/** Backs off briefly so a persistent accept error does not spin the CPU. */
	private static void pause() {
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignore) {
			// already gone
		}
	}

	private void refuse(Socket socket, String reason) {
		Log.warn("refuse", "remote", socket.getRemoteSocketAddress(), "reason", reason);
		try (socket; OutputStream out = socket.getOutputStream()) {
			out.write(("421 4.3.2 " + config.hostname() + " Too many connections, try again later\r\n")
					.getBytes(StandardCharsets.US_ASCII));
		} catch (IOException ignore) {
			// client already gone
		}
	}

	/** Counts a session from the address; false when it already has the maximum. */
	private boolean acquireAddress(InetAddress address) {
		int max = config.maxConnectionsPerIp();
		if (max <= 0) {
			return true;
		}
		boolean[] acquired = { false };
		connectionsPerAddress.compute(address, (a, n) -> {
			int count = n == null ? 0 : n;
			if (count >= max) {
				return n;
			}
			acquired[0] = true;
			return count + 1;
		});
		return acquired[0];
	}

	private void releaseAddress(InetAddress address) {
		if (config.maxConnectionsPerIp() > 0) {
			connectionsPerAddress.computeIfPresent(address, (a, n) -> n <= 1 ? null : n - 1);
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
