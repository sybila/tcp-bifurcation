package cz.muni.fi.sybila.red

import com.github.sybila.Config
import com.github.sybila.ExplicitOdeFragment
import com.github.sybila.checker.MutableStateMap
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import cz.muni.fi.sybila.*
import cz.muni.fi.sybila.output.exportResults
import java.io.File
import java.util.concurrent.Executors

class ModelWeightAndConnections(
        override val n: Double,
        private val weightBounds: Pair<Double, Double> = 0.1 to 0.2,
        solver: RectangleSolver = RectangleSolver(rectangleOf(weightBounds.first, weightBounds.second))
) : TransitionModel(solver = solver, varBounds = 250.0 to 1000.0, thresholdCount = 3000) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(weightBounds.first, weightBounds.second)

    // monotonic increasing - we can just eval
    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    // monotonic decreasing in p, increasing in n - we can just eval in reversed order
    private fun nextQueue(p: IR) = irOf(sim.nextQueue(p.getH(0)), sim.nextQueue(p.getL(0)))

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val q = states[from]
        val drop = dropProbability(q)
        val nextQueue = nextQueue(drop)
        Array<RParams?>(stateCount) { to ->
            val qNext = states[to]
            val edgeParams = ((qNext minus q) divide (nextQueue minus q))
            val paramsRestricted = edgeParams.mapNotNull { it.intersect(paramBounds)?.roundTo(3.4) }
            paramsRestricted.mapTo(HashSet(paramsRestricted.size)) {
                rectangleOf(it.getL(0), it.getH(0))
            }.takeIf { it.isSat() }
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
    val nMin = 200
    val nMax = 300

    val solver = RectangleSolver(rectangleOf(0.1, 0.2, 200.0, 300.0))
    val allSmall = HashStateMap(solver.ff)
    val allBig = HashStateMap(solver.ff)
    val allBipartite = HashStateMap(solver.ff)
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    var model: OdeModel? = null
    val start = System.currentTimeMillis()

    (nMin..nMax).map { n ->
        executor.submit<Unit> {
            println("Started $n")
            val fakeConfig = Config()
            val system = ModelWeightAndConnections(n.toDouble())

            if (n == nMin) {
                val fakeModel = system.fakeOdeModel
                model = fakeModel.copy(parameters = fakeModel.parameters + listOf(OdeModel.Parameter(
                        "q", nMin.toDouble() to nMax.toDouble()
                )))
            }

            val transitionSystem = ExplicitOdeFragment(
                    solver = system, stateCount = system.stateCount,
                    pivotFactory = structureAndCardinalityPivotChooserFactory(),
                    successors = system.exportSuccessors(), predecessors = system.exportPredecessors()
            )

            val algorithm = Algorithm(config = fakeConfig.copy(logOutput = "none"), allStates = transitionSystem)
            algorithm.computeComponents()
            algorithm.close()

            // Extract small components
            system.run {
                val components = algorithm.store.components
                val (bipartite, notBipartite) = components.extractOscillation(transitionSystem)
                val (small, big) = notBipartite.extractSmallComponents(transitionSystem, system.smallComponentStateCount)
                fun processProperty(global: MutableStateMap<RParams>, local: Map<Int, RParams>) = synchronized(global) {
                    local.forEach { s, u ->
                        val extended = u.map { r ->
                            val (wL, wH) = r.asIntervals()[0]
                            rectangleOf(wL, wH, n.toDouble(), (n+1).toDouble())
                        }.toMutableSet()
                        solver.run {
                            global.setOrUnion(s, extended)
                        }
                    }
                }
                processProperty(allBipartite, bipartite)
                processProperty(allSmall, small)
                processProperty(allBig, big)
            }
            println("Finished $n")
        }
    }.forEach { it.get() }

    val groups: Map<String, List<StateMap<RParams>>> = mapOf(
            "stable" to listOf(allSmall),
            "oscillation" to listOf(allBipartite),
            "unstable" to listOf(allBig)
    )

    val rs = solver.exportResults(model!!, groups)

    val json = Gson()
    File("RED_weight_and_connections.json").writeText(json.toJson(rs))

    executor.shutdown()
    println("========== ELAPSED: ${(System.currentTimeMillis() - start)/1000}s ==========")
}