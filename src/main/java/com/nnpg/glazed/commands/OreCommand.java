package com.nnpg.glazed.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nnpg.glazed.modules.esp.OverworldOreFinder;
import com.nnpg.glazed.utils.DonutRegion;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.util.function.Function;

/**
 * Seed diagnostics for {@link OverworldOreFinder}. Run verify before trusting anything the module
 * draws: it is the only measurement here that does not need you to mine.
 */
public class OreCommand extends Command {

    public OreCommand() {
        super("ore", "Checks the overworld ore predictions against blocks anti-xray does not hide.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> run(finder -> finder.verifyAgainstVisibleOre(false)));

        builder.then(literal("verify")
            .executes(context -> run(finder -> finder.verifyAgainstVisibleOre(false)))
            .then(literal("all").executes(context -> run(finder -> finder.verifyAgainstVisibleOre(true)))));

        builder.then(literal("scan").executes(context -> run(OverworldOreFinder::scanIndices)));
        builder.then(literal("table").executes(context -> run(OverworldOreFinder::table)));

        builder.then(literal("seed")
            .executes(context -> run(OreCommand::seedStatus))
            .then(argument("value", StringArgumentType.word()).executes(context -> {
                String value = StringArgumentType.getString(context, "value");
                return run(finder -> finder.storeSeed(finder.activeRegion(), value));
            })));

        builder.then(literal("region")
            .executes(context -> run(OreCommand::seedStatus))
            .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                String name = StringArgumentType.getString(context, "name");
                DonutRegion region = DonutRegion.byLabel(name);

                if (region == null) {
                    error("Unknown region \"%s\". Known: %s", name, DonutRegion.labels());
                    return SINGLE_SUCCESS;
                }

                DonutRegion.set(region);
                return run(finder -> "Region set to " + region.label + ". " + seedStatus(finder));
            })));
    }

    private static String seedStatus(OverworldOreFinder finder) {
        DonutRegion region = finder.activeRegion();

        if (region == DonutRegion.UNKNOWN) {
            return "Region unknown, using the fallback seed. /rtp to a region, or run .ore region <"
                + DonutRegion.labels() + ">.";
        }

        return finder.seedIsPerRegion()
            ? "Region " + region.label + " has its own seed."
            : "Region " + region.label + " has no seed yet, falling back to the seed setting. "
              + "Set one with .ore seed <number> once you have cracked it.";
    }

    private int run(Function<OverworldOreFinder, String> action) {
        OverworldOreFinder finder = Modules.get().get(OverworldOreFinder.class);

        if (finder == null) {
            error("Module not found.");
            return SINGLE_SUCCESS;
        }

        String result;

        // a diagnostic that dies silently is worse than no diagnostic, so nothing gets swallowed
        try {
            result = action.apply(finder);
        } catch (Throwable t) {
            error("Diagnostic failed: %s", t);
            t.printStackTrace();
            return SINGLE_SUCCESS;
        }

        if (result == null || result.isBlank()) {
            error("Diagnostic returned nothing, which is a bug.");
            return SINGLE_SUCCESS;
        }

        for (String line : result.split("\n")) {
            if (!line.isBlank()) info("%s", line);
        }

        return SINGLE_SUCCESS;
    }
}
