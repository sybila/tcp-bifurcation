package cz.muni.fi.sybila.deadlock

import com.github.sybila.*
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Summand
import com.google.gson.Gson
import cz.muni.fi.sybila.Result
import cz.muni.fi.sybila.ResultSet
import cz.muni.fi.sybila.SolverModel
import cz.muni.fi.sybila.State
import cz.muni.fi.sybila.makeExplicitInt
import cz.muni.fi.sybila.runAnalysis
import java.io.File
import kotlin.math.max
import kotlin.math.min

private val BLOCK = 1024
private val MSS = 9204
private val rMult = 32
private val R = 1024 * rMult

private data class S(
        val toSend: Int,
        val dataChannel: List<Int>,
        val toAck: Int,
        val ackChannel: List<Int>
) {

    val dataSize: Int = dataChannel.sum()
    val ackSize: Int = ackChannel.sum()

}

private class ModelSender(
        val senderBounds: Pair<Int, Int> = 16 to 16,
        solver: IntRectSolver = IntRectSolver(IntRect(intArrayOf(senderBounds.first, senderBounds.second)))
) : SolverModel<IParams>, IntervalSolver<IParams> by solver, Solver<IParams> by solver {

    val states = ArrayList<S>()
    val stateMap = HashMap<S, IParams>()
    val successor = HashMap<S, List<S>>()
    val predecessor = HashMap<S, List<S>>()
    private val transitions = HashMap<Pair<S, S>, IParams>()

    init {
        println("Computing state space!")
        val init = S(0, emptyList(), 0, emptyList())
        val states = HashSet<S>()
        states.add(init)
        var frontier = setOf(init)
        stateMap[init] = tt
        while (frontier.isNotEmpty()) {
            println("States: ${states.size}")
            states.addAll(frontier)
            val newFrontier = HashSet<S>()
            frontier.forEach { source ->
                val sourceParams = stateMap[source]!!
                val print = source == S(toSend=32768, dataChannel= emptyList(), toAck=0, ackChannel= emptyList())
                source.run {
                    if (ackSize > 0) {
                        // receive ack packet
                        val dest = source.copy(ackChannel = ackChannel.drop(1))
                        if (stateMap.putOrUnion(dest, sourceParams)) {
                            states.add(dest); newFrontier.add(dest)
                        }
                        transitions.putOrUnion(source to dest, sourceParams)
                        registerTransition(source, dest)
                    }

                    if (dataSize > 0) {
                        // Receive packet if available (due to window restrictions, toAck should never overflow)
                        val packet = dataChannel[0]
                        if (toAck + packet >= 0.35 * R || toAck + packet >= 2 * MSS) {
                            // Receive and acknowledge
                            val dest = copy(dataChannel = dataChannel.drop(1), toAck = 0, ackChannel = ackChannel + (toAck + packet))
                            if (stateMap.putOrUnion(dest, sourceParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, sourceParams)
                            registerTransition(source, dest)
                        } else {
                            // Just receive
                            val dest = copy(dataChannel = dataChannel.drop(1), toAck = toAck + packet)
                            if (stateMap.putOrUnion(dest, sourceParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, sourceParams)
                            registerTransition(source, dest)
                        }
                    }

                    if (toSend >= MSS) {
                        // sendWindow >= MSS
                        // min(R, S) - dataSize - toAck - ackSize >= MSS

                        // Case 1: R >= S
                        // S - dataSize - toAck - ackSize >= MSS
                        // S >= MSS + dataSize + toAck + ackSize
                        val upperBound = min(senderBounds.second, rMult)
                        val lowerBound = max(senderBounds.first, Math.ceil((MSS + dataSize + toAck + ackSize) / BLOCK.toDouble()).toInt())
                        if (print) println("[$lowerBound, $upperBound]")
                        if (lowerBound <= upperBound) {
                            val dest = copy(toSend = toSend - MSS, dataChannel = dataChannel + MSS)
                            val transitionParams = sourceParams and iRectOf(lowerBound, upperBound).asParams()
                            if (transitionParams.isSat()) {
                                if (stateMap.putOrUnion(dest, transitionParams)) {
                                    states.add(dest); newFrontier.add(dest)
                                }
                                transitions.putOrUnion(source to dest, transitionParams)
                                registerTransition(source, dest)
                            }
                        }

                        // Case 2: R < S
                        // R - dataSize - toAck - ackSize >= MSS
                        if (rMult < senderBounds.second && R - dataSize - toAck - ackSize >= MSS) {
                            val dest = copy(toSend = toSend - MSS, dataChannel = dataChannel + MSS)
                            // rMult+1 <= senderBounds.second -> look up!
                            val transitionParams = sourceParams and iRectOf(rMult+1, senderBounds.second).asParams()
                            if (transitionParams.isSat()) {
                                if (stateMap.putOrUnion(dest, transitionParams)) {
                                    states.add(dest); newFrontier.add(dest)
                                }
                                transitions.putOrUnion(source to dest, transitionParams)
                                registerTransition(source, dest)
                            }
                        }
                    }

                    if (toSend > 0 &&/*toSend in 1..(MSS - 1) && */(dataSize + ackSize + toAck) == 0) {
                        // Send incomplete packet
                        // min(R, S) - dataSize - toAck - ackSize > 0

                        // This one we have to do extra, because the packet size depends on S
                        for (S in senderBounds.first..senderBounds.second) {
                            val window = min(R, S * BLOCK) - dataSize - toAck - ackSize
                            //if (print && S == 8) println("Win: $window")
                            if (window > 1) {
                                val packet = min(window, toSend)
                                if (packet < MSS) {
                                    //if (print && S == 8) println("Packet: $packet")
                                    val dest = copy(toSend = toSend - packet, dataChannel = dataChannel + packet)
                                    val transitionParams = sourceParams and iRectOf(S, S).asParams()
                                    if (transitionParams.isSat()) {
                                        if (stateMap.putOrUnion(dest, transitionParams)) {
                                            states.add(dest); newFrontier.add(dest)
                                        }
                                        transitions.putOrUnion(source to dest, transitionParams)
                                        registerTransition(source, dest)
                                    }
                                }
                            }
                        }
                    }

                    // Copy
                    for (S in senderBounds.first..senderBounds.second) {
                        val free = (S * BLOCK) - toSend - dataSize - toAck - ackSize
                        if (free >= BLOCK) {
                            val toCopy = min(4, free / BLOCK)
                            val dest = copy(toSend = toSend + toCopy * BLOCK)
                            val transitionParams = sourceParams and iRectOf(S, S).asParams()
                            if (transitionParams.isSat()) {
                                if (stateMap.putOrUnion(dest, transitionParams)) {
                                    states.add(dest); newFrontier.add(dest)
                                }
                                transitions.putOrUnion(source to dest, transitionParams)
                                registerTransition(source, dest)
                            }
                        }
                    }

                }
            }
            frontier = newFrontier
        }
        this.states.addAll(states)
    }

    val stateToIndex = states.mapIndexed { index, s -> s to index }.toMap()

    private fun <K> MutableMap<K, IParams>.putOrUnion(key: K, value: IParams): Boolean {
        val current = get(key)
        if (current == null) {
            put(key, value)
            return true
        } else if (current.andNot(value)) {
            put(key, current or value)
            return true
        }
        return false
    }

    private fun registerTransition(from: S, to: S) {
        val succ = successor.getOrDefault(from, emptyList())
        if (to !in succ) successor[from] = succ + to
        val pred = predecessor.getOrDefault(to, emptyList())
        if (from !in pred) predecessor[to] = pred + from
    }

    init {
        states.forEachIndexed { index, s ->
            println("$index: $s")
        }
        for ((t, p) in transitions) {
            println("${stateToIndex[t.first]} -> ${stateToIndex[t.second]} $p")
        }
    }

    override val stateCount: Int = states.size

    override fun Formula.Atom.Float.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Formula.Atom.Transition.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<IParams>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<IParams>> {
        return if (timeFlow) {
            transitions.entries
                    .filter { it.key.first == states[this] }
                    .map { Transition(stateToIndex[it.key.second]!!, DirectionFormula.Atom.True, it.value) }
                    .iterator()
        } else {
            transitions.entries
                    .filter { it.key.second == states[this] }
                    .map { Transition(stateToIndex[it.key.first]!!, DirectionFormula.Atom.True, it.value) }
                    .iterator()
        }
    }

    /*init {
        for ((s, p) in transitions) {
            if (s.second !in successor[s.first]!!) error("Fail: $s")
            if (s.first !in predecessor[s.second]!!) error("Fail: $s")
        }
    }*/

    fun makeExplicitInt(
            config: Config
    ): ExplicitOdeFragment<MutableSet<IntRect>> {
        val step = (stateCount / 1000).coerceAtLeast(100)
        val successors = Array(stateCount) { s ->
            if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
            //val expected = s.successors(true).asSequence().toList()
            val actual = successor.getOrDefault(states[s], emptyList()).map { t ->
                Transition(stateToIndex[t]!!, DirectionFormula.Atom.True, transitions[states[s] to t]!!)
            }
            //if (expected.toSet() != actual.toSet()) error("Fail: $expected $actual")
            actual
        }
        val predecessors = Array(stateCount) { s ->
            if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
            //val expected = s.predecessors(true).asSequence().toList()
            val actual = predecessor.getOrDefault(states[s], emptyList()).map { t ->
                Transition(stateToIndex[t]!!, DirectionFormula.Atom.True, transitions[t to states[s]]!!)
            }
            //if (expected.toSet() != actual.toSet()) error("Fail: $expected $actual")
            actual
        }

        val pivotChooser: (ExplicitOdeFragment<IParams>) -> PivotChooser<IParams> = if (config.disableHeuristic) {
            { fragment -> NaivePivotChooser(fragment) }
        } else {
            { fragment -> StructureAndCardinalityPivotChooser(fragment) }
        }

        return ExplicitOdeFragment(this, stateCount, pivotChooser, successors, predecessors)
    }

}

fun main(args: Array<String>) {
    val fakeConfig = Config()
    val system = ModelSender()
    /*IntRectSolver(iRectOf(24, 24)).run {
        val a = iRectOf(24, 24).asParams()
        val b = iRectOf(17, 24).asParams()
        println("And: ${a and b}")
    }*/

    val r = system.makeExplicitInt(fakeConfig).runAnalysis(fakeConfig, HashStateMap(system.ff, system.stateMap.map {
        system.stateToIndex[it.key]!! to it.value
    }.toMap()))
    /*for ((s, p) in r.entries()) {
        println("${system.states[s]} -> $p")
    }*/

    val pValues = r.entries().asSequence().map { it.second }.toSet().toList()
    val pIndices = pValues.mapIndexed { i, set -> set to i }.toMap()

    val ackThresholds = r.entries().asSequence().flatMap {
        sequenceOf((system.states[it.first].toAck - 100).toDouble(), (system.states[it.first].toAck + 100).toDouble())
    }.toSet().sorted()
    val sendThresholds = r.entries().asSequence().flatMap {
        sequenceOf((system.states[it.first].toSend - 100).toDouble(), (system.states[it.first].toSend + 100).toDouble())
    }.toSet().sorted()
    val fakeOdeModel = OdeModel(listOf(
            OdeModel.Variable("to_ack", ackThresholds.first() to ackThresholds.last(), ackThresholds, null, emptyList()),
            OdeModel.Variable("to_send", sendThresholds.first() to sendThresholds.last(), sendThresholds, null, emptyList())
    ))

    val encoder = NodeEncoder(fakeOdeModel)

    val rs = ResultSet(
            variables = listOf("to_ack", "to_send"),
            parameters = listOf("s_buf"),
            thresholds = listOf(ackThresholds, sendThresholds),
            parameterBounds = listOf(doubleArrayOf(system.senderBounds.first.toDouble(), system.senderBounds.second.toDouble())),
            states = r.entries().asSequence().map { j ->
                val s = system.states[j.first]
                // compute state id:
                val iAck = ackThresholds.indexOf((s.toAck - 100).toDouble())
                val iSend = sendThresholds.indexOf((s.toSend - 100).toDouble())
                State(encoder.encodeNode(intArrayOf(iAck, iSend)).toLong(), arrayOf(
                        doubleArrayOf(ackThresholds[iAck], ackThresholds[iAck+1]),
                        doubleArrayOf(sendThresholds[iSend], sendThresholds[iSend+1])
                ))
            }.toList(),
            type = "rectangular",
            parameterValues = pValues.map { p ->
                p.map { it.asIntervals() }.toTypedArray()
            },
            results = listOf(
                    Result(
                            formula = "tscc",
                            data = r.entries().asSequence().mapIndexed { i, res ->
                                intArrayOf(i, pIndices[res.second]!!)
                            }.toList()
                    )
            )
    )

    val json = Gson()
    File("/Users/daemontus/Downloads/TCP_sender.json").writeText(json.toJson(rs))
}