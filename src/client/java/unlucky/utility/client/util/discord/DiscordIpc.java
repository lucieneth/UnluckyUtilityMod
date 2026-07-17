package unlucky.utility.client.util.discord;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A minimal Discord IPC client — enough to say "playing Unlucky Client" and no
 * more. Hand-rolled rather than pulled in as a dependency: the protocol is a
 * length-prefixed JSON frame over a local socket, which is far less than the
 * Microsoft device-code flow we already hand-rolled, and it keeps the jar free of
 * third-party code.
 *
 * <p>Discord listens on {@code discord-ipc-0..9} — a named pipe on Windows, a
 * unix socket under the runtime dir elsewhere — and the numbers let several
 * Discord installs coexist, so we try each in turn and keep the first that opens.
 *
 * <p>Frame layout is a 4-byte little-endian opcode, a 4-byte little-endian
 * payload length, then UTF-8 JSON. Nothing here reads replies: the only one that
 * matters is the handshake's, and a failed handshake shows up as the next write
 * throwing on a closed pipe, which is handled the same way as any other drop.
 *
 * <p>Not thread-safe, and deliberately so — {@link DiscordRpcThread} owns the one
 * instance and every call happens on that thread, off the render thread.
 */
public final class DiscordIpc implements Closeable {
	public static final int OP_HANDSHAKE = 0;
	public static final int OP_FRAME = 1;
	public static final int OP_CLOSE = 2;

	private static final int MAX_SOCKETS = 10;
	private static final int HEADER_BYTES = 8;

	/** Windows: a pipe handle opened as a file. Null off Windows. */
	private RandomAccessFile pipe;
	/** Everything else: a unix domain socket. Null on Windows. */
	private SocketChannel channel;

	private DiscordIpc() {
	}

	/** Connects to the first live Discord socket, or null when Discord isn't running. */
	public static DiscordIpc open() {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		for (int i = 0; i < MAX_SOCKETS; i++) {
			DiscordIpc ipc = new DiscordIpc();
			if (windows ? ipc.openPipe(i) : ipc.openSocket(i)) {
				return ipc;
			}
		}
		return null;
	}

	private boolean openPipe(int index) {
		try {
			pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw");
			return true;
		} catch (Exception e) {
			// nothing listening on this one; try the next
			pipe = null;
			return false;
		}
	}

	private boolean openSocket(int index) {
		try {
			channel = SocketChannel.open(StandardProtocolFamily.UNIX);
			channel.connect(UnixDomainSocketAddress.of(socketDir().resolve("discord-ipc-" + index)));
			return true;
		} catch (Exception e) {
			closeQuietly();
			return false;
		}
	}

	/** Discord's documented search order; the first one set wins, /tmp is the fallback. */
	private static Path socketDir() {
		for (String key : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
			String value = System.getenv(key);
			if (value != null && !value.isBlank()) {
				return Path.of(value);
			}
		}
		return Path.of("/tmp");
	}

	/** Writes one frame. Throws when the pipe has gone away, which ends the session. */
	public void send(int opcode, String json) throws IOException {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(opcode).putInt(payload.length).put(payload);
		if (pipe != null) {
			pipe.write(frame.array());
			return;
		}
		frame.flip();
		while (frame.hasRemaining()) {
			channel.write(frame);
		}
	}

	@Override
	public void close() {
		try {
			send(OP_CLOSE, "{}");
		} catch (Exception ignored) {
			// already gone — the point was just to be polite about it
		}
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			if (pipe != null) {
				pipe.close();
			}
			if (channel != null) {
				channel.close();
			}
		} catch (IOException ignored) {
			// nothing useful to do about a failed close
		}
		pipe = null;
		channel = null;
	}

	/** Our own pid, which Discord wants so it can tie the presence to the process. */
	public static long pid() {
		return ProcessHandle.current().pid();
	}
}
