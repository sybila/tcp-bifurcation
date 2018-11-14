package cz.muni.fi.sybila.red

import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import java.io.File

/**
 * Model which depends on the number of connections.
 */
class ModelConnections(
        private val connectionsBounds: Pair<Double, Double> = 200.0 to 300.0,
        solver: RectangleSolver = RectangleSolver(rectangleOf(connectionsBounds.first, connectionsBounds.second))
) : TransitionModel(solver = solver, varBounds = 250.0 to 800.0, thresholdCount = 5000) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(connectionsBounds.first, connectionsBounds.second)

    // monotonic increasing - we can just eval
    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    private val precision: Double = 0.0

    private fun IR.toRectangle() = rectangleOf(getL(0), getH(0))

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val fromQ = states[from]
        val drop = dropProbability(fromQ)
        if (from % 100 == 0) println("transitions: $from/${states.size}")
        Array<RParams?>(stateCount) { to ->
            val toQ = states[to]
            val transitionParams = HashSet<Rectangle>()
            // We have to consider three piece-wise cases. Fortunately, in two cases, the function is constant.
            // We first determine thresholds in n such that drop <= pL and drop >= pU. These values
            // will partition the parameter space into three regions where we subsequently test each function
            // if it provides requested results.

            // drop <= pL happens when drop is completely below pL or they have an intersection
            // drop[low] <= pL = ((n*m*k)/(d*c + m*B))^2
            // sqrt(drop[low]) * ((d*c + m*B) / (m*k)) <= n
            val thresholdLow = Math.sqrt(drop.getL(0)) * ((d*c + m*b) / (m*k))
            // pU <= drop happens when drop is completely above pU or they have an intersection
            // drop[high] >= pU = ((n*m*k)/(d*c))^2
            // sqrt(drop[high]) * ((d*c)/(m*k)) >= n
            val thresholdHigh = Math.sqrt(drop.getH(0)) * ((d*c)/(m*k))

            // tL <= n -> drop <= pL
            val caseOne = irOf(thresholdLow, Double.POSITIVE_INFINITY).intersect(paramBounds)
            // tL > n > tH -> pL <= drop <= pU, note that such value does not need to exist
            val caseTwo = if (thresholdHigh < thresholdLow) irOf(thresholdHigh, thresholdLow).intersect(paramBounds) else null
            // n <= tU -> pU <= drop
            val caseThree = irOf(Double.NEGATIVE_INFINITY, thresholdHigh).intersect(paramBounds)

            // Now we have have the bounds on parameters, next we check if the transitions are even possible.

            // First and last case is simple, we just check if the fixed new queue value can lead to a valid jump.
            // Also, the function is monotonous increasing, so we just need to evaluate it.
            caseOne?.let { bound ->
                //val result = ((1-w).toIR() times fromQ) plus (w * b).toIR()
                val result = irOf(
                        (1-w) * fromQ.getL(0) + (w*b),
                        (1-w) * fromQ.getH(0) + (w*b)
                )
                if (result.intersect(toQ) != null) transitionParams.add(bound.roundTo(precision).toRectangle())
            }
            caseThree?.let { bound ->
                //val result = ((1-w).toIR() times fromQ)
                val result = irOf(
                        (1-w) * fromQ.getL(0),
                        (1-w) * fromQ.getH(0)
                )
                if (result.intersect(toQ) != null) transitionParams.add(bound.roundTo(precision).toRectangle())
            }
            caseTwo?.let { bound ->
                // Case two is a bit more tricky, since we also want to reduce the bound second time
                // to remove parameters which can jump out of toQ.
                // First we need to compute the value we want to obtain from our function in order to jump to toQ
                // i.e. invert the rolling average.
                // toQ = (1-w)*fromQ + w*nextQ
                // (toQ - (1-w)*fromQ)/w = nextQ
                val nextQ = irOf(
                        (toQ.getL(0) - (1-w)*fromQ.getL(0))/w,
                        (toQ.getH(0) - (1-w)*fromQ.getH(0))/w
                )
                // Note: we know the function is monotone and decreasing in drop, hence we can simplify the reduction
                // to the two endpoints of drop interval. We also know it is increasing in n (on the considered interval).
                // nextQ = (n*k)/sqrt(drop) - c*d/m
                // nextQ[low] = (n*k)/sqrt(drop[high]) - c*d/m
                // (nextQ[low] + c*d/m) * sqrt(drop[high]) / k = n[low]
                // (nextQ[high] + c*d/m) * sqrt(drop[low]) / k = n[high]
                val high = (nextQ.getL(0) + (c*d/m)) * Math.sqrt(drop.getH(0)) / k
                val low = (nextQ.getH(0) + (c*d/m)) * Math.sqrt(drop.getL(0)) / k
                val restricted = irOf(low, high).intersect(bound)?.roundTo(precision)
                if (restricted != null) transitionParams.add(restricted.toRectangle())
            }
            transitionParams.takeIf { it.isNotEmpty() }
        }
    }
/*
    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val q = states[from]
        val drop = dropProbability(q)
        Array<RParams?>(stateCount) { to ->
            if (from % 100 == 0 && to == 0) println("transitions: $from/${states.size}")
            val qNext = states[to]
            val params = HashSet<Rectangle>()
            // First, we compute what value of queue size is needed to allow this transition under the given w
            // q' = (1-w)*q + w*nextQ -> extract value of nextQ needed to satisfy this equation
            // (q' - (1-w)*q)/w = nextQ
            val expectedNextQueue = (1/w).toIR() times (qNext minus (q times (1-w).toIR()))
            val print = false
            if (print) {
                println("Q: $q, QNext: $qNext")
            }
            val normalizedNextQueue = expectedNextQueue.intersect(queueBounds)
            if (print) println("Normalized next queue: $normalizedNextQueue")
            if (normalizedNextQueue != null) {
                // If such queue size can occur, we check the other direction to refine the interval arithmetic
                val possibleNext = ((1-w).toIR() times q) plus (w.toIR() times normalizedNextQueue)
                if (print) println("Possible next: $possibleNext")
                if (possibleNext.intersect(qNext) != null) {
                    if (normalizedNextQueue.getH(0) == b) {
                        // If necessary queue contain B in the interval, try to evaluate the first part of the piece-wise function
                        val possibleNextWhenOne = ((1-w).toIR() times q) plus (w*b).toIR()
                        if (print) println("Possible next with exact value: $possibleNextWhenOne")
                        if (possibleNextWhenOne.intersect(qNext) != null) {
                            // Find for which values of n can [0..pL] and drop intersect.
                            if (drop.getL(0) <= possiblePL.getH(0)) {
                                // Solve pL > drop.1
                                // n > sqrt(drop.1) * ((dc + bm)/mk)
                                val thres = Math.sqrt(drop.getL(0)) * ((d*c+b*m)/(m*k))
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
    }*/*/


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

fun main(args: Array<String>) {
    runExperiment(ModelConnections(), File("/Users/daemontus/Downloads/RED_connections.json"))
}