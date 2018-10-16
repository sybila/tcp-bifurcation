package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.solver.BoolSolver
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import java.io.File
import kotlin.math.sqrt

data class Interval(
        val x1: Double, val x2: Double
) {

    init {
        if (x2 < x1) error("Invalid interval $x1 .. $x2")
    }

    operator fun plus(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(x1 + y1, x2 + y2)
    }

    operator fun minus(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(x1 - y2, x2 - y1)
    }

    operator fun times(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(
                min(x1*y1, x1*y2, x2*y1, x2*y2),
                max(x1*y1, x1*y2, x2*y1, x2*y2)
        )
    }

    private fun inverse(): Interval {
        //if (x1 <= 0 && 0 <= x2) error("Division by zero")
        return Interval(1.0 / x2, 1.0 / x1)
    }

    operator fun div(y: Interval): Interval = this * y.inverse()

    fun restrictUp(y: Double) = Interval(min(x1, y), max(min(x1, y), min(x2, y)))
    fun restrictDown(y: Double) = Interval(max(x1, y), x2)

    override fun toString(): String {
        return "[$x1, $x2]"
    }

    fun intersects(other: Interval): Boolean = if (this.x1 <= other.x1) {
        this.x2 >= other.x1
    } else {
        other.x2 >= this.x1
    }

    fun union(other: Interval): Interval = Interval(min(this.x1, other.x1), max(this.x2, other.x2))

}

private fun isqrt(x: Interval): Interval {
    if (x.x1 < 0) error("Sqrt of negative: $x")
    // sqrt is monotone and positive
    return Interval(sqrt(x.x1), sqrt(x.x2))
}

fun Double.asI(): Interval = Interval(this, this)
fun Double.asI(delta: Double): Interval = Interval(this - delta, this + delta)

private fun min(vararg x: Double): Double = x.fold(Double.POSITIVE_INFINITY) { a, i -> if (a < i) a else i }
private fun max(vararg x: Double): Double = x.fold(Double.NEGATIVE_INFINITY) { a, i -> if (a > i) a else i }

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

fun next(q: Double) = A(q, G(H(q)))

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

class REDModel(
        val solver: RectangleSolver = RectangleSolver(Rectangle(doubleArrayOf(0.0, 1.0)))
) : Model<MutableSet<Rectangle>>, Solver<MutableSet<Rectangle>> by solver {

    private val min = 45.0
    private val max = 65.0
    private val thresholdCount = 200

    private val thresholds = run {
        val step = (max - min) / (thresholdCount - 1)
        (0 until thresholdCount).map { i -> min + step*i } + max
    }

    private val states: List<Interval> = run {
        thresholds.dropLast(1).zip(thresholds.drop(1)).map { Interval(it.first, it.second) }
    }

    private val transitions: List<Pair<Int, Int>> = run {
        states.indices.flatMap { a -> states.indices.map { b -> a to b } }.filter { (from, to) ->
            val next = iNext(states[from])
            next.intersects(states[to])
        }
    }

    val fakeOdeModel: OdeModel = OdeModel(
            variables = listOf(
                    OdeModel.Variable("q", min to max, thresholds, null, emptyList())
            ),
            parameters = listOf(
                    OdeModel.Parameter("p", 0.0 to 1.0)
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
    val ts = REDModel()
    val fakeConfig = Config()
    val result = ts.makeExplicit(fakeConfig).runAnalysis(ts.fakeOdeModel, fakeConfig)

    val json = Gson()
    File("/Users/daemontus/Downloads/result1.json").writeText(json.toJson(result))
}

private fun REDModel.makeExplicit(
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

    return ExplicitOdeFragment(this.solver, stateCount, pivotChooser, successors, predecessors)
}

private fun <T: Any> ExplicitOdeFragment<T>.runAnalysis(odeModel: OdeModel, config: Config): ResultSet {
    val algorithm = Algorithm(config, this, odeModel)

    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { config.logStream?.println("Search elapsed: ${System.currentTimeMillis() - start}ms") }
    }
}