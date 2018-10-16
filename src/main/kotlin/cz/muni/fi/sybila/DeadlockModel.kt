package cz.muni.fi.sybila

import com.github.sybila.checker.Solver
import com.github.sybila.checker.solver.BitSetSolver
import cz.muni.fi.sybila.DeadlockModel.State.IncompletePacketLocation.*
import cz.muni.fi.sybila.DeadlockModel.State.SenderState.COPY
import cz.muni.fi.sybila.DeadlockModel.State.SenderState.SEND
import java.io.File
import java.util.*
import java.util.stream.Collectors
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.streams.asSequence

typealias Params = BitSet

private const val COPY_BLOCK = 1024
private const val COPY_THRESHOLD = 4096
private const val MSS = 9204

class DeadlockModel(
        private val sBufMax: Int, private val rBufMax: Int, solver: Solver<Params>
) : Solver<Params> by solver {

    // all parameters go in interval [1..max]

    fun step(from: State): Map<State, Params> = from.stepInternal()

    private fun State.stepInternal(): Map<State, Params> {
        val transitions = HashMap<State, Params>()

        fun HashMap<State, Params>.add(state: State, params: Params) {
            if (state !in this) this[state] = params else {
                this[state] = this[state]!! or params
            }
        }

        // copy_from_user
        if (sender == COPY && copied < COPY_THRESHOLD) {
            // S - U - T - R - A >= BLOCK
            // S >= BLOCK + U + T + R + A
            val threshold = COPY_BLOCK + unsent + inTransit + received + acknowledged
            val params = cutXAbove(threshold)
            if (params.isSat()) {
                val state = copy(unsent = unsent + COPY_BLOCK, copied = copied + COPY_BLOCK)
                transitions.add(state, params)
            }
        }

        // try_send_after_copy
        if (sender == COPY && copied >= COPY_THRESHOLD) {
            transitions.add(copy(copied = 0, sender = SEND), tt)
        }

        // send_full_frame
        if (sender == SEND && inTransit == 0 && unsent >= MSS) {
            transitions.add(copy(inTransit = MSS, unsent = unsent - MSS, sender = COPY), tt)
        }

        // send_partial_frame
        if (sender == SEND && inTransit == 0 && unsent in (1 until MSS) && incompletePacked == NONE) {
            transitions.add(copy(inTransit = unsent, unsent = 0, sender = COPY, incompletePacked = TRANSIT), tt)
        }

        // send_nothing (we are in send situation, but we have nothing to send)
        if (sender == SEND && inTransit == 0 && unsent in (0 until MSS) && (incompletePacked != NONE || unsent == 0)) {
            transitions.add(copy(sender = COPY), ff)
        }

        // receive and update based on buffer size
        if (inTransit > 0 && inTransit + received < 2 * MSS) {
            // 0.35 * R > transit + received
            // R >= ceil((transit + received) / 0.35)
            val threshold = Math.ceil((inTransit + received) * 0.35).toInt()
            val aboveInclusive = cutYAbove(threshold)
            val below = aboveInclusive.not()
            // R >= threshold - no update
            if (aboveInclusive.isSat()) {
                val state = copy(received = received + inTransit, inTransit = 0,
                        incompletePacked = if (incompletePacked == TRANSIT) RECEIVED else incompletePacked
                )
                transitions.add(state, aboveInclusive)
            }

            // R < threshold
            if (below.isSat()) {
                val state = copy(received = 0, inTransit = 0, acknowledged = acknowledged + received + inTransit,
                        incompletePacked = if (incompletePacked in listOf(TRANSIT, RECEIVED)) ACKNOWLEDGED else incompletePacked
                )
                transitions.add(state, below)
            }
        }

        // receive and update based on MSS
        if (inTransit > 0 && inTransit + received >= 2 * MSS) {
            val state = copy(received = 0, inTransit = 0, acknowledged = acknowledged + received + inTransit,
                    incompletePacked = if (incompletePacked in listOf(TRANSIT, RECEIVED)) ACKNOWLEDGED else incompletePacked
            )
            transitions.add(state, tt)
        }

        // ack
        if (acknowledged > 0) {
            val state = copy(acknowledged = 0, sender = SEND,
                    incompletePacked = if (incompletePacked == ACKNOWLEDGED) NONE else incompletePacked
            )
            transitions.add(state, tt)
        }

        return transitions
    }

    // return bit set representing [t..max][1..max]
    private fun cutXAbove(threshold: Int): Params {
        if (threshold <= 1) return tt
        if (threshold > sBufMax) return ff
        val set = tt.clone() as BitSet
        val toClear = threshold - 1
        set.clear(0, toClear * rBufMax)
        return set
    }

    // return bit set representing [1..max][t..max]
    private fun cutYAbove(threshold: Int): Params {
        if (threshold <= 1) return tt
        if (threshold > rBufMax) return ff
        val set = tt.clone() as BitSet
        val toClear = threshold - 1
        for (row in 0 until sBufMax) {
            set.clear(row * rBufMax, row * rBufMax + toClear)
        }
        return set
    }

    data class State(
            val unsent: Int = 0, val inTransit: Int = 0, val received: Int = 0, val acknowledged: Int = 0, val copied: Int = 0,
            val incompletePacked: IncompletePacketLocation = NONE, val sender: SenderState = COPY
    ) {

        enum class IncompletePacketLocation {
            NONE, TRANSIT, RECEIVED, ACKNOWLEDGED
        }

        enum class SenderState {
            COPY, SEND
        }

        override fun toString(): String {
            fun incomplete(location: IncompletePacketLocation) = if (incompletePacked == location) "(P)" else ""
            return "{...|$unsent${incomplete(NONE)}|$inTransit${incomplete(TRANSIT)}|$received${incomplete(RECEIVED)}|$acknowledged${incomplete(ACKNOWLEDGED)}|...; $copied, $sender}"
        }
    }

}

fun main(args: Array<String>) {
    val sBufMax = 4096*2
    val rBufMax = 4096*2
    val total = sBufMax * rBufMax
    println("Parameter count: $total")
    val solver = BitSetSolver(total)
    solver.run {
        val model = DeadlockModel(sBufMax, rBufMax, solver)

        val reachable = HashMap<DeadlockModel.State, Params>()
        var recompute = setOf(DeadlockModel.State())
        while (recompute.isNotEmpty()) {
            val newRecompute = HashSet<DeadlockModel.State>()
            recompute.flatMap { model.step(it).entries }.forEach { (s, p) ->
                val original = reachable[s]
                if (original == null) {
                    reachable[s] = p
                    newRecompute.add(s)
                } else {
                    val merged = original or p
                    if ((merged and original.not()).isSat()) {
                        reachable[s] = merged
                        newRecompute.add(s)
                    }
                }
            }
            recompute = newRecompute
            println("Recompute: ${recompute.size} of total ${reachable.size}")
        }

        val deadlocks = reachable.filterKeys { model.step(it).isEmpty() }.map { it.key }
        println("Found ${deadlocks.size} deadlocks in:")
        deadlocks.forEach { println(it) }

        val deadlockParams = deadlocks.fold(ff) { p, s -> p or reachable[s]!! }
        println("Deadlock params count: ${deadlockParams.cardinality()}")
        val out = File("/Users/daemontus/Downloads/deadlock.txt")
        val outText = StringBuilder()
        for (row in 0 until sBufMax) {
            for (col in 0 until rBufMax) {
                outText.append(if (deadlockParams[row * rBufMax + col]) 1 else 0)
            }
            outText.append("\n")
        }
        out.writeText(outText.toString())

    }
}