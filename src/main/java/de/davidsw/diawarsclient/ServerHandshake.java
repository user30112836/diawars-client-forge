package de.davidsw.diawarsclient;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Detects whether the current server runs the Diawars plugin and keeps every
 * other feature completely silent on foreign servers (mod selfdisable).
 *
 * <p>On every server join the mod sends an empty {@code diawars:handshake}
 * probe (through the loader-supplied {@link ProbeSender}); the plugin answers
 * with an empty {@code diawars:handshake_ack}. While no ack has arrived, the
 * mod sends nothing. When the ack does not arrive within
 * {@link #GRACE_MILLIS}, the mod disables itself: it stops all
 * client_info / image / report communication and ignores server signals.
 * Leaving the server resets the state; a world change (e.g. hopping into an
 * event world, or switching servers) probes again, so the mod re-enables
 * itself whenever the plugin is present.</p>
 *
 * <p>Older mod versions do not probe — the plugin never sends the ack
 * unsolicited — so they keep their old behavior unchanged.</p>
 *
 * <p>All methods run on the client thread (join/disconnect/tick events and
 * ack handling hop there via the loaders). State fields are volatile, so
 * {@link #isActive()} is also safe to call from background threads.</p>
 */
public final class ServerHandshake {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a probe may go unanswered before the mod disables itself. */
    public static final long GRACE_MILLIS = 5_000;

    enum State {
        /** Not currently connected to any server. */
        IDLE,
        /** Probe sent, no ack yet; nothing but probes may be sent. */
        WAITING_NEW,
        /** The plugin was confirmed, but a world-change probe is in flight. */
        WAITING_ACTIVE,
        /** Plugin confirmed; all features available. */
        ACTIVE,
        /** No ack in time: everything stays off until the next probe. */
        DISABLED
    }

    /** Probe sender provided by the loader (empty serverbound handshake). */
    public interface ProbeSender {
        boolean send();
    }

    /** Called on the first confirmed probe per connection (client_info send). */
    public interface ConfirmedCallback {
        void run();
    }

    private static volatile State state = State.IDLE;
    private static volatile long deadline;
    private static volatile ResourceKey<Level> lastDimension;
    private static volatile boolean confirmedOnce;
    private static ProbeSender probeSender;
    private static ConfirmedCallback confirmedCallback;

    private ServerHandshake() {
    }

    public static void init(ProbeSender probeSender, ConfirmedCallback confirmedCallback) {
        ServerHandshake.probeSender = probeSender;
        ServerHandshake.confirmedCallback = confirmedCallback;
    }

    /**
     * Client joined a server: waits for the ack. The probe itself is sent by
     * {@link #tick} on the first world sighting — one tick after the join —
     * so the client's channel registration packet is already on the wire and
     * the plugin's ack is not silently dropped.
     */
    public static void onJoined() {
        state = State.WAITING_NEW;
        deadline = System.currentTimeMillis() + GRACE_MILLIS;
        lastDimension = null;
        confirmedOnce = false;
    }

    /** The plugin's ack arrived: enable everything and notify once. */
    public static void onAck() {
        State current = state;
        if (current != State.WAITING_NEW && current != State.WAITING_ACTIVE) {
            return;
        }
        state = State.ACTIVE;
        deadline = 0;
        if (!confirmedOnce) {
            confirmedOnce = true;
            ConfirmedCallback callback = confirmedCallback;
            if (callback != null) {
                callback.run();
            }
        }
    }

    /** Client left the server: back to idle. */
    public static void onDisconnected() {
        state = State.IDLE;
        deadline = 0;
        lastDimension = null;
        confirmedOnce = false;
    }

    /**
     * Per-tick duty: sends the join probe on the first world sighting
     * (after the loader's channel registration), probes again on world
     * change and disables itself once a probe has timed out. Call on the
     * client thread every tick while connected.
     */
    public static void tick(Minecraft minecraft) {
        State current = state;
        if (current == State.IDLE) {
            return;
        }
        if (minecraft.player == null || minecraft.getConnection() == null) {
            // The disconnect event resets the state; wait for it / the player.
            return;
        }
        ResourceKey<Level> dimension = minecraft.player.level().dimension();
        ResourceKey<Level> previous = lastDimension;
        if (previous == null) {
            lastDimension = dimension;
            // First world sighting after join: the join probe goes out here,
            // one tick after the JOIN event, so the channel registration
            // packet Fabric queues behind the JOIN handlers has already been
            // sent and the plugin's ack channel is known server-side.
            requestProbe();
        } else if (!previous.equals(dimension)) {
            lastDimension = dimension;
            // Todo's retry rule: leaving for another world/server probes again.
            requestProbe();
        }
        State waiting = state;
        if ((waiting == State.WAITING_NEW || waiting == State.WAITING_ACTIVE)
                && System.currentTimeMillis() > deadline) {
            disable(minecraft);
        }
    }

    /** True while the plugin is present (or still considered present while its world-change probe runs). */
    public static boolean isActive() {
        State current = state;
        return current == State.ACTIVE || current == State.WAITING_ACTIVE;
    }

    /**
     * Sends a fresh probe. While the plugin is enabled, features stay usable
     * until the probe proves otherwise, so an event-world hop cannot flicker
     * everything off. Otherwise the mod stays fully disabled.
     */
    private static void requestProbe() {
        ProbeSender sender = probeSender;
        boolean sent = sender != null && sender.send();
        state = state == State.ACTIVE && sent ? State.WAITING_ACTIVE : State.WAITING_NEW;
        deadline = System.currentTimeMillis() + GRACE_MILLIS;
    }

    private static void disable(Minecraft minecraft) {
        if (state == State.DISABLED) {
            return;
        }
        state = State.DISABLED;
        deadline = 0;
        LOGGER.info("Diawars: no plugin handshake on this server — all mod features stay off.");
        // Stop any half-started feature work, like a disconnect would.
        ImageUploader.clearQueue();
        ImageDownloader.clearSession(minecraft);
    }
}
