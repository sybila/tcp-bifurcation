package cz.muni.fi.sybila.deadlock

import com.github.sybila.Config
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import cz.muni.fi.sybila.SolverModel
import cz.muni.fi.sybila.output.Result
import cz.muni.fi.sybila.output.ResultSet
import cz.muni.fi.sybila.output.State
import java.io.File

private class ModelSender(
        // A scale factor is used to skip some parameter values
        SCALE: Int = 1,
        // Maximal parameter bound
        private val MAX: Int = 64,
        // true if one random acknowledgement can be sent
        randomAck: Boolean = true,
        solver: IntRectSolver = IntRectSolver(IntRect(intArrayOf(1, MAX, 1, MAX)))
) : SolverModel<IParams>, IntervalSolver<IParams> by solver, Solver<IParams> by solver {

    private val model = TCPTransitionSystem(SCALE, MAX, randomAck)

    val indexToState = ArrayList<TCPState>()
    val stateToIndex = HashMap<TCPState, Int>()
    val stateParams = ArrayList<IParams>()
    val sinks = HashMap<Int, IParams>()

    init {
        println("Computing state space!")
        val init = TCPState(0, emptyList(), 0, emptyList())
        registerState(init)
        var frontier = setOf(init)
        val s = MAX
        val r = MAX
        extendStateParams(init, iRectOf(1,s,1,r).asParams())
        var flip = true
        while (frontier.isNotEmpty()) {
            flip = !flip
            if (flip) println("States: ${indexToState.size} frontier: ${frontier.size}")
            val newFrontier = HashSet<TCPState>()
            frontier.forEach { source ->
                // source is registered when it is added to the frontier
                val sourceParams = stateParams[stateToIndex[source]!!]
                val targets = model.successors(source)
                targets.forEach { (target, p) ->
                    val transition = sourceParams and p
                    if (transition.isSat()) {
                        val cachedTarget = registerState(target)
                        if (extendStateParams(cachedTarget, transition)) {
                            newFrontier.add(cachedTarget)
                        }
                    }
                }
                val sinkParams = targets.fold(sourceParams) { sinkParams, (t, p) ->
                    sinkParams and (if (t == source) tt else p.not())
                }
                if (sinkParams.isSat()) {
                    synchronized(sinks) {
                        sinks[s] = (sinks[s] ?: ff) or sinkParams
                    }
                }
            }
            frontier = newFrontier
        }
    }

    private fun extendStateParams(state: TCPState, params: IParams): Boolean {
        val stateIndex = stateToIndex[state]!!
        if (stateParams.size < stateIndex+1) {
            stateParams.ensureCapacity(stateIndex+1)
            while (stateParams.size < stateIndex+1) stateParams.add(ff)
        }
        val current = stateParams[stateIndex]
        return if (params.andNot(current)) {
            stateParams[stateIndex] = params or current
            true
        } else {
            false
        }
    }

    // returns a cached copy of the state or the given copy if the state is new
    private fun registerState(state: TCPState): TCPState {
        return if (state !in stateToIndex) {
            indexToState.add(state)
            val newIndex = indexToState.lastIndex
            stateToIndex[state] = newIndex
            state
        } else {
            indexToState[stateToIndex[state]!!]
        }
    }

    override val stateCount: Int = stateToIndex.size

    override fun Formula.Atom.Float.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Formula.Atom.Transition.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<IParams>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<IParams>> {
        error("not implemented")
    }

}

fun runExperiment(scale: Int, maxParam: Int, randomAck: Boolean, output: File) {
    val system = ModelSender(scale, maxParam, randomAck)

    val r = HashStateMap(system.ff, system.sinks)
    val pValues = r.entries().asSequence().map { it.second }.toSet().toList()
    val pIndices = pValues.mapIndexed { i, set -> set to i }.toMap()

    val ackThresholds = r.entries().asSequence().flatMap {
        sequenceOf((system.indexToState[it.first].toAck - 100).toDouble(), (system.indexToState[it.first].toAck + 100).toDouble())
    }.toSet().sorted()
    val sendThresholds = r.entries().asSequence().flatMap {
        sequenceOf((system.indexToState[it.first].toSend - 100).toDouble(), (system.indexToState[it.first].toSend + 100).toDouble())
    }.toSet().sorted()
    val fakeOdeModel = OdeModel(listOf(
            OdeModel.Variable("to_ack", ackThresholds.first() to ackThresholds.last(), ackThresholds, null, emptyList()),
            OdeModel.Variable("to_send", sendThresholds.first() to sendThresholds.last(), sendThresholds, null, emptyList())
    ))

    val encoder = NodeEncoder(fakeOdeModel)

    val rs = ResultSet(
            variables = listOf("to_ack", "to_send"),
            parameters = listOf("s_buf", "r_buf"),
            thresholds = listOf(ackThresholds, sendThresholds),
            parameterBounds = listOf(doubleArrayOf(1.0, (maxParam+1).toDouble()), doubleArrayOf(1.0, (maxParam+1).toDouble())),
            states = r.entries().asSequence().map { j ->
                val s = system.indexToState[j.first]
                // compute state id:
                val iAck = ackThresholds.indexOf((s.toAck - 100).toDouble())
                val iSend = sendThresholds.indexOf((s.toSend - 100).toDouble())
                State(encoder.encodeNode(intArrayOf(iAck, iSend)).toLong(), arrayOf(
                        doubleArrayOf(ackThresholds[iAck], ackThresholds[iAck + 1]),
                        doubleArrayOf(sendThresholds[iSend], sendThresholds[iSend + 1])
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
    output.writeText(json.toJson(rs))
}

