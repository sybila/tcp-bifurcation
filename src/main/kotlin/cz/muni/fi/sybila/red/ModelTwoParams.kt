package cz.muni.fi.sybila.red

import com.github.daemontus.egholm.collections.repeat
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import java.io.File

/**
 * A model which combines two parameters - weight and number of connections.
 */
class ModelTwoParams(
        private val weightBounds: Pair<Double, Double> = 0.1 to 0.12,
        private val connectionsBounds: Pair<Double, Double> = 280.0 to 300.0,
        solver: RectangleSolver = RectangleSolver(rectangleOf(
                weightBounds.first, weightBounds.second,
                connectionsBounds.first, connectionsBounds.second
        ))
) : TransitionModel(solver = solver, varBounds = 250.0 to 1000.0, thresholdCount = 1500) {

    private val sim = ModelSimulation(this)

    private val nBounds = irOf(connectionsBounds.first, connectionsBounds.second)
    private val wBounds = irOf(weightBounds.first, weightBounds.second)

    private fun dropProbability(q: IR) = irOf(sim.dropProbability(q.getL(0)), sim.dropProbability(q.getH(0)))

    private val precisionW = 3.0
    private val precisionN = 0.0

    override val transitionArray: Array<Array<RParams?>> = Array(stateCount) { from ->
        val fromQ = states[from]
        val drop = dropProbability(fromQ)
        if (from % 100 == 0) println("transitions: $from/${states.size}")
        Array<RParams?>(stateCount) {to ->
            val toQ = states[to]
            // Now, we have a peculiar situation, because we need to consider three cases, each for two parameters.
            // First, we compute the connection bounds for each piece-wise case as in the connection model:
            val thresholdLow = Math.sqrt(drop.getL(0)) * ((d*c + m*b) / (m*k))
            val thresholdHigh = Math.sqrt(drop.getH(0)) * ((d*c)/(m*k))
            // tL <= n -> drop <= pL
            val caseOne = irOf(thresholdLow, Double.POSITIVE_INFINITY).intersect(nBounds)
            // tL > n > tH -> pL <= drop <= pU, note that such value does not need to exist
            val caseTwo = if (thresholdHigh < thresholdLow) {
                irOf(thresholdHigh, thresholdLow).intersect(nBounds)
            } else null
            // n <= tU -> pU <= drop
            val caseThree = irOf(Double.NEGATIVE_INFINITY, thresholdHigh).intersect(nBounds)

            val transitionParams = HashSet<Rectangle>()

            caseOne?.let { nBound ->
                // There are values of n for which we will end with nextQ = B. Now we have to derive values of w
                // for which fromQ goes to toQ. We know the function is monotone and increasing.
                // toQ = (1-w) * fromQ + w*B = fromQ + w*(B - fromQ)
                // w = (toQ - fromQ) / (B - fromQ)
                val wBound = ((toQ minus fromQ) divide (b.toIR() minus fromQ)).mapNotNull {
                    it.intersect(wBounds)?.roundTo(precisionW)
                }
                val roundedN = nBound.roundTo(precisionN)
                wBound.forEach {w ->
                    transitionParams.add(rectangleOf(
                            w.getL(0), w.getH(0), roundedN.getL(0), roundedN.getH(0)
                    ))
                }
            }

            caseThree?.let { nBound ->
                // There are values of n fro which we will end with nextQ = 0. Now we have to derive values of w
                // for which fromQ goes to toQ.
                // toQ = (1-w) * fromQ = fromQ - w*fromQ
                // w = (toQ - fromQ) / (fromQ)
                val wBound = ((toQ minus fromQ) divide (fromQ)).mapNotNull {
                    it.intersect(wBounds)?.roundTo(precisionW)
                }
                val roundedN = nBound.roundTo(precisionN)
                wBound.forEach { w ->
                    transitionParams.add(rectangleOf(
                            w.getL(0), w.getH(0), roundedN.getL(0), roundedN.getH(0)
                    ))
                }
            }

            caseTwo?.let { bound ->
                // Case two is a bit more tricky, since we also want to reduce the bound second time
                // to remove parameters which can jump out of toQ, but first we have to reduce w.
                // We combine the two equations and obtain:
                // toQ = (1-w) * fromQ + w * [(n*k)/sqrt(drop) - c*d/m]
                val KD = (1/k).toIR() times irOf(Math.sqrt(drop.getL(0)), Math.sqrt(drop.getH(0)))
                val CDM = c*d/m
                // simplified to:
                // toQ = fromQ - w*fromQ + w*(n*KD - CDM)
                // toQ = fromQ - w*(fromQ + n*KD - CDM)
                // w = (fromQ - toQ) / (fromQ + n*KD - CDM)
                fun reduceW(n: IR): List<IR> {
                    return (fromQ minus toQ) divide (fromQ plus (n times KD) minus CDM.toIR())
                }

                // toQ = fromQ - w*fromQ + w*n*KD - w*CDM
                // n = (toQ - fromQ + w*(fromQ + CDM)) / (w*KD)
                fun reduceN(w: IR): List<IR> {
                    return (toQ minus fromQ plus (w times (fromQ plus CDM.toIR()))) divide (w times KD)
                }

                var params = listOf(wBounds to bound)
                repeat(5) {
                    params = params.flatMap { (w, n) ->
                        val reducedW = reduceW(n).mapNotNull { it.intersect(w) }
                        val reducedN = reducedW.flatMap { newW ->
                            reduceN(newW).mapNotNull { newN ->
                                newN.intersect(n)
                            }.map { newN -> newW to newN }
                        }
                        reducedN
                    }
                }
                params.forEach { (w, n) ->
                    val rW = w.roundTo(precisionW)
                    val rN = n.roundTo(precisionN)
                    transitionParams.add(rectangleOf(
                            rW.getL(0), rW.getH(0), rN.getL(0), rN.getH(0)
                    ))
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
                        OdeModel.Parameter("w", weightBounds), OdeModel.Parameter("n", weightBounds)
                )
        )
}

fun main(args: Array<String>) {
    runExperiment(ModelTwoParams(), File("/Users/daemontus/Downloads/RED_both.json"))
}