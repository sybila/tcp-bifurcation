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
import com.google.gson.Gson
import cz.muni.fi.sybila.SolverModel
import cz.muni.fi.sybila.extractSinks
import cz.muni.fi.sybila.output.Result
import cz.muni.fi.sybila.output.ResultSet
import cz.muni.fi.sybila.output.State
import java.io.File

private val BLOCK = 1024
private val MSS = 9204
private val rMult = 4
private val R = 1024 * rMult

val max = 64

private class ModelSender(
        solver: IntRectSolver = IntRectSolver(IntRect(intArrayOf(1, max, 1, max)))
) : SolverModel<IParams>, IntervalSolver<IParams> by solver, Solver<IParams> by solver {

    private val model = TCPTransitionSystem()

    val indexToState = ArrayList<TCPState>()
    val stateToIndex = HashMap<TCPState, Int>()
    val stateParams = HashMap<TCPState, IParams>()
    val transitionParams = HashMap<Pair<TCPState, TCPState>, IParams>()
    val successors = ArrayList<IntArray?>()

    init {
        println("Computing state space!")
        val init = TCPState(0, emptyList(), 0, emptyList())
        registerState(init)
        var frontier = setOf(init)
        val s = 48
        val r = 48
        stateParams[init] = iRectOf(1,s,1,r).asParams()
        while (frontier.isNotEmpty()) {
            println("States: ${indexToState.size} frontier: ${frontier.size}")
            val newFrontier = HashSet<TCPState>()
            frontier.forEach { source ->
                // source is registered when it is added to the frontier
                val sourceParams = stateParams[source]!!
                val targets = model.successors(source)
                ensureSuccessors(source, targets.size)
                targets.forEachIndexed { i, (target, p) ->
                    val transition = sourceParams and p
                    if (transition.isSat()) {
                        val cachedTarget = registerState(target)
                        if (stateParams.putOrUnion(cachedTarget, transition)) {
                            newFrontier.add(cachedTarget)
                        }
                        transitionParams.putOrUnion((source to cachedTarget), transition)
                        registerTransition(source, cachedTarget, i)
                    }
                }
            }
            frontier = newFrontier
        }
    }

    private fun <K> MutableMap<K, IParams>.putOrUnion(key: K, value: IParams): Boolean {
        val current = get(key)
        if (current == null) {
            put(key, value)
            return true
        } else if (value.andNot(current)) {
            put(key, current or value)
            return true
        }
        return false
    }

    private fun ensureSuccessors(from: TCPState, maxSuccessors: Int) {
        val index = stateToIndex[from]!!
        if (successors.size < index+1) {
            successors.ensureCapacity(index+1)
            while (successors.size < index+1) successors.add(null)
        }
        if (successors[index] == null) {
            successors[index] = IntArray(maxSuccessors) { -1 }
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

    private fun registerTransition(from: TCPState, to: TCPState, index: Int) {
        val array = successors[stateToIndex[from]!!]!!
        val toIndex = stateToIndex[to]!!
        array[index] = toIndex
    }

    init {
        /*states.forEachIndexed { index, s ->
            println("$index: $s")
        }
        for ((t, p) in transitions) {
            println("${stateToIndex[t.first]}: ${t.first} -> ${stateToIndex[t.second]}: ${t.second} $p")
        }*/
    }

    override val stateCount: Int = stateToIndex.size

    override fun Formula.Atom.Float.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Formula.Atom.Transition.eval(): StateMap<IParams> { error("unimplemented") }
    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<IParams>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<IParams>> {
        if (!timeFlow) error("not implemented") else {
            val s = successors[this]
            return s?.asSequence()?.map { t ->
                if (t >= 0) {
                    Transition(t, DirectionFormula.Atom.True, transitionParams[indexToState[this] to indexToState[t]]!!)
                } else null
            }?.filterNotNull()?.iterator() ?: emptyList<Transition<IParams>>().iterator()
        }
        /*return if (timeFlow) {
            transitions.entries
                    .filter { it.key.first == states[this] }
                    .map { Transition(stateToIndex[it.key.second]!!, DirectionFormula.Atom.True, it.value) }
                    .iterator()
        } else {
            transitions.entries
                    .filter { it.key.second == states[this] }
                    .map { Transition(stateToIndex[it.key.first]!!, DirectionFormula.Atom.True, it.value) }
                    .iterator()
        }*/
    }

    /*init {
        for ((s, p) in transitions) {
            if (s.second !in successor[s.first]!!) error("Fail: $s")
            if (s.first !in predecessor[s.second]!!) error("Fail: $s")
        }
    }*/

    /*fun makeExplicitInt(
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
    }*/

}


fun main(args: Array<String>) {
    val fakeConfig = Config(disableHeuristic = true)
    val system = ModelSender()

    /*val (full, r) = system.makeExplicitInt(fakeConfig).runAnalysisWithSinks(fakeConfig, HashStateMap(system.ff, system.stateMap.map {
        system.stateToIndex[it.key]!! to it.value
    }.toMap()))
    println("Component: ${r.entries().asSequence().count()}")*/
    val allStates = system.stateParams.map { (k, v) -> system.stateToIndex[k]!! to v }.toMap()
    val r = HashStateMap(system.ff, allStates.extractSinks(system))
    /*val one = iRectOf(4,4,1,1).asParams()
    for ((s, p) in r.entries()) {
        system.run {
            //if ((p and one).isSat()) {
                println("${system.states[s]} -> $p")
            //}
        }
    }*/

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
            parameterBounds = listOf(doubleArrayOf(1.0, (max+1).toDouble()), doubleArrayOf(1.0, (max+1).toDouble())),
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
    File("/Users/daemontus/Downloads/TCP_sender.json").writeText(json.toJson(rs))
}

