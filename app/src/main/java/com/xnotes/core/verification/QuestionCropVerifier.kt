package com.xnotes.core.verification

import com.xnotes.core.model.*
import java.util.UUID

/** Complete upright page, never a PDF or an editor screenshot. */
class VerifierPageInput(val pageIndex: Int, val image: ByteArray, val mimeType: String = "image/jpeg")
data class VerifierProposal(val id: String, val crop: NormalizedRect)
enum class VerificationAction { KEEP, ADJUST, DELETE, ADD }
data class VerificationOperation(val action: VerificationAction, val id: String? = null,
    val left: Double? = null, val top: Double? = null, val right: Double? = null, val bottom: Double? = null)
data class VerificationResult(val operations: List<VerificationOperation>, val debugResponse: String? = null)
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
        require(original.all { it.sourcePageIndex == page })
        require(original.map { it.id }.distinct().size == original.size)
        require(result.operations.size <= 256)
        val byId = original.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val additions = mutableListOf<DetectedQuestion>()
        val replacements = mutableMapOf<String, DetectedQuestion?>()
        val addedCrops = mutableSetOf<NormalizedRect>()
        for (op in result.operations) {
            val target = if (op.action == VerificationAction.ADD) {
                require(op.id == null); null
            } else {
                require(op.id != null && seen.add(op.id))
                requireNotNull(byId[op.id])
            }
            val crop = if (op.action == VerificationAction.ADD || op.action == VerificationAction.ADJUST) {
                val values = listOf(op.left, op.top, op.right, op.bottom).map { requireNotNull(it) }
                require(values.all { it.isFinite() })
                require(values[0] < values[2] && values[1] < values[3])
                val c = values.map { it.coerceIn(0.0, 1.0) }
                require(c[2] - c[0] >= MIN_SIZE && c[3] - c[1] >= MIN_SIZE)
                NormalizedRect(c[0], c[1], c[2], c[3])
            } else {
                require(listOf(op.left, op.top, op.right, op.bottom).all { it == null }); null
            }
            when (op.action) {
                VerificationAction.KEEP -> replacements[target!!.id] = target
                VerificationAction.DELETE -> replacements[target!!.id] = null
                VerificationAction.ADJUST -> replacements[target!!.id] = target.copy(crop = crop!!,
                    accepted = false, reasons = listOf("AI adjusted; review before accepting"))
                VerificationAction.ADD -> {
                    require(addedCrops.add(crop!!))
                    val id = newId()
                    require(id.isNotBlank() && id !in byId && additions.none { it.id == id })
                    additions += DetectedQuestion(id, page, crop, listOf("AI added; review before accepting"))
                }
            }
        }
        return original.mapNotNull { if (replacements.containsKey(it.id)) replacements[it.id] else it } + additions
    }
}
