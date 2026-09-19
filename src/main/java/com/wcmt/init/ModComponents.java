package com.wcmt.init;

import com.wcmt.WcmtMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;

/**
 * UUID of the player the terminal is owned by. Used to enforce the SELF/TEAM operation permission.
 * Absent means "not yet claimed"; the first player to open the terminal becomes the owner.
 */
public final class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WcmtMod.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> OWNER =
            DATA_COMPONENTS.register("owner", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    private ModComponents() {
    }
}
