package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

public class InvisESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgMobs = settings.createGroup("Mobs");
    
    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box should be rendered")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    
    private final Setting<Double> lineWidth = sgGeneral.add(new DoubleSetting.Builder()
        .name("line-width")
        .description("Thickness of the box lines")
        .defaultValue(2.0)
        .min(0.1)
        .max(10.0)
        .sliderMax(5.0)
        .visible(() -> shapeMode.get() != ShapeMode.Sides)
        .build()
    );
    
    private final Setting<Double> fadeDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("fade-distance")
        .description("Distance at which boxes start to fade (0 = no fade)")
        .defaultValue(0.0)
        .min(0.0)
        .max(100.0)
        .sliderMax(50.0)
        .build()
    );
    
    private final Setting<Boolean> onlyInvis = sgGeneral.add(new BoolSetting.Builder()
        .name("only-invisible")
        .description("Only show hitboxes for invisible entities")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder().name("notifications").description("Show chat feedback.").defaultValue(true).build());
    
    private final Setting<Boolean> showPlayers = sgPlayers.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Show hitboxes for players")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<SettingColor> playerColor = sgPlayers.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Color of player hitboxes")
        .defaultValue(new SettingColor(255, 0, 0, 100))
        .visible(showPlayers::get)
        .build()
    );
    
    private final Setting<SettingColor> playerLineColor = sgPlayers.add(new ColorSetting.Builder()
        .name("player-line-color")
        .description("Color of player hitbox lines")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> showPlayers.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );
    
    private final Setting<Boolean> showSelf = sgPlayers.add(new BoolSetting.Builder()
        .name("show-self")
        .description("Show your own hitbox when invisible")
        .defaultValue(false)
        .visible(showPlayers::get)
        .build()
    );
    
    private final Setting<Boolean> showMobs = sgMobs.add(new BoolSetting.Builder()
        .name("show-mobs")
        .description("Show hitboxes for mobs")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<SettingColor> mobColor = sgMobs.add(new ColorSetting.Builder()
        .name("mob-color")
        .description("Color of mob hitboxes")
        .defaultValue(new SettingColor(0, 255, 0, 100))
        .visible(showMobs::get)
        .build()
    );
    
    private final Setting<SettingColor> mobLineColor = sgMobs.add(new ColorSetting.Builder()
        .name("mob-line-color")
        .description("Color of mob hitbox lines")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(() -> showMobs.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );
    
    private final Setting<Boolean> showPassive = sgMobs.add(new BoolSetting.Builder()
        .name("show-passive")
        .description("Show hitboxes for passive mobs (animals)")
        .defaultValue(false)
        .visible(showMobs::get)
        .build()
    );
    
    private final Setting<Boolean> showHostile = sgMobs.add(new BoolSetting.Builder()
        .name("show-hostile")
        .description("Show hitboxes for hostile mobs")
        .defaultValue(true)
        .visible(showMobs::get)
        .build()
    );

    public InvisESP() {
        super(GlazedAddon.esp, "invis-esp", "Shows 3D hitbox for invisible players and mobs");
    }

    @Override
    public void onActivate() {
        if (notifications.get()) info("Invisible hitboxes will now be displayed");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity livingEntity)) continue;
            
            if (entity == mc.player && !showSelf.get()) continue;
            
            if (onlyInvis.get() && !entity.isInvisible()) continue;
            
            if (entity instanceof Player) {
                if (!showPlayers.get()) continue;
                renderHitbox(event, entity, playerColor.get(), playerLineColor.get());
            }
            else if (entity instanceof Mob mob) {
                if (!showMobs.get()) continue;
                
                boolean isHostile = mob.isAggressive() || mob.getTarget() != null;
                if (isHostile && !showHostile.get()) continue;
                if (!isHostile && !showPassive.get()) continue;
                
                renderHitbox(event, entity, mobColor.get(), mobLineColor.get());
            }
        }
    }

    private void renderHitbox(Render3DEvent event, Entity entity, SettingColor fillColor, SettingColor lineColor) {
        AABB box = entity.getBoundingBox();
        
        double distance = mc.player.distanceToSqr(entity);
        double fadeStart = fadeDistance.get();
        
        Color finalFillColor = fillColor.copy();
        Color finalLineColor = lineColor.copy();
        
        if (fadeStart > 0 && distance > fadeStart * fadeStart) {
            double fadeFactor = Math.max(0, 1 - (Math.sqrt(distance) - fadeStart) / fadeStart);
            finalFillColor.a = (int) (fillColor.a * fadeFactor);
            finalLineColor.a = (int) (lineColor.a * fadeFactor);
        }
        
        event.renderer.box(
            box.minX, box.minY, box.minZ,
            box.maxX, box.maxY, box.maxZ,
            finalFillColor,
            finalLineColor,
            shapeMode.get(),
            0
        );
    }

    @Override
    public String getInfoString() {
        int count = 0;
        if (mc.level != null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity)) continue;
                if (entity == mc.player && !showSelf.get()) continue;
                if (onlyInvis.get() && !entity.isInvisible()) continue;
                
                if ((entity instanceof Player && showPlayers.get()) ||
                    (entity instanceof Mob && showMobs.get())) {
                    count++;
                }
            }
        }
        return count + " entities";
    }
}
