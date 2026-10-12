package com.wcmt.part;

import com.wcmt.WcmtMod;
import com.wcmt.init.ModMenus;

import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.parts.PartModels;
import appeng.api.util.IConfigManagerBuilder;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;
import com.wcmt.menu.WcmtMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/**
 * 元件管理终端的线缆版本。
 *
 * <p>继承 AE2 的终端部件即可获得线缆终端所需的一切：它需要频道、
 * 拥有自己的链接状态，并以自身为宿主打开菜单。这里只需提供菜单类型和前面板。
 */
public class WcmtTerminalPart extends AbstractTerminalPart {

    /** 本部件自己的前面板，绘制在共用的外壳与状态指示灯之上。 */
    private static final ResourceLocation MODEL_OFF = ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID,
            "part/cmt_terminal_off");
    private static final ResourceLocation MODEL_ON = ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID,
            "part/cmt_terminal_on");

    // AE2 用三个叠加的模型组装显示部件：共用的外壳、状态指示灯之一，以及部件自己的面板。
    // 基类部件会根据电力与频道状态从这三个之中选一个。
    private static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_STATUS_OFF, MODEL_OFF);
    private static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_STATUS_ON, MODEL_ON);
    private static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_STATUS_HAS_CHANNEL,
            MODEL_ON);

    public WcmtTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    /**
     * 把本部件的模型交给 AE2，AE2 会维护一张模型表，并拒绝渲染模型不在表中的部件。
     * 这必须在模型表冻结之前执行，因此由模组构造器调用，而不是在本类初始化时惰性调用。
     */
    public static void registerModels() {
        PartModels.registerModels(MODEL_OFF, MODEL_ON);
    }

    @Override
    protected void registerSettings(IConfigManagerBuilder builder) {
        super.registerSettings(builder);
        // 排序键由我们自己定义；AE2 自己的 SORT_DIRECTION 由父类注册。
        builder.registerSetting(WcmtMenu.SORT_MODE, WcmtMenu.SortMode.POSITION);
    }

    @Override
    public MenuType<?> getMenuType(Player player) {
        return ModMenus.WCMT_PART_MENU_TYPE;
    }

    @Override
    public IPartModel getStaticModels() {
        return selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL);
    }
}
