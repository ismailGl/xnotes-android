package com.xnotes.core

import com.xnotes.core.model.*
import com.xnotes.core.verification.*
import com.xnotes.platform.GeminiVerificationJson
import org.junit.Assert.*
import org.junit.Test

class LocalVerificationPlanTest {
    private val area=NormalizedRect(.3,.05,.95,.95)
    private val a=NormalizedRect(.32,.1,.6,.3)
    private val b=NormalizedRect(.32,.35,.6,.6)
    private val union=NormalizedRect(.32,.1,.6,.6)
    private fun plan(vararg c:LocalBoundaryCandidate)=LocalVerificationPlan(listOf(VerifierProposal("a",a),VerifierProposal("b",b)),
        listOf(area),listOf(NormalizedRect(0.0,0.0,.3,1.0)),c.toList())
    private fun decision(action:SemanticAction,target:String)=SemanticDecision(action,target)
    private fun apply(p:LocalVerificationPlan,vararg d:SemanticDecision):List<DetectedQuestion> {
        val original=p.proposals.map { DetectedQuestion(it.id,0,it.crop,accepted=true) }
        var n=0
        return VerificationPatch.apply(0,original,p.resolve(VerificationResult(emptyList(),decisions=d.toList()))) { "new${++n}" }
    }
    @Test fun keepAndOmissionPreserveIdentityAndAcceptance() {
        val result=apply(plan(),decision(SemanticAction.KEEP,"P1"))
        assertEquals(listOf("a","b"),result.map { it.id }); assertTrue(result.all { it.accepted })
    }
    @Test fun mergeUsesLocalUnionAndRequiresReview() {
        val p=plan(LocalBoundaryCandidate("C1",SemanticAction.MERGE,listOf("a","b"),listOf(union),"union"))
        val result=apply(p,decision(SemanticAction.MERGE,"C1"))
        assertEquals(union,result.single().crop); assertFalse(result.single().accepted)
    }
    @Test fun splitUsesSelectedLocalBoundaries() {
        val p=LocalVerificationPlan(listOf(VerifierProposal("a",union)),listOf(area),emptyList(),
            listOf(LocalBoundaryCandidate("C1",SemanticAction.SPLIT,listOf("a"),listOf(a,b),"whitespace")))
        val result=apply(p,decision(SemanticAction.SPLIT,"C1"))
        assertEquals(listOf(a,b),result.map { it.crop }); assertTrue(result.none { it.accepted })
    }
    @Test fun missedRequiresAnExistingLocalCandidate() {
        val box=NormalizedRect(.65,.1,.9,.6)
        val p=plan(LocalBoundaryCandidate("C1",SemanticAction.MISSED,emptyList(),listOf(box),"uncovered"))
        assertEquals(box,apply(p,decision(SemanticAction.MISSED,"C1")).last().crop)
        assertEquals(2,apply(p,decision(SemanticAction.MISSED,"C99")).size)
    }
    @Test fun excludedAnswerKeyAndSidebarCorrectionsAreRejected() {
        for(bad in listOf(NormalizedRect(.1,.1,.5,.4),NormalizedRect(.4,.85,.8,.94))) {
            val p=plan(LocalBoundaryCandidate("C1",SemanticAction.MISSED,emptyList(),listOf(bad),"bad"))
                .copy(excluded=listOf(NormalizedRect(.4,.88,.8,.94)))
            assertEquals(2,apply(p,decision(SemanticAction.MISSED,"C1")).size)
            val audit=p.resolve(VerificationResult(emptyList(),decisions=listOf(decision(SemanticAction.MISSED,"C1")))).debugResponse!!
            assertTrue(audit.contains("Rejected:")); assertTrue(audit.contains("Original:"));assertTrue(audit.contains("Local candidate:"))
        }
    }
    @Test fun overlapCannotCreateDuplicateQuestion() {
        val p=plan(LocalBoundaryCandidate("C1",SemanticAction.MISSED,emptyList(),listOf(a),"overlap"))
        assertEquals(2,apply(p,decision(SemanticAction.MISSED,"C1")).size)
    }
    @Test fun conflictingKeepAndMergeRejectBothIndependentOfOrder() {
        val p=plan(LocalBoundaryCandidate("C1",SemanticAction.MERGE,listOf("a","b"),listOf(union),"union"))
        val d=listOf(decision(SemanticAction.MERGE,"C1"),decision(SemanticAction.DELETE,"P1"))
        for(order in listOf(d,d.reversed())) assertEquals(listOf("a","b"),apply(p,*order.toTypedArray()).map { it.id })
    }
    @Test fun arbitraryCoordinatesAreNeverAcceptedByWireParser() {
        for(text in listOf("""{"questions":[[100,100,400,500]]}""",
            """{"decisions":[{"action":"MISSED","target":"C1","left":0.1}]}""",
            """{"decisions":[{"action":"KEEP","target":"P1"},{"action":"DELETE","target":"P1"}]}""")) {
            try { GeminiVerificationJson.decisions(text); fail("Expected rejection") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun rasterImagesPreventFalseWhitespaceSplitAndUncoveredCandidatesRespectRegionRoles() {
        val width=100; val height=200
        val ink=BooleanArray(width*height)
        // Image-first question occupies y .1-.3; paragraph below has its own internal gap.
        for(y in 20..60) for(x in 32..59) ink[y*width+x]=true
        for(y in 67..75) for(x in 32..59) ink[y*width+x]=true
        for(y in 110..145) for(x in 32..59) ink[y*width+x]=true
        val runs=listOf(QuestionLayoutDetector.TextRun("1.",NormalizedRect(.32,.1,.34,.12)),
            QuestionLayoutDetector.TextRun("2.",NormalizedRect(.32,.55,.34,.57)),
            QuestionLayoutDetector.TextRun("Substantial first question and options A) a E) e",NormalizedRect(.32,.33,.59,.38)),
            QuestionLayoutDetector.TextRun("Substantial second question and options",NormalizedRect(.32,.55,.59,.72)))
        val regions=listOf(QuestionPageRegions.Region(area,QuestionPageRegions.Role.QUESTIONS,.9,"test",false),
            QuestionPageRegions.Region(NormalizedRect(0.0,0.0,.3,1.0),QuestionPageRegions.Role.INSTRUCTIONAL,.9,"test"))
        val d=QuestionLayoutDetector.Diagnostics(0,1,null,emptyList(),emptyList(),listOf(QuestionLayoutDetector.ColumnBounds(.3,.95)),regions)
        val p=LocalVerificationPlan.build(emptyList(),runs,QuestionLayoutDetector.Layout(width,height,ink),d)
        assertTrue(p.candidates.any { it.action==SemanticAction.MISSED })
        assertTrue(p.candidates.flatMap { it.rectangles }.all(p::safe))
        assertTrue(p.candidates.any { it.rectangles.single().top<=.1 && it.rectangles.single().bottom>=.38 })
    }
    @Test fun noQuestionRegionMeansNoRecoveryCandidates() {
        val layout=QuestionLayoutDetector.Layout(10,10,BooleanArray(100) { true })
        val d=QuestionLayoutDetector.Diagnostics(0,0,null,emptyList(),emptyList(),emptyList(),listOf(
            QuestionPageRegions.Region(NormalizedRect(0.0,0.0,1.0,1.0),QuestionPageRegions.Role.DOCUMENT,1.0,"contents")))
        assertTrue(LocalVerificationPlan.build(emptyList(),emptyList(),layout,d).candidates.isEmpty())
    }
    private fun numberedFixture(count: Int): Triple<List<QuestionLayoutDetector.TextRun>,QuestionLayoutDetector.Layout,QuestionLayoutDetector.Diagnostics> {
        val runs=mutableListOf<QuestionLayoutDetector.TextRun>()
        for(i in 0 until count) {
            val y=.1+i*.25
            runs+=QuestionLayoutDetector.TextRun("${i+1}.",NormalizedRect(.31,y,.33,y+.01))
            runs+=QuestionLayoutDetector.TextRun("An entire question with substantial text and an image",NormalizedRect(.35,y,.85,y+.07))
            runs+=QuestionLayoutDetector.TextRun("A) first choice",NormalizedRect(.35,y+.1,.55,y+.11))
            runs+=QuestionLayoutDetector.TextRun("E) last choice",NormalizedRect(.35,y+.14,.55,y+.15))
        }
        val ink=BooleanArray(200*400)
        for(r in runs) for(y in (r.box.top*400).toInt() until (r.box.bottom*400).toInt())
            for(x in (r.box.left*200).toInt() until (r.box.right*200).toInt()) ink[y*200+x]=true
        val d=QuestionLayoutDetector.Diagnostics(0,1,null,emptyList(),emptyList(),listOf(QuestionLayoutDetector.ColumnBounds(.3,.95)),
            listOf(QuestionPageRegions.Region(area,QuestionPageRegions.Role.QUESTIONS,.9,"question area",false)))
        return Triple(runs,QuestionLayoutDetector.Layout(200,400,ink),d)
    }
    @Test fun threeQuestionsHaveAMultiwaySplitAndInternalOptionGapsDoNotSplit() {
        val (runs,layout,d)=numberedFixture(3)
        val plan=LocalVerificationPlan.build(listOf(VerifierProposal("a",area)),runs,layout,d)
        assertTrue(plan.candidates.any { it.action==SemanticAction.SPLIT && it.rectangles.size==3 })
        assertTrue(plan.candidates.filter { it.action==SemanticAction.SPLIT }.flatMap { it.rectangles }.all { b ->
            runs.any { it.text.startsWith("E)") && LocalVerificationPlan.contains(b,it.box) }
        })
    }
    @Test fun separateCompleteQuestionsAreNotOfferedAsMergeAndMissingSpansKeepLastOptions() {
        val (runs,layout,d)=numberedFixture(2)
        val p=listOf(VerifierProposal("a",NormalizedRect(.3,.09,.9,.3)),VerifierProposal("b",NormalizedRect(.3,.34,.9,.56)))
        assertTrue(LocalVerificationPlan.build(p,runs,layout,d).candidates.none { it.action==SemanticAction.MERGE })
        val missing=LocalVerificationPlan.build(emptyList(),runs,layout,d).candidates
        assertTrue(missing.isNotEmpty())
        assertTrue(missing.all { c -> runs.any { it.text.startsWith("E)") && LocalVerificationPlan.contains(c.rectangles.single(),it.box) } })
    }
    @Test fun localCandidatesCannotCrossExistingColumnClamps() {
        val (runs,layout,d)=numberedFixture(2)
        val divided=d.copy(columnBounds=listOf(QuestionLayoutDetector.ColumnBounds(.3,.6),QuestionLayoutDetector.ColumnBounds(.61,.95)),
            regions=d.regions.map { it.copy(inferColumns=true) })
        val p=LocalVerificationPlan.build(emptyList(),runs,layout,divided)
        assertTrue(p.candidates.flatMap { it.rectangles }.all { b -> divided.columnBounds.any { b.left>=it.left && b.right<=it.right } })
    }
}
