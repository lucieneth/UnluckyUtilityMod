package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.RotationManager;

/** Breaks and replaces a lectern until its villager offers the requested book. */
public class VillagerRoller extends Module {
	public final ModeSetting enchantment = add(new ModeSetting("Enchantment",
			"Book enchantment to roll for", "minecraft:mending", "minecraft:mending")
			.withLabels(VillagerRoller::prettyId));
	public final ModeSetting levelMode = add(new ModeSetting("Level mode",
			"How the offered enchantment level is matched", "Maximum", "Maximum", "At least", "Exact"));
	public final NumberSetting level = add(new NumberSetting("Level",
			"Minimum or exact level when Level mode is not Maximum", 1, 1, 10, 1),
			() -> !levelMode.is("Maximum"));
	public final NumberSetting maxPrice = add(new NumberSetting("Max emerald price",
			"Reject matching books that cost more than this", 64, 1, 64, 1));
	public final NumberSetting searchRange = add(new NumberSetting("Setup range",
			"Range used to find one villager and its lectern", 5, 2, 8, 0.5));
	public final NumberSetting interactRetry = add(new NumberSetting("Interact retry",
			"Ticks between attempts to open the trade screen", 20, 5, 100, 1));
	public final NumberSetting professionTimeout = add(new NumberSetting("Profession timeout",
			"Ticks to wait for the villager to claim or release the lectern", 100, 20, 300, 5));
	public final BooleanSetting pauseOnScreen = add(new BooleanSetting("Pause on screens",
			"Pause instead of closing unrelated screens", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Silently face the lectern or villager before acting", true));
	public final BooleanSetting disableWhenFound = add(new BooleanSetting("Disable when found",
			"Turn the module off after the requested trade appears", true));
	public final BooleanSetting feedback = add(new BooleanSetting("Chat feedback",
			"Report setup problems, rolls, and a matching trade", true));

	private enum State {
		FIND_SETUP, OPEN_TRADES, BREAK_LECTERN, WAIT_PROFESSION_CLEAR, PLACE_LECTERN, WAIT_LIBRARIAN, FOUND
	}

	private State state = State.FIND_SETUP;
	private Villager villager;
	private BlockPos lectern;
	private int waited;
	private int rolls;
	private boolean enchantmentsLoaded;

	public VillagerRoller() {
		super("VillagerRoller", "Villager Roller by FlexCoral — rerolls librarian books automatically", Category.WORLD, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		// Releasing sends the STOP that closes any break still open on the wire.
		MiningActionCoordinator.release(this);
		villager = null;
		lectern = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			return;
		}
		loadEnchantments();
		if (mc().gui.screen() instanceof MerchantScreen screen) {
			if (state == State.OPEN_TRADES) {
				checkTrades(screen.getMenu().getOffers());
			}
			return;
		}
		if (pauseOnScreen.get() && mc().gui.screen() != null) {
			return;
		}
		if (villager != null && (!villager.isAlive() || villager.isRemoved())) {
			state = State.FIND_SETUP;
			villager = null;
		}

		switch (state) {
			case FIND_SETUP -> findSetup(player);
			case OPEN_TRADES -> {
				if (++waited >= interactRetry.getInt()) {
					waited = 0;
					openTrades(player);
				}
			}
			case BREAK_LECTERN -> breakLectern();
			case WAIT_PROFESSION_CLEAR -> waitProfessionClear();
			case PLACE_LECTERN -> placeLectern(player);
			case WAIT_LIBRARIAN -> waitForLibrarian(player);
			case FOUND -> { }
		}
	}

	private void findSetup(LocalPlayer player) {
		double range = searchRange.get();
		lectern = nearestLectern(player.blockPosition(), (int) Math.ceil(range));
		villager = mc().level.getEntitiesOfClass(Villager.class,
				player.getBoundingBox().inflate(range), v -> !v.isBaby() && v.isAlive()).stream()
				.min(Comparator.comparingDouble(v -> lectern == null
						? player.distanceToSqr(v) : v.distanceToSqr(Vec3.atCenterOf(lectern)))).orElse(null);
		if (villager == null || lectern == null) {
			if (waited++ % 100 == 0 && feedback.get()) {
				ChatUtil.info("§eVillagerRoller needs one adult villager and a lectern within "
						+ oneDecimal(range) + " blocks.");
			}
			return;
		}
		if (villager.getVillagerXp() > 0 || villager.getVillagerData().level() > 1) {
			if (feedback.get()) {
				ChatUtil.info("§cThat villager has traded before, so its offers are locked.");
			}
			setEnabled(false);
			return;
		}
		if (!villager.getVillagerData().profession().is(VillagerProfession.NONE)
				&& !villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
			if (feedback.get()) {
				ChatUtil.info("§cThe selected villager already has another profession.");
			}
			setEnabled(false);
			return;
		}
		state = State.OPEN_TRADES;
		waited = interactRetry.getInt();
		if (feedback.get()) {
			ChatUtil.info("§7Rolling §f" + prettyId(enchantment.get()) + "§7 from lectern §b" + lectern.toShortString());
		}
	}

	private void openTrades(LocalPlayer player) {
		if (villager == null) {
			state = State.FIND_SETUP;
			return;
		}
		if (rotate.get()) {
			RotationManager.lookAt(villager.getBoundingBox().getCenter());
		}
		mc().gameMode.interact(player, villager,
				new EntityHitResult(villager, villager.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
	}

	private void checkTrades(MerchantOffers offers) {
		Match match = findMatch(offers);
		mc().player.closeContainer();
		if (match != null) {
			ChatUtil.info("§aFound §f" + prettyId(enchantment.get()) + " " + match.level()
					+ "§a for §f" + match.price() + " emeralds §8(after " + rolls + " rolls)");
			if (disableWhenFound.get()) {
				setEnabled(false);
			} else {
				state = State.FOUND;
			}
			return;
		}
		rolls++;
		state = State.BREAK_LECTERN;
		waited = 0;
	}

	private void breakLectern() {
		if (lectern == null) {
			state = State.FIND_SETUP;
			return;
		}
		if (!mc().level.getBlockState(lectern).is(Blocks.LECTERN)) {
			MiningActionCoordinator.release(this);
			state = State.WAIT_PROFESSION_CLEAR;
			waited = 0;
			return;
		}
		if (rotate.get()) {
			RotationManager.lookAt(Vec3.atCenterOf(lectern));
		}
		InteractUtil.mineTick(this, MiningActionCoordinator.PRIORITY_UTILITY, lectern, faceToward(lectern));
	}

	private void waitProfessionClear() {
		if (villager == null || !villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)
				|| ++waited >= professionTimeout.getInt()) {
			state = State.PLACE_LECTERN;
			waited = 0;
		}
	}

	private void placeLectern(LocalPlayer player) {
		if (mc().level.getBlockState(lectern).is(Blocks.LECTERN)) {
			state = State.WAIT_LIBRARIAN;
			waited = 0;
			return;
		}
		int slot = findLectern(player.getInventory());
		if (slot < 0) {
			if (waited++ % 40 == 0 && feedback.get()) {
				ChatUtil.info("§eWaiting to pick up a lectern...");
			}
			return;
		}
		Placement placement = placementFor(lectern);
		if (placement == null) {
			if (feedback.get()) {
				ChatUtil.info("§cNo solid neighboring face can support the lectern at " + lectern.toShortString());
			}
			setEnabled(false);
			return;
		}
		if (rotate.get()) {
			RotationManager.lookAt(placement.hit().getLocation());
		}
		int previous = player.getInventory().getSelectedSlot();
		player.getInventory().setSelectedSlot(slot);
		mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placement.hit());
		player.swing(InteractionHand.MAIN_HAND);
		player.getInventory().setSelectedSlot(previous);
		state = State.WAIT_LIBRARIAN;
		waited = 0;
	}

	private void waitForLibrarian(LocalPlayer player) {
		if (villager != null && villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
			state = State.OPEN_TRADES;
			waited = interactRetry.getInt();
			return;
		}
		if (++waited >= professionTimeout.getInt()) {
			// Re-place if the server bounced the block, otherwise retry interaction.
			state = mc().level.getBlockState(lectern).is(Blocks.LECTERN)
					? State.OPEN_TRADES : State.PLACE_LECTERN;
			waited = interactRetry.getInt();
		}
	}

	private record Match(int level, int price) {
	}

	private Match findMatch(MerchantOffers offers) {
		for (MerchantOffer offer : offers) {
			ItemEnchantments stored = offer.getResult().get(DataComponents.STORED_ENCHANTMENTS);
			if (stored == null) {
				continue;
			}
			for (var entry : stored.entrySet()) {
				String id = entry.getKey().getRegisteredName();
				int offeredLevel = entry.getIntValue();
				int price = offer.getCostA().getCount();
				if (id.equals(enchantment.get()) && price <= maxPrice.getInt() && levelMatches(entry.getKey().value().getMaxLevel(), offeredLevel)) {
					return new Match(offeredLevel, price);
				}
			}
		}
		return null;
	}

	private boolean levelMatches(int maximum, int offered) {
		return switch (levelMode.get()) {
			case "Exact" -> offered == level.getInt();
			case "At least" -> offered >= level.getInt();
			default -> offered >= maximum;
		};
	}

	private void loadEnchantments() {
		if (enchantmentsLoaded || mc().level == null) {
			return;
		}
		List<String> ids = new ArrayList<>();
		mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
				.forEach(holder -> ids.add(holder.getRegisteredName()));
		ids.sort(String.CASE_INSENSITIVE_ORDER);
		if (!ids.isEmpty()) {
			enchantment.setModes(ids);
		}
		enchantmentsLoaded = true;
	}

	private BlockPos nearestLectern(BlockPos center, int radius) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
				center.offset(radius, radius, radius))) {
			if (!mc().level.getBlockState(pos).is(Blocks.LECTERN)) {
				continue;
			}
			double distance = pos.distSqr(center);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.immutable();
			}
		}
		return best;
	}

	private Direction faceToward(BlockPos pos) {
		Vec3 difference = mc().player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(difference.x, difference.y, difference.z);
	}

	private record Placement(BlockHitResult hit) {
	}

	private Placement placementFor(BlockPos target) {
		Direction[] order = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};
		for (Direction face : order) {
			BlockPos support = target.relative(face.getOpposite());
			if (mc().level.getBlockState(support).canBeReplaced()) {
				continue;
			}
			Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			return new Placement(new BlockHitResult(hit, face, support, false));
		}
		return null;
	}

	private static int findLectern(Inventory inventory) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (inventory.getItem(slot).is(Items.LECTERN)) {
				return slot;
			}
		}
		return -1;
	}

	private void reset() {
		state = State.FIND_SETUP;
		villager = null;
		lectern = null;
		waited = 0;
		rolls = 0;
		enchantmentsLoaded = false;
	}

	private static String prettyId(String raw) {
		String path = raw;
		try {
			path = Identifier.parse(raw).getPath();
		} catch (RuntimeException ignored) {
		}
		StringBuilder out = new StringBuilder(path.length());
		boolean upper = true;
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if (c == '_' || c == '-') {
				out.append(' ');
				upper = true;
			} else {
				out.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}
		}
		return out.toString();
	}

	private static String oneDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}
}
