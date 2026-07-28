package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.UUID;

public final class OwnerNbtResolverSmokeTest {
    private OwnerNbtResolverSmokeTest() {}

    public static void main(String[] args) {
        UUID topLevelOwner = UUID.randomUUID();
        CompoundTag topLevel = new CompoundTag();
        topLevel.putUUID("Owner", topLevelOwner);
        require(topLevelOwner.equals(resolve(topLevel, "Owner")), "top-level UUID was not resolved");

        UUID nestedOwner = UUID.randomUUID();
        CompoundTag nested = new CompoundTag();
        CompoundTag forgeData = new CompoundTag();
        CompoundTag petData = new CompoundTag();
        petData.putString("OwnerUUID", nestedOwner.toString());
        forgeData.put("PetData", petData);
        nested.put("ForgeData", forgeData);
        require(nestedOwner.equals(resolve(nested, "ForgeData.PetData.OwnerUUID")),
                "nested UUID string was not resolved");

        UUID fallbackOwner = UUID.randomUUID();
        CompoundTag wrongIntermediateType = new CompoundTag();
        wrongIntermediateType.putString("ForgeData", "not a compound");
        wrongIntermediateType.putUUID("OwnerUUID", fallbackOwner);
        require(fallbackOwner.equals(resolve(wrongIntermediateType, "ForgeData.Owner", "OwnerUUID")),
                "wrong intermediate type did not fall through to the next path");

        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        CompoundTag ordered = new CompoundTag();
        ordered.putUUID("First", firstOwner);
        ordered.putUUID("Second", secondOwner);
        require(firstOwner.equals(resolve(ordered, "First", "Second")), "configured path order was not preserved");

        CompoundTag invalidThenValid = new CompoundTag();
        invalidThenValid.putString("Owner", "not-a-uuid");
        invalidThenValid.putUUID("OwnerUUID", secondOwner);
        require(secondOwner.equals(resolve(invalidThenValid, "Owner", "OwnerUUID")),
                "invalid UUID string did not fall through to the next path");

        CompoundTag hiddenNestedOwner = new CompoundTag();
        CompoundTag unrelated = new CompoundTag();
        unrelated.putUUID("Owner", nestedOwner);
        hiddenNestedOwner.put("Unrelated", unrelated);
        require(resolve(hiddenNestedOwner, "Owner") == null, "resolver searched outside the configured path");

        require(!OwnerNbtResolver.isValidPath(".Owner"), "leading empty path segment was accepted");
        require(!OwnerNbtResolver.isValidPath("ForgeData..Owner"), "empty nested path segment was accepted");
        require(!OwnerNbtResolver.isValidPath("Owner."), "trailing empty path segment was accepted");

        System.out.println("OwnerNbtResolverSmokeTest: 9/9 passed");
    }

    private static UUID resolve(CompoundTag nbt, String... paths) {
        return OwnerNbtResolver.resolve(nbt, OwnerNbtResolver.parsePaths(List.of(paths)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
