package unsafeloots;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.SurvivesExplosionLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.structure.Structure;

import unsafeloots.block.ModBlocks;
import unsafeloots.item.ModItemGroups;
import unsafeloots.item.ModItems;
import unsafeloots.config.LoadStructureConfig;

import java.util.*;

public class UnsafeLoots implements ModInitializer {
	public static final String MOD_ID = "unsafe-loots";
	public static final String UNSAFE_TAG = "unsafe_loot";

	// 玩家状态跟踪集合
	private static final Set<UUID> PlayersInStructure = new HashSet<>();
	private static final Set<UUID> rewardedPlayers = new HashSet<>();

	// 掉落物保留
	private static final List<ItemStack> itemsToKeep = new ArrayList<>();

	// 配置常量
	private static final int CHECK_INTERVAL = 4; // 检测间隔(20 ticks = 1秒)

	// 结构检测名单
	private static LoadStructureConfig structureConfig;
	private static final Set<Identifier> whitelistStructures = new HashSet<>();
	private static final Set<Identifier> blacklistStructures = new HashSet<>();

	@Override
	public void onInitialize() {
		ModItemGroups.registerItemGroups();
		ModItems.registerModItems();
		ModBlocks.registerModBlocks();

		structureConfig = LoadStructureConfig.load();
		loadStructureLists();

		// 注册服务器每tick事件
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % CHECK_INTERVAL != 0) return;

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				checkStructureStatus(player);
				checkUnsafeItems(player);
				checkVillageAndGiveStick(player);
			}
		});

		// 死亡时处理
		ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageAmount) -> {
			if (PlayersInStructure.contains(player.getUuid())) {
				itemsToKeep.clear();
				for (int i = 0; i < player.getInventory().size(); i++) {
					ItemStack stack = player.getInventory().getStack(i);
					if (isUnsafeItem(stack)) {
						itemsToKeep.add(purifyItem(stack));
					}
				}
			}
			return true;
		});

		// 重生后处理
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
				for (ItemStack stack : itemsToKeep) {
					if (!stack.isEmpty()) {
						newPlayer.getInventory().offerOrDrop(stack);
					}
				}
				itemsToKeep.clear();
		});

		// 监听战利品表
		LootTableEvents.MODIFY.register((resourceManager, lootManager, id, tableBuilder, source) -> {
			// If the loot table is for the cobblestone block, and it is not overridden by a user:
			if (Blocks.COBBLESTONE.getLootTableId().equals(id) && source.isBuiltin()) {
				// Create a new loot pool that will hold the diamonds.
				LootPool.Builder pool = LootPool.builder()
						// Add diamonds...
						.with(ItemEntry.builder(Items.DIAMOND))
						// ...only if the block would survive a potential explosion.
						.conditionally(SurvivesExplosionLootCondition.builder());

				// Add the loot pool to the loot table
				tableBuilder.pool(pool);
			}
		});
	}

	// 加载结构黑白名单
	private void loadStructureLists() {
		whitelistStructures.clear();
		blacklistStructures.clear();

		structureConfig.whitelist.forEach(id -> whitelistStructures.add(new Identifier(id)));
		structureConfig.blacklist.forEach(id -> blacklistStructures.add(new Identifier(id)));
	}

	// 木棍奖励功能
	private void checkVillageAndGiveStick(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		boolean inVillage = isInStructure(player);

		if (inVillage && !rewardedPlayers.contains(playerId)) {
			// 创建木棍并正确标记为不安全
			ItemStack stick2 = UnsafeLoots.markItemAsUnsafe(new ItemStack(Items.STICK, 1));
			player.giveItemStack(stick2);
			rewardedPlayers.add(playerId);
		} else if (!inVillage) {
			rewardedPlayers.remove(playerId);
		}
	}

	// 检查玩家出入结构变化
	private void checkStructureStatus(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		boolean isInStructure = isInStructure(player);
		boolean alreadyInStructure = PlayersInStructure.contains(playerId);

		if (isInStructure && !alreadyInStructure) {
			// 玩家进入
			player.sendMessage(
					Text.literal("§a进入结构，获得的物品将被标记为不安全！"),
					false
			);
			PlayersInStructure.add(playerId);
		} else if (!isInStructure && alreadyInStructure) {
			// 玩家离开
			player.sendMessage(
					Text.literal("§c离开结构"),
					false
			);
			PlayersInStructure.remove(playerId);
		}

		if (isInStructure && alreadyInStructure) {

		}
	}

	// 检查并处理不安全物品
	private void checkUnsafeItems(ServerPlayerEntity player) {
		boolean inVillage = PlayersInStructure.contains(player.getUuid());
		boolean hasUnsafeItems = false;

		// 检查玩家物品栏中的不安全物品
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (isUnsafeItem(stack)) {
				hasUnsafeItems = true;

				// 如果不在村庄内，销毁不安全物品
				if (!inVillage) {
					player.sendMessage(
							Text.literal("§4你的" + stack.getName().getString() + "化为灰烬！"),
							false
					);
					player.getInventory().removeStack(i);
					i--; // 因为移除了一个物品，索引需要调整
				}
			}
		}
	}

	// 标记物品为不安全
	public static ItemStack markItemAsUnsafe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return stack;

		NbtCompound tag = stack.getOrCreateNbt();

		// 1. 添加不安全标记
		tag.putBoolean(UNSAFE_TAG, true);

		// 2. 设置自定义名称
		NbtCompound displayTag = new NbtCompound();
		displayTag.putString("Name", Text.Serializer.toJson(
				Text.literal("§c不安全的 ").append(stack.getName())
		));
		tag.put("display", displayTag);

		// 3. NBT设置
		stack.setNbt(tag);

		return stack;
	}
	// 去除不安全标记
	public static ItemStack purifyItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return stack;

		// 1. 移除标签
		if (stack.hasNbt()) {
			NbtCompound tag = stack.getNbt();
			tag.remove(UNSAFE_TAG);

			// 2. 移除自定义名称
			if (tag.contains("display", NbtElement.COMPOUND_TYPE)) {
				NbtCompound display = tag.getCompound("display");
				if (display.contains("Name", NbtElement.STRING_TYPE)) {
					String nameJson = display.getString("Name");
					if (nameJson.contains("不安全的")) {
						display.remove("Name");
						// 如果display标签为空，可以移除整个display
						if (display.isEmpty()) {
							tag.remove("display");
						}
					}
				}
			}

			// 如果NBT变空，彻底移除
			if (tag.isEmpty()) {
				stack.setNbt(null);
			}
		}

		return stack;
	}
	// 检查物品是否不安全
	public static boolean isUnsafeItem(ItemStack stack) {
		return stack != null &&
				!stack.isEmpty() &&
				stack.hasNbt() &&
				stack.getNbt().getBoolean(UNSAFE_TAG);
	}
	// 检查玩家是否在结构内
	private boolean isInStructure(ServerPlayerEntity player) {
		if (!(player.getWorld() instanceof ServerWorld world)) {
			return false;
		}

		var structureRegistry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);

		// 检查所有已注册结构
		for (Structure structureAt : structureRegistry) {
			Identifier structureId = structureRegistry.getId(structureAt);

			// 黑白名单检查
			if (!whitelistStructures.isEmpty() && !whitelistStructures.contains(structureId)) {
				continue;
			}
			if (blacklistStructures.contains(structureId)) {
				continue;
			}

			// 结构位置检查
			StructureStart structure = world.getStructureAccessor()
					.getStructureAt(player.getBlockPos(), structureAt);

			if (structure != null && structure.hasChildren()) {
				return true;
			}
		}
        return false;
    }
}