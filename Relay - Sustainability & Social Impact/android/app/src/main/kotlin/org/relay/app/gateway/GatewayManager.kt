package org.relay.app.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import org.relay.core.gossip.GossipNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches connectivity and, while this phone has validated internet (and "Pretend offline" is off), marks
 * the gossip node as a gateway and runs the sync loop (every 10 s, plus immediately when a NEED arrives).
 */
class GatewayManager(
    context: Context,
    private val node: GossipNode,
    private val syncer: GatewaySyncer,
    private val executor: ScheduledExecutorService,
    private val pretendOffline: () -> Boolean,
    private val log: (String) -> Unit,
    private val onChange: (internet: Boolean, outcome: SyncOutcome?) -> Unit,
) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val normalNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private val constrainedNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private val kickQueued = AtomicBoolean(false)
    @Volatile private var started = false

    val internetAvailable: Boolean get() = normalNetworks.isNotEmpty() || constrainedNetworks.isNotEmpty()

    private fun callback(into: MutableSet<Network>) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { into.add(network); refresh() }
        override fun onLost(network: Network) { into.remove(network); refresh() }
    }

    fun start() {
        if (started) return
        started = true
        val mgr = cm
        if (mgr == null) { log("gateway: no ConnectivityManager"); return }
        try {
            val normal = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            val cb = callback(normalNetworks)
            mgr.registerNetworkCallback(normal, cb)
            callbacks += cb

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Opt in to constrained (e.g. satellite) networks that the default request would filter out.
                val constrained = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                    .build()
                val cb2 = callback(constrainedNetworks)
                mgr.registerNetworkCallback(constrained, cb2)
                callbacks += cb2
            }
        } catch (e: Exception) {
            log("gateway: registerNetworkCallback failed: $e")
        }
        executor.scheduleWithFixedDelay({ runSafely { tick() } }, 10, 10, TimeUnit.SECONDS)
        refresh()
    }

    fun stop() {
        started = false
        val mgr = cm
        for (cb in callbacks) try { mgr?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        callbacks.clear()
        normalNetworks.clear()
        constrainedNetworks.clear()
        node.isGateway = false
    }

    /** Re-evaluate gateway status (connectivity or the "Pretend offline" setting changed). */
    fun refresh() {
        val want = started && internetAvailable && !pretendOffline()
        if (node.isGateway != want) {
            log("gateway: ${if (want) "ONLINE" else "offline"} (internet=$internetAvailable pretendOffline=${pretendOffline()})")
            node.isGateway = want
            if (want) kick()
        }
        onChange(internetAvailable, null)
    }

    /** Run a sync round soon (a new NEED was stored). Coalesces bursts. */
    fun kick() {
        if (!node.isGateway || !kickQueued.compareAndSet(false, true)) return
        try {
            executor.execute { kickQueued.set(false); runSafely { tick() } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            kickQueued.set(false)
        }
    }

    private fun tick() {
        if (!node.isGateway) return
        val outcome = syncer.syncOnce()
        onChange(internetAvailable, outcome)
        if (outcome.ok && syncer.moreWaiting) kick()
    }

    private inline fun runSafely(block: () -> Unit) {
        try { block() } catch (e: Throwable) { log("gateway: tick error $e") }
    }
}
