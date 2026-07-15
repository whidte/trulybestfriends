package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-side: teleport a released (non-recalled) pet to the player's current position. */
public class TeleportPetToPlayerPacket implements CustomPacketPayload {
    public static final Type<TeleportPetToPlayerPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "teleport_pet_to_player"));
    public static final StreamCodec<FriendlyByteBuf, TeleportPetToPlayerPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), TeleportPetToPlayerPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    public static void handle(TeleportPetToPlayerPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            ServerLevel playerLevel = player.serverLevel();

            // Case 1: pet is alive in player's current dimension — teleport it directly
            Entity entity = playerLevel.getEntity(packet.petUuid);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;
                teleportEntityToPlayer(living, player, playerLevel);
                return;
            }

            // Read NBT for cross-dimension lookup
            java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                PetWarningPacket.send(player, 1, packet.petUuid); // lost / no data
                return;
            }

            CompoundTag nbt;
            try {
                nbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException e) {
                PetWarningPacket.send(player, 1, packet.petUuid);
                return;
            }

            // Filter: recalled or dead pets should not be summoned
            if (nbt.getBoolean("Recalled")) {
                PetWarningPacket.send(player, 0, packet.petUuid); // recalled
                return;
            }
            if (nbt.contains("Health", 5) && nbt.getFloat("Health") <= 0) {
                PetWarningPacket.send(player, 1, packet.petUuid); // dead
                return;
            }

            // Resolve pet's dimension from NBT (no need to scan all dimensions)
            ServerLevel petLevel = resolvePetLevel(player.server, nbt);
            if (petLevel == null) {
                PetWarningPacket.send(player, 1, packet.petUuid); // unknown dimension
                return;
            }

            // Case 2: pet is alive in another dimension (or same dim loaded chunk) —
            // find and discard original, then summon from disk at player's location
            Entity petEntity = petLevel.getEntity(packet.petUuid);
            if (petEntity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;
                if (!RecallPetPacket.savePetToDisk(player.getUUID(), living, petLevel, false)) {
                    PetWarningPacket.send(player, 1, packet.petUuid);
                    return;
                }
                try {
                    CompoundTag freshSnapshot = NbtFileIO.readCompressed(nbtFile);
                    if (summonFromDisk(freshSnapshot, packet.petUuid, player, playerLevel)) {
                        living.discard();
                    } else {
                        PetWarningPacket.send(player, 1, packet.petUuid);
                    }
                } catch (IOException e) {
                    trulybestfriends.LOGGER.error("Failed to reload pet snapshot {}: {}",
                            packet.petUuid, e.getMessage());
                }
                return;
            }

            // Case 3: pet is in an unloaded chunk — force-load and defer to next tick.
            // setChunkForced cannot return entities in the same tick (async entity
            // loading), so we queue the request and process it in onServerTick.
            // ChunkX/ChunkZ are derived from the entity's Pos (vanilla NBT stores
            // only world coordinates, not chunk coordinates).
            int cx = Integer.MIN_VALUE;
            int cz = Integer.MIN_VALUE;
            if (nbt.contains("ChunkX", 3) && nbt.contains("ChunkZ", 3)) {
                cx = nbt.getInt("ChunkX");
                cz = nbt.getInt("ChunkZ");
            } else if (nbt.contains("Pos", 9)) {
                var posList = nbt.getList("Pos", 6);
                if (posList.size() >= 3) {
                    cx = net.minecraft.util.Mth.floor(posList.getDouble(0)) >> 4;
                    cz = net.minecraft.util.Mth.floor(posList.getDouble(2)) >> 4;
                }
            }
            if (cx != Integer.MIN_VALUE && cz != Integer.MIN_VALUE) {
                // If the chunk is already loaded but the entity wasn't found,
                // it truly doesn't exist — don't waste time in the pending queue.
                if (petLevel.hasChunk(cx, cz)) {
                    PetWarningPacket.send(player, 1, packet.petUuid);
                    return;
                }

                // Per-player limit: prevent unbounded queue growth from rapid re-summons
                long playerPending = pendingSummons.stream()
                        .filter(p -> p.playerUuid.equals(player.getUUID()))
                        .count();
                if (playerPending >= maxPendingPerPlayer()) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                boolean alreadyPending = pendingSummons.stream().anyMatch(pending ->
                        pending.playerUuid.equals(player.getUUID())
                                && pending.petUuid.equals(packet.petUuid));
                if (alreadyPending) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                trulybestfriends.retainForcedChunk(petLevel, cx, cz);
                pendingSummons.add(new PendingSummon(
                        packet.petUuid, player.getUUID(), cx, cz, petLevel));
            } else {
                PetWarningPacket.send(player, 1, packet.petUuid);
            }
        });

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
        int radius = Math.max(1, (int) Math.ceil(entity.getBbWidth()));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, radius, 6, 16);
        if (safePosition != null) {
            entity.teleportTo(safePosition.x, safePosition.y, safePosition.z);
            entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
            return;
        }
        entity.teleportTo(player.getX(), player.getY(), player.getZ());
        entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
    }

    /**
     * Find a safe, pathable BlockPos near the player for the pet to walk to.
     * Searches in expanding rings around the player's position.
     */
    private static BlockPos findSafeBlockNearPlayer(ServerLevel level, ServerPlayer player, Entity entity) {
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, 1, 4, 12);
        return safePosition != null ? BlockPos.containing(safePosition) : null;
    }

    static boolean summonFromDisk(CompoundTag nbt, UUID petUuid, ServerPlayer player, ServerLevel level) {
        Entity entity = PetEntitySnapshot.restore(nbt, petUuid, level);
        if (entity == null) return false;
        restoreChestInventory(entity, nbt);

        float bbWidth = entity instanceof LivingEntity living ? living.getBbWidth() : 0.6f;
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, radius, 6, 16);
        if (safePosition != null) {
            entity.setPos(safePosition.x, safePosition.y, safePosition.z);
            if (!level.tryAddFreshEntityWithPassengers(entity)) return false;
            finishRestoredEntity(entity, nbt, player, level);
            if (entity instanceof LivingEntity living) {
                living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
            }
            return true;
        }
        entity.setPos(player.getX(), player.getY(), player.getZ());
        if (level.tryAddFreshEntityWithPassengers(entity)) {
            finishRestoredEntity(entity, nbt, player, level);
            return true;
        }
        return false;
    }

    private static void finishRestoredEntity(Entity entity, CompoundTag nbt,
                                               ServerPlayer player, ServerLevel level) {
        // Capabilities may only be exposed after the entity joins the world.
        restoreChestInventory(entity, nbt);
        if (!trulybestfriends.persistRestoredPet(player.getUUID(), entity, level)) {
            trulybestfriends.LOGGER.error("Failed to persist restored container snapshot for {}", entity.getUUID());
        }
    }

    /** Restores a horse/container inventory without replacing its live container. */
    public static void restoreChestInventory(Entity entity, CompoundTag nbt) {
        boolean hasContainerBackup = nbt.contains("TBF_ChestSize", 3)
                || nbt.contains("TBF_ChestItems", 9)
                || nbt.getBoolean("ChestedHorse");
        boolean hasItemHandlerBackup = nbt.contains("TBF_ItemHandlerSize", 3)
                || nbt.contains("TBF_ItemHandlerItems", 9);
        if (!hasContainerBackup && !hasItemHandlerBackup)
            return;

        try {
            if (entity instanceof AbstractChestedHorse chestedHorse
                    && !chestedHorse.hasChest()
                    && (nbt.getBoolean("ChestedHorse") || nbt.getInt("TBF_ChestSize") > 1)) {
                chestedHorse.getSlot(AbstractHorse.CHEST_SLOT_OFFSET)
                        .set(new ItemStack(Items.CHEST));
            }

            Container inventory = getEntityInventory(entity);
            if (inventory == null) {
                restoreItemHandler(entity, nbt);
                return;
            }

            // TBF backups use absolute live-container slot indices.
            if (nbt.contains("TBF_ChestItems", 9)) {
                restoreContainerItems(inventory, nbt.getList("TBF_ChestItems", 10),
                        entity.registryAccess(), 0);
            }

            // Vanilla 1.21 horse NBT uses slots relative to the chest area and
            // remains authoritative when a stale legacy backup is also present.
            if (nbt.contains("Items", 9)) {
                int slotOffset = entity instanceof AbstractChestedHorse ? 1 : 0;
                restoreContainerItems(inventory, nbt.getList("Items", 10),
                        entity.registryAccess(), slotOffset);
            }
            inventory.setChanged();
        } catch (Exception e) {
            trulybestfriends.LOGGER.error(
                    "restoreChestInventory: failed for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /** Backs up the live inventory using stable NeoForge/public container APIs. */
    public static void backupChestInventory(Entity entity, CompoundTag nbt) {
        try {
            Container inventory = getEntityInventory(entity);
            if (inventory == null) {
                backupItemHandler(entity, nbt);
                return;
            }

            int size = inventory.getContainerSize();
            nbt.putInt("TBF_ChestSize", size);
            nbt.put("TBF_ChestItems", saveContainerItems(inventory, entity.registryAccess()));
        } catch (Exception e) {
            trulybestfriends.LOGGER.error(
                    "backupChestInventory: failed for {}: {}",
                    entity.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    static ListTag saveContainerItems(Container inventory, HolderLookup.Provider registries) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;

            CompoundTag tag = new CompoundTag();
            tag.putByte("Slot", (byte) slot);
            items.add(stack.save(registries, tag));
        }
        return items;
    }

    static void restoreContainerItems(Container inventory, ListTag items,
                                      HolderLookup.Provider registries, int slotOffset) {
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = (itemTag.getByte("Slot") & 255) + slotOffset;
            if (slot >= inventory.getContainerSize()) continue;

            ItemStack stack = ItemStack.parseOptional(registries, itemTag);
            if (!stack.isEmpty()) inventory.setItem(slot, stack);
        }
    }

    private static Container getEntityInventory(Entity entity) {
        if (entity instanceof AbstractHorse horse) return horse.getInventory();
        if (entity instanceof Container container) return container;
        return null;
    }

    private static void backupItemHandler(Entity entity, CompoundTag nbt) {
        IItemHandler handler = entity.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler == null) return;

        ListTag items = new ListTag();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag tag = new CompoundTag();
            tag.putInt("Slot", slot);
            items.add(stack.save(entity.registryAccess(), tag));
        }
        nbt.putInt("TBF_ItemHandlerSize", handler.getSlots());
        nbt.put("TBF_ItemHandlerItems", items);
    }

    private static void restoreItemHandler(Entity entity, CompoundTag nbt) {
        IItemHandler handler = entity.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable modifiable)) return;

        int savedSize = nbt.getInt("TBF_ItemHandlerSize");
        int restorableSlots = Math.min(savedSize, handler.getSlots());
        for (int slot = 0; slot < restorableSlots; slot++) {
            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
        }

        ListTag items = nbt.getList("TBF_ItemHandlerItems", 10);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= restorableSlots) continue;
            ItemStack stack = ItemStack.parseOptional(entity.registryAccess(), itemTag);
            if (!stack.isEmpty()) modifiable.setStackInSlot(slot, stack);
        }
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
    private static final int MAX_PENDING_ATTEMPTS = 100; // ~5s at 20 TPS
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

        for (PendingSummon pending : pendingSummons) {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUuid);

            if (player == null) {
                // Player logged out — clean up forced chunk and cancel
                finishPendingSummon(pending);
                continue;
            }

            Entity entity = pending.petLevel.getEntity(pending.petUuid);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                failPendingSummon(pending, player);
                continue;
            }
            if (!trulybestfriends.isOwnedBy(living, player.getUUID())) {
                finishPendingSummon(pending);
                continue;
            }
            if (pending.petLevel == player.serverLevel()) {
                if (recreateAfterForcedLoad(living, player, pending.petLevel)) finishPendingSummon(pending);
                else failPendingSummon(pending, player);
                continue;
            }
            // Chunk loaded: discard original and summon at player.
            if (RecallPetPacket.savePetToDisk(player.getUUID(), living, pending.petLevel, false)
                    && completeSummon(player, pending.petUuid)) {
                living.discard();
                finishPendingSummon(pending);
            } else {
                failPendingSummon(pending, player);
            }
        }
    }

    private static void failPendingSummon(PendingSummon pending, ServerPlayer player) {
        if (++pending.attempts < MAX_PENDING_ATTEMPTS) return;
        finishPendingSummon(pending, player);
    }

    private static void finishPendingSummon(PendingSummon pending) {
        finishPendingSummon(pending, null);
    }

    private static void finishPendingSummon(PendingSummon pending, ServerPlayer warningRecipient) {
        trulybestfriends.releaseForcedChunk(pending.petLevel, pending.chunkX, pending.chunkZ);
        if (warningRecipient != null) PetWarningPacket.send(warningRecipient, 1, pending.petUuid);
        pendingSummons.remove(pending);
    }

    /**
     * Re-adds a pet loaded from a forced chunk so nearby clients receive a fresh
     * tracking/spawn sequence. A coordinate-only teleport can leave the entity
     * tracked from its old section until after that section is unloaded.
     */
    private static boolean recreateAfterForcedLoad(LivingEntity original, ServerPlayer player, ServerLevel level) {
        UUID petUuid = original.getUUID();
        if (!RecallPetPacket.savePetToDisk(player.getUUID(), original, level, false)) return false;

        File nbtFile = PetIOUtil.getOwnerDir(player).resolve(petUuid + ".nbt").toFile();
        CompoundTag snapshot;
        try {
            snapshot = NbtFileIO.readCompressed(nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to read forced-chunk snapshot for {}: {}", petUuid, e.getMessage());
            return false;
        }

        original.discard();
        if (summonFromDisk(snapshot, petUuid, player, level)) return true;

        trulybestfriends.LOGGER.error("Failed to re-add forced-chunk pet {}; restoring its original snapshot", petUuid);
        if (!restoreAtStoredPosition(snapshot, petUuid, player, level)) {
            trulybestfriends.LOGGER.error("Failed to restore pet {} after a failed forced-chunk teleport", petUuid);
        }
        return false;
    }

    private static boolean restoreAtStoredPosition(CompoundTag snapshot, UUID petUuid,
                                                    ServerPlayer player, ServerLevel level) {
        Entity restored = PetEntitySnapshot.restore(snapshot, petUuid, level);
        if (restored == null) return false;
        restoreChestInventory(restored, snapshot);
        if (!level.tryAddFreshEntityWithPassengers(restored)) return false;
        finishRestoredEntity(restored, snapshot, player, level);
        return true;
    }

    /** Read NBT and summon pet from disk at player's current location. */
    private static boolean completeSummon(ServerPlayer player, UUID petUuid) {
        ServerLevel playerLevel = player.serverLevel();
        java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(player);
        File nbtFile = ownerDir.resolve(petUuid + ".nbt").toFile();
        if (!nbtFile.exists()) return false;
        try {
            CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
            return summonFromDisk(nbt, petUuid, player, playerLevel);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to complete summon for {}: {}", petUuid, e.getMessage());
            return false;
        }
    }

    public static void clearPendingSummons() {
        pendingSummons.clear();
    }
}
