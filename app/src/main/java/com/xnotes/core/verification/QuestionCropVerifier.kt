package com.xnotes.core.verification

import com.xnotes.core.model.*
import java.util.UUID

/** Complete upright page, never a PDF or an editor screenshot. */
class VerifierPageInput(val pageIndex: Int, val image: ByteArray, val mimeType: String = "image/jpeg")
data class VerifierProposal(val id: String, val crop: NormalizedRect)
enum class VerificationAction { KEEP, ADJUST, DELETE, ADD }
data class VerificationOperation(val action: VerificationAction, val id: String? = null,
    val left: Double? = null, val top: Double? = null, val right: Double? = null, val bottom: Double? = null)
data class VerificationResult(val operations: List<VerificationOperation>, val debugResponse: String? = null,
    val finalQuestions: List<NormalizedRect>? = null)
fun interface QuestionCropVerifier {
    suspend fun verify(page: VerifierPageInput, proposals: List<VerifierProposal>): VerificationResult
}
/** Safe, user-visible messages only; never wrap provider response bodies or credentials. */
enum class VerificationStage(val label: String) {
    PAGE_LOAD("page render/load"), NETWORK("network/provider"), PARSING("response parsing"), VALIDATION("response validation")
}
class VerificationFailure(val status: String, val stage: VerificationStage = VerificationStage.NETWORK,
    val debugResponse: String? = null) : Exception(status)

object VerificationPatch {
    const val MIN_SIZE = .005
    /** Atomic: all operations must validate before the caller replaces any proposals. */
    fun apply(page: Int, original: List<DetectedQuestion>, result: VerificationResult,
              newId: () -> String = { UUID.randomUUID().toString() }): List<DetectedQuestion> {
        require(original.all { it.sourcePageIndex == page }) { "Local rule: all original proposals must belong to the requested page" }
        require(original.map { it.id }.distinct().size == original.size) { "Local rule: original proposal IDs must be unique" }
        require(result.operations.size <= 256) { "Local rule: at most 256 operations are allowed" }
        result.finalQuestions?.let { boxes ->
            require(result.operations.isEmpty() && boxes.size <= 256) { "Local rule: final questions must be exclusive and limited to 256" }
            val additions = boxes.map { VerificationOperation(VerificationAction.ADD,
                left=it.left, top=it.top, right=it.right, bottom=it.bottom) }
            // Reuse strict geometry validation atomically; no detector IDs or acceptance survive replacement.
            return apply(page, emptyList(), VerificationResult(additions), newId).also { proposals ->
                require(proposals.none { p -> original.any { it.id == p.id } }) { "Local rule: final proposal IDs must be new" }
            }
        }
        val byId = original.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val additions = mutableListOf<DetectedQuestion>()
        val replacements = mutableMapOf<String, DetectedQuestion?>()
        val addedCrops = mutableSetOf<NormalizedRect>()
        for (op in result.operations) {
            val target = if (op.action == VerificationAction.ADD) {
                require(op.id == null) { "Local rule: ADD must not include an id" }; null
            } else {
                require(op.id != null && seen.add(op.id)) { "Local rule: KEEP/ADJUST/DELETE require an id used at most once" }
                requireNotNull(byId[op.id]) { "Local rule: operation id must reference an existing proposal" }
            }
            val crop = if (op.action == VerificationAction.ADD || op.action == VerificationAction.ADJUST) {
                val values = listOf(op.left, op.top, op.right, op.bottom).map { requireNotNull(it) { "Local rule: ADJUST/ADD require left, top, right and bottom" } }
                require(values.all { it.isFinite() && it in 0.0..1.0 }) { "Local rule: coordinates must be finite and normalized to [0,1]" }
                require(values[0] < values[2] && values[1] < values[3]) { "Local rule: left must be less than right and top less than bottom" }
                val c = values.map { it.coerceIn(0.0, 1.0) }
                require(c[2] - c[0] >= MIN_SIZE && c[3] - c[1] >= MIN_SIZE) { "Local rule: clamped crop width and height must each be at least 0.005" }
                NormalizedRect(c[0], c[1], c[2], c[3])
            } else {
                require(listOf(op.left, op.top, op.right, op.bottom).all { it == null }) { "Local rule: KEEP/DELETE must not include coordinates" }; null
            }
            when (op.action) {
                VerificationAction.KEEP -> replacements[target!!.id] = target
                VerificationAction.DELETE -> replacements[target!!.id] = null
                VerificationAction.ADJUST -> replacements[target!!.id] = target.copy(crop = crop!!,
                    accepted = false, reasons = listOf("AI adjusted; review before accepting"))
                VerificationAction.ADD -> {
                    require(addedCrops.add(crop!!)) { "Local rule: duplicate ADD crop" }
                    val id = newId()
                    require(id.isNotBlank() && id !in byId && additions.none { it.id == id }) { "Local rule: generated proposal id must be nonblank and unique" }
                    additions += DetectedQuestion(id, page, crop, listOf("AI added; review before accepting"))
                }
            }
        }
        return original.mapNotNull { if (replacements.containsKey(it.id)) replacements[it.id] else it } + additions
    }
}
