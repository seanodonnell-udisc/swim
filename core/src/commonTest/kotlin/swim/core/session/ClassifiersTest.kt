package swim.core.session

import swim.core.model.PrStatus
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassifiersTest {
    @Test
    fun theStateNameDecidesTheCategory() {
        val cases = listOf(
            "Done" to StateCategory.DONE,
            "Completed" to StateCategory.DONE,
            "Canceled" to StateCategory.DONE,
            "Cancelled" to StateCategory.DONE,
            "Invalid" to StateCategory.DONE,
            "In Progress" to StateCategory.IN_PROGRESS,
            "Progressing" to StateCategory.IN_PROGRESS,
            "In Review" to StateCategory.IN_REVIEW,
            "Code review" to StateCategory.IN_REVIEW,
            "Blocked" to StateCategory.BLOCKED,
            "Paused" to StateCategory.PAUSED,
            "Todo" to StateCategory.TODO,
            "TODO next" to StateCategory.TODO,
        )
        for ((name, expected) in cases) {
            assertEquals(expected, stateCategory(name), "state name: $name")
        }
    }

    @Test
    fun earlierRulesWinOverLaterOnes() {
        // "Done, blocked follow-up" matches both `done` and `blocked`; done comes first.
        assertEquals(StateCategory.DONE, stateCategory("Done, blocked follow-up"))
        // "In progress review" matches both; progress comes first.
        assertEquals(StateCategory.IN_PROGRESS, stateCategory("In progress review"))
    }

    @Test
    fun theStateTypeSettlesNamesNoSubstringCovers() {
        val cases = listOf(
            WorkflowStateType.COMPLETED to StateCategory.DONE,
            WorkflowStateType.CANCELED to StateCategory.DONE,
            WorkflowStateType.STARTED to StateCategory.IN_PROGRESS,
            WorkflowStateType.UNSTARTED to StateCategory.TODO,
            WorkflowStateType.BACKLOG to StateCategory.BACKLOG,
            WorkflowStateType.TRIAGE to StateCategory.BACKLOG,
        )
        for ((type, expected) in cases) {
            assertEquals(expected, stateCategory("In Limbo", type), "state type: $type")
        }
    }

    @Test
    fun anUnknownNameWithNoTypeIsBacklog() {
        assertEquals(StateCategory.BACKLOG, stateCategory("Shipping"))
    }

    @Test
    fun prNumberComesFromTheUrl() {
        assertEquals(42, prNumber("https://github.com/acme/app/pull/42"))
        assertEquals(42, prNumber("https://github.com/acme/app/pull/42/files"))
        assertNull(prNumber("https://github.com/acme/app/issues/42"))
    }

    @Test
    fun aPullRequestWithNoStatusHasNoBadgesAndOnlyTheTitleAsTooltip() {
        val badge = prBadge("https://github.com/acme/app/pull/7", null, "Fix the thing")

        assertEquals(PrBadge(7, ReviewLevel.NONE, CheckLevel.NONE, "Fix the thing"), badge)
    }

    @Test
    fun aPullRequestWithNoStatusAndNoTitleHasNoTooltip() {
        assertNull(prBadge("https://github.com/acme/app/pull/7", null).tooltip)
    }

    @Test
    fun everyReviewDecisionMapsToALevel() {
        val cases = listOf(
            "APPROVED" to ReviewLevel.APPROVED,
            "CHANGES_REQUESTED" to ReviewLevel.CHANGES_REQUESTED,
            "REVIEW_REQUIRED" to ReviewLevel.NONE,
        )
        for ((wire, expected) in cases) {
            val badge = prBadge(PR_URL, PrStatus(reviewDecision = wire))
            assertEquals(expected, badge.review, "review decision: $wire")
        }
    }

    @Test
    fun everyCheckStateMapsToALevel() {
        val cases = listOf(
            "SUCCESS" to CheckLevel.OK,
            "FAILURE" to CheckLevel.FAILED,
            "ERROR" to CheckLevel.FAILED,
            "PENDING" to CheckLevel.PENDING,
            "EXPECTED" to CheckLevel.PENDING,
            "SOMETHING_NEW" to CheckLevel.NONE,
        )
        for ((wire, expected) in cases) {
            val badge = prBadge(PR_URL, PrStatus(checkState = wire))
            assertEquals(expected, badge.checks, "check state: $wire")
        }
    }

    @Test
    fun theTooltipJoinsTitleReviewAndCheckWording() {
        val badge = prBadge(
            PR_URL,
            PrStatus(reviewDecision = "CHANGES_REQUESTED", checkState = "ERROR"),
            "Fix the thing",
        )
        assertEquals("Fix the thing · Changes requested · Checks errored", badge.tooltip)
    }

    @Test
    fun theTooltipSkipsThePartsThatAreMissing() {
        assertEquals("Checks passing", prBadge(PR_URL, PrStatus(checkState = "SUCCESS")).tooltip)
    }
}

private const val PR_URL = "https://github.com/acme/app/pull/7"
