package com.nnpg.glazed.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nnpg.glazed.modules.esp.DebrisLeakESP;
import com.nnpg.glazed.modules.esp.NetheriteFinder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class DebrisCommand extends Command {

    public DebrisCommand() {
        super("debris", "Dumps what the server actually reports at every predicted ancient debris position.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            NetheriteFinder finder = Modules.get().get(NetheriteFinder.class);

            if (finder == null) {
                error("Module not found.");
                return SINGLE_SUCCESS;
            }

            info("%s", finder.diagnose());
            return SINGLE_SUCCESS;
        });

        builder.then(literal("scan").executes(context -> {
            NetheriteFinder f = Modules.get().get(NetheriteFinder.class);
            if (f == null) { error("Module not found."); return SINGLE_SUCCESS; }
            info("%s", f.scanIndices());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("leakscan").executes(context -> {
            NetheriteFinder f = Modules.get().get(NetheriteFinder.class);
            if (f == null) { error("Module not found."); return SINGLE_SUCCESS; }
            info("%s", f.leakScan(6));
            return SINGLE_SUCCESS;
        }).then(argument("radius", IntegerArgumentType.integer(1, 16)).executes(context -> {
            NetheriteFinder f = Modules.get().get(NetheriteFinder.class);
            if (f == null) { error("Module not found."); return SINGLE_SUCCESS; }
            info("%s", f.leakScan(IntegerArgumentType.getInteger(context, "radius")));
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("index").executes(context -> {
            NetheriteFinder f = Modules.get().get(NetheriteFinder.class);
            if (f == null) { error("Module not found."); return SINGLE_SUCCESS; }
            info("%s", f.scanDebrisIndices());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("forget").executes(context -> {
            DebrisLeakESP leak = Modules.get().get(DebrisLeakESP.class);
            if (leak == null) { error("Module not found."); return SINGLE_SUCCESS; }
            info("%s", leak.forgetDone());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("verify").executes(context -> {
            NetheriteFinder finder = Modules.get().get(NetheriteFinder.class);

            if (finder == null) {
                error("Module not found.");
                return SINGLE_SUCCESS;
            }

            info("%s", finder.verifyAgainstVisibleOre());
            return SINGLE_SUCCESS;
        }));
    }
}
