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
 * 服务端 -> 客户端快照，描述当前位于滚动窗口内的驱动器，以及滚动偏移量和排序/搜索状态。
 * 元件物品堆本身通过常规容器槽位同步；此数据包只携带各驱动器的分组元数据和元件读数。
 *
 * <p>{@code offset} 和 {@code totalRows} 以内容行计数（一个驱动器为一行名称行加上它的元件行），
 * 而非以驱动器计数，因此比一个驱动器还矮的窗口仍能显示该驱动器靠下的行。{@code skippedRows} 是
 * 第一个可见驱动器有多少行滚出到窗口上方。{@code blocked} 告知客户端终端当前无法使用（未链接、
 * 超出范围、电力不足或无可用空间），{@code status} 则是要显示的文本。
 */
public record DriveSnapshotPayload(int offset, int totalRows, int skippedRows, int windowRows,
        boolean blocked, String sort, String search, Component status, List<DriveInfo> drives,
        long typeUsed, long typeTotal, long byteUsed, long byteTotal, boolean infinite, boolean sortDesc,
        boolean workCard, int configScroll, int copyMode, int selectedSlot)
        implements CustomPacketPayload {

    /**
     * 单个元件的读数。{@code infinite} 表示“不必再去推断容量”，驱动紫色容量条；
     * {@code capacityInfinite} 范围更窄——该元件确实没有容量上限——全网进度条凹槽响应的是它。
     * {@code typeCapacity} 是该元件可容纳的不同类型数量（不报告时为 0）。
     */
    public record SlotStat(long used, long total, boolean infinite, boolean capacityInfinite,
            long typeCapacity, long typeUsed, byte state) {

        /** 该槽位没有元件。 */
        public static SlotStat absent() {
            return new SlotStat(0, 0, false, false, 0, 0, (byte) CellState.ABSENT.ordinal());
        }
    }

    /** 当前窗口内的一个 ME 驱动器；{@code icon} 是驱动器方块自身的物品。 */
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
        buf.writeVarInt(p.windowRows);
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
        buf.writeBoolean(p.workCard());
        buf.writeVarInt(p.configScroll());
        buf.writeVarInt(p.copyMode());
        buf.writeVarInt(p.selectedSlot());
    }

    private static DriveSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        int offset = buf.readVarInt();
        int totalRows = buf.readVarInt();
        int skippedRows = buf.readVarInt();
        int windowRows = buf.readVarInt();
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
        boolean workCard = buf.readBoolean();
        int configScroll = buf.readVarInt();
        int copyMode = buf.readVarInt();
        int selectedSlot = buf.readVarInt();
        return new DriveSnapshotPayload(offset, totalRows, skippedRows, windowRows, blocked, sort, search,
                status, drives, typeUsed, typeTotal, byteUsed, byteTotal, infinite, sortDesc, workCard,
                configScroll, copyMode, selectedSlot);
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
        buf.writeVarLong(s.typeUsed());
        buf.writeByte(s.state());
    }

    private static SlotStat decodeStat(RegistryFriendlyByteBuf buf) {
        return new SlotStat(buf.readVarLong(), buf.readVarLong(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarLong(), buf.readVarLong(), buf.readByte());
    }

    public static void handle(DriveSnapshotPayload payload, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        context.enqueueWork(() -> ClientDriveData.update(payload));
    }
}
