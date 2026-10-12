package com.wcmt.menu;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.wcmt.WcmtMod;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * AE2 Omni Cells（通用元件）的容量读数。
 *
 * <p>这类元件的 inventory 不是 AE2 的 {@code BasicCellInventory}，而它对外只有 {@code common}
 * 内部包里的 {@code IAEUniversalCell}（没有公开的 api 包），所以以反射访问：字节与类型用量是
 * 接口上的静态辅助方法，容量是元件物品自己的实例方法。约定与它本身一致——容量 {@code <= 0}
 * 表示不限，读数时据此换算成我们的「无限」表示。
 */
final class OmniCellSupport {

    /** 元件的读数接口；模组未安装或以后改名时为 null。 */
    private static final Class<?> CELL_INTERFACE =
            findClass("com.wintercogs.ae2omnicells.common.me.IAEUniversalCell");
    /** 静态：已用字节数。 */
    private static final Method GET_USED_BYTES = findStatic("getUsedBytes", ItemStack.class);
    /** 静态：已用类型数。 */
    private static final Method GET_USED_TYPES = findStatic("getUsedTypes", ItemStack.class);
    /** 实例：总字节数，{@code <= 0} 表示不限。 */
    private static final Method GET_TOTAL_BYTES = findStatic("getTotalBytes");
    /** 实例：总类型数，{@code <= 0} 表示不限。 */
    private static final Method GET_TOTAL_TYPES = findStatic("getTotalTypes");

    /** 已报告过的失败，避免每 tick 刷屏。 */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * 元件读数。{@code total} 为 {@link ManagedDrive#INFINITE_BYTES} 表示总量不限，
     * {@code typeCapacity} 为负表示类型数不限。
     */
    record CellStats(long used, long total, long typeUsed, long typeCapacity) {
    }

    /** 元件是否是 omni 通用元件；模组缺失或元件不属于它时为 false。 */
    static boolean handles(ItemStack stack) {
        return CELL_INTERFACE != null && !stack.isEmpty() && CELL_INTERFACE.isInstance(stack.getItem());
    }

    /** 读取元件读数；不是 omni 元件或接口不匹配时为 null。 */
    @Nullable
    static CellStats read(ItemStack stack) {
        if (!handles(stack)) {
            return null;
        }
        try {
            long total = instanceNumber(GET_TOTAL_BYTES, stack.getItem());
            long typeCapacity = instanceNumber(GET_TOTAL_TYPES, stack.getItem());
            return new CellStats(staticNumber(GET_USED_BYTES, stack),
                    total > 0 ? total : ManagedDrive.INFINITE_BYTES,
                    staticNumber(GET_USED_TYPES, stack),
                    typeCapacity > 0 ? typeCapacity : -1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (REPORTED.add("read")) {
                WcmtMod.LOGGER.warn("Could not read an AE2 Omni Cells cell; it will show no capacity", e);
            }
            return null;
        }
    }

    private static long staticNumber(@Nullable Method method, ItemStack stack)
            throws ReflectiveOperationException {
        return method == null ? 0 : ((Number) method.invoke(null, stack)).longValue();
    }

    private static long instanceNumber(@Nullable Method method, Object target)
            throws ReflectiveOperationException {
        return method == null ? 0 : ((Number) method.invoke(target)).longValue();
    }

    @Nullable
    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            // 没装 AE2 Omni Cells：静默跳过。
            return null;
        }
    }

    @Nullable
    private static Method findStatic(String name, Class<?>... parameters) {
        if (CELL_INTERFACE == null) {
            return null;
        }
        try {
            return CELL_INTERFACE.getMethod(name, parameters);
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private OmniCellSupport() {
    }
}
