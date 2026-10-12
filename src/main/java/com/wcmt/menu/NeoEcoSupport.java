package com.wcmt.menu;

import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import cn.dancingsnow.neoecoae.api.storage.ECOStorageCells;
import cn.dancingsnow.neoecoae.api.storage.IECOStorageCell;
import cn.dancingsnow.neoecoae.util.ICellHost;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * NeoECO 公开 API 的调用点。
 *
 * <p>NeoECO 是可选依赖，本类中对 NeoECO 类型的直接引用只在模组确实存在时才允许被加载：
 * {@link NeoEcoDrive} 会先用 {@code Class.forName} 确认它的宿主类可解析，之后才调用这里的
 * 静态方法。集群、控制器、宿主节点与方块界面 NeoECO 没有对外暴露，那些仍由反射完成。
 */
final class NeoEcoSupport {

    /** 元件读数；{@code present} 表示槽位确实装着一个可读的元件。 */
    record CellStats(long used, long total, long typeCapacity, long typeUsed, byte status,
            boolean present) {

        static CellStats absent() {
            return new CellStats(0, 0, 0, 0, (byte) CellState.ABSENT.ordinal(), false);
        }
    }

    /** 宿主方块里的元件；槽位为空时为 null。 */
    @Nullable
    static ItemStack getCellStack(BlockEntity part) {
        if (!(part instanceof ICellHost host)) {
            return null;
        }
        try {
            return host.getCellStack();
        } catch (RuntimeException | LinkageError e) {
            NeoEcoDrive.reportOnce("getCellStack", e);
            return null;
        }
    }

    /** 替换宿主方块里的元件；宿主只认 null 作为清空请求，空物品堆会被拒绝。 */
    static void setCellStack(BlockEntity part, ItemStack stack) {
        if (!(part instanceof ICellHost host)) {
            return;
        }
        try {
            host.setCellStack(stack.isEmpty() ? null : stack.copyWithCount(1));
        } catch (RuntimeException | LinkageError e) {
            NeoEcoDrive.reportOnce("setCellStack", e);
        }
    }

    static boolean isItemValid(BlockEntity part, ItemStack stack) {
        if (!(part instanceof ICellHost host)) {
            return false;
        }
        try {
            return host.isItemValid(stack);
        } catch (RuntimeException | LinkageError e) {
            NeoEcoDrive.reportOnce("isItemValid", e);
            return false;
        }
    }

    static boolean canExtractCell(BlockEntity part) {
        if (!(part instanceof ICellHost host)) {
            return false;
        }
        try {
            return host.canExtractCell();
        } catch (RuntimeException | LinkageError e) {
            NeoEcoDrive.reportOnce("canExtractCell", e);
            return false;
        }
    }

    /**
     * 元件的容量读数。宿主本身就是元件存档提供者（{@code ISaveProvider}），元件因此能挂到它
     * 上面；没有元件或读不到时为 {@link CellStats#absent()}。
     */
    static CellStats readStats(BlockEntity part, ItemStack cell) {
        if (cell.isEmpty()) {
            return CellStats.absent();
        }
        try {
            IECOStorageCell inventory = ECOStorageCells.getCellInventory(cell,
                    part instanceof ISaveProvider provider ? provider : null);
            if (inventory == null) {
                return CellStats.absent();
            }
            CellState status = inventory.getStatus();
            return new CellStats(inventory.getUsedBytes(), inventory.getTotalBytes(),
                    inventory.getTotalItemTypes(), inventory.getStoredItemTypes(),
                    (byte) (status == null ? CellState.ABSENT.ordinal() : status.ordinal()), true);
        } catch (RuntimeException | LinkageError e) {
            NeoEcoDrive.reportOnce("readStats", e);
            return CellStats.absent();
        }
    }

    private NeoEcoSupport() {
    }
}
