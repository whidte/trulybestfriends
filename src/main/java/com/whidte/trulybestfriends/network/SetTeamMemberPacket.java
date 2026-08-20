package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: assign, move, or remove a pet inside the formation teams.
 * The server answers with an authoritative {@link TeamDataPacket}.
 */
public class SetTeamMemberPacket {
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

    public static void handle(SetTeamMemberPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            String color = PetTeamData.colorAt(packet.colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                switch (packet.action) {
                    case ACTION_ASSIGN -> PetTeamData.setMember(ownerDir, color, packet.slot, packet.petUuid);
                    case ACTION_MOVE -> PetTeamData.moveMember(ownerDir, color, packet.fromSlot, packet.slot);
                    case ACTION_REMOVE -> PetTeamData.removeMember(ownerDir, color, packet.petUuid);
                    case ACTION_SELECT -> PetTeamData.setSelectedTeam(ownerDir, color);
                    default -> {}
                }
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(ownerDir));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to update team data for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
