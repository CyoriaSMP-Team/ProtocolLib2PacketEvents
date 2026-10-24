package com.comphenix.protocol.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import java.util.Objects;

/** Folia global/async scheduling for plugin-wide work with no world or entity access.
 * Player/world operations still require scheduling against the owning region. */
public final class FoliaScheduler implements ProtocolScheduler {
    private final Plugin plugin;
    public FoliaScheduler(Plugin plugin) { this.plugin = Objects.requireNonNull(plugin); }
    private java.util.function.Consumer<ScheduledTask> action(Runnable task) {
        Objects.requireNonNull(task, "task");
        return scheduled -> task.run();
    }
    private Task wrap(ScheduledTask task) { return () -> task.cancel(); }
    @Override public Task scheduleSyncRepeatingTask(Runnable task, long delay, long period) {
        return wrap(plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin, action(task), Math.max(1, delay), Math.max(1, period)));
    }
    @Override public Task runTask(Runnable task) {
        return wrap(plugin.getServer().getGlobalRegionScheduler().run(plugin, action(task)));
    }
    @Override public Task scheduleSyncDelayedTask(Runnable task, long delay) {
        return delay <= 0 ? runTask(task) : wrap(plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, action(task), delay));
    }
    @Override public Task runTaskAsync(Runnable task) {
        return wrap(plugin.getServer().getAsyncScheduler().runNow(plugin, action(task)));
    }
}
