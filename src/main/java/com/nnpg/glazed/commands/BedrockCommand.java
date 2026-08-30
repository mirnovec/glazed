package com.nnpg.glazed.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nnpg.glazed.modules.main.BedrockLogger;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.io.IOException;

public class BedrockCommand extends Command {

    public BedrockCommand() {
        super("bedrock", "Shows how much nether bedrock the bedrock-logger has banked for seed cracking.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            int count = BedrockLogger.count();
            int[] split = BedrockLogger.split();

            info("Region (highlight)%s(default): (highlight)%d(default) point%s logged.",
                BedrockLogger.region().label, count, count == 1 ? "" : "s");
            info("Floor: (highlight)%d(default)   Roof: (highlight)%d", split[0], split[1]);
            info("File: (highlight)%s", BedrockLogger.file());

            // one sided data is the usual reason a crack comes back with nothing
            if (split[0] == 0 || split[1] == 0) {
                warning("Only one side collected. Get points from BOTH the floor (y0-4) and the roof (y123-127) or the crack will not narrow down.");
            } else if (count < 40) {
                info("Around 40 spread across floor and roof is usually enough. Keep going.");
            } else {
                info("That should be enough to try a crack.");
            }

            return SINGLE_SUCCESS;
        });

        builder.then(literal("path").executes(context -> {
            info("(highlight)%s", BedrockLogger.file());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            try {
                BedrockLogger.clear();
                info("Deleted the bedrock log.");
            } catch (IOException e) {
                error("Could not delete %s: %s", BedrockLogger.file(), e.getMessage());
            }
            return SINGLE_SUCCESS;
        }));
    }
}
