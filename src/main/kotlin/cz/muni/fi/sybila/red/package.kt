package cz.muni.fi.sybila.red

import com.github.sybila.Config
import com.github.sybila.ExplicitOdeFragment
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.ode.generator.rect.Rectangle
import com.google.gson.Gson
import cz.muni.fi.sybila.Algorithm
import cz.muni.fi.sybila.exportPredecessors
import cz.muni.fi.sybila.exportSuccessors
import cz.muni.fi.sybila.output.exportResults
import cz.muni.fi.sybila.structureAndCardinalityPivotChooserFactory
import java.io.File

typealias RParams = MutableSet<Rectangle>

fun runExperiment(system: TransitionModel, output: File) {
    val start = System.currentTimeMillis()
    val fakeConfig = Config()

    println("========== COMPUTE TRANSITION SYSTEM ==========")

    val transitionSystem = ExplicitOdeFragment(
            solver = system, stateCount = system.stateCount,
            pivotFactory = structureAndCardinalityPivotChooserFactory(),
            successors = system.exportSuccessors(), predecessors = system.exportPredecessors()
    )

    println("========== COMPUTE TERMINAL COMPONENTS ==========")

    println("States in a small component: ${system.smallComponentStateCount}")

    val algorithm = Algorithm(config = fakeConfig, allStates = transitionSystem,
            initialUniverse = null, postProcessor = null)
    algorithm.computeComponents()
    algorithm.close()

    println("========== PRINT RESULTS ==========")

    // Extract small components
    system.run {
        val components = algorithm.store.components
        val (bipartite, notBipartite) = components.extractOscillation(transitionSystem)
        val (small, big) = notBipartite.extractSmallComponents(transitionSystem, system.smallComponentStateCount)

        val componentCounts: Map<String, List<StateMap<RParams>>> = algorithm.store.getComponentMapping(algorithm.count).mapIndexed { index, stateMap ->
            "${index+1} tSCC(s)" to listOf(stateMap)
        }.filter { prop -> prop.second.any { it.entries().asSequence().count() > 0 } }.toMap()

        val groups: Map<String, List<StateMap<RParams>>> = mapOf(
                "stable" to listOf(HashStateMap(ff, small)),
                "oscillation" to listOf(HashStateMap(ff, bipartite)),
                "unstable" to listOf(HashStateMap(ff, big))
        )

        val rs = system.exportResults(system.fakeOdeModel, componentCounts + groups)

        val json = Gson()
        output.writeText(json.toJson(rs))

        println("========== ELAPSED: ${(System.currentTimeMillis() - start)/1000}s ==========")
    }
}