package de.saschahlusiak.freebloks.client

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import de.saschahlusiak.freebloks.model.GameConfig
import de.saschahlusiak.freebloks.model.*
import de.saschahlusiak.freebloks.model.Game.Companion.PLAYER_LOCAL
import de.saschahlusiak.freebloks.network.*
import de.saschahlusiak.freebloks.network.message.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.EOFException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class GameClientTest {
    private val game = Game()
    private val client = GameClient(game, GameConfig())

    private val toServerStream = PipedOutputStream()
    private val fromServerStream = PipedInputStream()
    private val toClientStream = PipedOutputStream()
    private val fromClientStream = PipedInputStream()

    private val toClient = MessageWriter(toClientStream)
    private lateinit var serverThread: MessageReadThread

    private var readerClosed = false
    private var clientDisconnectError: Exception? = null
    private val messages = mutableListOf<Message>()

    /**
     * Simulate a server and collect messages received by the client
     */
    private val serverMessageHandler = object: MessageHandler, Closeable {
        override fun handleMessage(message: Message) {
            messages.add(message)
        }

        override fun close() {
            toServerStream.close()
            fromServerStream.close()
            readerClosed = true
        }
    }

    private enum class Event {
        Status, Disconnected
    }

    /**
     * Used to watch for the client to have processed a server status message
     */
    private val gameObserver = object: GameEventObserver {

        private val events = Channel<Event>(Channel.UNLIMITED)
        var statusReceived: Boolean = false

        @WorkerThread
        override fun serverStatus(status: MessageServerStatus) {
            events.sendBlocking(Event.Status)
        }

        @UiThread
        override fun onDisconnected(client: GameClient, error: Exception?) {
            clientDisconnectError = error
            events.sendBlocking(Event.Disconnected)
        }

        /**
         * Block until the server status message is received
         */
        suspend fun awaitStatus() {
            for (event in events) {
                if (event == Event.Status) return
            }
        }

        /**
         * Blocks until any game event is received
         */
        suspend fun awaitGameEvent(): Event {
            return withTimeout(250) {
                events.receive()
            }
        }
    }

    private suspend fun awaitGameEvent() = gameObserver.awaitGameEvent()

    /**
     * Sends out a [MessageServerStatus] to the client and blocks until it is processed
     */
    private fun MessageWriter.flush() = runBlocking {
        gameObserver.statusReceived = false

        write(MessageServerStatus(
            0, 4, 1,
            game.board.width, game.board.height,
            game.gameMode,
            arrayOfNulls(4),
            arrayOfNulls(8)
        ))

        toClientStream.flush()

        gameObserver.awaitStatus()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Default)

        fromServerStream.connect(toClientStream)
        fromClientStream.connect(toServerStream)

        client.connected(serverMessageHandler, fromServerStream, toServerStream)
        client.addObserver(gameObserver)

        serverThread = MessageReadThread(fromClientStream, serverMessageHandler) { readerClosed = true }
        serverThread.start()

        // player 1 is local and the current player
        toClient.write(
            MessageGrantPlayer(1),
            MessageCurrentPlayer(1)
        )

        toClient.flush()

        assertEquals(1, game.currentPlayer)
        assertTrue(game.isLocalPlayer(1))
    }

    @After
    fun teardown() {
        serverThread.goDown()
    }

    @Test
    fun test_gameClient_notConnected() {
        val client = GameClient(Game(), GameConfig())
        assertFalse(client.isConnected())
    }

    @Test
    fun test_gameClient_connected() {
        assertTrue(client.isConnected())
        assertNull(clientDisconnectError)
    }

    @Test
    fun test_gameClient_client_disconnect() {
        client.disconnect()
        serverThread.join()
        assertFalse(client.isConnected())
        assertTrue(readerClosed)
        assertNull(clientDisconnectError)
    }

    @Test
    fun test_gameClient_server_disconnect() = runBlocking {
        assertTrue(client.isConnected())
        toClientStream.close()
        awaitGameEvent()
        assertFalse(client.isConnected())
        assertTrue(clientDisconnectError is EOFException)
    }

    @Test
    fun test_gameClient_requestPlayer() {
        client.requestPlayer(3, "hello")
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageRequestPlayer(3, "hello"), messages[0])
    }

    @Test
    fun test_gameClient_requestHint() {
        // client will refuse to send request if the current player is not local
        client.game.currentPlayer = 2
        client.game.setPlayerType(2, PLAYER_LOCAL)

        client.requestHint()
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageRequestHint(2), messages[0])
    }

    @Test
    fun test_gameClient_requestStart() {
        client.requestGameStart()
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageStartGame(), messages[0])
    }

    @Test
    fun test_gameClient_revokePlayer() {
        client.revokePlayer(1)
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageRevokePlayer(1), messages[0])
    }

    @Test
    fun test_gameClient_chat() {
        client.sendChat("Hey hey")
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageChat(0, "Hey hey"), messages[0])
    }

    @Test
    fun test_gameClient_setStone() {
        client.setStone(Turn(1, 2, 3, 4, Orientation.Default))
        assertEquals(-1, client.game.currentPlayer)
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageSetStone(1, 2, Orientation.Default, 4, 3), messages[0])
    }

    @Test
    fun test_gameClient_undo() {
        client.requestUndo()
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageRequestUndo(), messages[0])
    }

    @Test
    fun test_gameClient_requestGameMode() {
        client.requestGameMode(17, 17, GameMode.GAMEMODE_DUO, IntArray(21) { 1 })
        client.disconnect()
        serverThread.join(2000)
        assertTrue(serverThread.error is EOFException)
        assertFalse(client.isConnected())

        assertEquals(1, messages.size)
        assertEquals(MessageRequestGameMode(17, 17, GameMode.GAMEMODE_DUO, IntArray(21) { 1 }), messages[0])
    }
}