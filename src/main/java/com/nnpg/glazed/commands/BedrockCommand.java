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

            info("(highlight)%d(default) bedrock point%s logged.", count, count == 1 ? "" : "s");
            info("File: (highlight)%s", BedrockLogger.file());

            if (count < 40) info("Around 40 spread across the floor and the roof is usually enough to crack a seed.");
            else info("That should be enough to try a crack.");

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
