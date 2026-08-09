package unlucky.utility.client.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Wire format for direct server-side inventory placement.
 *
 * <p>The id {@code "unlucky:place"}, field order, and codecs form the complete
 * client/server contract. Both endpoints must change together or decoding will fail.
 *
 * <p>Stacks ride as {@link ItemStack#OPTIONAL_STREAM_CODEC}: binary, all components,
 * empty allowed so an entry can clear a slot. Binary is the whole point — the items
 * that needed hundreds of chunked SNBT commands are a single small packet here.
 */
public record PlacePayload(List<Entry> entries) implements CustomPacketPayload {

	/** {@link Entry#target} value: the sender's own inventory, slot as an inventory index. */
	public static final int PLAYER = 0;
	/** {@link Entry#target} value: the chest of the horse the sender is riding, slot 0-based. */
	public static final int VEHICLE = 1;

	/** One placement. {@code stack} may be empty, which clears the slot. */
	public record Entry(int target, int slot, ItemStack stack) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Entry::target,
				ByteBufCodecs.VAR_INT, Entry::slot,
				ItemStack.OPTIONAL_STREAM_CODEC, Entry::stack,
				Entry::new);

		public static Entry player(int slot, ItemStack stack) {
			return new Entry(PLAYER, slot, stack);
		}

		public static Entry vehicle(int slot, ItemStack stack) {
			return new Entry(VEHICLE, slot, stack);
		}
	}

	// createType(String) treats its whole argument as a path under the minecraft
	// namespace — "unlucky:place" would become the illegal id "minecraft:unlucky:place".
	// Build the Type from a real namespaced Identifier instead.
	public static final Type<PlacePayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath("unlucky", "place"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PlacePayload> STREAM_CODEC = StreamCodec.composite(
			Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), PlacePayload::entries,
			PlacePayload::new);

	@Override
	public Type<PlacePayload> type() {
		return TYPE;
	}
}
