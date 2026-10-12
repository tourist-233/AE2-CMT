package com.wcmt.client;

import com.wcmt.WcmtMod;
import com.wcmt.init.ModMenus;

import appeng.init.client.InitScreens;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = WcmtMod.MOD_ID, value = Dist.CLIENT)
public final class WcmtClientSetup {

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(event, ModMenus.WCMT_MENU_TYPE, WcmtScreen::new, "/screens/cmt.json");
        // 线缆部件共用菜单与界面，但不共用界面标题。
        InitScreens.register(event, ModMenus.WCMT_PART_MENU_TYPE, WcmtScreen::new, "/screens/cmt_part.json");
    }

    private WcmtClientSetup() {
    }
}
