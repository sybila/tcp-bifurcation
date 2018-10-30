package cz.muni.fi.sybila.red

import com.github.sybila.Config
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import cz.muni.fi.sybila.RParams
import cz.muni.fi.sybila.exportResults
import cz.muni.fi.sybila.makeExplicit
import cz.muni.fi.sybila.runAnalysis
import java.io.File

/**
 * RED model version parametrised by low queue threshold. The threshold determines how long it takes the
 * router to start dropping packets, hence it influences only the drop probability.
 *
 * Now, we need to determine how to compute parameter set for each edge. In case of smooth functions, we can just
 * derive an "inverse". However, for the piecewise functions, we need to analyse each case separately.
 *
 * First, we derive a set of parameters which allow the specific case. Then, we reduce the input variable to the
 * range where the case is allowed. Finally, we evaluate the function.
 *
 * First, we examine the equation for drop probability. It has three cases.
 * 1. q \in [0 .. qL] => qL \in [q.1 .. max], value = 0
 * 2. q \in [qU .. B] => can be tested explicitly, value = 1
 * 3. q \in [qL .. qU] => qL \in [min .. q.2], intersect q with qL and qU, value = ...
 *
 * This gives us possibly three branches to evaluate next-queue at.
 *
 */
class ModelQL(
        private val qLBounds: Pair<Double, Double> = 150.0 to 160.0,
        solver: RectangleSolver = RectangleSolver(rectangleOf(qLBounds.first, qLBounds.second))
) : Model(solver = solver, varBounds = 230.0 to 400.0, thresholdCount = 5000) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(qLBounds.first, qLBounds.second)

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val q = states[from]
        // compute the possible drop probabilities based on parameter:
        // 1. Params which have drop probability 1 (above qU)
        val oneParams = if (q.getH(0) > qU) paramBounds else null
        // Value of next queue is always zero for probability one
        // 2. Params which have drop probability 0 (below qL)
        val zeroParams = when {
            q.getL(0) > qLBounds.second -> null
            q.getL(0) < qLBounds.first -> paramBounds
            else -> irOf(q.getL(0), qLBounds.second)
        }
        // 3. Params which have linear drop probability.
        val linearParams = when {
            q.getH(0) < qLBounds.first -> null
            q.getH(0) > qLBounds.second -> paramBounds
            else -> irOf(qLBounds.first, q.getH(0))
        }
        val probBounds = irOf(0.0, 1.0)
        val linearValue = if (linearParams == null) null else {
            ((((q minus linearParams) times irOf(pMax, pMax)) divide (irOf(qU, qU) minus linearParams))).also {
                if (it.size > 1) error("WTF?")
            }.first().intersect(probBounds)
        }
        //println("Zero: $zeroParams, One: $oneParams, Linear: $linearParams Linear values: $linearValue")

        // sanity check - verify that under no circumstances, we'll jump out of the model
        val nextZero = if (zeroParams != null) {
            val next = ((1-w).toIR() times q) plus (w * sim.nextQueue(0.0)).toIR()
            if (next.getL(0) < varBounds.first || next.getH(0) > varBounds.second) {
                error("State $q jumps to $next.")
            }
            next
        } else null
        val nextOne = if (oneParams != null) {
            val next = ((1-w).toIR() times q) plus (w * sim.nextQueue(1.0)).toIR()
            if (next.getL(0) < varBounds.first || next.getH(0) > varBounds.second) {
                error("State $q jumps to $next.")
            }
            next
        } else null
        val linearNext = if (linearValue != null) {
            val next = ((1-w).toIR() times q) plus (w.toIR() times irOf(sim.nextQueue(linearValue.getH(0)), sim.nextQueue(linearValue.getL(0))))
            if (next.getL(0) < varBounds.first || next.getH(0) > varBounds.second) {
                error("State $q jumps to $next.")
            }
            next
        } else null
        Array<RParams?>(stateCount) { to ->
            if (from % 100 == 0 && to == 0) println("transitions: $from/${states.size}")
            val qNext = states[to]
            val set = HashSet<Rectangle>()
            if (zeroParams != null && nextZero != null && qNext.intersect(nextZero) != null) {
                set.add(rectangleOf(zeroParams.getL(0), zeroParams.getH(0)))
            }
            if (oneParams != null && nextOne != null && qNext.intersect(nextOne) != null) {
                set.add(rectangleOf(oneParams.getL(0), oneParams.getH(0)))
            }
            if (linearParams != null && linearNext != null && qNext.intersect(linearNext) != null) {
                set.add(rectangleOf(linearParams.getL(0), linearParams.getH(0)))
            }
            set.takeIf { it.isNotEmpty() }/*?.also {
                println("From $from:$q to $to:$qNext for $it")
            }*/
        }
    }


    override val fakeOdeModel: OdeModel
        get() = OdeModel(
                variables = listOf(
                        OdeModel.Variable("queue", varBounds, thresholds, null, emptyList())
                ),
                parameters = listOf(
                        OdeModel.Parameter("qL", qLBounds)
                )
        )

}

fun main(args: Array<String>) {
    val fakeConfig = Config()
    val system = ModelQL()

    val r = system.makeExplicit(fakeConfig).runAnalysis(system.fakeOdeModel, fakeConfig)
    val rs = system.exportResults(system.fakeOdeModel, mapOf("all" to listOf(r)))

    val json = Gson()
    File("/Users/daemontus/Downloads/RED_qL.json").writeText(json.toJson(rs))
}
