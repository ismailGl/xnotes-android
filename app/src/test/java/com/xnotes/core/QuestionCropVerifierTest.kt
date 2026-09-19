package com.xnotes.core

import com.xnotes.core.model.*
import com.xnotes.core.verification.*
import com.xnotes.platform.GeminiVerificationJson
import com.xnotes.platform.GeminiVerifierConfig
import com.xnotes.platform.GeminiQuestionCropVerifier
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionCropVerifierTest {
    private val crop = NormalizedRect(.1, .1, .4, .4)
    private fun proposal(id: String = "p1", page: Int = 0) = DetectedQuestion(id, page, crop)
    private fun patch(vararg operations: VerificationOperation) = VerificationPatch.apply(0,
        listOf(proposal(), proposal("p2"), proposal("p3")), VerificationResult(operations.toList())) { "new" }
    private fun bad(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
    }
    @Test fun allFourActionsAndStableIds() {
        val result = patch(VerificationOperation(VerificationAction.KEEP, "p1"),
            VerificationOperation(VerificationAction.ADJUST, "p2", 0.0, .2, .8, 1.0),
            VerificationOperation(VerificationAction.DELETE, "p3"),
            VerificationOperation(VerificationAction.ADD, left=.5, top=.5, right=.9, bottom=.9))
        assertEquals(listOf("p1", "p2", "new"), result.map { it.id })
        assertEquals(crop, result[0].crop)
        assertEquals(NormalizedRect(0.0, .2, .8, 1.0), result[1].crop)
        assertTrue(result.none { it.accepted })
    }
    @Test fun malformedRectanglesAreAtomicFailures() {
        val invalid = listOf(
            listOf(Double.NaN,.1,.5,.5), listOf(.1,.1,Double.POSITIVE_INFINITY,.5),
            listOf(.6,.1,.5,.5), listOf(.1,.6,.5,.5), listOf(.1,.1,.101,.5),
            listOf(1.1,.1,1.5,.5), listOf(.1,.1,.5,.101))
        invalid.forEach { c -> bad { patch(VerificationOperation(VerificationAction.DELETE,"p1"),
            VerificationOperation(VerificationAction.ADJUST,"p2",c[0],c[1],c[2],c[3])) } }
        bad { patch(VerificationOperation(VerificationAction.ADJUST,"p1",left=.1)) }
    }
    @Test fun unknownDuplicateIdsAndUnexpectedFieldsRejected() {
        bad { patch(VerificationOperation(VerificationAction.DELETE,"unknown")) }
        bad { patch(VerificationOperation(VerificationAction.KEEP,"p1"), VerificationOperation(VerificationAction.DELETE,"p1")) }
        bad { patch(VerificationOperation(VerificationAction.KEEP,"p1",left=.1)) }
        bad { patch(VerificationOperation(VerificationAction.ADD,"p1",.1,.1,.5,.5)) }
        val add = VerificationOperation(VerificationAction.ADD,left=.1,top=.1,right=.5,bottom=.5)
        bad { patch(add,add) }
    }
    @Test fun mixedPixelCoordinatesRejectedAndNormalizedAddAccepted() {
        bad { patch(VerificationOperation(VerificationAction.ADD,left=.354,top=105.0,right=.648,bottom=574.0)) }
        bad { patch(VerificationOperation(VerificationAction.ADD,left=-.01,top=.1,right=.6,bottom=.7)) }
        val added = patch(VerificationOperation(VerificationAction.ADD,left=.354,top=.105,right=.648,bottom=.574)).last()
        assertEquals(NormalizedRect(.354,.105,.648,.574),added.crop)
        assertFalse(added.accepted)
    }
    @Test fun omittedProposalsRemainUnchanged() { assertEquals(3, patch().size) }
    @Test fun responseMustBeCompleteAndRequestUsesSemanticSchema() {
        val request=JSONObject(GeminiVerificationJson.request(VerifierPageInput(4,byteArrayOf(1,2,3)),
            listOf(VerifierProposal("private-uuid",crop))))
        val parts=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertFalse(parts.getJSONObject(1).getString("text").contains("private-uuid"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("P1"))
        val format=request.getJSONObject("generationConfig").getJSONObject("responseFormat").getJSONObject("text")
        assertEquals("APPLICATION_JSON",format.getString("mimeType"))
        val properties=format.getJSONObject("schema").getJSONObject("properties")
        assertEquals(setOf("decisions"),properties.keys().asSequence().toSet())
        val fields=properties.getJSONObject("decisions").getJSONObject("items").getJSONObject("properties")
        assertEquals(setOf("action","target"),fields.keys().asSequence().toSet())
        val response=JSONObject().put("candidates",org.json.JSONArray().put(JSONObject().put("finishReason","STOP")
            .put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text","{\"decisions\":[]}")))))).toString()
        assertTrue(GeminiVerificationJson.response(response).decisions!!.isEmpty())
        bad { GeminiVerificationJson.response(response.replace("STOP","MAX_TOKENS")) }
        for(text in listOf("{\"questions\":[]}","[]","{\"decisions\":[{\"action\":\"ADD\",\"target\":\"C1\"}]}"))
            bad { GeminiVerificationJson.decisions(text) }
    }
    @Test fun missingKeyDoesNotMakeAnyNetworkRequest() = runBlocking {
        val config = GeminiVerifierConfig("  ")
        assertFalse(config.available)
        try { GeminiQuestionCropVerifier(config).verify(VerifierPageInput(0,byteArrayOf()),emptyList()); fail() }
        catch (e: VerificationFailure) { assertEquals("AI unavailable", e.status) }
    }

    private class Harness(verifier: QuestionCropVerifier?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val proposals = (0..49).associateWith { listOf(DetectedQuestion("p$it",it,NormalizedRect(.1,.1,.4,.4))) }.toMutableMap()
        val states = mutableMapOf<Int,String>()
        val loaded = mutableListOf<Int>()
        val queue = VerificationQueue(scope,"doc","fake-v1",verifier,
            { loaded += it; VerifierPageInput(it,byteArrayOf(1)) },
            { proposals.getValue(it) }, { page,value -> proposals[page] = value }, { page,value -> states[page] = value })
        fun review(page: Int, enabled: Boolean = true, blocked: Set<Int> = emptySet()) = queue.review(page,(0..49).toList(),enabled,blocked)
        fun close() { queue.close(); scope.cancel() }
    }
    @Test fun boundedPrefetchAndBackwardNavigationCache() {
        var calls = 0
        val h = Harness(QuestionCropVerifier { _,_ -> calls++; VerificationResult(emptyList()) })
        try {
            h.review(10); assertEquals((10..19).toList(),h.loaded); assertEquals(10,calls)
            h.review(10); assertEquals(10,calls)
            h.review(11); assertEquals(11,calls)
            h.review(10); assertEquals(11,calls)
        } finally { h.close() }
    }
    @Test fun changedRevisionInvalidatesCacheAndCachedOutputDoesNotLoop() {
        var calls = 0
        val h = Harness(QuestionCropVerifier { _,p -> calls++
            VerificationResult(listOf(VerificationOperation(VerificationAction.ADJUST,p.single().id,.1,.1,.7,.7))) })
        try {
            h.review(49); assertEquals(1,calls)
            h.review(49); assertEquals(1,calls)
            h.proposals[49] = h.proposals.getValue(49).map { it.copy(crop=NormalizedRect(.2,.2,.8,.8)) }
            h.review(49); assertEquals(2,calls)
            h.review(49); assertEquals(2,calls)
        } finally { h.close() }
    }
    @Test fun failureAndInvalidPatchPreserveProposalsWithoutAutomaticRetry() {
        for (invalid in listOf(false,true)) {
            var calls = 0
            val h = Harness(QuestionCropVerifier { _,_ -> calls++
                if (!invalid) throw VerificationFailure("AI rate limited")
                VerificationResult(listOf(VerificationOperation(VerificationAction.DELETE,"unknown"))) })
            try {
                val before = h.proposals[49]
                h.review(49); h.review(49)
                assertEquals(before,h.proposals[49]); assertEquals(1,calls)
                assertTrue(h.states.getValue(49).contains("unchanged"))
                h.queue.retry(49); assertEquals(2,calls)
            } finally { h.close() }
        }
    }
    @Test fun rapidNavigationReprioritizesWithOneInFlight() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        var active = 0; var maximum = 0
        val h = Harness(QuestionCropVerifier { page,_ ->
            calls += page.pageIndex; active++; maximum=maxOf(maximum,active)
            if (page.pageIndex == 0) gate.await()
            active--; VerificationResult(emptyList()) })
        try {
            h.review(0); h.review(12); h.review(30)
            assertEquals(listOf(0),calls)
            gate.complete(Unit)
            assertEquals(listOf(0) + (30..39).toList(),calls)
            assertEquals(1,maximum)
            h.review(0)
            assertEquals(1,calls.count { it == 0 }) // Completed off-window result was cached.
        } finally { h.close() }
    }
    @Test fun manualEditOrRejectInvalidatesLateResponseEvenWithIdenticalCrop() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(QuestionCropVerifier { _,p -> gate.await()
            VerificationResult(listOf(VerificationOperation(VerificationAction.DELETE,p.single().id))) })
        try {
            h.review(49)
            val before=h.proposals[49]
            h.queue.invalidate(); h.review(49,blocked=setOf(49))
            gate.complete(Unit)
            assertEquals(before,h.proposals[49])
        } finally { h.close() }
    }
    @Test fun disablingPreventsLateApplyAndFurtherRequests() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(QuestionCropVerifier { _,p -> gate.await()
            VerificationResult(listOf(VerificationOperation(VerificationAction.DELETE,p.single().id))) })
        try {
            val before=h.proposals[0]
            h.review(0); h.review(0,false); gate.complete(Unit)
            assertEquals(listOf(0),h.loaded); assertEquals(before,h.proposals[0])
        } finally { h.close() }
    }
    @Test fun unavailableQueueLeavesOfflineProposalsUsable() {
        val h=Harness(null)
        try {
            val before=h.proposals.toMap(); h.review(0)
            assertEquals(before,h.proposals); assertTrue(h.loaded.isEmpty())
            assertTrue(h.states.getValue(0).contains("unavailable"))
        } finally { h.close() }
    }
    @Test fun timeoutFallsBackWithoutRetryLoop() = runBlocking {
        val original = listOf(proposal())
        var current = original
        var calls = 0
        val failed = CompletableDeferred<String>()
        val queue = VerificationQueue(this, "doc", "fake", QuestionCropVerifier { _,_ ->
            calls++; awaitCancellation()
        }, { VerifierPageInput(it,byteArrayOf(1)) }, { current }, { _,p -> current=p },
            { _,s -> if (s.contains("timed out")) failed.complete(s) }, timeoutMillis=10)
        try {
            queue.review(0,listOf(0),true)
            withTimeout(2_000) { failed.await() }
            assertEquals(original,current)
            queue.review(0,listOf(0),true); yield()
            assertEquals(1,calls)
        } finally { queue.close() }
    }
    @Test fun closingSessionCancelsPendingWork() = runBlocking {
        val gate=CompletableDeferred<Unit>()
        val h=Harness(QuestionCropVerifier { _,_ -> gate.await(); VerificationResult(emptyList()) })
        h.review(0); h.close(); gate.complete(Unit)
        assertEquals(listOf(0),h.loaded)
        assertTrue(h.states.values.none { it.startsWith("AI verified") })
    }
    @Test fun localReplacementCachesStableUnacceptedIds() {
        var calls=0
        val h=Harness(QuestionCropVerifier { _,_ -> calls++
            VerificationResult(listOf(VerificationOperation(VerificationAction.DELETE,"p49"),
                VerificationOperation(VerificationAction.ADD,left=.05,top=.05,right=.4,bottom=.4),
                VerificationOperation(VerificationAction.ADD,left=.5,top=.5,right=.9,bottom=.9))) })
        try {
            h.review(49)
            val first=h.proposals.getValue(49)
            assertEquals(2,first.size); assertTrue(first.none { it.id=="p49" || it.accepted })
            h.queue.retry(49)
            assertEquals(first,h.proposals[49]); assertEquals(1,calls)
        } finally { h.close() }
    }
    @Test fun cachedResultCanBeReappliedWithoutRequestOrNewAddIds() {
        var calls=0
        val h=Harness(QuestionCropVerifier { _,_ -> calls++
            VerificationResult(listOf(VerificationOperation(VerificationAction.ADD,left=.5,top=.5,right=.8,bottom=.8))) })
        try {
            h.review(49)
            val first=h.proposals[49]
            h.queue.retry(49)
            assertEquals(first,h.proposals[49]); assertEquals(1,calls)
        } finally { h.close() }
    }

}
