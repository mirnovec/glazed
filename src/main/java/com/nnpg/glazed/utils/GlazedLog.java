package com.nnpg.glazed.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only text log under .minecraft/logs, for working out what a module actually did rather than
 * what it looked like it did. Chat is useless for this - it truncates, it scrolls away, and half the
 * interesting lines land inside a tick you were not watching.
 *
 * Writes are synchronised because packet events arrive on the network thread, not the tick thread.
 */
public class GlazedLog {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path file;
    private Writer writer;

    public GlazedLog(String name) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("logs");
        this.file = dir.resolve("glazed-" + name + ".log");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
    }

    public Path path() {
        return file;
    }

    public synchronized boolean isOpen() {
        return writer != null;
    }

    public synchronized void open(String header) {
        close();
        try {
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            line("======== %s ========", header);
        } catch (IOException e) {
            writer = null;
        }
    }

    public synchronized void line(String format, Object... args) {
        if (writer == null) return;
        try {
            writer.write(LocalDateTime.now().format(STAMP));
            writer.write(' ');
            writer.write(args.length == 0 ? format : String.format(format, args));
            writer.write('\n');
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    public synchronized void close() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
    }
}
