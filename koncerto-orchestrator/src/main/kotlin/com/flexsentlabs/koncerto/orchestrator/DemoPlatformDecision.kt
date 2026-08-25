package com.flexsentlabs.koncerto.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Which recorder a ticket's demo should use. */
enum class DemoPlatformKind { BROWSER, API, ANDROID }

/** The chosen recorder plus why, so the choice is traceable in the logs rather than inferred. */
data class DemoPlatformVerdict(val kind: DemoPlatformKind, val reason: String)

/**
 * Chooses between a browser demo and a server-side API demo for a ticket.
 *
 * The decision is made from what the *live deployment* actually exposes, not from file extensions
 * in the diff: a repo full of Kotlin controllers may still serve a UI, and a repo with a handful of
 * HTML files may not serve one on any reachable route.
 *
 * A browsable UI wins whenever one exists — it demonstrates the feature the way a person would use
 * it. The API path is for tickets where there is nothing to look at.
 *
 * Pure and total: no I/O, and every input yields a verdict, because failing to decide must never
 * block a ticket.
 */
object DemoPlatformDecision {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Inventory keys whose contents mean a human would have something to interact with. */
    private val SIGNAL_KEYS = listOf("testids", "fields", "buttons")

    fun decide(
        domInventory: String?,
        openApiInventory: String?,
        isAndroidApp: Boolean = false
    ): DemoPlatformVerdict {
        val hasUi = hasUiSignal(domInventory)
        val hasApi = !openApiInventory.isNullOrBlank()
        return when {
            // An Android app has no URL to crawl or probe, so the repository is the only
            // evidence and it outranks both deployment signals — a web deployment of an
            // Android repo, if one somehow exists, is not what the ticket is about.
            isAndroidApp -> DemoPlatformVerdict(
                DemoPlatformKind.ANDROID,
                "the repository builds an Android application"
            )
            hasUi -> DemoPlatformVerdict(
                DemoPlatformKind.BROWSER,
                "the deployed app exposes a browsable UI"
            )
            hasApi -> DemoPlatformVerdict(
                DemoPlatformKind.API,
                "the deployed app publishes an OpenAPI document and no browsable UI"
            )
            // Neither inventory says anything. Staying on the browser path keeps the existing
            // behaviour intact, which is the safe default: an inconclusive probe is not evidence
            // that the ticket is server-side.
            else -> DemoPlatformVerdict(
                DemoPlatformKind.BROWSER,
                "no UI inventory and no OpenAPI document; keeping the browser path"
            )
        }
    }

    /**
     * Whether the crawled inventory shows anything a person could actually interact with.
     *
     * A non-blank inventory is NOT evidence of a UI: the crawler emits an entry for `/`
     * unconditionally (`if (!hasSignal && route !== '/') continue`), so every service answering
     * below 400 on its root — a JSON index, an actuator root, a redirect to docs — yields a
     * one-element inventory with empty element arrays. Treating that as "has a UI" would send every
     * REST service down the browser path and the API recorder would never run.
     *
     * Unparseable input counts as "has a UI". Choosing API is the destructive branch (it discards
     * the browser scenario), so an inventory we cannot read must never be the reason we take it.
     */
    internal fun hasUiSignal(domInventory: String?): Boolean {
        val raw = domInventory?.takeIf { it.isNotBlank() } ?: return false
        val routes = runCatching { JSON.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return true
        return routes.any { route ->
            val fields = route as? JsonObject ?: return@any true
            SIGNAL_KEYS.any { key -> (fields[key] as? JsonArray)?.isNotEmpty() == true }
        }
    }
}
