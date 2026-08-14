package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Client → Server: assign, move, or remove a pet inside the formation teams.
 * The server answers with an authoritative {@link TeamDataPacket}.
 */
public class SetTeamMemberPacket implements CustomPacketPayload {
    public static final Type<SetTeamMemberPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "set_team_member"));
    public static final StreamCodec<FriendlyByteBuf, SetTeamMemberPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), SetTeamMemberPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static final int ACTION_ASSIGN = 0;
    public static final int ACTION_MOVE = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_SELECT = 3;

    private final int action;
    private final int colorIndex;
    private final int slot;
    private final int fromSlot;
    private final UUID petUuid;

    private SetTeamMemberPacket(int action, int colorIndex, int slot, int fromSlot, UUID petUuid) {
        this.action = action;
        this.colorIndex = colorIndex;
        this.slot = slot;
        this.fromSlot = fromSlot;
        this.petUuid = petUuid;
    }

    /** Place a pet into a slot, replacing (kicking) the previous occupant. */
    public static SetTeamMemberPacket assign(int colorIndex, int slot, UUID petUuid) {
        return new SetTeamMemberPacket(ACTION_ASSIGN, colorIndex, slot, -1, petUuid);
    }

    /** Move a member to another slot, swapping with the occupant when present. */
    public static SetTeamMemberPacket move(int colorIndex, int fromSlot, int toSlot) {
        return new SetTeamMemberPacket(ACTION_MOVE, colorIndex, toSlot, fromSlot, null);
    }

    /** Remove a pet from one color team. */
    public static SetTeamMemberPacket remove(int colorIndex, UUID petUuid) {
        return new SetTeamMemberPacket(ACTION_REMOVE, colorIndex, -1, -1, petUuid);
    }

    /** Persist the currently selected team color. */
    public static SetTeamMemberPacket select(int colorIndex) {
        return new SetTeamMemberPacket(ACTION_SELECT, colorIndex, -1, -1, null);
    }

    public static void encode(SetTeamMemberPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.action);
        buf.writeVarInt(packet.colorIndex);
        buf.writeVarInt(packet.slot);
        buf.writeVarInt(packet.fromSlot);
        buf.writeUUID(packet.petUuid == null ? new UUID(0L, 0L) : packet.petUuid);
    }

    public static SetTeamMemberPacket decode(FriendlyByteBuf buf) {
        int action = buf.readVarInt();
        int colorIndex = buf.readVarInt();
        int slot = buf.readVarInt();
        int fromSlot = buf.readVarInt();
        UUID petUuid = buf.readUUID();
        if (petUuid.equals(new UUID(0L, 0L))) petUuid = null;
        return new SetTeamMemberPacket(action, colorIndex, slot, fromSlot, petUuid);
    }

    public static void handle(SetTeamMemberPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            int colorIndex = Math.max(0, Math.min(PetTeamData.TEAM_COLORS.size() - 1, packet.colorIndex));
            String color = PetTeamData.TEAM_COLORS.get(colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                CompoundTag data = PetTeamData.teamData(ownerDir);
                int capacity = data.getInt("Capacity");
                ListTag members = data.getCompound("Teams")
                        .getCompound(color).getList("Members", Tag.TAG_COMPOUND);
                switch (packet.action) {
                    case ACTION_ASSIGN -> {
                        UUID uuid = packet.petUuid;
                        if (packet.slot >= 1 && packet.slot <= PetTeamData.GRID_SLOT_COUNT
                                && uuid != null
                                && Files.isRegularFile(ownerDir.resolve(uuid + ".nbt"))) {
                            boolean member = false;
                            boolean occupied = false;
                            for (Tag tag : members) {
                                CompoundTag memberTag = (CompoundTag) tag;
                                if (memberTag.hasUUID("UUID") && uuid.equals(memberTag.getUUID("UUID"))) {
                                    member = true;
                                }
                                if (memberTag.getInt("Slot") == packet.slot) {
                                    occupied = true;
                                }
                            }
                            if (members.size() < capacity || member || occupied) {
                                PetTeamData.setMember(ownerDir, color, packet.slot, uuid);
                            }
                        }
                    }
                    case ACTION_MOVE -> {
                        if (packet.fromSlot >= 1 && packet.fromSlot <= PetTeamData.GRID_SLOT_COUNT
                                && packet.slot >= 1 && packet.slot <= PetTeamData.GRID_SLOT_COUNT) {
                            PetTeamData.moveMember(ownerDir, color, packet.fromSlot, packet.slot);
                        }
                    }
                    case ACTION_REMOVE -> {
                        if (packet.petUuid != null) {
                            PetTeamData.removeMember(ownerDir, color, packet.petUuid);
                        }
                    }
                    case ACTION_SELECT -> PetTeamData.setSelectedTeam(ownerDir, color);
                    default -> {}
                }
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(ownerDir));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to update team data for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
    }
}
