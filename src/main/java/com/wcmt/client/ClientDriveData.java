package com.wcmt.client;

import java.util.List;

import com.wcmt.menu.WcmtMenu;
import com.wcmt.network.DriveSnapshotPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Latest drive snapshot received from the server while a terminal is open. */
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

    /** Total content rows of all drives; a drive spans a name row plus its cell rows. */
    public static int totalRows() {
        return snapshot == null ? 0 : snapshot.totalRows();
    }

    /** Empty while the terminal is usable, otherwise why it cannot reach the network. */
    public static Component status() {
        return snapshot == null ? Component.empty() : snapshot.status();
    }

    /** True while the terminal cannot be used (unlinked, out of range, out of power, nothing fits). */
    public static boolean blocked() {
        return snapshot != null && snapshot.blocked();
    }

    public static List<DriveSnapshotPayload.DriveInfo> drives() {
        return snapshot == null ? List.of() : snapshot.drives();
    }

    /** Network-wide totals shown by the two grooves beside the player inventory. */
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

    /** True while any cell the network holds is unlimited. */
    public static boolean infinite() {
        return snapshot != null && snapshot.infinite();
    }

    private ClientDriveData() {
    }
}
