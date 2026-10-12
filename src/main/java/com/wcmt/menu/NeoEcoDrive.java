package com.wcmt.menu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import appeng.api.networking.IManagedGridNode;
import com.wcmt.WcmtMod;
import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 一个 NeoECO LD 存储矩阵（「ECO - LD 存储矩阵驱动器」方块）的 {@link ManagedDrive} 视图。
 *
 * <p>NeoECO 存储矩阵是多块结构：多个驱动器方块同属一个集群，每个方块保存一个元件。因此本类的
 * 一个实例代表整个集群，并为每个驱动器方块暴露一个元件槽位。CD 计算驱动器（「晶阵驱动器」）
 * 对我们而言不是存储宿主，会被忽略。
 *
 * <p>元件槽与容量读数走 NeoECO 的公开 API（见 {@link NeoEcoSupport}）：那是编译期依赖，模组
 * 缺失时这些类不会被加载。集群、控制器、宿主节点与方块界面 NeoECO 没有对外暴露，仍以反射
 * 访问；模组缺失时 {@link #collect} 直接返回空列表，终端不受影响。
 */
final class NeoEcoDrive implements ManagedDrive {

    private static final String STORAGE_DRIVE_CLASS =
            "cn.dancingsnow.neoecoae.blocks.entity.storage.ECODriveBlockEntity";
    private static final String BLOCK_ENTITY_BASE = "cn.dancingsnow.neoecoae.blocks.entity.NEBlockEntity";

    private static final Class<?> storageDriveClass;
    private static final Method getMainNode;
    private static final Method getCluster;
    /** {@code ECODriveBlockEntity#isLockedByInfiniteMode()}：该 API 缺失时为 null。 */
    private static final Method isLockedByInfiniteMode;
    /**
     * LDLib2 用于打开方块自身界面的入口（{@code BlockUIMenuType.openUI}）。NeoECO 会带上
     * LDLib2，但终端并不依赖它，所以这里以反射查找。
     */
    private static final Method openBlockUi;

    static {
        Class<?> driveClass = null;
        try {
            driveClass = Class.forName(STORAGE_DRIVE_CLASS);
        } catch (ClassNotFoundException e) {
            // NeoECO 根本没安装：保持沉默不做任何事。
            driveClass = null;
        } catch (LinkageError e) {
            WcmtMod.LOGGER.warn("NeoECO is installed but its drive class did not load; "
                    + "NeoECO storage matrices will be ignored", e);
            driveClass = null;
        }
        storageDriveClass = driveClass;
        // 这几项都不在 NeoECO 的 api 包里，只能继续反射。
        getCluster = driveClass == null ? null : findMethod(driveClass, "getCluster");
        isLockedByInfiniteMode = driveClass == null
                ? null
                : findMethod(driveClass, "isLockedByInfiniteMode");
        Method node = null;
        try {
            node = findMethod(Class.forName(BLOCK_ENTITY_BASE), "getMainNode");
        } catch (ReflectiveOperationException | LinkageError e) {
            node = null;
        }
        getMainNode = node;
        openBlockUi = findOpenBlockUi();
    }

    /** 一个集群的驱动器方块，按位置排序，使滚动时元件保持其槽位。 */
    private final List<BlockEntity> parts;
    /**
     * 集群的存储系统主机（「可拓展存储系统主机」），多块结构未成型时为 null。
     * 主机决定了等级（L4/L6/L9），因此条目以主机形式展示。
     */
    @Nullable
    private final BlockEntity controller;

    private NeoEcoDrive(List<BlockEntity> parts, @Nullable BlockEntity controller) {
        this.parts = parts;
        this.controller = controller;
    }

    /** 每个集群一个被管理的驱动器；不是 LD 存储矩阵方块的宿主会被忽略。 */
    static List<ManagedDrive> collect(List<BlockEntity> owners) {
        if (storageDriveClass == null) {
            return List.of();
        }
        List<ManagedDrive> result = new ArrayList<>();
        Set<Object> seenClusters = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockEntity owner : owners) {
            if (!isStorageDrive(owner)) {
                continue;
            }
            Object cluster = getCluster == null ? null : invoke(getCluster, owner);
            if (cluster == null) {
                // （尚）不属于已成型集群：单独展示它。
                result.add(new NeoEcoDrive(List.of(owner), null));
                continue;
            }
            if (!seenClusters.add(cluster)) {
                continue;
            }
            List<BlockEntity> parts = drivesOf(cluster);
            result.add(new NeoEcoDrive(parts.isEmpty() ? List.of(owner) : parts, controllerOf(cluster)));
        }
        return result;
    }

    private static boolean isStorageDrive(Object candidate) {
        return storageDriveClass != null && storageDriveClass.isInstance(candidate);
    }

    private static List<BlockEntity> drivesOf(Object cluster) {
        Method getDrives = cachedMethod(cluster.getClass(), "getDrives");
        if (getDrives == null || !(invoke(getDrives, cluster) instanceof List<?> list)) {
            return List.of();
        }
        List<BlockEntity> parts = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof BlockEntity blockEntity && !blockEntity.isRemoved()
                    && !parts.contains(blockEntity)) {
                parts.add(blockEntity);
            }
        }
        parts.sort(Comparator.comparingLong(part -> part.getBlockPos().asLong()));
        return parts;
    }

    /** 解析 LDLib2 的方块界面打开器；NeoECO（及 LDLib2）未安装时为 null。 */
    private static Method findOpenBlockUi() {
        try {
            return Class.forName("com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType")
                    .getMethod("openUI", ServerPlayer.class, BlockPos.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * NeoECO 存储系统的优先级属于多块结构的主机，因此打开的是主机的界面（其中包含优先级
     * 控件），而非任何驱动器方块的界面。
     */
    @Override
    public boolean openPriority(ServerPlayer player) {
        if (controller == null || openBlockUi == null) {
            return false;
        }
        try {
            openBlockUi.invoke(null, player, controller.getBlockPos());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reportOnce("openUI", e);
            return false;
        }
    }

    /** 按「类#方法名」缓存反射查找结果，未找到的也记住，避免每次刷新重走一遍父类链。 */
    private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private static Method cachedMethod(Class<?> type, String name) {
        return METHOD_CACHE.computeIfAbsent(type.getName() + '#' + name,
                key -> Optional.ofNullable(findMethod(type, name))).orElse(null);
    }

    private static Method findMethod(Class<?> start, String name) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续向上查找父类
            } catch (RuntimeException e) {
                // 最终还是不可访问（模块或安全管理器）：放弃这次查找。
                return null;
            }
        }
        return null;
    }

    /** 已报告过失败的方法，使损坏的桥接只警告一次，而非每 tick 都警告。 */
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    /** 记录一次桥接失败；同名的只警告一次。 */
    static void reportOnce(String what, Throwable error) {
        if (REPORTED_FAILURES.add(what)) {
            WcmtMod.LOGGER.warn("NeoECO bridge call {} failed; the drive still works but reports "
                    + "no data for it", what, error);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 无论出什么问题（签名变更、类缺失、参数被拒）都必须降级为「无值」，而不是
            // 逸出到菜单的 tick 中，但也不应静默消失。
            reportOnce(method.getName(), e);
            return null;
        }
    }

    private BlockEntity part(int slot) {
        return slot >= 0 && slot < parts.size() ? parts.get(slot) : null;
    }

    /** 集群的存储系统主机方块实体，没有时为 null。 */
    private static BlockEntity controllerOf(Object cluster) {
        Method getController = cachedMethod(cluster.getClass(), "getController");
        return getController != null && invoke(getController, cluster) instanceof BlockEntity host
                && !host.isRemoved()
                ? host
                : null;
    }

    /**
     * 条目用于命名和图标显示的方块：集群成型时为该主机（其方块带有等级，例如
     * 「ECO - L4 可拓展存储系统主机」），否则为第一个驱动器方块。
     */
    private BlockEntity displayEntity() {
        return controller != null ? controller : parts.get(0);
    }

    @Override
    public Component name() {
        BlockEntity shown = displayEntity();
        if (shown instanceof Nameable nameable && nameable.hasCustomName()) {
            return nameable.getCustomName();
        }
        return shown.getBlockState().getBlock().getName();
    }

    @Override
    public ItemStack icon() {
        return displayEntity().getBlockState().getBlock().asItem().getDefaultInstance();
    }

    @Override
    public boolean online() {
        BlockEntity first = parts.get(0);
        if (getMainNode == null) {
            return true;
        }
        return invoke(getMainNode, first) instanceof IManagedGridNode node && node.isOnline();
    }

    @Override
    public BlockPos pos() {
        return parts.get(0).getBlockPos();
    }

    @Override
    public ResourceLocation dimension() {
        var level = parts.get(0).getLevel();
        return level != null
                ? level.dimension().location()
                : ResourceLocation.withDefaultNamespace("overworld");
    }

    @Override
    public int cellCount() {
        return parts.size();
    }

    @Override
    public ItemStack cell(int slot) {
        BlockEntity part = part(slot);
        if (part == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = NeoEcoSupport.getCellStack(part);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public boolean acceptsCell(int slot, ItemStack stack) {
        BlockEntity part = part(slot);
        return part != null && !stack.isEmpty() && NeoEcoSupport.isItemValid(part, stack);
    }

    @Override
    public boolean canExtractCell(int slot) {
        BlockEntity part = part(slot);
        return part != null && NeoEcoSupport.canExtractCell(part);
    }

    @Override
    public void setCell(int slot, ItemStack stack) {
        BlockEntity part = part(slot);
        if (part == null) {
            return;
        }
        // 宿主只认 null 作为清空请求，空物品堆会被它拒绝（见 NeoEcoSupport）。
        NeoEcoSupport.setCellStack(part, stack);
    }

    @Override
    public DriveSnapshotPayload.SlotStat stat(int slot) {
        BlockEntity part = part(slot);
        if (part == null) {
            return DriveSnapshotPayload.SlotStat.absent();
        }
        NeoEcoSupport.CellStats stats = NeoEcoSupport.readStats(part, cell(slot));
        if (!stats.present()) {
            return DriveSnapshotPayload.SlotStat.absent();
        }
        // NeoECO 中的无限存储指集群运行在无限模式；仅提供无限资源的元件（无限材料矩阵）
        // 仍会显示紫色条。只有前者才是真正的容量限制，进度槽会对此作出反应。
        boolean clusterInfinite = inInfiniteMode(part);
        boolean infinite = clusterInfinite || ManagedDrive.isInfiniteItem(cell(slot));
        return new DriveSnapshotPayload.SlotStat(stats.used(), stats.total(), infinite, clusterInfinite,
                stats.typeCapacity(), stats.typeUsed(), stats.status());
    }

    /**
     * 该驱动器所属集群当前是否运行在 NeoECO 的无限模式。仅靠无限「成员」元件物品并不代表无限
     * 存储，因此这里不能依据它判断。
     */
    private static boolean inInfiniteMode(BlockEntity part) {
        if (part == null || isLockedByInfiniteMode == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoke(isLockedByInfiniteMode, part));
    }

    @Override
    public double fillRatio() {
        long used = 0;
        long total = 0;
        for (int i = 0; i < cellCount(); i++) {
            var stat = stat(i);
            used += stat.used();
            total += stat.total();
        }
        return total > 0 ? (double) used / total : 0;
    }
}
