package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.ai.assistance.operit.util.markdown.MarkdownNode
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import com.ai.assistance.operit.util.stream.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderBatchCoordinatorTest {
    @Test
    fun snapshotCadenceUsesDirtyTreeSizeAndNeverSlowsToolXml() {
        assertEquals(1, snapshotRefreshPasses(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "short")))
        val large = MarkdownNode(MarkdownProcessorType.CODE_BLOCK, "x".repeat(400_000))
        assertEquals(5, snapshotRefreshPasses(large))
        val parent = MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x".repeat(40_000))
        parent.children.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x".repeat(40_000)))
        assertEquals(2, snapshotRefreshPasses(parent))
        assertEquals(1, snapshotRefreshPasses(MarkdownNode(MarkdownProcessorType.XML_BLOCK, "x".repeat(400_000))))
    }

    @Test
    fun longGrowingBlockIsThrottledButFinalFlushIsImmediate() = runTest {
        val nodes = mutableStateListOf(MarkdownNode(MarkdownProcessorType.CODE_BLOCK, "x".repeat(400_000)))
        val rendered = mutableStateListOf<MarkdownNodeStable>()
        val updater = BatchNodeUpdater(nodes, rendered, mutableMapOf(), mutableMapOf(),
            mutableMapOf(), "large-tail", this)
        updater.requestUpdate()
        advanceTimeBy(200)
        runCurrent()
        val first = rendered.single()
        updater.appendBlockChunk(nodes.single(), " more")
        advanceTimeBy(200)
        runCurrent()
        org.junit.Assert.assertSame(first, rendered.single())
        advanceUntilIdle()
        assertEquals(1200L, testScheduler.currentTime) // Five passes after the first render.
        assertEquals(400_005, rendered.single().content.length)
        updater.appendBlockChunk(nodes.single(), " final")
        updater.flushNow()
        assertEquals(400_011, rendered.single().content.length)
        advanceUntilIdle()
    }

    @Test
    fun structuralChangeBypassesLargeBlockThrottleAndStablePrefixDoesNotSlowNewText() = runTest {
        val nodes = mutableStateListOf(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x".repeat(400_000)))
        val rendered = mutableStateListOf<MarkdownNodeStable>()
        val updater = BatchNodeUpdater(nodes, rendered, mutableMapOf(), mutableMapOf(),
            mutableMapOf(), "structural", this)
        updater.requestUpdate()
        updater.flushNow()
        nodes[0] = MarkdownNode(MarkdownProcessorType.BLOCK_LATEX, "x".repeat(400_000))
        updater.requestStructuralUpdate(0)
        nodes.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "new text"))
        updater.requestUpdate(1)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(MarkdownProcessorType.BLOCK_LATEX, rendered[0].type)
        assertEquals("new text", rendered[1].content)
        updater.appendBlockChunk(nodes[1], " suffix")
        advanceTimeBy(200)
        runCurrent()
        assertEquals("new text suffix", rendered[1].content)
    }

    @Test
    fun shrinkClearsSparseCachesBeforeReusingAnIndex() = runTest {
        val nodes = mutableStateListOf(
            MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "a"),
            MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "b"),
        )
        val rendered = mutableStateListOf<MarkdownNodeStable>()
        val cache = mutableMapOf<Int, Pair<Int, MarkdownNodeStable>>()
        val updater = BatchNodeUpdater(nodes, rendered, cache, mutableMapOf(),
            mutableMapOf(), "shrink", this)
        updater.requestUpdate()
        updater.flushNow()
        cache.remove(0)
        nodes.removeAt(1)
        updater.requestUpdate()
        updater.flushNow()
        org.junit.Assert.assertFalse(cache.containsKey(1))
        nodes.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "c"))
        nodes.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "d"))
        updater.requestUpdate()
        updater.flushNow()
        assertEquals(listOf("a", "c", "d"), rendered.map { it.content })
        advanceUntilIdle()
    }

    @Test
    fun thousandCompletedNodesAreNotReconvertedWhenTailChanges() = runTest {
        val nodes = mutableStateListOf<MarkdownNode>()
        repeat(1000) { nodes.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "node-$it")) }
        val rendered = mutableStateListOf<MarkdownNodeStable>()
        val cache = mutableMapOf<Int, Pair<Int, MarkdownNodeStable>>()
        val updater = BatchNodeUpdater(nodes, rendered, cache, mutableMapOf(),
            mutableMapOf(), "large", this)
        updater.requestUpdate()
        updater.flushNow()
        val prefix = rendered.first()
        // A missing prefix cache entry would force conversion if an entire-list pass were made.
        cache.remove(0)
        updater.appendBlockChunk(nodes.last(), " appended")
        updater.flushNow()
        org.junit.Assert.assertSame(prefix, rendered.first())
        org.junit.Assert.assertFalse(cache.containsKey(0))
        assertEquals("node-999 appended", rendered.last().content)
        advanceUntilIdle()
    }

    @Test
    fun flushAndCancelDoNotLeaveAnOldTimerInANewGeneration() = runTest {
        var flushes = 0
        val coordinator = RenderBatchCoordinator(this, 200) { flushes++ }
        coordinator.requestUpdate()
        coordinator.flushNow()
        coordinator.requestUpdate()
        advanceUntilIdle()
        assertEquals(2, flushes)
        coordinator.requestUpdate()
        coordinator.cancelPending()
        advanceUntilIdle()
        assertEquals(2, flushes)
    }

    @Test
    fun requestWhileBatchIsPending_isIncludedWithoutAnotherInput() = runTest {
        var flushCount = 0
        val coordinator =
            RenderBatchCoordinator(
                scope = this,
                intervalMs = 200,
                onFlush = { flushCount++ },
            )

        coordinator.requestUpdate()
        advanceTimeBy(100)
        coordinator.requestUpdate()
        advanceUntilIdle()

        assertEquals(1, flushCount)
    }

    @Test
    fun requestDuringFlush_isDrainedBeforeCoordinatorBecomesIdle() = runTest {
        var flushCount = 0
        lateinit var coordinator: RenderBatchCoordinator
        coordinator =
            RenderBatchCoordinator(
                scope = this,
                intervalMs = 200,
                onFlush = {
                    flushCount++
                    if (flushCount == 1) {
                        coordinator.requestUpdate()
                    }
                },
            )

        coordinator.requestUpdate()
        advanceUntilIdle()

        assertEquals(2, flushCount)
    }

    @Test
    fun toolXmlTailMutation_isRenderedWithoutAnotherInput() = runTest {
        val nodes = mutableStateListOf<MarkdownNode>()
        val renderNodes = mutableStateListOf<MarkdownNodeStable>()
        val nodeAnimationStates = mutableStateMapOf<String, Boolean>()
        val updater =
            BatchNodeUpdater(
                nodes = nodes,
                renderNodes = renderNodes,
                conversionCache = mutableMapOf(),
                nodeAnimationStates = nodeAnimationStates,
                xmlNodeStreams = mutableMapOf<Int, Stream<String>>(),
                rendererId = "tool-xml-test",
                scope = this,
            )
        val toolNode = MarkdownNode(type = MarkdownProcessorType.XML_BLOCK)
        nodes.add(toolNode)

        val prefix = "<tool name=\"shell\"><param name=\"command\">echo par"
        updater.appendBlockChunk(toolNode, prefix)
        advanceUntilIdle()
        assertEquals(prefix, renderNodes.single().content)

        val tail = "tial</param></tool>"
        updater.appendBlockChunk(toolNode, tail)
        advanceUntilIdle()

        assertEquals(prefix + tail, renderNodes.single().content)
    }

    @Test
    fun equalLengthBlockNodeReplacement_isRendered() = runTest {
        val nodes = mutableStateListOf(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x"))
        val renderNodes = mutableStateListOf<MarkdownNodeStable>()
        val updater =
            BatchNodeUpdater(
                nodes = nodes,
                renderNodes = renderNodes,
                conversionCache = mutableMapOf(),
                nodeAnimationStates = mutableStateMapOf(),
                xmlNodeStreams = mutableMapOf<Int, Stream<String>>(),
                rendererId = "equal-length-block-test",
                scope = this,
            )

        updater.requestUpdate()
        advanceUntilIdle()

        nodes[0] = MarkdownNode(MarkdownProcessorType.BLOCK_LATEX, "x")
        updater.requestStructuralUpdate(0)
        advanceUntilIdle()

        assertEquals(MarkdownProcessorType.BLOCK_LATEX, renderNodes.single().type)
    }

    @Test
    fun equalLengthChildNodeReplacement_isRendered() = runTest {
        val parent = MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x")
        parent.children.add(MarkdownNode(MarkdownProcessorType.PLAIN_TEXT, "x"))
        val nodes = mutableStateListOf(parent)
        val renderNodes = mutableStateListOf<MarkdownNodeStable>()
        val updater =
            BatchNodeUpdater(
                nodes = nodes,
                renderNodes = renderNodes,
                conversionCache = mutableMapOf(),
                nodeAnimationStates = mutableStateMapOf(),
                xmlNodeStreams = mutableMapOf<Int, Stream<String>>(),
                rendererId = "equal-length-child-test",
                scope = this,
            )

        updater.requestUpdate()
        advanceUntilIdle()

        parent.children[0] = MarkdownNode(MarkdownProcessorType.INLINE_LATEX, "x")
        updater.requestStructuralUpdate(0)
        advanceUntilIdle()

        assertEquals(MarkdownProcessorType.INLINE_LATEX, renderNodes.single().children.single().type)
    }
}
