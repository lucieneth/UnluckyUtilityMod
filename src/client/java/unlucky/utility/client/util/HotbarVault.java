package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Your saved creative hotbars, read back off disk.
 *
 * <p>Ctrl+1..9 in the creative inventory writes {@code hotbar.nbt} in the game
 * directory, and it keeps the whole stack — every data component, including ones
 * only a command could have produced. Vanilla stores each slot with
 * {@code ItemStack.OPTIONAL_CODEC} and {@code Hotbar.load} hands them back as real
 * {@link ItemStack}s, so nothing here parses anything by hand.
 *
 * <p>{@link #encode} runs the same codec in the other direction. That matters more
 * than it looks: a stack that came out of this file goes back in through the same
 * serialisation, so it is a round trip rather than a reconstruction, and components
 * nobody wrote special handling for survive anyway.
 */
public final class HotbarVault {
	/** {@code HotbarManager.NUM_HOTBAR_GROUPS}. */
	public static final int GROUPS = 9;

	/**
	 * The longest command that can leave the client, from
	 * {@code ServerboundChatCommandPacket.write}'s bare {@code writeUtf()}.
	 *
	 * <p>This is not a soft limit to stay under politely. {@code Utf8String.write}
	 * throws an {@code EncoderException} on the Netty thread, and nothing downstream
	 * catches it — {@code Connection.exceptionCaught} tears down the channel, so one
	 * oversized command is an instant disconnect rather than a rejected command.
	 * A saved stack can blow past it easily: a shulker box of shulker boxes carries
	 * every nested component, and Scarpet escaping doubles every backslash on top.
	 */
	public static final int MAX_COMMAND = 32767;

	/** Whether {@code command} can be sent without killing the connection. */
	public static boolean fits(String command) {
		return command.length() <= MAX_COMMAND;
	}

	/**
	 * The Scarpet global the chunked transfer accumulates into.
	 *
	 * <p>A list rather than a string, because the payload is appended one chunk per
	 * command and {@code s = s + chunk} would copy the whole accumulated string every
	 * time — quadratic, and the big stacks here run to a hundred-plus chunks. Appending
	 * to a list is a reference copy and {@code join} walks it once at the end.
	 *
	 * <p>Globals survive between commands, which is the whole reason this works: an app
	 * at {@code scope -> 'player'} gives each player their own, and {@code /script run}
	 * keeps them in the default app's host.
	 */
	public static final String BUFFER = "global_unlucky_payload";

	/**
	 * Vanilla's command throttle, from {@code ServerGamePacketListenerImpl}: the
	 * command {@code TickThrottler} is {@code new TickThrottler(20, 20 *
	 * command-spam-threshold-seconds)}, so every command adds 20 to a counter that
	 * decays by 1 per tick, and crossing the threshold is {@code disconnect.spam}.
	 *
	 * <p>{@code command-spam-threshold-seconds} defaults to 10, giving a threshold of
	 * 200. That is only the default — a server may lower it — so this is the assumption
	 * the pacing is built on rather than a guarantee.
	 */
	public static final int SPAM_STEP = 20;

	/** {@code 20 * command-spam-threshold-seconds} at the vanilla default of 10. */
	public static final int SPAM_THRESHOLD = 200;

	/**
	 * The tick delay a run of {@code commands} needs in order not to be kicked.
	 *
	 * <p>Below {@link #SPAM_STEP} ticks apart the counter climbs by {@code 20 - delay}
	 * per command and the kick is only a matter of how many are sent: at 4 ticks that is
	 * the thirteenth. Any chunked stack is longer than that, so a short delay is fine
	 * for a handful of whole-stack commands and never fine for a transfer.
	 *
	 * <p>Ops and the singleplayer owner are checked before the disconnect and skip it
	 * entirely, which is why {@code bypasses} short-circuits all of this.
	 */
	public static int safeDelay(int commands, int wanted, boolean bypasses) {
		if (bypasses || wanted >= SPAM_STEP) {
			return wanted;
		}
		int perCommand = SPAM_STEP - wanted;
		int burst = (SPAM_THRESHOLD - 1) / perCommand;
		// one command of headroom, since the counter does not start at zero if
		// anything else was typed recently
		return commands < burst ? wanted : SPAM_STEP;
	}

	/**
	 * Splits a payload into pieces of at most {@code room} characters.
	 *
	 * <p>Never cuts between a backslash and the character it escapes. That matters for
	 * both transports: the Scarpet path escapes {@code \\} and {@code \'} before this
	 * runs, and raw SNBT carries {@code \"} of its own, so a split landing mid-escape
	 * would corrupt the payload in a way that only shows up on reassembly.
	 */
	public static List<String> split(String payload, int room) {
		List<String> out = new ArrayList<>();
		if (room < 2) {
			return out;
		}
		int at = 0;
		while (at < payload.length()) {
			int end = Math.min(at + room, payload.length());
			if (end < payload.length()) {
				int back = end;
				int slashes = 0;
				while (back > at && payload.charAt(back - 1) == '\\') {
					slashes++;
					back--;
				}
				// an odd run means the last backslash owns the character we would cut
				// away from it; room >= 2 keeps this from emptying the chunk
				if ((slashes & 1) == 1) {
					end--;
				}
			}
			out.add(payload.substring(at, end));
			at = end;
		}
		return out;
	}

	/** A saved stack and the hotbar slot it was saved in. */
	public record Entry(int slot, ItemStack stack) {
	}

	private HotbarVault() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** True once a world is joined — the codec needs the server's registries. */
	public static boolean ready() {
		return mc().getConnection() != null;
	}

	/**
	 * Forces vanilla to read and data-fix {@code hotbar.nbt} now.
	 *
	 * <p>{@code HotbarManager} intentionally defers this work until its first
	 * {@code get}; DonkeyRitual normally becomes that first caller. Selecting this
	 * action lets the player choose when the potentially expensive disk read happens.
	 * It only warms vanilla's in-memory hotbar data and never writes the file.
	 */
	public static void preload() {
		mc().getHotbarManager().get(0);
	}

	/**
	 * Every non-empty slot of saved hotbar {@code group} (1-based, as the UI counts).
	 * Empty when the group was never saved, so callers can treat "nothing there" and
	 * "not in a world" as one case.
	 */
	public static List<Entry> read(int group) {
		List<Entry> out = new ArrayList<>();
		if (!ready() || group < 1 || group > GROUPS) {
			return out;
		}
		HolderLookup.Provider registries = mc().getConnection().registryAccess();
		List<ItemStack> stacks = mc().getHotbarManager().get(group - 1).load(registries);
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (!stack.isEmpty()) {
				out.add(new Entry(slot, stack));
			}
		}
		return out;
	}

	/** All nine groups, for the picker — index 0 is hotbar 1. */
	public static List<List<Entry>> readAll() {
		List<List<Entry>> out = new ArrayList<>(GROUPS);
		for (int group = 1; group <= GROUPS; group++) {
			out.add(read(group));
		}
		return out;
	}

	/** How many items hotbar {@code group} would produce. */
	public static int count(int group) {
		return read(group).size();
	}

	/** A stack as SNBT, or null when the codec refuses it. */
	public static String encode(ItemStack stack) {
		if (!ready()) {
			return null;
		}
		try {
			HolderLookup.Provider registries = mc().getConnection().registryAccess();
			Tag tag = ItemStack.CODEC
					.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
					.getOrThrow();
			return tag.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
