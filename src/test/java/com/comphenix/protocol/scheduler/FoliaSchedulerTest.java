package com.comphenix.protocol.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FoliaSchedulerTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test
    void usesFoliaGlobalAndAsyncSchedulersWithCancellableTasks() {
        AtomicReference<String> call = new AtomicReference<>();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ScheduledTask scheduled = proxy(ScheduledTask.class, (p, method, args) -> {
            if (method.getName().equals("cancel")) { cancelled.incrementAndGet(); return null; }
            throw new AssertionError("Unexpected scheduled-task call: " + method.getName());
        });
        GlobalRegionScheduler global = proxy(GlobalRegionScheduler.class, (p, method, args) -> {
            call.set("global:" + method.getName());
            assertTrue(args[1] instanceof Consumer<?>);
            ((Consumer<ScheduledTask>) args[1]).accept(scheduled);
            return scheduled;
        });
        AsyncScheduler async = proxy(AsyncScheduler.class, (p, method, args) -> {
            call.set("async:" + method.getName());
            assertTrue(args[1] instanceof Consumer<?>);
            ((Consumer<ScheduledTask>) args[1]).accept(scheduled);
            return scheduled;
        });
        Server server = proxy(Server.class, (p, method, args) -> switch (method.getName()) {
            case "getGlobalRegionScheduler" -> global;
            case "getAsyncScheduler" -> async;
            // Fail when any legacy CraftScheduler entrypoint is accessed.
            case "getScheduler" -> throw new AssertionError("Legacy Bukkit scheduler used on Folia");
            default -> throw new AssertionError("Unexpected server call: " + method.getName());
        });
        Plugin plugin = proxy(Plugin.class, (p, method, args) -> {
            if (method.getName().equals("getServer")) return server;
            throw new AssertionError("Unexpected plugin call: " + method.getName());
        });
        FoliaScheduler scheduler = new FoliaScheduler(plugin);

        Task once = scheduler.runTask(ran::incrementAndGet);
        assertEquals("global:run", call.get());
        Task delayed = scheduler.scheduleSyncDelayedTask(ran::incrementAndGet, 3);
        assertEquals("global:runDelayed", call.get());
        Task repeating = scheduler.scheduleSyncRepeatingTask(ran::incrementAndGet, 1, 20);
        assertEquals("global:runAtFixedRate", call.get());
        Task immediate = scheduler.runTaskAsync(ran::incrementAndGet);
        assertEquals("async:runNow", call.get());
        assertEquals(4, ran.get());

        once.cancel(); delayed.cancel(); repeating.cancel(); immediate.cancel();
        assertEquals(4, cancelled.get());
    }

}
