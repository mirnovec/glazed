package com.nnpg.glazed.modules.main;

import com.mojang.blaze3d.platform.NativeImage;
import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.hud.SpotifyBoard;
import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyAPIFactory;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.model.Track;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;

public class SpotifyHud extends Module {
    private static final Identifier ART_TEXTURE = Identifier.fromNamespaceAndPath("glazed", "spotify_art");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Boolean> showAlbumArt = sgGeneral.add(new BoolSetting.Builder()
        .name("album-art")
        .description("Show the cover of the track that is playing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("progress-bar")
        .description("Show the elapsed time, total time and seek bar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEqualizer = sgGeneral.add(new BoolSetting.Builder()
        .name("equalizer")
        .description("Show the bouncing bars.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> equalizerSensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("equalizer-sensitivity")
        .description("How hard the bars react.")
        .defaultValue(0.5)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .visible(showEqualizer::get)
        .build()
    );

    private final Setting<Integer> panelWidth = sgHud.add(new IntSetting.Builder()
        .name("width")
        .description("Panel width.")
        .defaultValue(280)
        .min(200)
        .max(500)
        .sliderRange(200, 500)
        .build()
    );

    private final Setting<Integer> hudX = sgHud.add(new IntSetting.Builder()
        .name("hud-x")
        .description("Panel X. -1 parks it top right.")
        .defaultValue(-1)
        .min(-1)
        .sliderRange(-1, 4000)
        .build()
    );

    private final Setting<Integer> hudY = sgHud.add(new IntSetting.Builder()
        .name("hud-y")
        .description("Panel Y. -1 parks it top right.")
        .defaultValue(-1)
        .min(-1)
        .sliderRange(-1, 4000)
        .build()
    );

    private final Setting<Integer> scale = sgHud.add(new IntSetting.Builder()
        .name("scale")
        .description("Size of the panel and text.")
        .defaultValue(100)
        .min(70)
        .max(150)
        .sliderRange(70, 150)
        .build()
    );

    private final SpotifyBoard board = new SpotifyBoard();
    private final Object lock = new Object();

    private SpotifyAPI api;
    private Track track;
    private int position;
    private boolean playing;
    private long lastUpdate;
    private boolean started;

    private volatile BufferedImage pendingArt;
    private DynamicTexture artTexture;
    private boolean hasArt;

    public SpotifyHud() {
        super(GlazedAddon.CATEGORY, "spotify-hud", "Shows the track playing in the desktop Spotify app.");
    }

    @Override
    public void onActivate() {
        board.reset();

        if (started) return;
        started = true;

        Thread thread = new Thread(this::connect, "glazed-spotify");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onDeactivate() {
        board.reset();
    }

    private void connect() {
        try {
            api = SpotifyAPIFactory.create();
            api.registerListener(new SpotifyListener() {
                @Override
                public void onConnect() {
                }

                @Override
                public void onTrackChanged(Track changed) {
                    synchronized (lock) {
                        track = changed;
                        lastUpdate = System.currentTimeMillis();
                    }

                    if (changed != null && changed.getCoverArt() != null) pendingArt = changed.getCoverArt();
                }

                @Override
                public void onPositionChanged(int changed) {
                    synchronized (lock) {
                        position = changed;
                        lastUpdate = System.currentTimeMillis();
                    }
                }

                @Override
                public void onPlayBackChanged(boolean changed) {
                    synchronized (lock) {
                        playing = changed;
                        lastUpdate = System.currentTimeMillis();
                    }
                }

                @Override
                public void onSync() {
                }

                @Override
                public void onDisconnect(Exception exception) {
                }
            });
            api.initialize();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        uploadPendingArt();

        board.drag(hudX, hudY, panelWidth, scale);
        board.render(event.graphics, snapshot(), hasArt && showAlbumArt.get() ? ART_TEXTURE : null,
            hudX, hudY, panelWidth, scale,
            showAlbumArt.get(), showProgress.get(), showEqualizer.get(), equalizerSensitivity.get());
    }

    private SpotifyBoard.Track snapshot() {
        synchronized (lock) {
            if (track == null) return SpotifyBoard.Track.idle();

            int duration = track.getLength() / 1000;
            long elapsed = playing ? System.currentTimeMillis() - lastUpdate : 0L;
            int seconds = position / 1000;

            if (playing) seconds = Math.min(seconds + (int) (elapsed / 1000), duration);

            String title = track.getName() == null ? "" : track.getName();
            String artist = track.getArtist() == null ? "" : track.getArtist();

            return new SpotifyBoard.Track(title, artist, seconds, duration, playing, position + elapsed);
        }
    }

    private void uploadPendingArt() {
        BufferedImage image = pendingArt;
        if (image == null) return;

        pendingArt = null;

        try {
            int width = image.getWidth();
            int height = image.getHeight();
            NativeImage native0 = new NativeImage(width, height, false);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;

                    native0.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }

            if (artTexture != null) {
                mc.getTextureManager().release(ART_TEXTURE);
                artTexture.close();
            }

            artTexture = new DynamicTexture(() -> "glazed-spotify-art", native0);
            mc.getTextureManager().register(ART_TEXTURE, artTexture);
            hasArt = true;
        } catch (Throwable ignored) {
            hasArt = false;
        }
    }
}
