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
        // Touch ModMenus so its menu type is queued into AE2's registry before the registry event fires.
        ModMenus.init();
        // AE2 freezes its part-model table early; the cable terminal's models must be in it by then.
        WcmtTerminalPart.registerModels();

        // Register with AE2WTlib before it collects its wireless terminals, so this terminal is also
        // reachable from AE2WTlib's universal terminal. The item has to exist already, hence the
        // eager registration in ModItems.
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
            // Make the terminal linkable to a wireless access point like AE2's own terminals.
            GridLinkables.register(ModItems.WCMT_TERMINAL.get(), WirelessTerminalItem.LINKABLE_HANDLER);
            // Let energy cards go into the terminal's upgrade slots. AE2 only registers them for its
            // own terminals, so without this the slots would reject every card.
            Upgrades.add(AEItems.ENERGY_CARD, ModItems.WCMT_TERMINAL.get(), WcmtMenu.UPGRADE_SLOTS);
            LOGGER.info("CMT menu registered as {}", BuiltInRegistries.MENU.getKey(ModMenus.WCMT_MENU_TYPE));
        });
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (AECreativeTabIds.MAIN.equals(event.getTabKey())) {
            event.accept(new ItemStack(ModItems.WCMT_TERMINAL.get()));
            event.accept(new ItemStack(ModItems.COMPONENT_MANAGEMENT_TERMINAL.get()));
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
