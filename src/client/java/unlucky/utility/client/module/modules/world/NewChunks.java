package unlucky.utility.client.module.modules.world;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Session-scoped evidence map for chunks that are likely newly generated.
 *
 * <p>This is a heuristic overlay, not a generator oracle. A post-load non-source fluid update
 * is strong evidence of active terrain settling; a complete chunk that remains quiet only
 * becomes weakly OLD after a grace period. Pregeneration and custom server packet behavior can
 * invalidate either inference, so UNKNOWN remains a first-class state.
 */
public class NewChunks extends Module {
	private static final int EVIDENCE_VERSION = 1;
	private static final long OLD_GRACE_TICKS = 20;

	public enum Classification {
		UNKNOWN,
		OLD,
		NEW
	}

	private record ChunkKey(String dimension, long position) {
	}

	private record Evidence(Classification classification, long observedTick, int version) {
	}

	public final NumberSetting renderDistance = add(new NumberSetting("Render distance",
			"Maximum distance in chunks", 32, 4, 128, 1));
	public final ModeSetting yMode = add(new ModeSetting("Y mode",
			"Draw below the player automatically or at a fixed height", "Auto", "Auto", "Fixed"));
	public final NumberSetting fixedY = add(new NumberSetting("Fixed Y",
			"Plane height in Fixed mode", 0, -128, 512, 1), () -> yMode.is("Fixed"));
	public final BooleanSetting showNew = add(new BooleanSetting("Show new",
			"Render chunks with strong new-generation evidence", true));
	public final BooleanSetting showOld = add(new BooleanSetting("Show old",
			"Render chunks that arrived complete and remained quiet", true));
	public final BooleanSetting showUnknown = add(new BooleanSetting("Show unknown",
			"Render chunks still inside the evidence grace period", false));
	public final BooleanSetting smooth = add(new BooleanSetting("Smooth",
			"Blend old borders toward nearby new chunks", true));
	public final BooleanSetting keepUnloaded = add(new BooleanSetting("Keep unloaded",
			"Keep classifications after a chunk leaves view for this session", true));
	public final ModeSetting renderMode = add(new ModeSetting("Render",
			"Draw a filled plane, its border, or both", "Plane", "Plane", "Border", "Both"));
	public final ColorSetting newColor = add(new ColorSetting("New color",
			"Likely-new chunk color", 0x5000FF55));
	public final ColorSetting oldColor = add(new ColorSetting("Old color",
			"Likely-old chunk color", 0x50FF4040));
	public final ColorSetting unknownColor = add(new ColorSetting("Unknown color",
			"Unclassified chunk color", 0x50888888));
	public final BooleanSetting resetDimension = add(new BooleanSetting("Reset on dimension change",
			"Clear session evidence when entering another dimension", true));
	public final ActionSetting clearData = add(new ActionSetting("Clear data",
			"Forget every recorded chunk classification", this::clear));

	private final ConcurrentHashMap<ChunkKey, Evidence> chunks = new ConcurrentHashMap<>();
	private ClientPacketListener lastConnection;
	private ClientLevel lastLevel;
	private long clientTick;

	public NewChunks() {
		super("NewChunks", "Highlights chunks that are likely newly generated",
				Category.WORLD, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		clear();
		lastConnection = mc().getConnection();
		lastLevel = mc().level;
		clientTick = 0;
	}

	@Override
	protected void onDisable() {
		clear();
		lastConnection = null;
		lastLevel = null;
		clientTick = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) return;
		clientTick++;
		syncIdentity();
		render();
	}

	/** Packet arrival can precede the first module tick in a new world; sync before recording. */
	private void syncIdentity() {
		if (mc().getConnection() != lastConnection) {
			clear();
			lastConnection = mc().getConnection();
			lastLevel = mc().level;
		} else if (mc().level != lastLevel) {
			if (resetDimension.get()) clear();
			lastLevel = mc().level;
		}
	}

	/** Called at TAIL of the existing ClientPacketListener chunk-load handler. */
	public void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet) {
		if (!packetThreadReady()) return;
		ChunkKey key = key(ChunkPos.pack(packet.getX(), packet.getZ()));
		chunks.putIfAbsent(key, new Evidence(Classification.UNKNOWN, tick(), EVIDENCE_VERSION));
	}

	/** Called at TAIL of the existing ClientPacketListener unload handler. */
	public void onChunkForgotten(ClientboundForgetLevelChunkPacket packet) {
		if (!packetThreadReady() || keepUnloaded.get()) return;
		chunks.remove(key(packet.pos().pack()));
	}

	/** Called at TAIL of the existing single-block update handler. */
	public void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
		if (!packetThreadReady() || !isNewEvidence(packet.getBlockState())) return;
		markNew(ChunkPos.pack(packet.getPos()));
	}

	/** Called at TAIL of the existing section-block update handler. */
	public void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
		if (!packetThreadReady()) return;
		packet.runUpdates((pos, state) -> {
			if (isNewEvidence(state)) markNew(ChunkPos.pack(pos));
		});
	}

	/** Evidence predicate kept visible so the client gametest can pin the heuristic version. */
	public static boolean isNewEvidence(BlockState state) {
		if (state == null) return false;
		var fluid = state.getFluidState();
		return !fluid.isEmpty() && !fluid.isSource();
	}

	private void markNew(long packed) {
		chunks.put(key(packed), new Evidence(Classification.NEW, tick(), EVIDENCE_VERSION));
	}

	private void render() {
		if (chunks.isEmpty()) return;
		String dimension = dimension();
		ChunkPos playerChunk = mc().player.chunkPosition();
		int radius = renderDistance.getInt();
		int radiusSquared = radius * radius;
		double y = yMode.is("Fixed") ? fixedY.get()
				: Math.max(mc().level.getMinY() + 1.0, mc().player.getY() - 100.0);
		boolean plane = !renderMode.is("Border");
		boolean border = !renderMode.is("Plane");
		long now = tick();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radiusSquared) continue;
				ChunkPos pos = new ChunkPos(playerChunk.x() + dx, playerChunk.z() + dz);
				ChunkKey key = new ChunkKey(dimension, pos.pack());
				Evidence evidence = settle(key, chunks.get(key), now);
				if (evidence == null || !shown(evidence.classification())) continue;
				int color = colorFor(dimension, pos, evidence.classification());
				AABB slab = new AABB(pos.getMinBlockX(), y, pos.getMinBlockZ(),
						pos.getMaxBlockX() + 1.0, y + 0.01, pos.getMaxBlockZ() + 1.0);
				Render3D.box(slab, border ? color : 0, 1.5f, plane ? color : 0, true);
			}
		}
	}

	/** Lazily settles only chunks inside the bounded render radius; history size never sets cost. */
	private Evidence settle(ChunkKey key, Evidence evidence, long now) {
		if (evidence == null || evidence.classification() != Classification.UNKNOWN
				|| now - evidence.observedTick() < OLD_GRACE_TICKS) {
			return evidence;
		}
		Evidence old = new Evidence(Classification.OLD, evidence.observedTick(), EVIDENCE_VERSION);
		return chunks.replace(key, evidence, old) ? old : chunks.get(key);
	}

	private boolean shown(Classification classification) {
		return switch (classification) {
			case NEW -> showNew.get();
			case OLD -> showOld.get();
			case UNKNOWN -> showUnknown.get();
		};
	}

	private int colorFor(String dimension, ChunkPos pos, Classification classification) {
		int color = switch (classification) {
			case NEW -> newColor.get();
			case OLD -> oldColor.get();
			case UNKNOWN -> unknownColor.get();
		};
		if (!smooth.get() || classification != Classification.OLD) return color;
		double weight = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) continue;
				Evidence neighbor = chunks.get(new ChunkKey(dimension,
						ChunkPos.pack(pos.x() + dx, pos.z() + dz)));
				if (neighbor != null && neighbor.classification() == Classification.NEW) {
					weight += (3.0 - Math.max(Math.abs(dx), Math.abs(dz))) / 2.0;
				}
			}
		}
		return weight <= 0 ? color : ColorUtil.lerp(color, newColor.get(),
				(float) Math.min(1.0, weight / 12.0));
	}

	private boolean packetThreadReady() {
		if (!mc().isSameThread() || mc().level == null) return false;
		syncIdentity();
		return true;
	}

	private ChunkKey key(long packed) {
		return new ChunkKey(dimension(), packed);
	}

	private String dimension() {
		return mc().level.dimension().identifier().toString();
	}

	private long tick() {
		return clientTick;
	}

	private void clear() {
		chunks.clear();
	}
}
