package de.saschahlusiak.freebloks.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.crashlytics.android.Crashlytics;
import de.saschahlusiak.freebloks.R;
import de.saschahlusiak.freebloks.game.GameConfiguration;
import de.saschahlusiak.freebloks.model.Board;
import de.saschahlusiak.freebloks.model.GameMode;
import de.saschahlusiak.freebloks.model.Game;
import de.saschahlusiak.freebloks.model.Turn;
import de.saschahlusiak.freebloks.network.*;
import de.saschahlusiak.freebloks.network.message.MessageChat;
import de.saschahlusiak.freebloks.network.message.MessageRequestGameMode;
import de.saschahlusiak.freebloks.network.message.MessageRequestHint;
import de.saschahlusiak.freebloks.network.message.MessageRequestPlayer;
import de.saschahlusiak.freebloks.network.message.MessageRequestUndo;
import de.saschahlusiak.freebloks.network.message.MessageRevokePlayer;
import de.saschahlusiak.freebloks.network.message.MessageSetStone;
import de.saschahlusiak.freebloks.network.message.MessageStartGame;

public class GameClient {
	private static final int DEFAULT_TIMEOUT = 10000;
	public static final int DEFAULT_PORT = 59995;

	private Object client_socket;
	public final Game game;
	public final Board board;
	private MessageWriter messageWriter;
	private HandlerThread sendThread;
	private MessageReadThread readThread;
	private Handler sendHandler;
	private GameConfiguration config;
	private GameClientMessageHandler gameClientMessageHandler;

	@UiThread
	public GameClient(Game game, GameConfiguration config) {
		this.config = config;
		if (game == null) {
			Board board = new Board(config.getFieldSize());
			board.startNewGame(GameMode.GAMEMODE_4_COLORS_4_PLAYERS);
			board.setAvailableStones(0, 0, 0, 0, 0);

			this.game = new Game(board);
		} else
			this.game = game;

		this.board = game.getBoard();

		client_socket = null;

		gameClientMessageHandler = new GameClientMessageHandler(this.game);
	}

	@Override
	protected void finalize() throws Throwable {
		disconnect();
		super.finalize();
	}

	public GameConfiguration getConfig() {
		return this.config;
	}

	public boolean isConnected() {
		if (client_socket == null) return false;
		if (client_socket instanceof Socket && ((Socket)client_socket).isClosed()) return false;
		if (client_socket instanceof BluetoothSocket && !((BluetoothSocket)client_socket).isConnected()) return false;

		return true;
	}

	public void addObserver(GameEventObserver sci) {
		gameClientMessageHandler.addObserver(sci);
	}

	public void removeObserver(GameEventObserver sci) {
		gameClientMessageHandler.removeObserver(sci);
	}

	@WorkerThread
	public void connect(Context context, String host, int port) throws IOException {
		final Socket socket = new Socket();
		try {
			SocketAddress address;
			if (host == null)
				address = new InetSocketAddress((InetAddress)null, port);
			else
				address = new InetSocketAddress(host, port);
			
			socket.connect(address, DEFAULT_TIMEOUT);
		} catch (IOException e) {
			e.printStackTrace();
			throw new IOException(context.getString(R.string.connection_refused));
		}
		synchronized(this) {
			messageWriter = new MessageWriter(socket.getOutputStream());
			readThread = new MessageReadThread(socket.getInputStream(), gameClientMessageHandler, () -> {
				disconnect();
				return null;
			});
			readThread.start();

			sendThread = new HandlerThread("SendThread");
			sendThread.start();
			sendHandler = new Handler(sendThread.getLooper());

			gameClientMessageHandler.onConnected();

			this.client_socket = socket;
		}
	}

	public void setSocket(BluetoothSocket socket) throws IOException {
		synchronized(this) {
			this.client_socket = socket;

			messageWriter = new MessageWriter(socket.getOutputStream());
			readThread = new MessageReadThread(socket.getInputStream(), gameClientMessageHandler, () -> {
				disconnect();
				return null;
			});
			readThread.start();

			sendThread = new HandlerThread("SendThread");
			sendThread.start();
			sendHandler = new Handler(sendThread.getLooper());

			gameClientMessageHandler.onConnected();
		}
	}

	public synchronized void disconnect() {
		if (client_socket != null) {
			final Exception lastError = readThread.getError();
			try {
				Crashlytics.log("Disconnecting from " + config.getServer());
				readThread.goDown();
				if (client_socket instanceof Socket) {
					Socket socket = (Socket) client_socket;
					if (socket.isConnected())
						socket.shutdownInput();
					socket.close();
				}
				if (client_socket instanceof BluetoothSocket) {
					BluetoothSocket socket = (BluetoothSocket) client_socket;
					socket.close();
				}
				if (sendThread != null)
					sendThread.quit();
				readThread = null;
				sendThread = null;
			} catch (IOException e) {
				e.printStackTrace();
			}

			gameClientMessageHandler.onDisconnected(lastError);
		}
		client_socket = null;
	}

	public void request_player(int player, @Nullable String name) {
		send(new MessageRequestPlayer(player, name));
	}

	public void revoke_player(int player) {
		send(new MessageRevokePlayer(player));
	}
	
	public void request_game_mode(int width, int height, GameMode g, int stones[]) {
		send(new MessageRequestGameMode(width, height, g, stones));
	}

	public void request_hint(int player) {
		if (game == null)
			return;
		if (!isConnected())
			return;
		if (!game.isLocalPlayer())
			return;
		send(new MessageRequestHint(player));
	}

	public void sendChat(String message) {
		// the client does not matter, it will be filled in by the server then broadcasted
		send(new MessageChat(0, message));
	}

	/**
	 * Wird von der GUI aufgerufen, wenn ein Spieler einen Stein setzen will Die
	 * Aktion wird nur an den Server geschickt, der Stein wird NICHT lokal
	 * gesetzt
	 **/
	public int set_stone(Turn turn)
	{
		if (game.getCurrentPlayer() ==-1)
			return Board.FIELD_DENIED;
		
		/* Lokal keinen Spieler als aktiv setzen.
	   	Der Server schickt uns nachher den neuen aktiven Spieler zu */
		game.setCurrentPlayer(-1);

		send(new MessageSetStone(turn));

		return Board.FIELD_ALLOWED;
	}

	/**
	 * Erbittet den Spielstart beim Server
	 **/
	public void request_start() {
		send(new MessageStartGame());
	}

	/**
	 * Erbittet eine Zugzuruecknahme beim Server
	 **/
	public void request_undo() {
		if (game == null)
			return;
		if (!isConnected())
			return;
		if (!game.isLocalPlayer())
			return;
		send(new MessageRequestUndo());
	}

	@AnyThread
	private void send(de.saschahlusiak.freebloks.network.Message msg) {
		if (sendHandler == null)
			return;

		// ignore sending to closed stream
		sendHandler.post(() -> messageWriter.write(msg));
	}
}
