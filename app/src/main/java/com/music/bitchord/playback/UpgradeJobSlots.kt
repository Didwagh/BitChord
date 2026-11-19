package com.music.bitchord.playback

import kotlinx.coroutines.Job

/**
 * The one live quality search plus the searches the queue has moved past.
 *
 * A search that has already found a FLAC is worth more than the slot it
 * occupies. It used to be cancelled the moment the listener skipped, which
 * threw away a Qobuz 24-bit file that had taken two minutes to find: the
 * result never reached `swapIn`, so the shelf at the end of `swapIn` never saw
 * it either. Retiring the job instead lets it finish, prove its stream and
 * shelf it for exactly the return visit the shelf was written for.
 *
 * Only a bounded number of retired searches are kept. Each one can still be
 * downloading from a slow catalogue, and an unlistener'd skip should not leave
 * an unbounded set of them running. The oldest *cancellable* one goes first;
 * a job in the middle of proving an upgrade is never cut, because that is the
 * case the shelf already knows how to finish safely.
 */
internal class UpgradeJobSlots(
    private val maxStale: Int = 3,
    private val mayCancel: (String?) -> Boolean = { true },
) {
    /** The search for the track currently being played, if one is running. */
    var current: Job? = null
        private set

    /** Which track [current] is hunting for. */
    var currentFor: String? = null
        private set

    private val stale = LinkedHashMap<String, Job>()

    /** Whether the current search belongs to [mediaId]. */
    fun isCurrentFor(mediaId: String): Boolean = current?.isActive == true && currentFor == mediaId

    /** Whether a retired search for [mediaId] is still running. */
    fun hasStaleFor(mediaId: String): Boolean = stale[mediaId]?.isActive == true

    /** Starts [job] as the current search, retiring whatever was current. */
    fun start(mediaId: String, job: Job) {
        retire()
        current = job
        currentFor = mediaId
    }

    /**
     * Moves the current search out of the way without cancelling it.
     *
     * The queue moving on is not a no from the catalogue; it is the question
     * being asked about a different song. Nothing has been learned, so the
     * search keeps its answer and `swapIn` decides what to do with it when it
     * arrives — a swap if the listener came back, a shelf if they did not.
     */
    fun retire() {
        val outgoing = current?.takeIf { it.isActive } ?: run {
            current = null
            currentFor = null
            return
        }
        val outgoingFor = currentFor
        current = null
        currentFor = null
        if (outgoingFor != null) stale[outgoingFor] = outgoing
        trim()
    }

    /**
     * Brings a retired search back as the current one.
     *
     * Coming back to a track must not start a second hunt alongside the one
     * that has been running since it was skipped past: two searches can find
     * two answers and audition both over the same playing bytes.
     */
    fun promoteStale(mediaId: String): Boolean {
        val job = stale.remove(mediaId) ?: return false
        if (!job.isActive) return false
        current = job
        currentFor = mediaId
        return true
    }

    /** Ends every search. Used only when the ceiling changes what may be asked. */
    fun cancelAll() {
        current?.cancel()
        stale.values.forEach { it.cancel() }
        current = null
        currentFor = null
        stale.clear()
    }

    /** Drops the oldest search that is safe to cancel until the set fits. */
    private fun trim() {
        while (stale.size > maxStale) {
            // Nothing safe to drop is worse than breaking a proof that is
            // already in flight. The set can run one over its limit for the
            // few seconds an audition lasts.
            val victim = stale.entries.firstOrNull { (id, job) -> job.isActive && mayCancel(id) }
                ?: return
            stale.remove(victim.key)?.cancel()
        }
    }
}
