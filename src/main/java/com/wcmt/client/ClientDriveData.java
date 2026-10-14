package com.wcmt.client;

import java.util.List;

import com.wcmt.menu.WcmtMenu;
import com.wcmt.network.DriveSnapshotPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 终端打开期间从服务端收到的最新驱动器快照。 */
public final class ClientDriveData {

    private static DriveSnapshotPayload snapshot;

    public static void update(DriveSnapshotPayload payload) {
        snapshot = payload;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof WcmtMenu menu) {
            menu.onClientSnapshot(payload);
        }
    }

    public static void clear() {
        snapshot = null;
    }

    public static DriveSnapshotPayload get() {
        return snapshot;
    }

    /** 所有驱动器的内容总行数；一个驱动器占一行名称行加上它的元件行。 */
    public static int totalRows() {
        return snapshot == null ? 0 : snapshot.totalRows();
    }

    /** 服务端当前显示的行数；滚动范围必须以此为准，而不是我们自己猜测的值。 */
    public static int windowRows() {
        return snapshot == null ? 0 : snapshot.windowRows();
    }

    /** 终端可用时为空，否则是不能连接网络的原因。 */
    public static Component status() {
        return snapshot == null ? Component.empty() : snapshot.status();
    }

    /** 终端无法使用时为 true（未链接、超出范围、电力不足、无可用空间）。 */
    public static boolean blocked() {
        return snapshot != null && snapshot.blocked();
    }

    /** 该区域显示工作卡分区网格的哪一步。 */
    public static int configScroll() {
        return snapshot == null ? 0 : snapshot.configScroll();
    }

    public static List<DriveSnapshotPayload.DriveInfo> drives() {
        return snapshot == null ? List.of() : snapshot.drives();
    }

    /** 玩家物品栏旁两条凹槽显示的全网总计。 */
    public static long typeUsed() {
        return snapshot == null ? 0 : snapshot.typeUsed();
    }

    public static long typeTotal() {
        return snapshot == null ? 0 : snapshot.typeTotal();
    }

    public static long byteUsed() {
        return snapshot == null ? 0 : snapshot.byteUsed();
    }

    public static long byteTotal() {
        return snapshot == null ? 0 : snapshot.byteTotal();
    }

    /** 网络中只要有任意元件为无限，就为 true。 */
    public static boolean infinite() {
        return snapshot != null && snapshot.infinite();
    }

    /** 网络中只要有元件的类型数不受限（例如 omni 的通用元件），就为 true。 */
    public static boolean infiniteTypes() {
        return snapshot != null && snapshot.typeInfinite();
    }

    /** 服务端最后上报的排序键，用于初始化排序按钮。 */
    public static WcmtMenu.SortMode sortMode() {
        return snapshot == null ? WcmtMenu.SortMode.POSITION : WcmtMenu.SortMode.valueOf(snapshot.sort());
    }

    /** 服务端按从下到上的顺序排列驱动器时为 true。 */
    public static boolean sortDescending() {
        return snapshot != null && snapshot.sortDesc();
    }

    private ClientDriveData() {
    }
}
