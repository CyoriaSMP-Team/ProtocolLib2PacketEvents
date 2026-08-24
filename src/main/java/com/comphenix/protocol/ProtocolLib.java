/*
 * ProtocolLib2PacketEvents (P2P) - a drop-in ProtocolLib compatibility layer
 * powered by PacketEvents.
 *
 * Copyright (C) 2026 CyoriaSMP Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.comphenix.protocol;

import com.comphenix.protocol.error.BasicErrorReporter;
import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.error.ReportType;
import com.comphenix.protocol.injector.AsynchronousManagerImpl;
import com.comphenix.protocol.injector.PacketManagerImpl;
import com.comphenix.protocol.injector.ListenerManager;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector;
import com.comphenix.protocol.reflect.ObjectAllocator;
import com.comphenix.protocol.scheduler.DefaultScheduler;
import com.comphenix.protocol.scheduler.ProtocolScheduler;
import com.comphenix.protocol.metrics.Statistics;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.comphenix.protocol.updater.Updater;

/**
 * Main plugin class. On enable this hooks a single internal listener into PacketEvents (already
 * initialized by the separate PacketEvents plugin this one depends on) and fans every packet out
 * to whatever ProtocolLib-style {@link com.comphenix.protocol.events.PacketListener}s other
 * plugins registered through {@link ProtocolLibrary#getProtocolManager()}.
 */
public class ProtocolLib extends JavaPlugin implements Listener {
    public static final ReportType REPORT_CANNOT_DELETE_CONFIG = new ReportType("Cannot delete configuration.");
    public static final ReportType REPORT_PLUGIN_LOAD_ERROR = new ReportType("Cannot load plugin.");
    public static final ReportType REPORT_CANNOT_LOAD_CONFIG = new ReportType("Cannot load configuration.");
    public static final ReportType REPORT_PLUGIN_ENABLE_ERROR = new ReportType("Cannot enable plugin.");
    public static final ReportType REPORT_METRICS_IO_ERROR = new ReportType("Cannot write metrics.");
    public static final ReportType REPORT_METRICS_GENERIC_ERROR = new ReportType("Metrics failure.");
    public static final ReportType REPORT_CANNOT_PARSE_MINECRAFT_VERSION = new ReportType("Cannot parse Minecraft version.");
    public static final ReportType REPORT_CANNOT_REGISTER_COMMAND = new ReportType("Cannot register command.");
    public static final ReportType REPORT_CANNOT_CREATE_TIMEOUT_TASK = new ReportType("Cannot create timeout task.");
    public static final ReportType REPORT_CANNOT_UPDATE_PLUGIN = new ReportType("Cannot update plugin.");
    private enum ProtocolCommand { PROTOCOL, FILTER, PACKET, LOGGING }

    private PacketManagerImpl protocolManager;
    private AsynchronousManagerImpl asynchronousManager;
    private PacketListenerAbstract internalListener;
    private ProtocolConfig protocolConfig;
    private ProtocolScheduler scheduler;
    private Statistics statistics;
    private NetworkManagerInjector networkManagerInjector;

    public ProtocolLib() { }

    public void onLoad() { }

    @Override public void reloadConfig() {
        super.reloadConfig();
        if (protocolConfig != null) protocolConfig.reloadConfig();
    }

    @Override
    public void onEnable() {
        ProtocolLogger.init(this);
        ErrorReporter errorReporter = new BasicErrorReporter(getLogger());
        this.protocolManager = new PacketManagerImpl(errorReporter);
        this.asynchronousManager = new AsynchronousManagerImpl(errorReporter);
        protocolManager.setAsynchronousManager(asynchronousManager);
        this.networkManagerInjector = new NetworkManagerInjector(this, (ListenerManager) protocolManager,
                errorReporter);

        this.protocolConfig = new ProtocolConfig(this);
        this.scheduler = new DefaultScheduler(this);
        ProtocolLibrary.init(this, protocolConfig, protocolManager, scheduler, errorReporter);
        ProtocolLibrary.attachRuntime(asynchronousManager, getDescription().getVersion());

        // Bukkit command executors are wired after the manager exists. The updater is metadata-
        // only by default and never replaces a running jar without an explicit deployment step.
        Updater updater = Updater.create(this, -1,
                new java.io.File(getDataFolder(), getFile().getName()),
                Updater.UpdateType.NO_DOWNLOAD, false);
        CommandFilter commandFilter = new CommandFilter(errorReporter, this, protocolConfig);
        if (getCommand("protocol") != null) {
            getCommand("protocol").setExecutor(new CommandProtocol(errorReporter, this, updater, protocolConfig));
        }
        if (getCommand("packet") != null) {
            getCommand("packet").setExecutor(new CommandPacket(errorReporter, this, getLogger(), commandFilter, protocolManager));
        }
        if (getCommand("filter") != null) getCommand("filter").setExecutor(commandFilter);
        if (getCommand("packetlog") != null) getCommand("packetlog").setExecutor(new PacketLogging(this, protocolManager));

        this.internalListener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                protocolManager.dispatchReceive(event);
            }

            @Override
            public void onPacketSend(PacketSendEvent event) {
                protocolManager.dispatchSend(event);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(internalListener);
        getServer().getPluginManager().registerEvents(this, this);
        networkManagerInjector.inject();
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            injectPlayerChannel(player);
        }

        int packetTypeCount = 0;
        for (PacketType ignored : PacketType.values()) packetTypeCount++;
        getLogger().info("Hooked into PacketEvents " + PacketEvents.getAPI().getVersion()
                + " - bridged " + packetTypeCount + " packet types"
                + " (packet allocation via " + ObjectAllocator.getStrategy() + ").");

        if (packetTypeCount == 0) {
            getLogger().severe("The PacketType registry is EMPTY. PacketEvents' internal class layout may have "
                    + "changed in a way this plugin's reflection-based registry builder does not handle. "
                    + "Every packet-type based listener registration will silently match nothing.");
        }
        if (!ObjectAllocator.isAvailable()) {
            getLogger().warning("No constructor-bypassing allocation strategy is available on this JVM. "
                    + "ProtocolManager.createPacket(...) will fail; intercepting and editing existing packets "
                    + "still works.");
        }
    }

    public Statistics getStatistics() {
        if (statistics == null) {
            try { statistics = new Statistics(this); }
            catch (java.io.IOException error) { getLogger().log(java.util.logging.Level.WARNING, "Cannot initialize statistics", error); }
        }
        return statistics;
    }
    public ProtocolConfig getProtocolConfig() { return protocolConfig; }
    public ProtocolScheduler getScheduler() { return scheduler; }

    /** Releases a disconnecting player's async execution lane so it is not retained. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayerChannel(event.getPlayer());
    }

    private void injectPlayerChannel(org.bukkit.entity.Player player) {
        if (networkManagerInjector == null || player == null) return;
        try {
            Injector injector = networkManagerInjector.getInjector(player);
            injector.inject();
        } catch (Throwable error) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Unable to install P2P direct Netty fallback for " + player.getName(), error);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (asynchronousManager != null) {
            asynchronousManager.releasePlayer(event.getPlayer().getUniqueId());
        }
        if (networkManagerInjector != null) {
            networkManagerInjector.invalidate(event.getPlayer());
        }
    }

    @Override
    public void onDisable() {
        if (internalListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(internalListener);
        }
        if (asynchronousManager != null) {
            asynchronousManager.shutdown();
        }
        if (networkManagerInjector != null) {
            networkManagerInjector.close();
            networkManagerInjector = null;
        }
        ProtocolLibrary.shutdown();
    }
}
