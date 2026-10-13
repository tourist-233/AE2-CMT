package com.wcmt.network;

import com.wcmt.WcmtMod;
import com.wcmt.menu.WcmtMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端 -> 服务端：更改已打开终端的滚动窗口 / 排序模式 / 搜索文本。 */
public record WcmtActionPayload(int action, int a, int b, String text) implements CustomPacketPayload {

    public static final int ACTION_LAYOUT = 0;
    public static final int ACTION_SORT = 1;
    public static final int ACTION_SEARCH = 2;
    public static final int ACTION_SORT_DIRECTION = 3;
    /** 打开由 {@code a} 选中的驱动器的优先级界面（{@code a} 为它在快照中的索引）。 */
    public static final int ACTION_PRIORITY = 4;
    /** 将工作卡的分区网格滚动到第 {@code a} 步。 */
    public static final int ACTION_CONFIG_SCROLL = 5;
    /** 工作台：将当前选中元件的内容捕获到分区网格。 */
    public static final int ACTION_PARTITION = 6;
    /** 工作台：清空分区网格。 */
    public static final int ACTION_CLEAR = 7;
    /** 工作台：切换复制模式。 */
    public static final int ACTION_COPY_MODE = 8;
    /** 右键点击了已存储的元件：选中它并将分区网格应用到它。{@code a} 是它的驱动器槽位索引。 */
    public static final int ACTION_APPLY_CELL = 9;
    /** 设置所选元件的模糊模式；{@code a} 是 {@code FuzzyMode} 的序号。 */
    public static final int ACTION_FUZZY_MODE = 10;

    public static final Type<WcmtActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID, "action"));

    public static final StreamCodec<FriendlyByteBuf, WcmtActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WcmtActionPayload::action,
            ByteBufCodecs.VAR_INT, WcmtActionPayload::a,
            ByteBufCodecs.VAR_INT, WcmtActionPayload::b,
            ByteBufCodecs.STRING_UTF8, WcmtActionPayload::text,
            WcmtActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WcmtActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player != null && player.containerMenu instanceof WcmtMenu menu) {
                menu.handleAction(payload.action(), payload.a(), payload.b(), payload.text());
            }
        });
    }
}
