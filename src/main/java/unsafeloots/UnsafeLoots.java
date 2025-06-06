package unsafeloots;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unsafeloots.block.ModBlocks;
import unsafeloots.item.ModItemGroups;
import unsafeloots.item.ModItems;

import java.util.*;

public class UnsafeLoots implements ModInitializer {
	public static final String MOD_ID = "unsafe-loots";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String UNSAFE_TAG = "unsafe_loot";

	// 玩家状态跟踪集合
	private static final Set<UUID> inVillagePlayers = new HashSet<>();
	private static final Set<UUID> hasUnsafeItemsPlayers = new HashSet<>();
	private static final Set<UUID> rewardedPlayers = new HashSet<>();

	// 掉落物保留
	private static final Set<UUID> playersWithItemsToKeep = new HashSet<>();
	private static final List<ItemStack> itemsToKeep = new ArrayList<>();

	// 配置常量
	private static final Identifier VILLAGE_ID = new Identifier("village_plains");
	private static final int CHECK_INTERVAL = 20; // 检测间隔(20 ticks = 1秒)

	@Override
	public void onInitialize() {
		ModItemGroups.registerItemGroups();
		ModItems.registerModItems();
		ModBlocks.registerModBlocks();

		LOGGER.info("Unsafe Loots mod initialized!");

		// 注册服务器每tick事件
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % CHECK_INTERVAL != 0) return;

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				checkVillageStatus(player);
				checkUnsafeItems(player);
				checkVillageAndGiveStick(player); // 保留原有的木棍奖励功能
			}
		});

		// 死亡时处理
		ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageAmount) -> {
			if (inVillagePlayers.contains(player.getUuid())) {
				itemsToKeep.clear();
				for (int i = 0; i < player.getInventory().size(); i++) {
					ItemStack stack = player.getInventory().getStack(i);
					if (isUnsafeItem(stack)) {
						// 创建完全干净的物品堆
						ItemStack cleanStack = new ItemStack(stack.getItem(), stack.getCount());

						// 复制除unsafe标签外的NBT数据
						if (stack.hasNbt()) {
							NbtCompound tag = stack.getNbt().copy();
							tag.remove(UNSAFE_TAG);

							// 只移除包含"不安全的"的自定义名称
							if (tag.contains("CustomName")) {
								String nameJson = tag.getString("CustomName");
								if (nameJson.contains("不安全的")) {
									tag.remove("CustomName");
								}
							}

							if (!tag.isEmpty()) {
								cleanStack.setNbt(tag);
							}
						}
						itemsToKeep.add(cleanStack);
					}
				}

				if (!itemsToKeep.isEmpty()) {
					playersWithItemsToKeep.add(player.getUuid());
					player.sendMessage(
							Text.literal("§6你的物品将在重生后恢复正常状态"),
							false
					);
				}
			}
			return true;
		});

		// 重生后处理
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (playersWithItemsToKeep.remove(oldPlayer.getUuid())) {
				for (ItemStack stack : itemsToKeep) {
					if (!stack.isEmpty()) {
						// 给予完全正常的物品
						newPlayer.getInventory().offerOrDrop(stack.copy());
					}
				}
				newPlayer.sendMessage(
						Text.literal("§a你的物品已恢复正常状态"),
						false
				);
				itemsToKeep.clear();
			}
		});
	}

	// 保留原有的木棍奖励功能
	private void checkVillageAndGiveStick(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		boolean inVillage = isInVillage(player);

		if (inVillage && !rewardedPlayers.contains(playerId)) {
			// 创建木棍并正确标记为不安全
			ItemStack unsafeStick = createUnsafeStick();
			player.giveItemStack(unsafeStick);

			player.sendMessage(
					Text.literal("§a你获得了不安全的木棍！"),
					false
			);
			rewardedPlayers.add(playerId);
		} else if (!inVillage) {
			rewardedPlayers.remove(playerId);
		}
	}
	// 创建不安全木棍的方法
	private ItemStack createUnsafeStick() {
		ItemStack stick = new ItemStack(Items.STICK, 1); // 1根木棍

		// 创建NBT标签
		NbtCompound tag = new NbtCompound();
		tag.putBoolean(UNSAFE_TAG, true);

		// 设置自定义名称
		stick.setCustomName(Text.literal("§c不安全的木棍"));
		stick.setNbt(tag);

		return stick;
	}

	// 检查玩家村庄状态变化
	private void checkVillageStatus(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		boolean currentlyInVillage = isInVillage(player);
		boolean previouslyInVillage = inVillagePlayers.contains(playerId);

		if (currentlyInVillage && !previouslyInVillage) {
			// 玩家刚进入村庄
			player.sendMessage(
					Text.literal("§a你进入了村庄区域，获得的物品将被标记为不安全！"),
					false
			);
			inVillagePlayers.add(playerId);
		} else if (!currentlyInVillage && previouslyInVillage) {
			// 玩家刚离开村庄
			player.sendMessage(
					Text.literal("§c你离开了村庄区域，小心携带的不安全物品！"),
					false
			);
			inVillagePlayers.remove(playerId);
		}
	}

	// 检查并处理不安全物品
	private void checkUnsafeItems(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		boolean inVillage = inVillagePlayers.contains(playerId);
		boolean hasUnsafeItems = false;

		// 检查玩家物品栏中的不安全物品
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (isUnsafeItem(stack)) {
				hasUnsafeItems = true;

				// 如果不在村庄内，销毁不安全物品
				if (!inVillage) {
					player.sendMessage(
							Text.literal("§4你的" + stack.getName().getString() + "在离开村庄后化为灰烬！"),
							false
					);
					player.getInventory().removeStack(i);
					i--; // 因为移除了一个物品，索引需要调整
				}
			}
		}

		// 更新玩家状态
		if (hasUnsafeItems) {
			hasUnsafeItemsPlayers.add(playerId);
		} else {
			hasUnsafeItemsPlayers.remove(playerId);
		}
	}

	// 标记物品为不安全
	public static ItemStack markItemAsUnsafe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return stack;

		ItemStack newStack = stack.copy();
		NbtCompound tag = newStack.getOrCreateNbt();
		tag.putBoolean(UNSAFE_TAG, true);

		// 正确设置自定义名称的语法
		tag.putString("CustomName", Text.Serializer.toJson(
				Text.literal("§c不安全的 ").append(stack.getName())
		));

		return newStack;
	}

	// 检查物品是否不安全
	public static boolean isUnsafeItem(ItemStack stack) {
		return stack != null &&
				!stack.isEmpty() &&
				stack.hasNbt() &&
				stack.getNbt().getBoolean(UNSAFE_TAG);
	}
	// 检查玩家是否在村庄内
	private boolean isInVillage(ServerPlayerEntity player) {
		if (!(player.getWorld() instanceof ServerWorld world)) {
			return false;
		}

		var structureRegistry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
		Structure villageStructure = structureRegistry.get(VILLAGE_ID);

		if (villageStructure == null) {
			LOGGER.warn("Could not find village structure in registry!");
			return false;
		}

		StructureStart structure = world.getStructureAccessor()
				.getStructureAt(player.getBlockPos(), villageStructure);

		return structure != null && structure.hasChildren();
	}
}