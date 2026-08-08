package com.nnpg.glazed.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

// one scheduler for delayed stuff
// modules used to make a brand new executor on every totem pop and never shut it down
// vro that leaks a thread every single pop
public final class GlazedScheduler {

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Glazed-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

    private GlazedScheduler() {}

    // runs on the main thread after the delay
    public static void schedule(Runnable task, long delay, TimeUnit unit) {
        SCHEDULER.schedule(() -> Minecraft.getInstance().execute(task), delay, unit);
    }

    // same but in seconds
    public static void scheduleSeconds(Runnable task, long seconds) {
        schedule(task, seconds, TimeUnit.SECONDS);
    }
}
