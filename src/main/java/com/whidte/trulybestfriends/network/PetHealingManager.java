package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.PetIndexState;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative healing state that remains active while pets are unloaded or recalled. */
public final class PetHealingManager {
    public static final String CLIENT_DATA_TAG = "TBF_Healing";
    private static final int CLIENT_DATA_SIZE = 11;
    private static final int NORMAL_REMAINING_INDEX = 0;
    private static final int ADVANCED_REMAINING_INDEX = 1;
    private static final int PENDING_INDEX = 2;
    private static final int NORMAL_HUNGER_COST_INDEX = 3;
    private static final int ADVANCED_HUNGER_COST_INDEX = 4;
    private static final int NORMAL_PULSE_INTERVAL_INDEX = 5;
    private static final int ADVANCED_PULSE_INTERVAL_INDEX = 6;
    private static final int DURATION_PER_CLICK_INDEX = 7;
    private static final int MAX_DURATION_INDEX = 8;
    private static final int FLAT_AMOUNT_INDEX = 9;
    private static final int MAX_HEALTH_FRACTION_INDEX = 10;

    static final String HEALING_TAG = "Healing";
    private static final String INDEX_FILE_NAME = "pets_index.nbt";
    private static final Map<UUID, HealingEntry> ENTRIES = new HashMap<>();
    private static final Set<UUID> PENDING_APPLY_ATTEMPTED = new HashSet<>();
    private static File indexFile;

    private PetHealingManager() {}

    public enum ActivationResult {
        SUCCESS, NOT_FOUND, NOT_OWNED, DEAD, CORRUPTED, INSUFFICIENT_FOOD, MAX_DURATION
    }

    public static void load(MinecraftServer server) {
        ENTRIES.clear();
        PENDING_APPLY_ATTEMPTED.clear();
        File modDir = PetIOUtil.getModDir(server.overworld()).toFile();
        indexFile = new File(modDir, INDEX_FILE_NAME);
        if (indexFile.exists()) {
            try {
                CompoundTag root = NbtFileIO.readCompressed(indexFile);
                loadEntries(root);
            } catch (IOException | RuntimeException e) {
                trulybestfriends.LOGGER.error("Failed to load pet healing state from pet index", e);
            }
        }
    }

    public static void shutdown() {
        save();
        ENTRIES.clear();
        PENDING_APPLY_ATTEMPTED.clear();
        indexFile = null;
    }

    public static ActivationResult activate(ServerPlayer player, UUID petUuid, boolean advanced) {
        if (!trulybestfriends.isTrackedPet(petUuid)) return ActivationResult.NOT_FOUND;

        File petFile = PetIOUtil.getOwnerDir(player).resolve(petUuid + ".nbt").toFile();
        if (!petFile.exists()) return ActivationResult.NOT_FOUND;

        CompoundTag stored;
        try {
            trulybestfriends.flushPendingPetSaves(player.getUUID());
            stored = NbtFileIO.readCompressed(petFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to read pet {} before healing", petUuid, e);
            return ActivationResult.NOT_FOUND;
        }
        if (!player.getUUID().toString().equals(stored.getString("OwnerUUID"))) {
            clear(petUuid);
            return ActivationResult.NOT_OWNED;
        }
        if (PetDeathState.isDeadSnapshot(stored)) {
            clear(petUuid);
            return ActivationResult.DEAD;
        }
        if (!stored.contains("Pos") || !stored.contains("Dimension")) return ActivationResult.CORRUPTED;

        LivingEntity living = findLoadedPet(player.server, petUuid);
        if (living != null && !trulybestfriends.isOwnedBy(living, player.getUUID())) {
            clear(petUuid);
            return ActivationResult.NOT_OWNED;
        }
        if (living != null && !living.isAlive()) {
            clear(petUuid);
            return ActivationResult.DEAD;
        }
        CompoundTag shoulder = PetIOUtil.getShoulderEntity(player, petUuid);
        if (shoulder != null && shoulder.contains("Health") && shoulder.getFloat("Health") <= 0.0F) {
            clear(petUuid);
            return ActivationResult.DEAD;
        }

        long now = player.server.overworld().getGameTime();
        HealingEntry entry = ENTRIES.get(petUuid);
        if (entry != null && !entry.ownerUuid.equals(player.getUUID())) {
            ENTRIES.remove(petUuid);
            entry = null;
        }

        HealingTimer timer = entry == null ? null : entry.timer(advanced);
        int remaining = timer == null ? 0 : timer.remaining(now);
        if (!canExtend(remaining, Config.healDurationPerClickTicks, Config.healMaxDurationTicks)) {
            return ActivationResult.MAX_DURATION;
        }

        int hungerCost = advanced ? Config.advancedHealHungerCost : Config.healHungerCost;
        if (!player.getAbilities().instabuild
                && player.getFoodData().getFoodLevel() < hungerCost) {
            return ActivationResult.INSUFFICIENT_FOOD;
        }

        CompoundTag baseline = living != null ? null : (shoulder != null ? shoulder : stored);
        float health = living != null ? living.getHealth() : readHealth(baseline);
        float maxHealth = living != null
                ? (float) living.getAttributeValue(Attributes.MAX_HEALTH)
                : readMaxHealth(baseline);
        maxHealth = Math.max(maxHealth, health);

        if (entry == null) {
            entry = new HealingEntry(player.getUUID(), petUuid,
                    new HealingTimer(), new HealingTimer(),
                    0.0F, health, maxHealth);
            ENTRIES.put(petUuid, entry);
        } else {
            entry.projectedHealth = Math.min(maxHealth, Math.max(entry.projectedHealth, health));
            entry.maxHealthSnapshot = maxHealth;
        }

        int pulseInterval = advanced
                ? Config.advancedHealPulseIntervalTicks
                : Config.healPulseIntervalTicks;
        entry.timer(advanced).activate(
                now, Config.healDurationPerClickTicks, pulseInterval);

        if (!player.getAbilities().instabuild && hungerCost > 0) {
            int food = Math.max(0, player.getFoodData().getFoodLevel() - hungerCost);
            player.getFoodData().setFoodLevel(food);
            player.getFoodData().setSaturation(Math.min(player.getFoodData().getSaturationLevel(), food));
        }
        save();
        return ActivationResult.SUCCESS;
    }

    public static void tick(MinecraftServer server) {
        if (ENTRIES.isEmpty()) return;
        long now = server.overworld().getGameTime();
        boolean persistenceRequired = false;
        for (HealingEntry entry : new ArrayList<>(ENTRIES.values())) {
            Entity any = PetIOUtil.findEntity(server, entry.petUuid);
            if (any != null && (!(any instanceof LivingEntity living)
                    || !living.isAlive()
                    || !trulybestfriends.isOwnedBy(living, entry.ownerUuid))) {
                ENTRIES.remove(entry.petUuid);
                PENDING_APPLY_ATTEMPTED.remove(entry.petUuid);
                persistenceRequired = true;
                continue;
            }

            LivingEntity living = any instanceof LivingEntity value ? value : null;
            if (living != null && entry.pendingHeal > 0.0F
                    && PENDING_APPLY_ATTEMPTED.add(entry.petUuid)) {
                // Normally handled by onEntityLoaded; this covers entities discovered first by tick().
                persistenceRequired |= applyPendingDurably(entry, living);
            }

            processTimer(entry, entry.advancedTimer,
                    Config.advancedHealPulseIntervalTicks, now, living, true);
            processTimer(entry, entry.normalTimer,
                    Config.healPulseIntervalTicks, now, living, false);

            if (entry.normalTimer.expired(now)
                    && entry.advancedTimer.expired(now)
                    && !shouldRetainExpired(entry.pendingHeal)) {
                ENTRIES.remove(entry.petUuid);
                PENDING_APPLY_ATTEMPTED.remove(entry.petUuid);
                persistenceRequired = true;
            }
        }
        if (persistenceRequired) save();
    }

    private static void processTimer(HealingEntry entry, HealingTimer timer, int interval,
                                     long now, LivingEntity living, boolean advanced) {
        while (timer.nextPulseTick <= now && timer.nextPulseTick <= timer.endTick) {
            long pulseTick = timer.nextPulseTick;
            timer.nextPulseTick += interval;

            if (!advanced && entry.advancedTimer.activeAt(pulseTick)) continue;
            applyPulse(entry, living);
        }
    }

    private static void applyPulse(HealingEntry entry, LivingEntity living) {
        if (living != null) {
            float maxHealth = (float) living.getAttributeValue(Attributes.MAX_HEALTH);
            living.heal(pulseAmount(maxHealth, Config.healFlatAmount, Config.healMaxHealthFraction));
            entry.projectedHealth = living.getHealth();
            entry.maxHealthSnapshot = maxHealth;
            return;
        }

        float amount = pulseAmount(entry.maxHealthSnapshot,
                Config.healFlatAmount, Config.healMaxHealthFraction);
        float increase = cappedIncrease(entry.projectedHealth, entry.maxHealthSnapshot, amount);
        entry.pendingHeal += increase;
        entry.projectedHealth += increase;
    }

    /**
     * Applies pending healing and durably stores the healed snapshot before clearing it from the index.
     *
     * @return whether this call already persisted the current live pet snapshot
     */
    public static boolean onEntityLoaded(LivingEntity living, UUID ownerUuid) {
        HealingEntry entry = ENTRIES.get(living.getUUID());
        if (entry == null) return false;
        if (!entry.ownerUuid.equals(ownerUuid) || living.getHealth() <= 0.0F) {
            clear(living.getUUID());
            return false;
        }
        PENDING_APPLY_ATTEMPTED.remove(entry.petUuid);
        boolean snapshotPersisted = false;
        if (entry.pendingHeal > 0.0F) {
            snapshotPersisted = applyPendingDurably(entry, living);
            if (!snapshotPersisted) PENDING_APPLY_ATTEMPTED.add(entry.petUuid);
        }
        entry.projectedHealth = living.getHealth();
        entry.maxHealthSnapshot = (float) living.getAttributeValue(Attributes.MAX_HEALTH);
        long now = living.level().getServer().overworld().getGameTime();
        if (entry.normalTimer.expired(now)
                && entry.advancedTimer.expired(now)
                && !shouldRetainExpired(entry.pendingHeal)) {
            ENTRIES.remove(entry.petUuid);
            PENDING_APPLY_ATTEMPTED.remove(entry.petUuid);
        }
        save();
        return snapshotPersisted;
    }

    public static void onEntityUnloaded(LivingEntity living, UUID ownerUuid) {
        HealingEntry entry = ENTRIES.get(living.getUUID());
        if (entry == null) return;
        // Unload and dimension transfer mark a healthy entity as removed before this event.
        if (!entry.ownerUuid.equals(ownerUuid) || living.getHealth() <= 0.0F) {
            clear(living.getUUID());
            return;
        }
        entry.projectedHealth = living.getHealth();
        entry.maxHealthSnapshot = (float) living.getAttributeValue(Attributes.MAX_HEALTH);
        PENDING_APPLY_ATTEMPTED.remove(entry.petUuid);
        save();
    }

    public static void onPetSaved(UUID petUuid, UUID ownerUuid) {
        HealingEntry entry = ENTRIES.get(petUuid);
        if (entry != null && !entry.ownerUuid.equals(ownerUuid)) clear(petUuid);
    }

    public static void clear(UUID petUuid) {
        PENDING_APPLY_ATTEMPTED.remove(petUuid);
        if (ENTRIES.remove(petUuid) != null) save();
    }

    public static void clearAll(Iterable<UUID> petUuids) {
        boolean changed = false;
        for (UUID petUuid : petUuids) {
            PENDING_APPLY_ATTEMPTED.remove(petUuid);
            changed |= ENTRIES.remove(petUuid) != null;
        }
        if (changed) save();
    }

    /** Adds transient healing fields and theoretical unloaded health to a client-only NBT copy. */
    public static void decorateClientNbt(MinecraftServer server, UUID ownerUuid, UUID petUuid,
                                         CompoundTag storedNbt, CompoundTag clientNbt) {
        HealingEntry entry = ENTRIES.get(petUuid);
        boolean validEntry = entry != null && entry.ownerUuid.equals(ownerUuid)
                && !PetDeathState.isDeadSnapshot(storedNbt);
        long now = server.overworld().getGameTime();
        int normalRemaining = validEntry ? entry.normalTimer.remaining(now) : 0;
        int advancedRemaining = validEntry ? entry.advancedTimer.remaining(now) : 0;
        float pending = validEntry ? entry.pendingHeal : 0.0F;
        clientNbt.putIntArray(CLIENT_DATA_TAG, new int[] {
                normalRemaining,
                advancedRemaining,
                Float.floatToIntBits(pending),
                Config.healHungerCost,
                Config.advancedHealHungerCost,
                Config.healPulseIntervalTicks,
                Config.advancedHealPulseIntervalTicks,
                Config.healDurationPerClickTicks,
                Config.healMaxDurationTicks,
                Float.floatToIntBits((float) Config.healFlatAmount),
                Float.floatToIntBits((float) Config.healMaxHealthFraction)
        });
        if (validEntry && findLoadedPet(server, petUuid) == null) {
            clientNbt.putFloat("Health", Math.min(entry.projectedHealth, entry.maxHealthSnapshot));
            if (entry.maxHealthSnapshot > 0.0F) clientNbt.putFloat("MaxHealth", entry.maxHealthSnapshot);
        }
    }

    public static int getRemainingTicks(CompoundTag nbt) {
        return getRemainingTicks(nbt, false);
    }

    public static int getRemainingTicks(CompoundTag nbt, boolean advanced) {
        return clientData(nbt, advanced ? ADVANCED_REMAINING_INDEX : NORMAL_REMAINING_INDEX);
    }

    public static float getPendingHeal(CompoundTag nbt) {
        return Float.intBitsToFloat(clientData(nbt, PENDING_INDEX));
    }

    public static int getHungerCost(CompoundTag nbt, boolean advanced) {
        return clientData(nbt, advanced ? ADVANCED_HUNGER_COST_INDEX : NORMAL_HUNGER_COST_INDEX);
    }

    public static int getPulseInterval(CompoundTag nbt, boolean advanced) {
        return clientData(nbt, advanced ? ADVANCED_PULSE_INTERVAL_INDEX : NORMAL_PULSE_INTERVAL_INDEX);
    }

    public static int getDurationPerClick(CompoundTag nbt) {
        return clientData(nbt, DURATION_PER_CLICK_INDEX);
    }

    public static int getMaxDuration(CompoundTag nbt) {
        return clientData(nbt, MAX_DURATION_INDEX);
    }

    private static int clientData(CompoundTag nbt, int index) {
        if (nbt == null) return 0;
        int[] data = nbt.getIntArray(CLIENT_DATA_TAG);
        return data.length == CLIENT_DATA_SIZE ? data[index] : 0;
    }

    static int remainingTicks(long endTick, long now) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, endTick - now));
    }

    static boolean canExtend(int remaining, int durationPerClick, int maximumDuration) {
        return durationPerClick > 0 && remaining >= 0
                && (long) remaining + durationPerClick <= maximumDuration;
    }

    static int pulseCount(int duration, int interval) {
        return duration > 0 && interval > 0 ? duration / interval : 0;
    }

    static boolean hasDuePulse(long nextPulseTick, long endTick, long now) {
        return nextPulseTick <= now && nextPulseTick <= endTick;
    }

    static boolean isAdvancedActiveAt(long advancedStart, long advancedEnd, long pulseTick) {
        return advancedStart <= pulseTick && pulseTick <= advancedEnd;
    }

    static float pulseAmount(float maxHealth, double flatAmount, double maxHealthFraction) {
        return (float) Math.max(0.0, flatAmount + Math.max(0.0F, maxHealth) * maxHealthFraction);
    }

    static float cappedIncrease(float health, float maxHealth, float requested) {
        return Math.max(0.0F, Math.min(Math.max(0.0F, requested), Math.max(0.0F, maxHealth - health)));
    }

    static boolean shouldRetainExpired(float pendingHeal) {
        return pendingHeal > 0.0F;
    }

    private static boolean applyPendingDurably(HealingEntry entry, LivingEntity living) {
        if (entry.pendingHeal <= 0.0F) return false;
        float originalHealth = living.getHealth();
        living.heal(entry.pendingHeal);
        entry.projectedHealth = living.getHealth();
        entry.maxHealthSnapshot = (float) living.getAttributeValue(Attributes.MAX_HEALTH);
        if (!trulybestfriends.persistRestoredPet(
                entry.ownerUuid, living, (ServerLevel) living.level())) {
            living.setHealth(originalHealth);
            entry.projectedHealth = originalHealth;
            return false;
        }
        entry.pendingHeal = 0.0F;
        return true;
    }

    private static LivingEntity findLoadedPet(MinecraftServer server, UUID petUuid) {
        Entity entity = PetIOUtil.findEntity(server, petUuid);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static float readHealth(CompoundTag nbt) {
        return nbt != null && nbt.contains("Health") ? Math.max(0.0F, nbt.getFloat("Health")) : 0.0F;
    }

    private static float readMaxHealth(CompoundTag nbt) {
        if (nbt == null) return 20.0F;
        float maxHealth = nbt.contains("MaxHealth") ? nbt.getFloat("MaxHealth") : 0.0F;
        if (maxHealth <= 0.0F && nbt.contains("Attributes")) {
            for (Tag raw : nbt.getList("Attributes", Tag.TAG_COMPOUND)) {
                CompoundTag attribute = (CompoundTag) raw;
                String name = attribute.getString("Name");
                if ("minecraft:generic.max_health".equals(name)
                        || "generic.max_health".equals(name)) {
                    maxHealth = attribute.getFloat("Base");
                    break;
                }
            }
        }
        return maxHealth > 0.0F ? maxHealth : 20.0F;
    }

    private static void loadEntries(CompoundTag indexRoot) {
        PetIndexState.visit(indexRoot, (petUuid, state) -> {
            if (!state.contains(HEALING_TAG, Tag.TAG_COMPOUND)) return false;
            HealingEntry entry = HealingEntry.load(state.getCompound(HEALING_TAG), petUuid);
            if (entry != null) ENTRIES.put(petUuid, entry);
            return false;
        });
    }

    static Set<UUID> syncPersistedEntries(CompoundTag indexRoot,
                                          Map<UUID, CompoundTag> healingByPet) {
        Set<UUID> unmatched = new HashSet<>(healingByPet.keySet());
        // Remove the short-lived top-level format from development builds without reading it.
        indexRoot.remove("TBF_HealingEntries");
        PetIndexState.visit(indexRoot, (petUuid, state) -> {
            CompoundTag healing = healingByPet.get(petUuid);
            if (healing == null) {
                state.remove(HEALING_TAG);
            } else {
                state.put(HEALING_TAG, healing);
                unmatched.remove(petUuid);
            }
            return false;
        });
        return unmatched;
    }

    private static boolean save() {
        if (indexFile == null) return false;
        Map<UUID, CompoundTag> healingByPet = new HashMap<>();
        for (HealingEntry entry : ENTRIES.values()) {
            healingByPet.put(entry.petUuid, entry.save());
        }
        try {
            CompoundTag indexRoot = indexFile.exists()
                    ? NbtFileIO.readCompressed(indexFile)
                    : new CompoundTag();
            Set<UUID> unmatched = syncPersistedEntries(indexRoot, healingByPet);
            if (!unmatched.isEmpty()) {
                trulybestfriends.LOGGER.warn(
                        "Could not persist healing state for pets missing from pet index: {}", unmatched);
            }
            NbtFileIO.writeCompressed(indexRoot, indexFile);
            return true;
        } catch (IOException | RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to save pet healing state in pet index", e);
            return false;
        }
    }

    private static final class HealingTimer {
        private long startTick;
        private long endTick;
        private long nextPulseTick;

        private HealingTimer() {
            this(0L, 0L, 1L);
        }

        private HealingTimer(long startTick, long endTick, long nextPulseTick) {
            this.startTick = startTick;
            this.endTick = endTick;
            this.nextPulseTick = Math.max(1L, nextPulseTick);
        }

        private int remaining(long now) {
            return remainingTicks(endTick, now);
        }

        private void activate(long now, int duration, int interval) {
            if (remaining(now) == 0 && !hasDuePulse(nextPulseTick, endTick, now)) {
                startTick = now;
                endTick = now + duration;
                nextPulseTick = now + interval;
            } else {
                endTick += duration;
            }
        }

        private boolean activeAt(long tick) {
            return endTick > 0L && isAdvancedActiveAt(startTick, endTick, tick);
        }

        private boolean expired(long now) {
            return endTick <= now;
        }

        private void save(CompoundTag nbt, String prefix) {
            nbt.putLong(prefix + "StartTick", startTick);
            nbt.putLong(prefix + "EndTick", endTick);
            nbt.putLong(prefix + "NextPulseTick", nextPulseTick);
        }

        private static HealingTimer load(CompoundTag nbt, String prefix) {
            return new HealingTimer(
                    nbt.getLong(prefix + "StartTick"),
                    nbt.getLong(prefix + "EndTick"),
                    nbt.getLong(prefix + "NextPulseTick"));
        }
    }

    private static final class HealingEntry {
        private final UUID ownerUuid;
        private final UUID petUuid;
        private final HealingTimer normalTimer;
        private final HealingTimer advancedTimer;
        private float pendingHeal;
        private float projectedHealth;
        private float maxHealthSnapshot;

        private HealingEntry(UUID ownerUuid, UUID petUuid,
                             HealingTimer normalTimer, HealingTimer advancedTimer,
                             float pendingHeal, float projectedHealth, float maxHealthSnapshot) {
            this.ownerUuid = ownerUuid;
            this.petUuid = petUuid;
            this.normalTimer = normalTimer;
            this.advancedTimer = advancedTimer;
            this.pendingHeal = Math.max(0.0F, pendingHeal);
            this.projectedHealth = Math.max(0.0F, projectedHealth);
            this.maxHealthSnapshot = Math.max(0.0F, maxHealthSnapshot);
        }

        private HealingTimer timer(boolean advanced) {
            return advanced ? advancedTimer : normalTimer;
        }

        private CompoundTag save() {
            CompoundTag nbt = new CompoundTag();
            nbt.putUUID("Owner", ownerUuid);
            normalTimer.save(nbt, "Normal");
            advancedTimer.save(nbt, "Advanced");
            nbt.putFloat("PendingHeal", pendingHeal);
            nbt.putFloat("ProjectedHealth", projectedHealth);
            nbt.putFloat("MaxHealth", maxHealthSnapshot);
            return nbt;
        }

        private static HealingEntry load(CompoundTag nbt, UUID petUuid) {
            if (!nbt.hasUUID("Owner")) return null;

            HealingTimer normal;
            if (nbt.contains("NormalEndTick")) {
                normal = HealingTimer.load(nbt, "Normal");
            } else {
                long nextPulse = nbt.getLong("NextPulseTick");
                normal = new HealingTimer(
                        Math.max(0L, nextPulse - Math.max(1, Config.healPulseIntervalTicks)),
                        nbt.getLong("EndTick"),
                        nextPulse);
            }
            HealingTimer advanced = nbt.contains("AdvancedEndTick")
                    ? HealingTimer.load(nbt, "Advanced")
                    : new HealingTimer();
            return new HealingEntry(nbt.getUUID("Owner"), petUuid,
                    normal, advanced,
                    nbt.getFloat("PendingHeal"), nbt.getFloat("ProjectedHealth"),
                    nbt.getFloat("MaxHealth"));
        }
    }
}
