package com.nnpg.glazed.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nnpg.glazed.modules.main.IronAhRestocker;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * Runs the ah listing remover on its own, so it can be tested without waiting for a limit cooldown.
 */
public class RemoverTestCommand extends Command {
    public RemoverTestCommand() {
        super("removertest", "Runs the Iron Ah Restocker listing remover once, then stops.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            IronAhRestocker module = Modules.get().get(IronAhRestocker.class);

            if (module == null) {
                error("Iron Ah Restocker is not loaded.");
                return SINGLE_SUCCESS;
            }

            // the tick handler only runs while the module is active, so switch it on first
            if (!module.isActive()) module.toggle();

            module.startRemoverTest();
            info("Running the listing remover. It will switch itself off when the pass finishes.");

            return SINGLE_SUCCESS;
        });
    }
}
