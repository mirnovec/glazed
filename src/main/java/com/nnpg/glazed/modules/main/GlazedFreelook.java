package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;

public class GlazedFreelook extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> seeThroughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Let the camera pass through blocks instead of pulling in against them.")
        .defaultValue(false)
        .build()
    );

    public float yaw;
    public float pitch;
    public float previousYaw;
    public float previousPitch;

    private CameraType savedPerspective;

    public GlazedFreelook() {
        super(GlazedAddon.CATEGORY, "glazed-freelook", "Look around without turning the way you walk.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.options == null) {
            toggle();
            return;
        }

        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        previousYaw = yaw;
        previousPitch = pitch;

        savedPerspective = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    @Override
    public void onDeactivate() {
        restoreView();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        restoreView();
        toggle();
    }

    private void restoreView() {
        if (mc.options == null) return;

        mc.options.setCameraType(savedPerspective != null ? savedPerspective : CameraType.FIRST_PERSON);
    }

    public boolean allowCameraNoClip() {
        return seeThroughWalls.get();
    }

    public void updateRotation(double deltaYaw, double deltaPitch) {
        previousYaw = yaw;
        previousPitch = pitch;

        yaw += (float) deltaYaw;
        pitch += (float) deltaPitch;

        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.clamp(pitch, -90.0f, 90.0f);
    }

    public double getInterpolatedYaw(float partialTicks) {
        return yaw;
    }

    public double getInterpolatedPitch(float partialTicks) {
        return pitch;
    }
}
