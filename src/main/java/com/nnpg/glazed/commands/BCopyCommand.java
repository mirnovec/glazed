package com.nnpg.glazed.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

public class BCopyCommand extends Command {
    private static final List<String> samples = new ArrayList<>();

    public BCopyCommand() {
        super("bcopy", "Copies your coords, biome and dimension to the clipboard. Repeats stack up.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            sample();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("clear").executes(context -> {
            samples.clear();
            info("Cleared the bcopy list.");
            return SINGLE_SUCCESS;
        }));
    }

    private void sample() {
        if (mc.player == null || mc.level == null) {
            error("Join a server first.");
            return;
        }

        BlockPos pos = mc.player.blockPosition();
        String line = String.format("%d %d %d | %s | %s",
            pos.getX(), pos.getY(), pos.getZ(), biomeAt(pos), mc.level.dimension().identifier());

        samples.add(line);
        mc.keyboardHandler.setClipboard(String.join("\n", samples));

        info("(highlight)%s", line);
        info("Copied %d line%s to your clipboard.", samples.size(), samples.size() == 1 ? "" : "s");
    }

    private String biomeAt(BlockPos pos) {
        try {
            Biome biome = mc.level.getBiome(pos).value();
            Identifier id = mc.level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome);
            if (id != null) return id.toString();
        } catch (Exception ignored) {

        }

        return mc.level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
    }
}
