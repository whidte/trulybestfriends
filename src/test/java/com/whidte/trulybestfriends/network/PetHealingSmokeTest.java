package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;

public final class PetHealingSmokeTest {
    public static void main(String[] args) {
        require(PetHealingManager.pulseCount(300, 50) == 6,
                "300 ticks at a 50-tick interval must produce six pulses");
        require(PetHealingManager.pulseCount(300, 25) == 12,
                "advanced healing must produce 12 pulses in 300 ticks");
        require(PetHealingManager.isAdvancedActiveAt(100, 400, 150),
                "normal pulses inside the advanced window must be suppressed");
        require(PetHealingManager.isAdvancedActiveAt(100, 400, 400),
                "the advanced effect remains authoritative on its final pulse tick");
        require(!PetHealingManager.isAdvancedActiveAt(100, 400, 401),
                "normal pulses after the advanced window must resume");
        require(PetHealingManager.hasDuePulse(300, 300, 300),
                "the final pulse must remain due when a timer is extended on its end tick");
        require(PetHealingManager.canExtend(900, 300, 1200),
                "900 remaining ticks must accept a 300-tick extension");
        require(!PetHealingManager.canExtend(901, 300, 1200),
                "a click must be rejected as a whole when it would exceed 1200 ticks");
        require(PetHealingManager.cappedIncrease(18.0F, 20.0F, 4.0F) == 2.0F,
                "projected healing must stop at maximum health");
        require(PetHealingManager.cappedIncrease(20.0F, 20.0F, 2.0F) == 0.0F,
                "full-health pets must not accumulate excess pending healing");
        require(PetHealingManager.shouldRetainExpired(0.25F),
                "expired entries must retain unapplied healing");
        require(!PetHealingManager.shouldRetainExpired(0.0F),
                "expired entries without pending healing must be removed");

        CompoundTag clientData = new CompoundTag();
        clientData.putIntArray(PetHealingManager.CLIENT_DATA_TAG, new int[] {
                900, 600, Float.floatToIntBits(2.5F), 3, 9, 50, 25,
                300, 1200, Float.floatToIntBits(1.0F), Float.floatToIntBits(0.01F)
        });
        require(PetHealingManager.getRemainingTicks(clientData) == 900,
                "compact client state must preserve remaining duration");
        require(PetHealingManager.getPendingHeal(clientData) == 2.5F,
                "compact client state must preserve pending healing");
        require(PetHealingManager.getRemainingTicks(clientData, true) == 600,
                "compact client state must preserve advanced duration");
        require(PetHealingManager.getHungerCost(clientData, true) == 9,
                "compact client state must preserve advanced hunger cost");
        require(PetHealingManager.getPulseInterval(clientData, true) == 25,
                "compact client state must preserve advanced pulse interval");

        CompoundTag dead = new CompoundTag();
        PetDeathState.markStoredDead(dead);
        require(PetDeathState.isDeadSnapshot(dead), "stored-dead pets must remain dead snapshots");
        require(dead.getFloat("Health") == 0.0F, "healing metadata must never revive a dead snapshot");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
