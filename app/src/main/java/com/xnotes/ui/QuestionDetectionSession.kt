package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.model.*
import com.xnotes.core.verification.*
import com.xnotes.platform.QuestionPageReader
import com.xnotes.platform.QuestionSetRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Temporary proposals only. The sole persistent operation is explicit commit(). */
class QuestionDetectionSession(
    val pdf: File, private val uri: String, private val title: String,
    val availablePages: List<Int>, private val repository: QuestionSetRepository,
    private val scope: CoroutineScope, private val stillCurrent: () -> Boolean,
    private val openExtractor: () -> QuestionPageReader,
    private val verifier: QuestionCropVerifier? = null,
    private val verifierVersion: String = "none",
    private val verifierPage: suspend (Int) -> VerifierPageInput = { error("AI unavailable") },
) {
    var proposals by mutableStateOf<List<DetectedQuestion>>(emptyList()); private set
    var pages by mutableStateOf<List<Int>>(emptyList()); private set
    var pageDiagnostics by mutableStateOf<Map<Int, QuestionLayoutDetector.Diagnostics>>(emptyMap()); private set
    var pageSources by mutableStateOf<Map<Int, QuestionTextSource>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var status by mutableStateOf("Choose PDF pages to scan"); private set
    var errors by mutableStateOf<List<String>>(emptyList()); private set
    private var sourceId: String? = null
    private var existing: List<Question> = emptyList()
    private var job: Job? = null
    val aiAvailable get() = verifier != null
    var aiEnabled by mutableStateOf(false); private set
    var aiStatuses by mutableStateOf<Map<Int, String>>(emptyMap()); private set
    var aiDiagnostics by mutableStateOf<Map<Int, String>>(emptyMap()); private set
    private val localPlans = mutableMapOf<Int, LocalVerificationPlan>()
    private var originalProposals = emptyMap<Int, List<DetectedQuestion>>()
    private val explicitlyRejected = mutableSetOf<String>()
    private val manuallyTouched = mutableSetOf<Int>()
    private var reviewPage: Int? = null
    private var verification: VerificationQueue? = null
    fun review(page: Int?) { reviewPage = page; refreshVerification() }
    fun enableAi(enabled: Boolean) { aiEnabled = enabled && aiAvailable; refreshVerification() }
    private fun refreshVerification() {
        val page = reviewPage ?: return
        verification?.review(page, pages, aiEnabled && !busy && !saving, manuallyTouched)
    }
    fun beginManualReview(page: Int) {
        if (!busy && !saving && page in pages) protect(page)
    }
    private fun protect(page: Int) {
        manuallyTouched += page
        verification?.invalidate()
        aiStatuses = aiStatuses + (page to "Manual changes protected · Verify page to recheck")
        refreshVerification()
    }
    fun verifyPage(page: Int) {
        if (busy || saving || !aiEnabled) return
        manuallyTouched -= page
        verification?.retry(page)
        refreshVerification()
    }
    fun revertAi(page: Int) {
        if (busy || saving) return
        val original = originalProposals[page] ?: return
        protect(page)
        proposals = proposals.filterNot { it.sourcePageIndex == page } + original.map { it.copy(accepted = false) }
        status = "${proposals.size} proposals · review before adding"
        aiStatuses = aiStatuses + (page to "Detector proposals restored · review before accepting")
    }
    private fun startVerification(identity: String) {
        originalProposals = pages.associateWith { page -> proposals.filter { it.sourcePageIndex == page } }
        verification = VerificationQueue(scope, identity, verifierVersion, verifier, { page ->
            val snapshot = proposals.filter { it.sourcePageIndex == page }.map { VerifierProposal(it.id,it.crop) }
            val cached = localPlans[page]
            val plan = if(cached?.proposals == snapshot) cached else withContext(Dispatchers.IO) {
                openExtractor().use { reader ->
                    val data=reader.page(page)
                    LocalVerificationPlan.build(snapshot,data.runs,data.layout,
                        requireNotNull(pageDiagnostics[page]))
                }
            }
            val input=verifierPage(page)
            VerifierPageInput(input.pageIndex,input.image,input.mimeType,plan,input.renderRegion)
        },
            { page -> proposals.filter { it.sourcePageIndex == page } },
            { page, repaired ->
                if (stillCurrent() && !busy && !saving && page !in manuallyTouched) {
                    proposals = proposals.filterNot { it.sourcePageIndex == page } + repaired
                    status = "${proposals.size} proposals · review before adding"
                    if (repaired.isNotEmpty()) errors = errors.filterNot { it.startsWith("Page ${page + 1}: no numbered questions") }
                }
            }, { page, value -> aiStatuses = aiStatuses + (page to value) },
            debugDiagnostics = com.xnotes.BuildConfig.DEBUG,
            diagnostic = { page, value ->
                aiDiagnostics = if (com.xnotes.BuildConfig.DEBUG && value != null) aiDiagnostics + (page to value)
                    else aiDiagnostics - page
            })
    }
    fun scan(requested: List<Int>) {
        if (busy || saving) return
        val targets = requested.distinct().sorted()
        if (targets.isEmpty() || targets.any { it !in availablePages }) { status = "Choose PDF-backed pages from this notebook"; return }
        verification?.close(); verification = null
        explicitlyRejected.clear(); manuallyTouched.clear(); originalProposals = emptyMap(); aiStatuses = emptyMap(); aiDiagnostics = emptyMap()
        localPlans.clear()
        pageDiagnostics = emptyMap()
        pageSources = emptyMap()
        busy = true; proposals = emptyList(); pages = targets; errors = emptyList(); sourceId = null
        job = scope.launch {
            try {
                val scanContext = currentCoroutineContext()
                val identity = withContext(Dispatchers.IO) { repository.identity(uri, pdf) }
                existing = withContext(Dispatchers.IO) { repository.find(uri, pdf)?.entries?.mapNotNull { it.question } ?: emptyList() }
                var extractor: QuestionPageReader? = null
                try {
                    withContext(Dispatchers.IO) { extractor = openExtractor() }
                    val reader = requireNotNull(extractor)
                    for ((i, page) in targets.withIndex()) {
                        ensureActive(); status = "Scanning ${i + 1} / ${targets.size} · PDF page ${page + 1}"
                        try {
                            val (data, analysis) = withContext(Dispatchers.IO) {
                                val data = reader.page(page) { phase ->
                                    withContext(scanContext) { status = "${i + 1} / ${targets.size} · PDF page ${page + 1} · $phase" }
                                }
                                ensureActive()
                                data to QuestionLayoutDetector.analyze(page, data.runs, data.layout, data.textSource)
                            }
                            val detected = analysis.proposals
                            pageDiagnostics = pageDiagnostics + (page to analysis)
                            pageSources = pageSources + (page to data.textSource)
                            if (detected.isEmpty()) errors = errors + "Page ${page + 1}: no numbered questions found. Add rectangles manually or try a clearer scan."
                            val pageProposals = detected.map { it.copy(id = UUID.randomUUID().toString(),
                                reasons = it.reasons + when {
                                    duplicate(page,it.crop) -> listOf("Overlaps an existing question; duplicate will be skipped")
                                    possibleDuplicate(page,it.crop) -> listOf("May contain an existing manual question; check before accepting")
                                    else -> emptyList()
                                }) }
                            proposals = proposals + pageProposals
                            if (aiAvailable) localPlans[page] = withContext(Dispatchers.Default) {
                                LocalVerificationPlan.build(pageProposals.map { VerifierProposal(it.id,it.crop) },data.runs,data.layout,analysis)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { errors = errors + "Page ${page + 1}: PDF text/OCR/layout extraction failed; add rectangles manually" }
                    }
                    sourceId = identity
                    startVerification(identity)
                    status = "${proposals.size} proposals · review before adding"
                } finally { withContext(NonCancellable + Dispatchers.IO) { extractor?.close() } }
            } catch (e: CancellationException) { status = "Scan cancelled"; throw e }
            catch (_: OutOfMemoryError) { status = "Not enough memory to scan this PDF. Try a smaller page range." }
            catch (_: Exception) { status = "Could not scan this PDF. Check the source and saved question metadata." }
            finally { busy = false; refreshVerification() }
        }
    }
    fun duplicate(page: Int, crop: NormalizedRect) = existing.any { it.sourcePageIndex == page && QuestionOverlap.duplicate(it.crop, crop) }
    fun possibleDuplicate(page: Int, crop: NormalizedRect) = existing.any { it.sourcePageIndex == page && QuestionOverlap.possibleDuplicate(it.crop,crop) }
    fun edit(id: String, crop: NormalizedRect) {
        if (busy || saving) return
        proposals.firstOrNull { it.id == id }?.let { protect(it.sourcePageIndex) }
        proposals = proposals.map { if (it.id == id) it.copy(crop = crop, accepted = false,
            reasons = listOf("Rectangle edited; review before accepting")) else it }
    }
    fun accept(id: String, value: Boolean) {
        if (!busy && !saving) {
            proposals.firstOrNull { it.id == id }?.let { protect(it.sourcePageIndex) }
            if (value) explicitlyRejected.remove(id) else explicitlyRejected.add(id)
            proposals = proposals.map { if (it.id == id) it.copy(accepted = value) else it }
        }
    }
    fun acceptAll() { if (!busy && !saving) {
        pages.forEach { protect(it) }
        proposals = proposals.map { if (it.accepted || it.id in explicitlyRejected) it else it.copy(accepted = !duplicate(it.sourcePageIndex, it.crop)) }
    } }
    fun add(page: Int, crop: NormalizedRect): String? {
        if (busy || saving || page !in pages) return null
        protect(page)
        val id = UUID.randomUUID().toString()
        proposals = proposals + DetectedQuestion(id, page, crop, listOf("Manual rectangle"), accepted = true)
        return id
    }
    fun commit(onSaved: (String) -> Unit) {
        val identity = sourceId ?: run { status = "Complete a scan before adding proposals"; return }
        if (busy || saving) return
        val accepted = proposals.filter { it.accepted }.map { Question(it.id, it.sourcePageIndex, it.crop) }
        if (accepted.isEmpty()) { status = "Accept at least one proposal first"; return }
        if (!stillCurrent()) { status = "Notebook changed; close and scan again"; return }
        verification?.close(); verification = null
        saving = true
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.appendBatch(uri, title, pdf, accepted, identity) }
                val added = saved.questions.count { q -> accepted.any { it.id == q.id } }
                onSaved("$added questions added; ${accepted.size - added} duplicates skipped")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Could not save proposals. Source may have changed; your existing questions were not replaced." }
            finally { saving = false }
        }
    }
    fun cancel() { verification?.close(); verification = null; if (!saving) job?.cancel() }
}
