package com.wcmt.network;

import com.wcmt.WcmtMod;
import com.wcmt.menu.WcmtMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: change scroll window / sort mode / search text of an open terminal. */
public record WcmtActionPayload(int action, int a, int b, String text) implements CustomPacketPayload {

    public static final int ACTION_LAYOUT = 0;
    public static final int ACTION_SORT = 1;
    public static final int ACTION_SEARCH = 2;

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
