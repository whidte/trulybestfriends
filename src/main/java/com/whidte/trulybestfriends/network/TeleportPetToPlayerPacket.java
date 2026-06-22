package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Server-side: teleport a released (non-recalled) pet to the player's current position. */
public class TeleportPetToPlayerPacket {
    private final UUID petUuid;

    public TeleportPetToPlayerPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(TeleportPetToPlayerPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static TeleportPetToPlayerPacket decode(FriendlyByteBuf buf) {
        return new TeleportPetToPlayerPacket(buf.readUUID());
    }

    public static void handle(TeleportPetToPlayerPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel playerLevel = player.serverLevel();

            // Case 1: pet is alive in player's current dimension — teleport it directly
            Entity entity = playerLevel.getEntity(packet.petUuid);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                teleportEntityToPlayer(living, player, playerLevel);
                return;
            }

            // Read NBT for cross-dimension lookup
            java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(playerLevel, player.getUUID());
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                sendWarning(player, 1, packet.petUuid); // lost / no data
                return;
            }

            CompoundTag nbt;
            try {
                nbt = NbtIo.readCompressed(nbtFile);
            } catch (IOException e) {
                sendWarning(player, 1, packet.petUuid);
                return;
            }

            // Filter: recalled or dead pets should not be summoned
            if (nbt.getBoolean("Recalled")) {
                sendWarning(player, 0, packet.petUuid); // recalled
                return;
            }
            if (nbt.contains("Health", 5) && nbt.getFloat("Health") <= 0) {
                sendWarning(player, 1, packet.petUuid); // dead
                return;
            }

            // Resolve pet's dimension from NBT (no need to scan all dimensions)
            ServerLevel petLevel = resolvePetLevel(player.server, nbt);
            if (petLevel == null) {
                sendWarning(player, 1, packet.petUuid); // unknown dimension
                return;
            }

            // Case 2: pet is alive in another dimension (or same dim loaded chunk) —
            // find and discard original, then summon from disk at player's location
            Entity petEntity = petLevel.getEntity(packet.petUuid);
            if (petEntity instanceof LivingEntity living && living.isAlive()) {
                living.discard();
                summonFromDisk(nbt, packet.petUuid, player, playerLevel);
                return;
            }

            // Case 3: pet is in an unloaded chunk — force-load and defer to next tick.
            // setChunkForced cannot return entities in the same tick (async entity
            // loading), so we queue the request and process it in onServerTick.
            if (nbt.contains("ChunkX", 3) && nbt.contains("ChunkZ", 3)) {
                int cx = nbt.getInt("ChunkX");
                int cz = nbt.getInt("ChunkZ");
                // Per-player limit: prevent unbounded queue growth from rapid re-summons
                long playerPending = pendingSummons.stream()
                        .filter(p -> p.playerUuid.equals(player.getUUID()))
                        .count();
                if (playerPending >= maxPendingPerPlayer()) {
                    // Too many pending — summon directly without discarding original
                    summonFromDisk(nbt, packet.petUuid, player, playerLevel);
                    return;
                }
                petLevel.setChunkForced(cx, cz, true);
                pendingSummons.add(new PendingSummon(
                        packet.petUuid, player.getUUID(), cx, cz, petLevel));
            } else {
                // No chunk info — cannot locate original, summon directly (best effort)
                summonFromDisk(nbt, packet.petUuid, player, playerLevel);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void teleportEntityToPlayer(LivingEntity entity, ServerPlayer player, ServerLevel level) {
        // If the pet is a Mob, within 16 blocks, and can path to the player —
        // make it walk to a safe point near the player instead of teleporting.
        if (entity instanceof Mob mob && mob.isAlive()) {
            double distance = entity.position().distanceTo(player.position());
            if (distance <= 16.0) {
                BlockPos safePos = findSafeBlockNearPlayer(level, player, entity);
                if (safePos != null) {
                    PathNavigation nav = mob.getNavigation();
                    Path path = nav.createPath(safePos, 1);
                    if (path != null && path.canReach()) {
                        nav.moveTo(path, 1.2);
                        return;
                    }
                }
            }
        }

        // Fallback: teleport directly
        float bbWidth = entity.getBbWidth();
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        for (int r = radius; r <= 6; r++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = player.getX() + Math.cos(angle) * r;
                double z = player.getZ() + Math.sin(angle) * r;
                double y = PetIOUtil.findSafeY(level, x, player.getY(), z, entity);

                entity.setPos(x, y, z);
                AABB box = entity.getBoundingBox();
                if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                    entity.teleportTo(x, y, z);
                    entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    return;
                }
            }
        }
        entity.teleportTo(player.getX(), player.getY(), player.getZ());
        entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
    }

    /**
     * Find a safe, pathable BlockPos near the player for the pet to walk to.
     * Searches in expanding rings around the player's position.
     */
    private static BlockPos findSafeBlockNearPlayer(ServerLevel level, ServerPlayer player, Entity entity) {
        float hw = entity instanceof LivingEntity le ? le.getBbWidth() / 2f : 0.3f;
        float h = entity instanceof LivingEntity le ? le.getBbHeight() : 1.8f;
        Vec3 playerPos = player.position();

        for (int r = 1; r <= 4; r++) {
            for (int attempt = 0; attempt < 12; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = playerPos.x + Math.cos(angle) * r;
                double z = playerPos.z + Math.sin(angle) * r;
                double y = PetIOUtil.findSafeY(level, x, playerPos.y, z, entity);

                AABB box = new AABB(x - hw, y, z - hw, x + hw, y + h, z + hw);
                if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                    return BlockPos.containing(x, y, z);
                }
            }
        }
        return null;
    }

    static void summonFromDisk(CompoundTag nbt, UUID petUuid, ServerPlayer player, ServerLevel level) {
        String typeKey = nbt.getString("EntityType");
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
        if (type == null) return;

        Entity entity = type.create(level);
        if (entity == null) return;
        entity.load(nbt);
        entity.setUUID(petUuid);

        float bbWidth = entity instanceof LivingEntity le ? le.getBbWidth() : 0.6f;
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        for (int r = radius; r <= 6; r++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double dx = Math.cos(angle) * r;
                double dz = Math.sin(angle) * r;
                double x = player.getX() + dx;
                double z = player.getZ() + dz;
                double y = PetIOUtil.findSafeY(level, x, player.getY(), z, entity);

                entity.setPos(x, y, z);
                AABB box = entity.getBoundingBox();
                if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                    level.addFreshEntity(entity);
                    restoreChestInventory(entity, nbt);
                    if (entity instanceof LivingEntity le) {
                        le.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    }
                    return;
                }
            }
        }
        entity.setPos(player.getX(), player.getY(), player.getZ());
        level.addFreshEntity(entity);
        restoreChestInventory(entity, nbt);
    }

    /**
     * Post-spawn: restore entity inventory from our custom TBF_ChestItems NBT backup.
     * Works with any entity that has a Container field (vanilla chested horses,
     * modded creatures with inventories, etc.).
     */
    public static void restoreChestInventory(Entity entity, CompoundTag nbt) {
        // Only intervene if our custom backup data exists in the NBT
        if (!nbt.contains("TBF_ChestSize", 3)
                && !nbt.contains("TBF_ChestItems", 9)
                && !nbt.getBoolean("ChestedHorse"))
            return;

        try {
            // Locate the inventory Container field by traversing class hierarchy
            java.lang.reflect.Field invField = null;
            Class<?> clazz = entity.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (Container.class.isAssignableFrom(f.getType())) {
                        invField = f;
                        break;
                    }
                }
                if (invField != null) break;
                clazz = clazz.getSuperclass();
            }
            if (invField == null) return;
            invField.setAccessible(true);

            Container currentInv = (Container) invField.get(entity);

            // Determine target container size: trust TBF_ChestSize from save time,
            // or fall back to the current inventory size (vanilla's restore result).
            int size = nbt.contains("TBF_ChestSize", 3)
                    ? nbt.getInt("TBF_ChestSize")
                    : (currentInv != null ? currentInv.getContainerSize() : 2);

            SimpleContainer newInv = new SimpleContainer(size);

            // Copy saddle (slot 0) and armor (slot 1) from current inventory
            if (currentInv != null) {
                int copyLimit = Math.min(currentInv.getContainerSize(), 2);
                for (int i = 0; i < copyLimit; i++) {
                    ItemStack stack = currentInv.getItem(i);
                    if (!stack.isEmpty()) newInv.setItem(i, stack.copy());
                }
            }

            // Fallback: read SaddleItem / ArmorItem directly from NBT
            if (nbt.contains("SaddleItem", 10))
                newInv.setItem(0, ItemStack.of(nbt.getCompound("SaddleItem")));
            if (nbt.contains("ArmorItem", 10))
                newInv.setItem(1, ItemStack.of(nbt.getCompound("ArmorItem")));

            // Read items from TBF_ChestItems (our custom full-inventory backup)
            if (nbt.contains("TBF_ChestItems", 9)) {
                ListTag items = nbt.getList("TBF_ChestItems", 10);
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag itemTag = items.getCompound(i);
                    int slot = itemTag.getByte("Slot") & 255;
                    if (slot < size) newInv.setItem(slot, ItemStack.of(itemTag));
                }
            } else if (nbt.contains("Items", 9)) {
                // Fallback: vanilla Items list
                ListTag items = nbt.getList("Items", 10);
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag itemTag = items.getCompound(i);
                    int slot = itemTag.getByte("Slot") & 255;
                    if (slot < size) newInv.setItem(slot, ItemStack.of(itemTag));
                }
            }

            invField.set(entity, newInv);

            // Restore chested visual flag for AbstractChestedHorse subclasses
            if (entity instanceof AbstractChestedHorse ch) {
                ch.setChest(true);
            }
        } catch (Exception e) {
            trulybestfriends.LOGGER.error(
                    "restoreChestInventory: failed for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /**
     * Pre-save: backup the entity's Container items into TBF_ChestItems NBT.
     * This ensures chest inventory survives the recall/summon cycle even when
     * vanilla's saveWithoutId/load pair fails to restore it (known issue with
     * some horse subtypes in 1.20.1).
     */
    public static void backupChestInventory(Entity entity, CompoundTag nbt) {
        try {
            Class<?> clazz = entity.getClass();
            java.lang.reflect.Field invField = null;
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (Container.class.isAssignableFrom(f.getType())) {
                        invField = f;
                        break;
                    }
                }
                if (invField != null) break;
                clazz = clazz.getSuperclass();
            }
            if (invField == null) return;
            invField.setAccessible(true);
            Container inv = (Container) invField.get(entity);
            if (inv == null) return;

            int size = inv.getContainerSize();
            ListTag items = new ListTag();
            for (int i = 0; i < size; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    CompoundTag tag = new CompoundTag();
                    tag.putByte("Slot", (byte) i);
                    stack.save(tag);
                    items.add(tag);
                }
            }
            nbt.put("TBF_ChestSize", net.minecraft.nbt.IntTag.valueOf(size));
            nbt.put("TBF_ChestItems", items);
        } catch (Exception e) {
            trulybestfriends.LOGGER.error(
                    "backupChestInventory: failed for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /** Send a PetWarningPacket to the player. type 0 = recalled, type 1 = lost/dead. */
    private static void sendWarning(ServerPlayer player, int type, UUID petUuid) {
        trulybestfriends.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PetWarningPacket(type, petUuid));
    }

    /** Resolve the ServerLevel where the pet was last saved, using NBT Dimension field. */
    private static ServerLevel resolvePetLevel(MinecraftServer server, CompoundTag nbt) {
        if (!nbt.contains("Dimension", 8)) return null;
        ResourceLocation dimRl = ResourceLocation.tryParse(nbt.getString("Dimension"));
        if (dimRl == null) return null;
        for (ServerLevel sl : server.getAllLevels()) {
            if (sl.dimension().location().equals(dimRl)) {
                return sl;
            }
        }
        return null;
    }

    // ---- Pending summon queue for pets in unloaded chunks ----

    private static final List<PendingSummon> pendingSummons = new CopyOnWriteArrayList<>();
    private static final int MAX_PENDING_ATTEMPTS = 10; // ~0.5s at 20 TPS
    /** Max simultaneous pending summons per player. = Config.maxPendingSummons + 2 buffer. */
    private static int maxPendingPerPlayer() {
        return Config.maxPendingSummons + 2;
    }

    private static class PendingSummon {
        final UUID petUuid;
        final UUID playerUuid;
        final int chunkX;
        final int chunkZ;
        final ServerLevel petLevel;
        int attempts;

        PendingSummon(UUID petUuid, UUID playerUuid, int chunkX, int chunkZ, ServerLevel petLevel) {
            this.petUuid = petUuid;
            this.playerUuid = playerUuid;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.petLevel = petLevel;
        }
    }

    /**
     * Called from trulybestfriends.onServerTick each tick.
     * Processes pending summon requests for pets in unloaded chunks.
     * Once the chunk loads and the original entity is found, discards it and
     * summons the pet from disk at the player's location.
     */
    public static void tickPendingSummons(MinecraftServer server) {
        if (pendingSummons.isEmpty()) return;

        Iterator<PendingSummon> it = pendingSummons.iterator();
        while (it.hasNext()) {
            PendingSummon pending = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUuid);

            if (player == null) {
                // Player logged out — clean up forced chunk and cancel
                pending.petLevel.setChunkForced(pending.chunkX, pending.chunkZ, false);
                pendingSummons.remove(pending);
                continue;
            }

            Entity entity = pending.petLevel.getEntity(pending.petUuid);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                // Chunk loaded — discard original and summon at player
                living.discard();
                pending.petLevel.setChunkForced(pending.chunkX, pending.chunkZ, false);
                completeSummon(player, pending.petUuid);
                pendingSummons.remove(pending);
            } else {
                pending.attempts++;
                if (pending.attempts >= MAX_PENDING_ATTEMPTS) {
                    // Timeout — chunk failed to load in time. Summon directly
                    // without discarding (best effort; syncAllPets will reconcile).
                    pending.petLevel.setChunkForced(pending.chunkX, pending.chunkZ, false);
                    completeSummon(player, pending.petUuid);
                    pendingSummons.remove(pending);
                }
            }
        }
    }

    /** Read NBT and summon pet from disk at player's current location. */
    private static void completeSummon(ServerPlayer player, UUID petUuid) {
        ServerLevel playerLevel = player.serverLevel();
        java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(playerLevel, player.getUUID());
        File nbtFile = ownerDir.resolve(petUuid + ".nbt").toFile();
        if (!nbtFile.exists()) return;
        try {
            CompoundTag nbt = NbtIo.readCompressed(nbtFile);
            summonFromDisk(nbt, petUuid, player, playerLevel);
        } catch (IOException ignored) {}
    }
}
