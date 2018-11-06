package cz.muni.fi.sybila.deadlock

import com.github.sybila.Config
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
import kotlin.math.min

private val BLOCK = 1024
private val MSS = 9204
private val rMult = 24
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
        val senderBounds: Pair<Int, Int> = 48 to 48,
        solver: IntRectSolver = IntRectSolver(IntRect(intArrayOf(senderBounds.first, senderBounds.second)))
) : SolverModel<IParams>, IntervalSolver<IParams> by solver, Solver<IParams> by solver {

    val states = ArrayList<S>()
    val stateMap = HashMap<S, IParams>()
    private val transitions = HashMap<Pair<S, S>, IParams>()

    init {
        println("Computing state space!")
        val states = HashSet<S>()
        val init = S(0, emptyList(), 0, emptyList())
        var frontier = setOf(init)
        stateMap[init] = tt
        while (frontier.isNotEmpty()) {
            //println("States: ${states}")
            states.addAll(frontier)
            val newFrontier = HashSet<S>()
            frontier.forEach { source ->
                val sourceParams = stateMap[source]!!
                source.run {
                    if (ackSize > 0) {
                        // receive ack packet
                        val dest = source.copy(ackChannel = ackChannel.drop(1))
                        if (stateMap.putOrUnion(dest, sourceParams)) {
                            states.add(dest); newFrontier.add(dest)
                        }
                        transitions.putOrUnion(source to dest, sourceParams)
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
                        } else {
                            // Just receive
                            val dest = copy(dataChannel = dataChannel.drop(1), toAck = toAck + packet)
                            if (stateMap.putOrUnion(dest, sourceParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, sourceParams)
                        }
                    }

                    if (toSend >= MSS) {
                        // sendWindow >= MSS
                        // min(R, S) - dataSize - toAck - ackSize >= MSS

                        // Case 1: R >= S
                        // S - dataSize - toAck - ackSize >= MSS
                        // S >= MSS + dataSize + toAck + ackSize
                        val upperBound = rMult
                        val lowerBound = MSS + dataSize + toAck + ackSize
                        if (lowerBound <= upperBound) {
                            val dest = copy(toSend = toSend - MSS, dataChannel = dataChannel + MSS)
                            val transitionParams = sourceParams and iRectOf(lowerBound, upperBound).asParams()
                            if (stateMap.putOrUnion(dest, transitionParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, transitionParams)
                        }

                        // Case 2: R < S
                        // R - dataSize - toAck - ackSize >= MSS
                        if (R < senderBounds.second && R - dataSize - toAck - ackSize >= MSS) {
                            val dest = copy(toSend = toSend - MSS, dataChannel = dataChannel + MSS)
                            val transitionParams = sourceParams and iRectOf(R+1, senderBounds.second).asParams()
                            if (stateMap.putOrUnion(dest, transitionParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, transitionParams)
                        }
                    }

                    if (toSend in 1..(MSS - 1) && (dataSize + ackSize + toAck) == 0) {
                        // Send incomplete packet
                        // min(R, S) - dataSize - toAck - ackSize > 0

                        // This one we have to do extra, because the packet size depends on S
                        for (S in senderBounds.first..senderBounds.second) {
                            val window = min(R, S * BLOCK) - dataSize - toAck - ackSize
                            if (window > 0) {
                                val packet = min(window, toSend)
                                val dest = copy(toSend = toSend - packet, dataChannel = dataChannel + packet)
                                val transitionParams = sourceParams and iRectOf(S, S).asParams()
                                if (stateMap.putOrUnion(dest, transitionParams)) {
                                    states.add(dest); newFrontier.add(dest)
                                }
                                transitions.putOrUnion(source to dest, transitionParams)
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
                            if (stateMap.putOrUnion(dest, transitionParams)) {
                                states.add(dest); newFrontier.add(dest)
                            }
                            transitions.putOrUnion(source to dest, transitionParams)
                        }
                    }

                }
            }
            frontier = newFrontier
        }
        println(transitions.toString())
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

    /*init {
        for ((t, p) in transitions) {
            println("${stateToIndex[t.first]} -> ${stateToIndex[t.second]} $p")
        }
    }*/

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

}

fun main(args: Array<String>) {
    val fakeConfig = Config()
    val system = ModelSender()

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