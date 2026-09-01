package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BedrockLogger extends Module {

    private static final Path FILE = Minecraft.getInstance().gameDirectory.toPath()
        .resolve("glazed").resolve("nether_bedrock.txt");

    private static final Set<Long> logged = new HashSet<>();
    private static boolean fileRead = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLayers = settings.createGroup("Layers");

    private final Setting<Boolean> onlyExposed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-exposed")
        .description("Only log blocks with an air or fluid face. Anti-xray fakes buried blocks, so turning this off can feed the cracker garbage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logOther = sgGeneral.add(new BoolSetting.Builder()
        .name("log-other")
        .description("Also log where bedrock is NOT. Knowing a spot is empty narrows the seed just as hard as knowing it is bedrock.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> announceEvery = sgGeneral.add(new IntSetting.Builder()
        .name("announce-every")
        .description("Print a running total to chat every this many new points. 0 disables it.")
        .defaultValue(25)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> scanFloor = sgLayers.add(new BoolSetting.Builder()
        .name("scan-floor")
        .description("Sample the bedrock floor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> floorY = sgLayers.add(new IntSetting.Builder()
        .name("floor-y")
        .description("Floor layer to sample. 4 is the rarest, so it carries the most information per block.")
        .defaultValue(4)
        .min(1)
        .max(4)
        .sliderRange(1, 4)
        .visible(scanFloor::get)
        .build()
    );

    private final Setting<Boolean> scanRoof = sgLayers.add(new BoolSetting.Builder()
        .name("scan-roof")
        .description("Sample the bedrock roof.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> roofY = sgLayers.add(new IntSetting.Builder()
        .name("roof-y")
        .description("Roof layer to sample. 123 is the rarest, so it carries the most information per block.")
        .defaultValue(123)
        .min(123)
        .max(126)
        .sliderRange(123, 126)
        .visible(scanRoof::get)
        .build()
    );

    private final List<String> pending = new ArrayList<>();
    private final Set<Long> scannedChunks = new HashSet<>();

    private int sinceAnnounce;
    private int tickCounter;

    public BedrockLogger() {
        super(GlazedAddon.CATEGORY, "bedrock-logger", "Logs nether bedrock so the world seed can be cracked from it. Fly around the nether with this on.");
    }

    @Override
    public void onActivate() {
        if (mc.level == null || mc.player == null) {
            error("Join a server first.");
            toggle();
            return;
        }

        if (PlayerUtils.getDimension() != Dimension.Nether) {
            error("Only works in the nether, the overworld uses a different RNG.");
            toggle();
            return;
        }

        readFile();
        sinceAnnounce = 0;
        tickCounter = 0;
        scannedChunks.clear();

        info("Logging to (highlight)%s(default).", FILE);
        info("Have (highlight)%d(default) points. Fly the floor and the roof, ~40 spread out is usually enough.", logged.size());

        scanLoadedChunks();
    }

    @Override
    public void onDeactivate() {
        info("Stopped at (highlight)%d(default) points.", logged.size());
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        scanChunk(event.chunk());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++tickCounter < 40) return;
        tickCounter = 0;

        scanLoadedChunks();

        rescanNearby();
    }

    private void scanLoadedChunks() {
        if (mc.level == null) return;

        for (ChunkAccess chunk : Utils.chunks(false)) {
            if (scannedChunks.contains(chunk.getPos().pack())) continue;
            scanChunk(chunk);
        }
    }

    private void rescanNearby() {
        if (mc.level == null || mc.player == null) return;

        ChunkPos center = mc.player.chunkPosition();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkAccess chunk = mc.level.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false);
                if (chunk != null) scanChunk(chunk);
            }
        }
    }

    private void scanChunk(ChunkAccess chunk) {
        if (mc.level == null || chunk == null) return;
        if (PlayerUtils.getDimension() != Dimension.Nether) return;

        scannedChunks.add(chunk.getPos().pack());

        int before = logged.size();

        if (scanFloor.get()) scanLayer(chunk, floorY.get());
        if (scanRoof.get()) scanLayer(chunk, roofY.get());

        flush();

        int added = logged.size() - before;
        if (added > 0 && announceEvery.get() > 0) {
            sinceAnnounce += added;
            if (sinceAnnounce >= announceEvery.get()) {
                sinceAnnounce = 0;
                info("(highlight)%d(default) bedrock points logged.", logged.size());
            }
        }
    }

    private void scanLayer(ChunkAccess chunk, int y) {
        if (y < mc.level.getMinY() || y > mc.level.getMaxY()) return;

        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                pos.set(baseX + dx, y, baseZ + dz);

                if (logged.contains(pos.asLong())) continue;

                BlockState state = chunk.getBlockState(pos);
                boolean bedrock = state.is(Blocks.BEDROCK);

                if (!bedrock && !logOther.get()) continue;
                if (onlyExposed.get() && !isExposed(pos)) continue;

                write(pos.getX(), pos.getY(), pos.getZ(), bedrock);
            }
        }
    }

    private boolean isExposed(BlockPos pos) {
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();

        for (Direction dir : Direction.values()) {
            side.set(pos).move(dir);

            if (side.getY() < mc.level.getMinY() || side.getY() > mc.level.getMaxY()) continue;

            if (!mc.level.isLoaded(side)) continue;

            if (!mc.level.getBlockState(side).canOcclude()) return true;
        }

        return false;
    }

    private void write(int x, int y, int z, boolean bedrock) {
        pending.add(x + " " + y + " " + z + " " + (bedrock ? "Bedrock" : "Other"));
        logged.add(BlockPos.asLong(x, y, z));
    }

    private void flush() {
        if (pending.isEmpty()) return;

        try {
            Files.createDirectories(FILE.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String line : pending) {
                    w.write(line);
                    w.newLine();
                }
            }
            pending.clear();
        } catch (IOException e) {

            for (String line : pending) {
                String[] parts = line.split(" ");
                logged.remove(BlockPos.asLong(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            }
            pending.clear();
            error("Could not write %s: %s", FILE, e.getMessage());
            toggle();
        }
    }

    private static void readFile() {
        if (fileRead) return;
        fileRead = true;

        if (!Files.exists(FILE)) return;

        try {
            List<String> lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3) continue;

                try {
                    logged.add(BlockPos.asLong(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {

                }
            }
        } catch (IOException ignored) {

        }
    }

    public static Path file() {
        return FILE;
    }

    public static int count() {
        readFile();
        return logged.size();
    }

    public static void clear() throws IOException {
        Files.deleteIfExists(FILE);
        logged.clear();
        fileRead = true;
    }
}
