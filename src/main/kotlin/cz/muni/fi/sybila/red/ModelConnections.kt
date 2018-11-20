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
) : TransitionModel(solver = solver, varBounds = 250.0 to 800.0, thresholdCount = 5500) {

    private val sim = ModelSimulation(this)
    private val paramBounds = irOf(connectionsBounds.first, connectionsBounds.second)

    // monotonic increasing - we can just eval
    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    private val precision: Double = 0.5

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
            val moreThan = Math.sqrt(drop.getL(0)) * ((d*c)/(m*k))
            val lessThan = Math.sqrt(drop.getH(0)) * ((d*c + b*m)/(m*k))
            val caseTwo = irOf(moreThan, lessThan).intersect(paramBounds)
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
                val pLPart = (bound times (m*k/(d*c + b*m)).toIR())
                val pUPart = (bound times (m*k/(d*c)).toIR())
                val pL = pLPart times pLPart
                val pU = pUPart times pUPart
                val cutOffBottom = Math.max(drop.getL(0), pL.getL(0))
                val cutOffTop = Math.min(drop.getH(0), pU.getH(0))
                if (cutOffBottom < drop.getH(0) && cutOffTop > drop.getL(0)) {
                    val reducedDrop = irOf(cutOffBottom, cutOffTop)
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
                    val sqrtP = irOf(Math.sqrt(reducedDrop.getL(0)), Math.sqrt(reducedDrop.getH(0)))
                    val n = (nextQ plus (c*d/m).toIR()) times sqrtP times (1/k).toIR()
                    //val high = (nextQ.getL(0) + (c*d/m)) * Math.sqrt(reducedDrop.getH(0)) / k
                    //val low = (nextQ.getH(0) + (c*d/m)) * Math.sqrt(reducedDrop.getL(0)) / k
                    val restricted = n.intersect(bound)?.roundTo(precision)
                    if (restricted != null) transitionParams.add(restricted.toRectangle())
                }
            }
            transitionParams.takeIf { it.isNotEmpty() }
        }
    }

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
    runExperiment(ModelConnections(), File("RED_connections.json"))
}