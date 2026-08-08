package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.stream.StreamSupport;

public class DrownedTridentESP extends Module {

    private final SettingGroup sgRender = settings.createGroup("Rendering");

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color")
            .description("Color of the ESP box and lines.")
            .defaultValue(new SettingColor(50, 255, 100, 200))
            .build());

    private final Setting<Double> lineWidth = sgRender.add(new DoubleSetting.Builder()
            .name("line-width")
            .description("Thickness of the outline lines.")
            .defaultValue(1.5)
            .min(0.5)
            .sliderRange(0.5, 5)
            .build());

    private final Setting<RenderMode> mode = sgRender.add(new EnumSetting.Builder<RenderMode>()
            .name("mode")
            .description("How the ESP should be rendered.")
            .defaultValue(RenderMode.Both)
            .build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
            .name("tracers")
            .description("Draw tracers from the player to Drowned holding tridents.")
            .defaultValue(true)
            .build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
            .name("tracer-color")
            .description("Color of the tracers.")
            .defaultValue(new SettingColor(255, 50, 50, 200))
            .visible(tracers::get)
            .build());

    public enum RenderMode {
        Lines,
        Box,
        Both
    }

    public DrownedTridentESP() {
        super(GlazedAddon.esp, "drowned-trident-esp", "Highlights Drowned mobs holding tridents with optional tracers.");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Vec3 playerPos = mc.player.getPosition(event.tickDelta);

        StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(entity -> entity instanceof Drowned)
                .map(entity -> (Drowned) entity)
                .filter(this::isHoldingTrident)
                .forEach(drowned -> {
                    AABB box = drowned.getBoundingBox();
                    Color fillColor = new Color(color.get());
                    Color lineColor = new Color(color.get());

                    if (mode.get() == RenderMode.Box || mode.get() == RenderMode.Both) {
                        event.renderer.box(box, fillColor, lineColor, ShapeMode.Sides, 0);
                    }

                    if (mode.get() == RenderMode.Lines || mode.get() == RenderMode.Both) {
                        event.renderer.box(box, fillColor, lineColor, ShapeMode.Lines, 0);
                    }

                    if (tracers.get()) {
                        Vec3 entityPos = drowned.position().add(0, drowned.getBbHeight() / 2.0, 0);
                        Vec3 startPos;

                        if (mc.options.getCameraType().isFirstPerson()) {
                            Vec3 lookDir = mc.player.getLookAngle();
                            startPos = playerPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0)
                                    .add(lookDir.scale(0.5));
                        } else {
                            startPos = playerPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
                        }

                        Color tracerCol = new Color(tracerColor.get());
                        event.renderer.line(startPos.x, startPos.y, startPos.z,
                                entityPos.x, entityPos.y, entityPos.z, tracerCol);
                    }
                });
    }

    private boolean isHoldingTrident(Drowned drowned) {
        return drowned.getItemInHand(InteractionHand.MAIN_HAND).getItem() == Items.TRIDENT ||
               drowned.getItemInHand(InteractionHand.OFF_HAND).getItem() == Items.TRIDENT;
    }
}
