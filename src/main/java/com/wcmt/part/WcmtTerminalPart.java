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
 * The cable-mounted variant of the component management terminal.
 *
 * <p>Extending AE2's terminal part gives it everything a cable terminal needs: it requires a channel,
 * has a link status of its own and opens a menu with itself as the host. Only the menu type and the
 * front panel have to be supplied here.
 */
public class WcmtTerminalPart extends AbstractTerminalPart {

    /** This part's own front panel, drawn over the shared chassis and status indicator. */
    private static final ResourceLocation MODEL_OFF = ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID,
            "part/cmt_terminal_off");
    private static final ResourceLocation MODEL_ON = ResourceLocation.fromNamespaceAndPath(WcmtMod.MOD_ID,
            "part/cmt_terminal_on");

    // AE2 assembles display parts from three overlapping models: the shared chassis, one of the status
    // indicators, and the part's own face. The base part picks one of these three according to power
    // and channel state.
    private static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_STATUS_OFF, MODEL_OFF);
    private static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_STATUS_ON, MODEL_ON);
    private static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_STATUS_HAS_CHANNEL,
            MODEL_ON);

    public WcmtTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    /**
     * Hands this part's models to AE2, which keeps a table of them and refuses to render a part whose
     * models are missing from it. Has to run before that table is frozen, so it is called from the mod
     * constructor rather than lazily from this class' initializer.
     */
    public static void registerModels() {
        PartModels.registerModels(MODEL_OFF, MODEL_ON);
    }

    @Override
    protected void registerSettings(IConfigManagerBuilder builder) {
        super.registerSettings(builder);
        // The ordering key is ours; AE2's own SORT_DIRECTION gets registered by the superclass.
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
