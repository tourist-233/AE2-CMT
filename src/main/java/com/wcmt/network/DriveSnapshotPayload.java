package com.wcmt.network;

import java.util.ArrayList;
import java.util.List;

import appeng.api.storage.cells.CellState;
import com.wcmt.WcmtMod;
import com.wcmt.client.ClientDriveData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client snapshot describing the drives currently inside the scrolling window, plus the
 * scroll offset and sort/search state. Cell item stacks themselves are synced through the regular
 * container slots; this payload only carries the per-drive grouping metadata and cell readouts.
 *
 * <p>{@code offset} and {@code totalRows} are counted in content rows (a drive is one name row plus
 * its cell rows), not in drives, so a window shorter than a drive can still show that drive's lower
 * rows. {@code skippedRows} is how many of the first visible drive's rows are scrolled off above the
 * window. {@code blocked} tells the client that the terminal cannot be used right now (unlinked, out
 * of range, out of power or nothing fits) and {@code status} is the text to show for it.
 */
public record DriveSnapshotPayload(int offset, int totalRows, int skippedRows,
        boolean blocked, String sort, String search, Component status, List<DriveInfo> drives,
        long typeUsed, long typeTotal, long byteUsed, long byteTotal, boolean infinite, boolean sortDesc)
        implements CustomPacketPayload {

    /**
     * Per-cell readout. {@code infinite} means "don't try to reason about the capacity" and drives the
     * purple capacity bar; {@code capacityInfinite} is narrower — the cell really has no capacity
     * limit — and is what the network-wide progress grooves react to. {@code typeCapacity} is how many
     * distinct types this cell can hold (0 when it doesn't report one).
     */
    public record SlotStat(long used, long total, boolean infinite, boolean capacityInfinite,
            long typeCapacity, byte state) {

        /** No cell in this slot. */
        public static SlotStat absent() {
            return new SlotStat(0, 0, false, false, 0, (byte) CellState.ABSENT.ordinal());
        }
    }

    /** One ME Drive inside the current window; {@code icon} is the drive block's own item. */
    public record DriveInfo(BlockPos pos, Component name, ItemStack icon, List<SlotStat> slots) {
    }

    public static final Type<DriveSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID, "drive_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DriveSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(DriveSnapshotPayload::encode, DriveSnapshotPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DriveSnapshotPayload p) {
        buf.writeVarInt(p.offset);
        buf.writeVarInt(p.totalRows);
        buf.writeVarInt(p.skippedRows);
        buf.writeBoolean(p.blocked);
        buf.writeUtf(p.sort);
        buf.writeUtf(p.search);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.status);
        buf.writeVarInt(p.drives.size());
        for (DriveInfo drive : p.drives) {
            encodeDrive(buf, drive);
        }
        buf.writeVarLong(p.typeUsed());
        buf.writeVarLong(p.typeTotal());
        buf.writeVarLong(p.byteUsed());
        buf.writeVarLong(p.byteTotal());
        buf.writeBoolean(p.infinite());
        buf.writeBoolean(p.sortDesc());
    }

    private static DriveSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        int offset = buf.readVarInt();
        int totalRows = buf.readVarInt();
        int skippedRows = buf.readVarInt();
        boolean blocked = buf.readBoolean();
        String sort = buf.readUtf();
        String search = buf.readUtf();
        Component status = ComponentSerialization.STREAM_CODEC.decode(buf);
        int driveCount = buf.readVarInt();
        List<DriveInfo> drives = new ArrayList<>(driveCount);
        for (int i = 0; i < driveCount; i++) {
            drives.add(decodeDrive(buf));
        }
        long typeUsed = buf.readVarLong();
        long typeTotal = buf.readVarLong();
        long byteUsed = buf.readVarLong();
        long byteTotal = buf.readVarLong();
        boolean infinite = buf.readBoolean();
        boolean sortDesc = buf.readBoolean();
        return new DriveSnapshotPayload(offset, totalRows, skippedRows, blocked, sort, search,
                status, drives, typeUsed, typeTotal, byteUsed, byteTotal, infinite, sortDesc);
    }

    private static void encodeDrive(RegistryFriendlyByteBuf buf, DriveInfo d) {
        buf.writeBlockPos(d.pos());
        ComponentSerialization.STREAM_CODEC.encode(buf, d.name());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, d.icon());
        buf.writeVarInt(d.slots().size());
        for (SlotStat stat : d.slots()) {
            encodeStat(buf, stat);
        }
    }

    private static DriveInfo decodeDrive(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
        ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        int statCount = buf.readVarInt();
        List<SlotStat> slots = new ArrayList<>(statCount);
        for (int i = 0; i < statCount; i++) {
            slots.add(decodeStat(buf));
        }
        return new DriveInfo(pos, name, icon, slots);
    }

    private static void encodeStat(RegistryFriendlyByteBuf buf, SlotStat s) {
        buf.writeVarLong(s.used());
        buf.writeVarLong(s.total());
        buf.writeBoolean(s.infinite());
        buf.writeBoolean(s.capacityInfinite());
        buf.writeVarLong(s.typeCapacity());
        buf.writeByte(s.state());
    }

    private static SlotStat decodeStat(RegistryFriendlyByteBuf buf) {
        return new SlotStat(buf.readVarLong(), buf.readVarLong(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarLong(), buf.readByte());
    }

    public static void handle(DriveSnapshotPayload payload, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        context.enqueueWork(() -> ClientDriveData.update(payload));
    }
}
