package com.whidte.trulybestfriends.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric replacement for {@code net.minecraftforge.network.NetworkEvent.Context}.
 * Fabric API already delivers play packets on the main thread, so queued work
 * executes inline. The sender is null for server-to-client packets.
 */
public final class PacketContext {
    private final ServerPlayer sender;

    public PacketContext(ServerPlayer sender) {
        this.sender = sender;
    }

    /** The sending player, or {@code null} for server-to-client packets. */
    public ServerPlayer getSender() {
        return sender;
    }

    /** Runs the task on the main thread. Already there for Fabric play packets. */
    public void enqueueWork(Runnable task) {
        task.run();
    }

    public void setPacketHandled(boolean handled) {
        // Fabric API has no per-packet completion marker; nothing to do.
    }
}
