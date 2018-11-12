package cz.muni.fi.sybila.red

import com.github.sybila.Config
import com.github.sybila.ExplicitOdeFragment
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import cz.muni.fi.sybila.*
import cz.muni.fi.sybila.output.exportResults
import java.io.File

/**
 * RED model version parametrised by weight. This is the simplest parametrisation, since it is the
 * only one where parameter does not influence the piecewise-bounds of the map.
 *
 * We don't have to do anything special, just evaluate the nextQueue function and propagate the results.
 *
 * In order ot compute the parameters for transitions, we observe that
 * q' = (1-w)*q + w * nextQ
 * q' = q - w*q + w * nextQ
 * q' - q = w * (nextQ - q)
 * w = (q' - q) / (nextQ - q)
 */
class ModelWeight(
        private val weightBounds: Pair<Double, Double> = 0.1 to 0.2,
        solver: RectangleSolver = RectangleSolver(rectangleOf(weightBounds.first, weightBounds.second))
) : TransitionModel(solver = solver, varBounds = 300.0 to 600.0, thresholdCount = 2500) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(weightBounds.first, weightBounds.second)

    // monotonic increasing - we can just eval
    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    // monotonic decreasing - we can just eval in reversed order
    private fun nextQueue(p: IR) = irOf(sim.nextQueue(p.getH(0)), sim.nextQueue(p.getL(0)))

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val q = states[from]
        val nextQueue = nextQueue(dropProbability(q))
        // sanity check - verify that under no circumstances, we'll jump out of the model
        val postImage = ((irOf(1.0, 1.0) minus paramBounds) times q) plus (paramBounds times nextQueue)
        if (postImage.getL(0) < varBounds.first || postImage.getH(0) > varBounds.second) {
            error("State $q jumps to $postImage.")
        }
        Array<RParams?>(stateCount) { to ->
            if (from % 100 == 0 && to == 0) println("Computing transitions: $from/${states.size}")
            val qNext = states[to]
            val edgeParams = ((qNext minus q) divide (nextQueue minus q))
            val paramsRestricted = edgeParams.mapNotNull { it.intersect(paramBounds)?.roundTo(3.4) }
            paramsRestricted.mapTo(HashSet(paramsRestricted.size)) {
                rectangleOf(it.getL(0), it.getH(0))
            }
        }
    }


    override val fakeOdeModel: OdeModel
        get() = OdeModel(
            variables = listOf(
                    OdeModel.Variable("queue", varBounds, thresholds, null, emptyList())
            ),
            parameters = listOf(
                    OdeModel.Parameter("w", weightBounds)
            )
    )

}

fun main(args: Array<String>) {
    val start = System.currentTimeMillis()
    val fakeConfig = Config()
    val system = ModelWeight()

    println("========== COMPUTE TRANSITION SYSTEM ==========")

    val transitionSystem = ExplicitOdeFragment(
            solver = system, stateCount = system.stateCount,
            pivotFactory = structureAndCardinalityPivotChooserFactory(),
            successors = system.exportSuccessors(), predecessors = system.exportPredecessors()
    )

    println("========== COMPUTE TERMINAL COMPONENTS ==========")

    val algorithm = Algorithm(config = fakeConfig, allStates = transitionSystem,
            initialUniverse = null, postProcessor = null)
    algorithm.computeComponents()
    algorithm.close()

    println("========== PRINT RESULTS ==========")

    val rs = system.exportResults(system.fakeOdeModel,
            algorithm.store.getComponentMapping(algorithm.count).mapIndexed { index, stateMap ->
                "${index+1} tSCC(s)" to listOf(stateMap)
            }.toMap()
    )

    val json = Gson()
    File("/Users/daemontus/Downloads/RED_weight.json").writeText(json.toJson(rs))

    println("========== ELAPSED: ${(System.currentTimeMillis() - start)/1000}s ==========")
}