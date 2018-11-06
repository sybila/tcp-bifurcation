package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.OdeModel
import cz.muni.fi.sybila.deadlock.IParams
import cz.muni.fi.sybila.deadlock.IntRect

/*
private fun isqrt(x: Interval): Interval {
    if (x.x1 < 0) error("Sqrt of negative: $x")
    // sqrt is monotone and positive
    return Interval(sqrt(x.x1), sqrt(x.x2))
}*/

/*
const val maxQ: Double = 100.0
const val minQ: Double = 50.0
const val c: Double = 1500.0
val k: Double = sqrt(8.0/3.0)
const val B: Double = 300.0
const val R0: Double = 0.1
const val M: Double = 0.5
const val n: Double = 20.0
val p0: Double = Math.pow((M*k)/ (R0 * c/n), 2.0)
const val maxP: Double = 0.1
val w: Double = Math.pow(10.0, -1.29)

fun A(avrQ: Double, nextQ: Double): Double = (1 - w) * avrQ + w * nextQ

fun G(p: Double): Double = if (p > p0) 0.0 else {
    val y = (c/M) * ((M*k)/(sqrt(p) * c / n) - R0)
    if (y < B) y else B
}

fun H(q: Double): Double = when (q) {
    in 0.0..minQ -> 0.0
    in minQ..maxQ -> maxP * (q - minQ) / (maxQ - minQ)
    in maxQ..B -> 1.0
    else -> error("Invalid value of queue size $q. Allowed interval is [0.0..$B]")
}

fun next(q: Double) = A(q, G(H(q)))*/
/*
fun iA(avrQ: Interval, nextQ: Interval): Interval = (1 - w).asI() * avrQ + w.asI() * nextQ

fun iG(p: Interval): Interval {
    if (p.x1 >= p0) return 0.0.asI()
    val factor = M * k
    val denominator = (c/n).asI() * isqrt(p)
    val fraction = factor.asI() / denominator
    val result = (c/M).asI() * (fraction - R0.asI())
    return result.restrictUp(B).restrictDown(0.0)
}

// Function is monotonic. That means we can approximate it using
// simple image of the interval, no need for extra treatment.
fun iH(q: Interval): Interval = Interval(H(q.x1), H(q.x2))

fun iNext(q: Interval) = iA(q, iG(iH(q)))
*/

/*
val paramMin = Math.pow(10.0, -1.65)
val paramMax = Math.pow(10.0, -1.05)

class REDModel(
        val pMin: Double, val pMax: Double,
        val solver: RectangleSolver = RectangleSolver(Rectangle(doubleArrayOf(0.0, 1.0)))
) : SolverModel<MutableSet<Rectangle>>, Solver<MutableSet<Rectangle>> by solver {

    private val min = 45.0
    private val max = 65.0
    private val thresholdCount = 1000

    private val thresholds = run {
        val step = (max - min) / (thresholdCount - 1)
        (0 until thresholdCount).map { i -> min + step*i } + max
    }

    private val states: List<Interval> = run {
        thresholds.dropLast(1).zip(thresholds.drop(1)).map { Interval(it.first, it.second) }
    }

    private val transitions: List<Pair<Int, Int>> = run {
        states.indices.flatMap { a -> states.indices.map { b -> a to b } }.filter { (from, to) ->
            val next = iNext(states[from], Interval(pMin, pMax))
            next.intersects(states[to])
        }
    }

    fun iA(avrQ: Interval, nextQ: Interval, w: Interval): Interval = (1.0.asI() - w) * avrQ + w * nextQ

    fun iG(p: Interval): Interval {
        if (p.x1 >= p0) return 0.0.asI()
        val factor = M * k
        val denominator = (c/n).asI() * isqrt(p)
        val fraction = factor.asI() / denominator
        val result = (c/M).asI() * (fraction - R0.asI())
        return result.restrictUp(B).restrictDown(0.0)
    }

    // Function is monotonic. That means we can approximate it using
// simple image of the interval, no need for extra treatment.
    fun iH(q: Interval): Interval = Interval(H(q.x1), H(q.x2))

    fun iNext(q: Interval, w: Interval) = iA(q, iG(iH(q)), w)


    val fakeOdeModel: OdeModel = OdeModel(
            variables = listOf(
                    OdeModel.Variable("q", min to max, thresholds, null, emptyList())
            ),
            parameters = listOf(
                    OdeModel.Parameter("p", paramMin to paramMax)
            )
    )

    override val stateCount: Int = states.size

    override fun Formula.Atom.Float.eval(): StateMap<MutableSet<Rectangle>> {
        error("Unsupported")
    }

    override fun Formula.Atom.Transition.eval(): StateMap<MutableSet<Rectangle>> {
        error("Unsupported")
    }

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<MutableSet<Rectangle>>> = successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<MutableSet<Rectangle>>> {
        return if (timeFlow) {
            transitions
                    .asSequence()
                    .filter { it.first == this }
                    .map { Transition(it.second, DirectionFormula.Atom.True, tt) }
                    .iterator()
        } else {
            transitions
                    .asSequence()
                    .filter { it.second == this }
                    .map { Transition(it.first, DirectionFormula.Atom.True, tt) }
                    .iterator()
        }
    }

}

fun main(args: Array<String>) {
    val pThresholds = 200
    val step = (paramMax - paramMin) / (pThresholds - 1)
    val thresholds = (0 until pThresholds).map { i -> paramMin + step*i } + paramMax
    val paramIntervals = thresholds.dropLast(1).zip(thresholds.drop(1))
    val fakeConfig = Config()
    val solver = RectangleSolver(Rectangle(doubleArrayOf(paramMin, paramMax)))
    solver.run {
        val result = HashStateMap<MutableSet<Rectangle>>(default = mutableSetOf())
        var lastTs: REDModel? = null
        for ((a, b) in paramIntervals) {
            println("Compute $a $b")
            val ts = REDModel(pMin = a, pMax = b, solver = solver)
            lastTs = ts
            val partialResult: StateMap<MutableSet<Rectangle>> = ts.makeExplicit(fakeConfig).runAnalysis(ts.fakeOdeModel, fakeConfig)
            val param = mutableSetOf(rectangleOf(a, b))
            for ((s, _) in partialResult.entries()) {
                result[s] = result[s] or param
            }
        }

        val rs = solver.exportResults(lastTs!!.fakeOdeModel, mapOf("all" to listOf(result)))
        val json = Gson()
        File("/Users/daemontus/Downloads/result1.json").writeText(json.toJson(rs))
    }

}*/

interface SolverModel<P : Any> : Model<P>, Solver<P>

fun SolverModel<MutableSet<Rectangle>>.makeExplicit(
        config: Config
): ExplicitOdeFragment<MutableSet<Rectangle>> {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    val pivotChooser: (ExplicitOdeFragment<MutableSet<Rectangle>>) -> PivotChooser<MutableSet<Rectangle>> = if (config.disableHeuristic) {
        { fragment -> NaivePivotChooser(fragment) }
    } else {
        { fragment -> StructureAndCardinalityPivotChooser(fragment) }
    }

    return ExplicitOdeFragment(this, stateCount, pivotChooser, successors, predecessors)
}

fun SolverModel<MutableSet<IntRect>>.makeExplicitInt(
        config: Config
): ExplicitOdeFragment<MutableSet<IntRect>> {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    val pivotChooser: (ExplicitOdeFragment<IParams>) -> PivotChooser<IParams> = if (config.disableHeuristic) {
        { fragment -> NaivePivotChooser(fragment) }
    } else {
        { fragment -> StructureAndCardinalityPivotChooser(fragment) }
    }

    return ExplicitOdeFragment(this, stateCount, pivotChooser, successors, predecessors)
}


fun <T: Any> ExplicitOdeFragment<T>.runAnalysis(config: Config, initialUniverse: StateMap<T>? = null): StateMap<T> {
    val algorithm = Algorithm(config, this, initialUniverse)

    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { config.logStream?.println("Search elapsed: ${System.currentTimeMillis() - start}ms") }
    }
}