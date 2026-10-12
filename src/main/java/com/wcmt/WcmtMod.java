package com.wcmt;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wcmt.init.ModItems;
import com.wcmt.init.ModMenus;
import com.wcmt.network.DriveSnapshotPayload;
import com.wcmt.network.WcmtActionPayload;

import appeng.api.features.GridLinkables;
import appeng.api.ids.AECreativeTabIds;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.wcmt.menu.WcmtMenu;
import com.wcmt.menu.WcmtMenuHost;
import com.wcmt.part.WcmtTerminalPart;
import com.wcmt.util.PriorityReturn;
import de.mari_023.ae2wtlib.api.gui.Icon;
import de.mari_023.ae2wtlib.api.registration.AddTerminalEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(WcmtMod.MOD_ID)
public class WcmtMod {
    public static final String MOD_ID = "cmt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WcmtMod(IEventBus modEventBus) {
        ModItems.register(modEventBus);
        // 监听玩家从驱动器优先级界面返回的情况。
        PriorityReturn.init();
        // 触碰 ModMenus，使其菜单类型在 AE2 的注册事件触发前入队。
        ModMenus.init();
        // AE2 会较早冻结其部件模型表；线缆终端的模型必须在此前放入表中。
        WcmtTerminalPart.registerModels();

        // 在 AE2WTlib 收集其无线终端之前注册，使本终端也能从 AE2WTlib 的通用终端打开。
        // 物品必须已经存在，因此在 ModItems 中提前注册。
        AddTerminalEvent.register(event -> {
            LOGGER.info("Registering the terminal with AE2WTlib (universal terminal integration)");
            event.builder("cmt",
                    (item, player, locator, returnToMainMenu) -> new WcmtMenuHost(item, player, locator,
                            returnToMainMenu),
                    ModMenus.WCMT_MENU_TYPE,
                    ModItems.WCMT_TERMINAL.get(),
                    Icon.PATTERN_ACCESS)
                    .hotkeyName("wireless_cmt_terminal")
                    .upgradeCount(WcmtMenu.UPGRADE_SLOTS)
                    .addTerminal();
        });

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreativeTabItems);
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 让本终端可以像 AE2 自带的终端那样链接到无线访问点。
            GridLinkables.register(ModItems.WCMT_TERMINAL.get(), WirelessTerminalItem.LINKABLE_HANDLER);
            // 允许能量卡放入本终端的升级槽位。AE2 只为自己的终端注册了这些卡，
            // 因此若没有这一步，槽位会拒绝所有能量卡。
            Upgrades.add(AEItems.ENERGY_CARD, ModItems.WCMT_TERMINAL.get(), WcmtMenu.UPGRADE_SLOTS);
            // 把工作卡注册为本终端真正的升级卡，使 AE2 的槽位校验接受它
            // （且只在这里接受，其它机器不接受）。
            Upgrades.add(ModItems.COMPONENT_WORK_CARD.get(), ModItems.WCMT_TERMINAL.get(), 1);
            LOGGER.info("CMT menu registered as {}", BuiltInRegistries.MENU.getKey(ModMenus.WCMT_MENU_TYPE));
        });
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (AECreativeTabIds.MAIN.equals(event.getTabKey())) {
            event.accept(new ItemStack(ModItems.WCMT_TERMINAL.get()));
            event.accept(new ItemStack(ModItems.COMPONENT_MANAGEMENT_TERMINAL.get()));
            event.accept(new ItemStack(ModItems.COMPONENT_WORK_CARD.get()));
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(DriveSnapshotPayload.TYPE, DriveSnapshotPayload.STREAM_CODEC,
                DriveSnapshotPayload::handle);
        registrar.playToServer(WcmtActionPayload.TYPE, WcmtActionPayload.STREAM_CODEC,
                WcmtActionPayload::handle);
    }
}
