package cz.muni.fi.sybila

import com.github.sybila.Config
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import cz.muni.fi.sybila.red.Interval
import cz.muni.fi.sybila.red.asI
import java.io.File

/*
//Settings from the shorter paper with more "high performance" network settings
object ConstDefault {
    val qMin = 250.0
    val qMax = 750.0
    val pMax = 0.1
    val N = 250.0
    val M = 4000.0
    val K = Math.sqrt(3.0/2.0)
    val B = 3750.0
    val d = 0.1
    val ConstDefault = 75000000.0
    val pL = run {
        val x = (N * M * K) / (d * ConstDefault + B * M)
        x * x
    }
    val pU = run {
        val x = (N * M * K) / (d * ConstDefault)
        x * x
    }
}*/


//First setting in the longer paper, shows bifurcation with respect to w
object C {
    val qMin = 250.0
    val qMax = 750.0
    val pMax = 0.1
    val N = 250
    val M = 4000
    val K = Math.sqrt(3.0/2.0)
    val B = 3750.0
    val d = 0.1
    val C = 75000000
    val pL = run {
        val x = (N * M * K) / (d * C + B * M)
        x * x
    }
    val pU = run {
        val x = (N * M * K) / (d * C)
        x * x
    }
}

/*
object ConstDefault {
    val qMin = 75.0
    val qMax = 100.0
    val pMax = 0.3
    val N = 20
    val M = 500
    val K = Math.sqrt(8.0/3.0)
    val B = 300.0
    val d = 0.1
    val ConstDefault = 1500000
    val pL = run {
        val x = (N * M * K) / (d * ConstDefault + B * M)
        x * x
    }
    val pU = run {
        val x = (N * M * K) / (d * ConstDefault)
        x * x
    }
}
*/

class RED2simulation(
        private val w: Double
) {

    fun dropProbability(q: Double) = when (q) {
        in Double.NEGATIVE_INFINITY..C.qMin -> 0.0
        in C.qMin..C.qMax -> ((q - C.qMin) / (C.qMax - C.qMin)) * C.pMax
        in C.qMax..Double.POSITIVE_INFINITY -> 1.0
        else -> error("Value $q is not a number")
    }

    fun nextQueue(p: Double) = when (p) {
        in 0.0..C.pL -> C.B
        in C.pL..C.pU -> (C.N*C.K)/Math.sqrt(p) - (C.C*C.d)/C.M
        in C.pU..1.0 -> 0.0
        else -> error("$p is not a valid probability in [0..1]")
    }.let { q -> if (q < 0.0) 0.0 else q }

    fun average(q: Double, w: Double) = (1.0 - w) * q + w * nextQueue(dropProbability(q))

    fun next(q: Double) = average(q, w)

}

typealias RParams = MutableSet<Rectangle>

class REDNoParam(
        private val w: Double,
        solver: RectangleSolver = RectangleSolver(rectangleOf(0.0, 1.0))
) : SolverModel<RParams>, Solver<RParams> by solver, IntervalSolver<RParams> by solver {

    private val sim = RED2simulation(w)

    private val min = 200.0
    private val max = 500.0
    private val tCount = 5000

    private val thresholds = run {
        val step = (max - min) / (tCount - 1)
        println("State step: $step")
        (0 until tCount).map { i -> min + step*i } + max
    }

    val states: List<Interval> = run {
        thresholds.dropLast(1).zip(thresholds.drop(1)).map { Interval(it.first, it.second) }
    }

    /* Drop probability and avr. queue are both monotonic, hence we can simply evaluate them. */

    // monotonic increasing
    private fun dropProbability(avrQueue: Interval) = Interval(
            sim.dropProbability(avrQueue.x1), sim.dropProbability(avrQueue.x2)
    )


    // monotonic decreasing
    private fun nextQueue(dropProb: Interval) = Interval(
            sim.nextQueue(dropProb.x2), sim.nextQueue(dropProb.x1)
    )

    fun average(q: Interval) = ((1.0 - w).asI() * q) + (w.asI() * nextQueue(dropProbability(q)))

    private val transitions: List<Pair<Int, Int>> = run {
        val jumpsTo = states.map { from -> average(from).also { to ->
            if (to.x1 < min || to.x2 > max) println("Warning: $from jumps out of bounds to $to")
        } }
        /*for (i in states.indices) {
            println("${states[i].x1} ${average(states[i]).x1}".replace('.', ','))
        }*/
        states.indices.asSequence().flatMap { a -> states.indices.asSequence().map { b -> a to b } }.filter { (from, to) ->
            if (from % 1000 == 0 && to == 0) println("transitions: $from/${states.size}")
            jumpsTo[from].intersects(states[to])
        }.toList()//.also { println(it) }
    }

    override val stateCount: Int = states.size

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<RParams>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<RParams>> {
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

    val fakeOdeModel: OdeModel = OdeModel(
            variables = listOf(
                    OdeModel.Variable("q", min to max, thresholds, null, emptyList())
            ),
            parameters = listOf(
                    OdeModel.Parameter("p", 0.0 to 1.0)
            )
    )

    override fun Formula.Atom.Float.eval(): StateMap<RParams> {
        TODO("not implemented")
    }

    override fun Formula.Atom.Transition.eval(): StateMap<RParams> {
        TODO("not implemented")
    }

}

class REDParam(
        private val pMin: Double = 0.1, // Math.pow(10.0, -1.35), // 0.0446
        private val pMax: Double = 0.3, // Math.pow(10.0, -1.25),
        solver: RectangleSolver = RectangleSolver(rectangleOf(pMin, pMax))
) : SolverModel<RParams>, IntervalSolver<RParams> by solver, Solver<RParams> by solver {

    private val min = 300.0
    private val max = 500.0
    private val tCount = 1000

    private val thresholds = run {
        val step = (max - min) / (tCount - 1)
        println("State step: $step")
        (0 until tCount).map { i -> min + step*i } + max
    }

    val states: List<Interval> = run {
        thresholds.dropLast(1).zip(thresholds.drop(1)).map { Interval(it.first, it.second) }
    }

    override val stateCount: Int = states.size

    private fun numDropProbability(q: Double) = when (q) {
        in Double.NEGATIVE_INFINITY..C.qMin -> 0.0
        in C.qMin..C.qMax -> ((q - C.qMin) / (C.qMax - C.qMin)) * C.pMax
        in C.qMax..Double.POSITIVE_INFINITY -> 1.0
        else -> error("Value $q is not a number")
    }

    private fun numNextQueue(p: Double) = when (p) {
        in 0.0..C.pL -> C.B
        in C.pL..C.pU -> (C.N*C.K)/Math.sqrt(p) - (C.C*C.d)/C.M
        in C.pU..1.0 -> 0.0
        else -> error("$p is not a valid probability in [0..1]")
    }.let { q -> if (q < 0.0) 0.0 else q }

    // monotonic increasing
    private fun dropProbability(avrQueue: Interval) = Interval(
            numDropProbability(avrQueue.x1), numDropProbability(avrQueue.x2)
    )


    // monotonic decreasing
    private fun nextQueue(dropProb: Interval) = Interval(
            numNextQueue(dropProb.x2), numNextQueue(dropProb.x1)
    )

    private val r = 1000.0
    private fun ceil(d: Double) = Math.ceil(d * r) / r
    private fun floor(d: Double) = Math.floor(d * r) / r

    private val transitionArray: Array<Array<RParams?>> = Array(states.size) { from ->
        val next = nextQueue(dropProbability(states[from]))
        Array(states.size) { to ->
            if (from % 100 == 0 && to == 0) println("transitions: $from/${states.size}")
            val d = next - states[from]
            val f = states[to] - states[from]
            //if (from == 3) println("$f/$d")
            if (d.x1 <= 0 && 0 <= d.x2) {
                /*// Division by zero!
                val y1 = Interval(Double.NEGATIVE_INFINITY, 1.0 / d.x1)
                println("$f * $y1")
                val w1: Interval? = (f * y1)/*.takeIf {
                    !(it.x2 < pMin || it.x1 > pMax)
                }*/
                val y2 = Interval(1.0 / d.x2, Double.POSITIVE_INFINITY)
                println("$f * $y2")
                val w2: Interval? = (f * y2)/*.takeIf {
                    !(it.x2 < pMin || it.x1 > pMax)
                }*/
                println("Split $to into $w1 and $w2")*/
                val wI = Interval(pMin, pMax)
                //println("$to: $f intersects ${wI * d} = ${f.intersects(wI * d)}")
                // close enough :(
                tt.takeIf { f.intersects(wI * d) }
            } else {
                val w = f / d
                if (w.x2 < pMin || w.x1 > pMax) null else {
                    val r = mutableSetOf(rectangleOf(Math.max(pMin, floor(w.x1)), Math.min(pMax, ceil(w.x2))))
                    (r and tt).takeIf { it.isSat() }
                }
            }
        }
    }/*.also { t ->
        t.forEachIndexed { i, succ ->
            println("$i : ${succ.mapIndexedNotNull { j, s -> j.takeIf { s != null } }}")
        }
    }*/
/*
    private val transitions: Map<Pair<Int, Int>, RParams> = run {
        val nextQueue = states.map { from -> nextQueue(dropProbability(from)) }
        states.indices.asSequence().flatMap { a -> states.indices.asSequence().map { b -> a to b } }.mapNotNull { (from, to) ->
            if (from % 100 == 0 && to == 0) println("transitions: $from/${states.size}")
            val w = (states[to] - states[from]) / (nextQueue[from] - states[from])
            if (w.x2 < pMin || w.x1 > pMax) null else {
                val r = mutableSetOf(rectangleOf(Math.max(w.x1, pMin), Math.min(w.x2, pMax)))
                val params = r.takeIf { it.isSat() }
                params?.let { (from to to) to params }
            }
        }.toMap().also { println(it) }
    }*/

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<RParams>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<RParams>> {
        val source = this
        return if (timeFlow) {
            /*transitions
                    .asSequence()
                    .filter { it.key.first == this }
                    .map { Transition(it.key.second, DirectionFormula.Atom.True, it.value) }
                    .iterator()*/
            states.indices.mapNotNull { target ->
                transitionArray[source][target]?.let { Transition(target, DirectionFormula.Atom.True, it) }
            }.iterator()
        } else {
            /*transitions
                    .asSequence()
                    .filter { it.key.second == this }
                    .map { Transition(it.key.first, DirectionFormula.Atom.True, it.value) }
                    .iterator()*/
            states.indices.mapNotNull { target ->
                transitionArray[target][source]?.let { Transition(target, DirectionFormula.Atom.True, it) }
            }.iterator()
        }
    }

    val fakeOdeModel: OdeModel = OdeModel(
            variables = listOf(
                    OdeModel.Variable("q", min to max, thresholds, null, emptyList())
            ),
            parameters = listOf(
                    OdeModel.Parameter("p", pMin to pMax)
            )
    )

    override fun Formula.Atom.Float.eval(): StateMap<RParams> {
        TODO("not implemented")
    }

    override fun Formula.Atom.Transition.eval(): StateMap<RParams> {
        TODO("not implemented")
    }


}


fun main(args: Array<String>) {

    val fakeConfig = Config()
    val w = Math.pow(10.0, -1.35)

    val ts = REDParam()

    val r = ts.makeExplicit(fakeConfig).runAnalysis(fakeConfig)
    val rs = ts.exportResults(ts.fakeOdeModel, mapOf("all" to listOf(r)))

    val json = Gson()
    File("/Users/daemontus/Downloads/result.json").writeText(json.toJson(rs))

    /*val ts = REDNoParam(w)

    ts.makeExplicit(fakeConfig).runAnalysis(ts.fakeOdeModel, fakeConfig)*/


    /*val ts = REDParam()
    val sim = RED2simulation(w)

    ts.run {
        val from = ts.states[100]
        100.successors(true).forEach {
            if (1546 == it.target) {
                val to = ts.states[it.target]
                println("$from -> ${it.target} for ${it.bound}")
            }
        }

    }*/
    /*var q = 45.0
    repeat(1000) {
        q = sim.next(q)
        println(q)
    }
    val l = q - 0.01
    val h = q + 0.01
    ts.makeExplicit(fakeConfig).runAnalysis(ts.fakeOdeModel, fakeConfig)
    println("[$l, $h] goes to [${sim.next(h)}, ${sim.next(l)}] and approx as ${ts.average(Interval(l,h))}")

    ts.run {
        val eq = states.find { it.x1 <= q && q <= it.x2 }!!
        val eqIndex = states.indexOf(eq)
        println("Eq: $eq, $eqIndex, succ: ${eqIndex.successors(true).asSequence().map { it.target }.toList()}")
        println("Eq goes to: ${ts.average(eq)}")
    }*/
}