package com.wcmt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WcmtConfig {
    public enum Permission {
        /** Only the player the terminal is bound to. */
        SELF,
        /** The owner and members of the owner's scoreboard team. */
        TEAM,
        /** Anyone. */
        ALL
    }

    public static final ModConfigSpec.BooleanValue ALLOW_EXTRACT;
    public static final ModConfigSpec.BooleanValue ALLOW_INSERT;
    public static final ModConfigSpec.EnumValue<Permission> PERMISSION;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment("Wireless Component Management Terminal").push("general");

        ALLOW_EXTRACT = builder
                .comment("Allow removing storage cells from ME Drives through the terminal.")
                .define("allowExtract", true);
        ALLOW_INSERT = builder
                .comment("Allow inserting storage cells into ME Drives through the terminal.")
                .define("allowInsert", true);
        PERMISSION = builder
                .comment("Who may operate a terminal: SELF (owner only), TEAM (owner's team), ALL (anyone).")
                .defineEnum("permission", Permission.SELF);
        SCAN_INTERVAL_TICKS = builder
                .comment("How often (in ticks) the terminal rescans the ME network for drives while open.")
                .defineInRange("scanIntervalTicks", 10, 1, 200);

        builder.pop();
        SPEC = builder.build();
    }

    private WcmtConfig() {
    }
}
