package com.wcmt.menu;

import java.util.Locale;

import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * One storage host the terminal can manage.
 *
 * <p>Two kinds exist: AE2's own {@link appeng.blockentity.storage.DriveBlockEntity} with its ten (or
 * more) cell slots, and addon hosts that keep a single cell — NeoECO's ECO drives implement
 * {@code cn.dancingsnow.neoecoae.util.ICellHost}, which is not a subclass of the AE2 drive. Both are
 * exposed through this interface so slot mapping, snapshots, sorting and searching stay agnostic.
 */
public interface ManagedDrive {

    Component name();

    ItemStack icon();

    boolean online();

    BlockPos pos();

    ResourceLocation dimension();

    int cellCount();

    /** The cell in this slot, or {@link ItemStack#EMPTY}; never null. */
    ItemStack cell(int slot);

    boolean acceptsCell(int slot, ItemStack stack);

    boolean canExtractCell(int slot);

    /** Replaces the cell in {@code slot}; an empty stack clears it. */
    void setCell(int slot, ItemStack stack);

    DriveSnapshotPayload.SlotStat stat(int slot);

    /** Used/total byte ratio of the whole drive, for sorting by usage. */
    double fillRatio();

    /** A cell that reports (nearly) {@link Long#MAX_VALUE} capacity counts as unlimited. */
    long INFINITE_BYTES = Long.MAX_VALUE / 2;

    /** Whether a reported byte count means "unlimited". */
    static boolean isInfinite(long bytes) {
        return bytes >= INFINITE_BYTES;
    }

    /**
     * Addon cells that create their content out of nothing ("infinite water cell", "infinite
     * cobblestone cell", ...) report ordinary byte counts — and some are not even basic AE2 cells —
     * so they are recognised by their item id or display name instead.
     */
    static boolean isInfiniteItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (matchesInfinite(id.getPath()) || matchesInfinite(id.getNamespace())) {
            return true;
        }
        // Display names cover cells whose id doesn't spell it out ("ME无限圆石元件").
        return matchesInfinite(stack.getHoverName().getString());
    }

    private static boolean matchesInfinite(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("infinite") || lower.contains("infinity") || lower.contains("无限");
    }
}
