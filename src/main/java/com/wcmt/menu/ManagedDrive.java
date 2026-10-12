package com.wcmt.menu;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 终端可管理的一个存储宿主。
 *
 * <p>存在两类：AE2 自带的 {@link appeng.blockentity.storage.DriveBlockEntity}（有十（或更多）
 * 个元件槽位），以及只保存单个元件的附属宿主——NeoECO 的 ECO 驱动器实现了
 * {@code cn.dancingsnow.neoecoae.util.ICellHost}，但它并非 AE2 驱动器的子类。两者都通过
 * 此接口暴露，使槽位映射、快照、排序与搜索保持与具体实现无关。
 */
public interface ManagedDrive {

    Component name();

    ItemStack icon();

    boolean online();

    BlockPos pos();

    ResourceLocation dimension();

    int cellCount();

    /** 该槽位中的元件，或 {@link ItemStack#EMPTY}；永不为 null。 */
    ItemStack cell(int slot);

    boolean acceptsCell(int slot, ItemStack stack);

    boolean canExtractCell(int slot);

    /** 替换 {@code slot} 中的元件；空物品堆表示清空。 */
    void setCell(int slot, ItemStack stack);

    DriveSnapshotPayload.SlotStat stat(int slot);

    /**
     * 打开该宿主自身的优先级界面（若有）。优先级位于另一个方块上的附属模组
     * （NeoECO 把它放在存储系统主机上）要么自行处理，要么拒绝。
     */
    default boolean openPriority(ServerPlayer player) {
        return false;
    }

    /** 整个驱动器的已用/总字节比，用于按使用量排序。 */
    double fillRatio();

    /** 报告（接近）{@link Long#MAX_VALUE} 容量的元件视为无限。 */
    long INFINITE_BYTES = Long.MAX_VALUE / 2;

    /** 报告的字节数是否表示「无限」。 */
    static boolean isInfinite(long bytes) {
        return bytes >= INFINITE_BYTES;
    }

    /**
     * 按物品缓存「id 或命名空间是否表明无限」的判定：它处在 {@code stat()} 这条热路径上，
     * 不应该每次都去查注册表并做几轮小写化。
     */
    Map<Item, Boolean> INFINITE_ITEM_IDS = new ConcurrentHashMap<>();

    /**
     * 凭空产出内容的附属元件（「无限水元件」「无限圆石元件」等）报告的是普通字节数，
     * 而且其中一些甚至不是基础 AE2 元件，因此改为通过物品 id 或显示名称来识别它们。
     */
    static boolean isInfiniteItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (INFINITE_ITEM_IDS.computeIfAbsent(stack.getItem(), item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return matchesInfinite(id.getPath()) || matchesInfinite(id.getNamespace());
        })) {
            return true;
        }
        // 显示名称用于覆盖 id 未直接表明的元件（「ME无限圆石元件」）；名称随物品堆变化，
        // 无法按物品缓存。
        return matchesInfinite(stack.getHoverName().getString());
    }

    private static boolean matchesInfinite(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("infinite") || lower.contains("infinity") || lower.contains("无限");
    }
}
