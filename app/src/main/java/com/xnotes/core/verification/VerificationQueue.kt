package com.xnotes.core.verification

import com.xnotes.core.model.DetectedQuestion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Confined to the review/UI dispatcher. One worker, one image, at most ten pending pages.
 * Navigation replaces pending work; an in-flight request finishes before another starts.
 * Cache is metadata only and lives no longer than this detection session.
 */
class VerificationQueue(
    private val scope: CoroutineScope,
    private val document: String,
    private val configVersion: String,
    private val verifier: QuestionCropVerifier?,
    private val loadPage: suspend (Int) -> VerifierPageInput,
    private val snapshot: (Int) -> List<DetectedQuestion>,
    private val apply: (Int, List<DetectedQuestion>) -> Unit,
    private val status: (Int, String) -> Unit,
    private val timeoutMillis: Long = 60_000,
    private val debugDiagnostics: Boolean = false,
    private val diagnostic: (Int, String?) -> Unit = { _, _ -> },
) {
    init { require(timeoutMillis > 0) }
    private data class Key(val document: String, val page: Int, val proposals: List<DetectedQuestion>, val config: String)
    private val cache = mutableMapOf<Key, List<DetectedQuestion>>()
    private val attempted = mutableSetOf<Key>()
    private var window = emptyList<Int>()
    private var enabled = false
    private var epoch = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (signal in wake) {
            while (enabled && verifier != null) {
                val key = window.map { key(it) }.firstOrNull { it !in attempted } ?: break
                attempted += key
                val generation = epoch
                status(key.page, "AI pending")
                diagnostic(key.page, null)
                var stage = VerificationStage.PAGE_LOAD
                var responseDiagnostic: String? = null
                try {
                    val cached = cache[key]
                    val repaired = cached ?: withTimeout(timeoutMillis) {
                        val input = loadPage(key.page)
                        require(input.pageIndex == key.page)
                        stage = VerificationStage.NETWORK
                        val result = verifier.verify(input, key.proposals.map { VerifierProposal(it.id, it.crop) })
                        stage = VerificationStage.VALIDATION
                        responseDiagnostic = if (debugDiagnostics) result.debugResponse else null
                        VerificationPatch.apply(key.page, key.proposals, result)
                    }
                    if (enabled && epoch == generation && key == key(key.page)) cache[key] = repaired
                    if (enabled && epoch == generation && key.page in window && key == key(key.page)) {
                        apply(key.page, repaired)
                        val outputKey = key(key.page)
                        cache[outputKey] = repaired
                        attempted += outputKey
                        status(key.page, "AI verified · review before accepting")
                    } else attempted.remove(key)
                } catch (_: TimeoutCancellationException) {
                    if (epoch == generation) status(key.page, "AI ${stage.label} failure · timed out · proposals unchanged; retry manually")
                } catch (e: CancellationException) { throw e }
                catch (e: VerificationFailure) {
                    if (epoch == generation) {
                        status(key.page, "AI ${e.stage.label} failure · " + e.status + " · proposals unchanged")
                        if (debugDiagnostics) diagnostic(key.page, e.debugResponse)
                    }
                } catch (_: Exception) {
                    if (epoch == generation) {
                        status(key.page, "AI ${stage.label} failure · proposals unchanged")
                        if (debugDiagnostics && stage == VerificationStage.VALIDATION) diagnostic(key.page, responseDiagnostic)
                    }
                } finally {
                    if (epoch != generation) attempted.remove(key)
                }
            }
        }
    }
    private fun key(page: Int) = Key(document, page, snapshot(page).toList(), configVersion)
    fun review(current: Int, pages: List<Int>, active: Boolean, blocked: Set<Int> = emptySet()) {
        if (enabled != active) epoch++
        enabled = active && verifier != null
        window = pages.filter { it >= current && it < current + 10 && it !in blocked }.distinct().sorted()
        if (active && verifier == null) status(current, "AI unavailable · configure a development API key")
        wake.trySend(Unit)
    }
    fun retry(page: Int) { attempted.remove(key(page)); wake.trySend(Unit) }
    /** Invalidates an in-flight result even when the user restored identical coordinates. */
    fun invalidate() { epoch++; wake.trySend(Unit) }
    fun close() { enabled = false; epoch++; worker.cancel(); wake.close() }
}
