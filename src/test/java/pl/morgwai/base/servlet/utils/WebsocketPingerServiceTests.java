// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.junit.*;
import pl.morgwai.base.servlet.utils.WebsocketPingerService.PingPongPlayer;
import pl.morgwai.base.servlet.utils.tests.TestServer;

import static org.junit.Assert.*;



public class WebsocketPingerServiceTests {



	org.eclipse.jetty.client.HttpClient wsHttpClient;
	WebSocketContainer clientWebsocketContainer;
	TestServer server;
	int port;
	String websocketUrl;



	@Before
	public void setup() throws Exception {
		final var cookieManager = new CookieManager();
		wsHttpClient = new org.eclipse.jetty.client.HttpClient();
		wsHttpClient.setCookieStore(cookieManager.getCookieStore());
		clientWebsocketContainer = JavaxWebSocketClientContainerProvider.getContainer(wsHttpClient);
		server = new TestServer(0);
		port = Arrays.stream(server.getConnectors())
			.filter(NetworkConnector.class::isInstance)
			.findFirst()
			.map(NetworkConnector.class::cast)
			.map(NetworkConnector::getLocalPort)
			.orElseThrow();
		websocketUrl = "ws://localhost:" + port + TestServer.APP_PATH;
	}



	@After
	public void shutdown() throws Exception {
		final var jettyWsContainer = ((JavaxWebSocketContainer) clientWebsocketContainer);
		jettyWsContainer.stop();
		jettyWsContainer.destroy();
		wsHttpClient.stop();
		wsHttpClient.destroy();
		server.stop();
		server.join();
		server.destroy();
	}



	/**
	 * A static point of synchronization between a given test method and the corresponding
	 * container-created {@link ServerEndpoint} instance. As each test method deploys its
	 * {@link ServerEndpoint} at a different URI/path, tests may safely run in parallel.
	 */
	static final ConcurrentMap<URI, CountDownLatch> serverEndpointCreated =
			new ConcurrentHashMap<>();

	/**
	 * A static place for a given test method to find its corresponding container-created
	 * {@link ServerEndpoint} instance.
	 * @see #serverEndpointCreated
	 */
	static final ConcurrentMap<URI, ServerEndpoint> serverEndpointInstance =
			new ConcurrentHashMap<>();



	public static abstract class BaseEndpoint extends Endpoint {

		protected Session connection;

		/** For logging/debugging: {@code ("client " | "server ") + PATH}. */
		protected String id;

		/** {@link #onError(Session, Throwable)} stores received errors here. */
		protected final List<Throwable> errors = new LinkedList<>();

		/** Switched in {@link #onClose(Session, CloseReason)}. */
		protected CountDownLatch closed = new CountDownLatch(1);
		/** Set in {@link #onClose(Session, CloseReason)}. */
		protected CloseCode closeCode;



		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			this.connection = connection;
			final var url = connection.getRequestURI().toString();
			this.id = (isServer() ? "server " : "client ") + url.substring(url.lastIndexOf('/'));
			log.fine(id + " got onOpen(...)");
		}

		protected abstract boolean isServer();



		@Override
		public void onClose(Session session, CloseReason closeReason) {
			log.fine(id + " got onClose(...), reason: " + closeReason.getCloseCode());
			closeCode = closeReason.getCloseCode();
			closed.countDown();
		}



		@Override
		public void onError(Session session, Throwable error) {
			System.err.println(id + ": " + error);
			errors.add(error);
		}
	}



	public static class ServerEndpoint extends BaseEndpoint {



		@Override
		protected boolean isServer() {
			return true;
		}



		/**
		 * Stores itself into {@link #serverEndpointInstance} and switches its
		 * {@link #serverEndpointCreated}.
		 */
		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			super.onOpen(connection, config);
			serverEndpointInstance.put(connection.getRequestURI(), this);
			serverEndpointCreated.get(connection.getRequestURI()).countDown();
		}
	}



	public static class ClientEndpoint extends BaseEndpoint {

		@Override
		protected boolean isServer() {
			return false;
		}
	}



	/**
	 * Performs {@code test} over a websocket connection.
	 * <ol>
	 *   <li>establishes a connection between a {@link ClientEndpoint} and {@link #server}</li>
	 *   <li>calls {@code test}</li>
	 *   <li>depending on the value of {@code closeClientConnection} closes the connections from the
	 *       client side</li>
	 *    <li>regardless of {@code closeClientConnection}, verifies if the connection was closed on
	 *        both endpoints with {@code expectedClientCloseCode} on the client side</li>
	 *    <li>verifies if there were no calls to {@link Endpoint#onError(Session, Throwable)} on
	 *        either endpoint</li>
	 * </ol>
	 */
	public void performTest(
		String path,
		boolean closeClientConnection,
		CloseCode expectedClientCloseCode,
		BiConsumer<ServerEndpoint, ClientEndpoint> test
	) throws Exception {
		server.addEndpoint(ServerEndpoint.class, path);
		final var clientEndpoint = new ClientEndpoint();
		final var url = URI.create(websocketUrl + path);
		serverEndpointCreated.put(url, new CountDownLatch(1));
		final ServerEndpoint serverEndpoint;
		final var clientConnection = clientWebsocketContainer.connectToServer(
				clientEndpoint, null, url);
		try {
			if ( !serverEndpointCreated.get(url).await(100L, TimeUnit.MILLISECONDS)) {
				fail("ServerEndpoint should be created");
			}
			serverEndpoint = serverEndpointInstance.get(url);
			test.accept(serverEndpoint, clientEndpoint);
		} finally {
			if (closeClientConnection) clientConnection.close();
		}
		if ( !clientEndpoint.closed.await(100L, TimeUnit.MILLISECONDS)) {
			fail("ClientEndpoint should be closed");
		}
		if ( !serverEndpoint.closed.await(100L, TimeUnit.MILLISECONDS)) {
			fail("ServerEndpoint should be closed");
		}
		assertEquals("client close code should match",
				expectedClientCloseCode.getCode(), clientEndpoint.closeCode.getCode());
		assertTrue("ServerEndpoint should not receive any onError(...) calls",
				serverEndpoint.errors.isEmpty());
		assertTrue("ClientEndpoint should not receive any onError(...) calls",
				clientEndpoint.errors.isEmpty());
	}



	@Test
	public void testKeepAlive() throws Exception {
		final var PATH = "/testKeepAlive";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			boolean[] rttReportedHolder = {false};
			final var pingPongPlayer = new PingPongPlayer(
				serverEndpoint.connection,
				-1,
				false,
				(connection, rttNanos) -> rttReportedHolder[0] = true
			);
			final var pongReceived = new CountDownLatch(1);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			final MessageHandler.Whole<PongMessage> decoratedPongHandler = (pong) -> {
				log.fine("server " + PATH + " got pong");
				final var someOtherData =
						ByteBuffer.wrap("someOtherData".getBytes(StandardCharsets.UTF_8));
				pingPongPlayer.onMessage(() -> someOtherData);
				pongReceived.countDown();
			};
			serverEndpoint.connection.addMessageHandler(PongMessage.class, decoratedPongHandler);

			pingPongPlayer.sendPing(new byte[]{69});
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
			assertNotNull("pingPongPlayer should still be awaiting for matching pong",
					pingPongPlayer.pingNanos);
			assertFalse("rtt should not be reported", rttReportedHolder[0]);

			serverEndpoint.connection.removeMessageHandler(decoratedPongHandler);

			pingPongPlayer.sendPing(new byte[]{69});
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
		});
	}



	@Test
	public void testServerPingPongWithRttReporting() throws Exception {
		final var PATH = "/testServerPingPongWithRttReporting";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final long[] pongNanosHolder = {0};
			final long[] rttNanosHolder = {0};
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(
				serverEndpoint.connection,
				2,
				false,
				(connection, rttNanos) -> rttNanosHolder[0] = rttNanos
			);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, forwarding");
				try {
					if ( !postPingVerificationsDone.await(100L, TimeUnit.MILLISECONDS)) {
						fail("post ping verifications should take just few ms");
					}
				} catch (InterruptedException ignored) {}
				pingPongPlayer.onMessage(pong);
				pongNanosHolder[0] = System.nanoTime();
				pongReceived.countDown();
			});
			pingPongPlayer.failureCount = 1;

			final var pingNanos = System.nanoTime();
			pingPongPlayer.sendPing("testPingData".getBytes(StandardCharsets.UTF_8));
			try {
				assertNotNull("pingPongPlayer should be awaiting for pong",
						pingPongPlayer.pingNanos);
			} finally {
				postPingVerificationsDone.countDown();
			}
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should be reset", 0, pingPongPlayer.failureCount);
			assertNull("pingPongPlayer should not be awaiting for pong anymore",
					pingPongPlayer.pingNanos);
			assertTrue("rtt should be accurately reported",
					pongNanosHolder[0] - pingNanos - rttNanosHolder[0] < 1_000_000);
		});
	}



	@Test
	public void testKeepAlivePongFromClient() throws Exception {
		final var PATH = "/testKeepAlivePongFromClient";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer =
					new PingPongPlayer(serverEndpoint.connection, 2, false, null);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, forwarding");
				pingPongPlayer.onMessage(pong);
				pongReceived.countDown();
			});
			final var pongData = ByteBuffer.wrap("keepAlive".getBytes(StandardCharsets.UTF_8));

			try {
				clientEndpoint.connection.getAsyncRemote().sendPong(pongData);
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			} catch (IOException e) {
				fail("unexpected connection problem " + e);
			}
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
			assertNull("pingPongPlayer should not be awaiting for pong",
					pingPongPlayer.pingNanos);
		});
	}



	@Test
	public void testUnmatchedPongAfterPing() throws Exception {
		final var PATH = "/testUnmatchedPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer =
					new PingPongPlayer(serverEndpoint.connection, 2, false, null);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, modifying data");
				final var someOtherData =
						ByteBuffer.wrap("someOtherData".getBytes(StandardCharsets.UTF_8));
				pingPongPlayer.onMessage(() -> someOtherData);
				pongReceived.countDown();
			});

			pingPongPlayer.sendPing("originalPingData".getBytes(StandardCharsets.UTF_8));
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
			assertNotNull("pingPongPlayer should still be awaiting for matching pong",
					pingPongPlayer.pingNanos);
		});
	}



	@Test
	public void testTimedOutPongAndFailureLimitExceeded() throws Exception {
		final var PATH = "/testTimedOutPongAndFailureLimitExceeded";
		performTest(PATH, false, CloseCodes.PROTOCOL_ERROR, (serverEndpoint, clientEndpoint) -> {
			final var pingPongPlayer =
					new PingPongPlayer(serverEndpoint.connection, 1, false, null);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);

			pingPongPlayer.sendPing("firstPingData".getBytes(StandardCharsets.UTF_8));
			assertNotNull("pingPongPlayer should be still awaiting for pong",
					pingPongPlayer.pingNanos);
			assertEquals("failure count should still be 0", 0, pingPongPlayer.failureCount);

			pingPongPlayer.sendPing("secondPingData".getBytes(StandardCharsets.UTF_8));
			assertNotNull("pingPongPlayer should be still awaiting for pong",
					pingPongPlayer.pingNanos);
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);

			pingPongPlayer.sendPing("secondPingData".getBytes(StandardCharsets.UTF_8));
		});
	}



	@Test
	public void testServiceKeepAliveRate() throws Exception {
		final var PATH = "/testServiceKeepAliveRate";
		final int NUM_EXPECTED_PONGS = 3;
		final var service = new WebsocketPingerService(1, false);
		boolean serviceEmpty;
		try {
			performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
				final var pongCounter = new AtomicInteger(0);
				assertEquals("there should be no registered connection initially",
						0, service.getNumberOfConnections());
				service.addConnection(serverEndpoint.connection);
				assertTrue("connection should be successfully registered",
						service.containsConnection(serverEndpoint.connection));
				final var pingPongPlayer = serverEndpoint.connection.getMessageHandlers().stream()
					.filter(PingPongPlayer.class::isInstance)
					.map(PingPongPlayer.class::cast)
					.findFirst()
					.orElseThrow();
				final var decoratedHandler = new MessageHandler.Whole<PongMessage>() {
					@Override public void onMessage(PongMessage pong) {
						log.fine("server " + PATH + " got pong");
						pongCounter.incrementAndGet();
						pingPongPlayer.onMessage(pong);
					}
				};
				serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
				serverEndpoint.connection.addMessageHandler(decoratedHandler);
				try {
					assertEquals("there should be 1 registered connection after adding",
							1, service.getNumberOfConnections());
					Thread.sleep(1000L * NUM_EXPECTED_PONGS);
				} catch (InterruptedException e) {
					fail("test interrupted");
				} finally {
					serverEndpoint.connection.removeMessageHandler(decoratedHandler);
					serverEndpoint.connection.addMessageHandler(pingPongPlayer);
					assertTrue("connection removal should succeed",
							service.removeConnection(serverEndpoint.connection));
					assertFalse("service should indicate that connection was removed",
							service.containsConnection(serverEndpoint.connection));
					assertEquals("there should be no registered connection after removing",
							0, service.getNumberOfConnections());
				}
				assertEquals("correct number of pongs should be received within the timeframe",
						NUM_EXPECTED_PONGS, pongCounter.get());
			});
		} finally {
			serviceEmpty = service.stop().isEmpty();
		}
		// verify after finally block to not suppress earlier errors
		assertTrue("there should be no remaining connections in the service", serviceEmpty);
	}



	@Test
	public void testRemoveUnregisteredConnection() {
		final var service = new WebsocketPingerService(1, false);
		final InvocationHandler handler = (proxy, method, args) -> {
			if (method.getDeclaringClass().equals(Object.class)) {
				return method.invoke(this, args);
			} else {
				throw new UnsupportedOperationException();
			}
		};
		try {
			final Session connectionMock = (Session) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class[]{Session.class}, handler);
			assertFalse("removing unregistered connection should indicate no action took place",
					service.removeConnection(connectionMock));
		} finally {
			service.stop();
		}
	}



	@Test
	public void testClientPingPong() throws Exception {
		final var PATH = "/testClientPingPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer =
					new PingPongPlayer(clientEndpoint.connection, 2, false, null);
			clientEndpoint.connection.removeMessageHandler(pingPongPlayer);
			clientEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("client " + PATH + " got pong, forwarding");
				try {
					if ( !postPingVerificationsDone.await(100L, TimeUnit.MILLISECONDS)) {
						fail("post ping verifications should take just few ms");
					}
				} catch (InterruptedException ignored) {}
				pingPongPlayer.onMessage(pong);
				pongReceived.countDown();
			});
			pingPongPlayer.failureCount = 1;

			pingPongPlayer.sendPing("testPingData".getBytes(StandardCharsets.UTF_8));
			try {
				assertNotNull("pingPongPlayer should be awaiting for pong",
						pingPongPlayer.pingNanos);
			} finally {
				postPingVerificationsDone.countDown();
			}
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should be reset", 0, pingPongPlayer.failureCount);
			assertNull("pingPongPlayer should not be awaiting for pong anymore",
					pingPongPlayer.pingNanos);
		});
	}



	/** {@code FINE} will log all endpoint lifecycle method calls. */
	static Level LOG_LEVEL = Level.WARNING;

	static final Logger log = Logger.getLogger(WebsocketPingerServiceTests.class.getName());
	static final Logger pingerLog = Logger.getLogger(WebsocketPingerService.class.getName());

	@BeforeClass
	public static void setupLogging() {
		try {
			LOG_LEVEL = Level.parse(System.getProperty(
				WebsocketPingerServiceTests.class.getPackageName() + ".level"));
		} catch (Exception ignored) {}
		log.setLevel(LOG_LEVEL);
		pingerLog.setLevel(LOG_LEVEL);
		for (final var handler: Logger.getLogger("").getHandlers()) handler.setLevel(LOG_LEVEL);
	}
}
