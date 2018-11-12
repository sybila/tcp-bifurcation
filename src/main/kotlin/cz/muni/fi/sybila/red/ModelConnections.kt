package cz.muni.fi.sybila.red

import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel

/**
 * Model which depends on the number of connections.
 */
class ModelConnections(
        private val connectionsBounds: Pair<Double, Double> = 200.0 to 350.0,
        solver: RectangleSolver = RectangleSolver(rectangleOf(connectionsBounds.first, connectionsBounds.second))
) : TransitionModel(solver = solver, varBounds = 200.0 to 850.0, thresholdCount = 2500) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(connectionsBounds.first, connectionsBounds.second)
    private val queueBounds = irOf(0.0, b)

    val possiblePU = Math.pow((m*k)/(d*c), 2.0).toIR() times paramBounds times paramBounds
    val possiblePL = Math.pow((m*k)/(d*c + b*m), 2.0).toIR() times paramBounds times paramBounds

    // monotonic increasing - we can just eval
    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    // monotonic decreasing - we can just eval
    private fun nextQueue(p: IR) = irOf(sim.nextQueue(p.getH(0)), sim.nextQueue(p.getL(0)))

    private val roundTo = 0

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val q = states[from]
        val drop = dropProbability(q)
        Array<RParams?>(stateCount) { to ->
            if (from % 100 == 0 && to == 0) println("transitions: $from/${states.size}")
            val qNext = states[to]
            val params = HashSet<Rectangle>()
            val expectedNextQueue = (1/w).toIR() times (qNext minus (q times (1-w).toIR()))
            val print = false//from == 0 && to == 9//q.contains(0, 325.0) && qNext.contains(0, 360.0)
            if (print) {
                println("Q: $q, QNext: $qNext")
                println("Expected next: $expectedNextQueue")
            }
            val normalizedNextQueue = expectedNextQueue.intersect(queueBounds)
            if (print) println("Normalized next: $normalizedNextQueue")
            if (normalizedNextQueue != null) {
                val possibleNext = ((1-w).toIR() times q) plus (w.toIR() times normalizedNextQueue)
                if (print) println("Possible next: $possibleNext")
                if (possibleNext.intersect(qNext) != null) {
                    if (normalizedNextQueue.getH(0) == b) {
                        val possibleNextWhenOne = ((1-w).toIR() times q) plus (w*b).toIR()
                        if (possibleNextWhenOne.intersect(qNext) != null) {
                            // Find for which values of n can [0..pL] and drop intersect.
                            if (drop.getL(0) <= possiblePL.getH(0)) {
                                // Solve pL > drop.1
                                // n > sqrt(drop.1) * ((dc + bm)/mk)
                                val thres = Math.sqrt(drop.getL(0)) * ((d*c+b*m)/m*k)
                                val interval = irOf(thres, Double.POSITIVE_INFINITY).intersect(paramBounds)?.roundTo(roundTo.toDouble())
                                if (interval != null) {
                                    params.add(rectangleOf(interval.getL(0), interval.getH(0)))
                                } else {
                                    // There are values of n where pL is above drop, but these are out of bounds
                                }
                            } else {
                                // No way any pL is below this drop rate.
                            }
                        } else {
                            // over-approximation fail
                        }
                    }
                    if (normalizedNextQueue.getL(0) == 0.0) {
                        val possibleNextWhenZero = ((1-w).toIR() times q)
                        if (possibleNextWhenZero.intersect(qNext) != null) {
                            // Find out for which values of n can [pU..1] and drop intersect.
                            if (drop.getH(0) >= possiblePU.getL(0)) {
                                // Solve equation pU < drop.2
                                // n^2 * (mk/dc)^2 < drop.2 -> n^2 < drop.2 * (mk/dc)^-2
                                // n < sqrt(drop.2 * (mk/dc)^-2)
                                val thres = Math.sqrt(drop.getH(0) * Math.pow((m * k)/(d * c), -2.0))
                                val interval = irOf(0.0, thres).intersect(paramBounds)?.roundTo(roundTo.toDouble())
                                if (interval != null) {
                                    //println("$q goes to $qNext for $interval")
                                    params.add(rectangleOf(interval.getL(0), interval.getH(0)))
                                } else {
                                    // There are values of n where pU is below drop, but these are out of bounds.
                                }
                            } else {
                                // There is no way pU will be slow low that drop can intersect with it.
                            }
                        } else {
                            // Interval arithmetic fooled us again - we thought we needed a zero, but we don't
                            // actually need it.
                        }
                    }
                    // Solve pU > drop.1 and pL < drop.2
                    // n^2 * (mk/dc)^2 > drop.1
                    // n > sqrt(drop.1) * (cd/mk)
                    // pL < drop.2
                    // n < sqrt(drop.2) * ((dc + bm)/(mk))
                    // This gives us parameters where main case is even possible
                    if (print) println("drop: $drop")
                    if (print) println("m*k/d*c: ${(m*k)/(d*c)}")
                    val moreThan = Math.sqrt(drop.getL(0)) * ((d*c)/(m*k))
                    val lessThan = Math.sqrt(drop.getH(0)) * ((d*c+b*m)/(m*k))
                    val admissibleParameters = irOf(moreThan, lessThan).intersect(paramBounds)
                    if (print) println("More than $moreThan, less than $lessThan")
                    if (admissibleParameters != null) {
                        // Next, we have to reduce this to parameters where we actually can make the jump.
                        // q + cd/m = nk/sqrt(p)
                        // sqrt(p) * (q + cd/m) * 1/k = n
                        val canJump = irOf(Math.sqrt(drop.getL(0)), Math.sqrt(drop.getH(0))) times (normalizedNextQueue plus (c*d/m).toIR()) times (1.0/k).toIR()
                        val goTo = canJump.intersect(paramBounds)?.roundTo(roundTo.toDouble())
                        if (goTo != null) {
                            if(print) println("$q goes to $qNext for $goTo")
                            params.add(rectangleOf(goTo.getL(0), goTo.getH(0)))
                        }
                    }
                    params.takeIf { it.isNotEmpty() }
                } else {
                    // Expected next queue is valid, but its application does not intersect with this state
                    // (= interval arithmetic over-approximation fail)
                    null
                }
            } else {
                // Expected next queue is completely out of range for valid queue - can't jump here no matter what.
                null
            }
            /*val edgeParams = ((qNext minus q) divide (nextQueue minus q))
            val paramsRestricted = edgeParams.mapNotNull { it.intersect(paramBounds)?.roundTo(3) }
            paramsRestricted.mapTo(HashSet(paramsRestricted.size)) {
                rectangleOf(it.getL(0), it.getH(0))
            }*/
        }
    }/*.also {
        it.forEachIndexed { i, a ->
            println("$i -> ${a.indices.filter { a[it] != null }}")
        }
    }*/


    override val fakeOdeModel: OdeModel
        get() = OdeModel(
                variables = listOf(
                        OdeModel.Variable("queue", varBounds, thresholds, null, emptyList())
                ),
                parameters = listOf(
                        OdeModel.Parameter("n", connectionsBounds)
                )
        )

}

/*
fun main(args: Array<String>) {
    val fakeConfig = Config()
    val system = ModelConnections()

    val r = system.makeExplicit(fakeConfig).runAnalysis(fakeConfig)
    val rs = system.exportResults(system.fakeOdeModel, mapOf("all" to listOf(r)))

    val json = Gson()
    File("/Users/daemontus/Downloads/RED_connections.json").writeText(json.toJson(rs))
}*/