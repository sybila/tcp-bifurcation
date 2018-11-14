package cz.muni.fi.sybila.red

import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.OdeModel
import java.io.File

/**
 * A model which combines two parameters - weight and number of connections.
 */
class ModelTwoParams(
        private val weightBounds: Pair<Double, Double> = 0.15 to 0.1501,
        private val connectionsBounds: Pair<Double, Double> = 200.0 to 300.0,
        solver: RectangleSolver = RectangleSolver(rectangleOf(
                weightBounds.first, weightBounds.second,
                connectionsBounds.first, connectionsBounds.second
        ))
) : TransitionModel(solver = solver, varBounds = 250.0 to 1000.0, thresholdCount = 200) {

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
            // tL <= n -> drop <= pL
            val caseOne = irOf(thresholdLow, Double.POSITIVE_INFINITY).intersect(nBounds)
            val moreThan = Math.sqrt(drop.getL(0)) * ((d*c)/(m*k))
            val lessThan = Math.sqrt(drop.getH(0)) * ((d*c + b*m)/(m*k))
            // tL > n > tH -> pL <= drop <= pU, note that such value does not need to exist
            val caseTwo = if (moreThan < lessThan) { irOf(moreThan, lessThan).intersect(nBounds) } else null
            val thresholdHigh = Math.sqrt(drop.getH(0)) * ((d*c)/(m*k))
            // n <= tU -> pU <= drop
            val caseThree = irOf(Double.NEGATIVE_INFINITY, thresholdHigh).intersect(nBounds)

            val print = (from == 50) && (to == 31)
            if (print) println("L: $thresholdLow H: $thresholdHigh")
            if (print) println("Cases $caseOne $caseTwo $caseThree")
            if (print) println("Drop: $drop")
            var transitionParams: RParams = HashSet()

            caseOne?.let { nBound ->
                // There are values of n for which we will end with nextQ = B. Now we have to derive values of w
                // for which fromQ goes to toQ. We know the function is monotone and increasing.
                // toQ = (1-w) * fromQ + w*B = fromQ + w*(B - fromQ)
                // w = (toQ - fromQ) / (B - fromQ)
                val possibleQueue = ((1.0.toIR() minus wBounds) times fromQ) plus (wBounds times b.toIR())
                if (print) println("possible next: $possibleQueue")
                if (possibleQueue.intersect(toQ) != null) {
                    val wBound = ((toQ minus fromQ) divide (b.toIR() minus fromQ)).mapNotNull {
                        if (print) println("unreduced bound: $it")
                        it.intersect(wBounds)?.roundTo(precisionW)
                    }
                    if (print) println("Case one: $wBound")
                    val roundedN = nBound.roundTo(precisionN)
                    wBound.forEach {w ->
                        transitionParams = transitionParams or mutableSetOf(rectangleOf(
                                w.getL(0), w.getH(0), roundedN.getL(0), roundedN.getH(0)
                        ))
                    }
                }
            }

            caseThree?.let { nBound ->
                // There are values of n fro which we will end with nextQ = 0. Now we have to derive values of w
                // for which fromQ goes to toQ.
                // toQ = (1-w) * fromQ = fromQ - w*fromQ
                // w = (toQ - fromQ) / (fromQ)
                val possibleQueue = ((1.0.toIR() minus wBounds) times fromQ)
                if (possibleQueue.intersect(toQ) != null) {
                    val wBound = ((toQ minus fromQ) divide (fromQ.additiveInverse())).mapNotNull {
                        it.intersect(wBounds)?.roundTo(precisionW)
                    }
                    if (print) println("Case three: $wBound")
                    val roundedN = nBound.roundTo(precisionN)
                    wBound.forEach { w ->
                        transitionParams = transitionParams or mutableSetOf(rectangleOf(
                                w.getL(0), w.getH(0), roundedN.getL(0), roundedN.getH(0)
                        ))
                    }
                }
            }

            caseTwo?.let { bound ->
                // Case two is a bit more tricky, since we also want to reduce the bound second time
                // to remove parameters which can jump out of toQ, but first we have to reduce w.
                // We combine the two equations and obtain:
                // toQ = (1-w) * fromQ + w * [(n*k)/sqrt(drop) - c*d/m]

                // First, we compute what queue sizes can we achieve with this bound
                val pLPart = (bound times (m*k/(d*c + b*m)).toIR())
                val pUPart = (bound times (m*k/(d*c)).toIR())
                val pL = pLPart times pLPart
                val pU = pUPart times pUPart
                val cutOffBottom = Math.max(drop.getL(0), pL.getL(0))
                val cutOffTop = Math.min(drop.getH(0), pU.getH(0))
                if (cutOffBottom < drop.getH(0) && cutOffTop > drop.getL(0)) {
                    val reducedDrop = irOf(cutOffBottom, cutOffTop)
                    val sqrtP = irOf(Math.sqrt(reducedDrop.getL(0)), Math.sqrt(reducedDrop.getH(0)))
                    val kOverP = (k.toIR() divide sqrtP)
                    if (kOverP.size != 1) error("WTF?")
                    val possibleNextQueue = bound times kOverP.first() minus (c*d/m).toIR()
                    val nextQ = (1.0.toIR() minus wBounds) times fromQ plus (wBounds times possibleNextQueue)
                    if (print) println("K over sqrt(p): $kOverP")
                    if (print) println("Reduced drop: $reducedDrop")
                    if (print) println("Possible next queue: $possibleNextQueue")
                    if (print) println("next queue: $nextQ")

                    // Then we derive

                    //val KD = (k.toIR() times irOf(Math.sqrt(drop.getL(0)), Math.sqrt(drop.getH(0)))
                    val CDM = c*d/m
                    //if (print) println("KD: $KD, CDM: $CDM")
                    // simplified to:
                    // toQ = fromQ - w*fromQ + w*(n*KD - CDM)
                    // toQ = fromQ + w*(n*KD - CDM - fromQ)
                    // w = (toQ - fromQ) / (n*KD - CDM - fromQ)
                    fun reduceW(n: IR): List<IR> {
                        return (toQ minus fromQ) divide ((n times kOverP.first()) minus fromQ minus CDM.toIR())
                    }

                    // toQ = fromQ - w*fromQ + w*n*KD - w*CDM
                    // n = (toQ - fromQ + w*(fromQ + CDM)) / (w*KD)
                    fun reduceN(w: IR): List<IR> {
                        return (toQ minus fromQ plus (w times (fromQ plus CDM.toIR()))) divide (w times kOverP.first())
                    }

                    var params = listOf(wBounds to bound)
                    repeat(2) {
                        if (print) println("Params: $params")
                        params = params.flatMap { (w, n) ->
                            val reducedW = reduceW(n).mapNotNull { it.intersect(w) }
                            if (print) println("ReducedW: $reducedW")
                            val reducedN = reducedW.flatMap { newW ->
                                reduceN(newW).mapNotNull { newN ->
                                    newN.intersect(n)
                                }.map { newN -> newW to newN }
                            }
                            if (print) println("ReducedN: $reducedN")
                            reducedN
                        }
                    }
                    params.forEach { (w, n) ->
                        if (print) println("Case two: $w $n")
                        val rW = w.roundTo(precisionW)
                        val rN = n.roundTo(precisionN)
                        transitionParams = transitionParams or mutableSetOf(rectangleOf(
                                rW.getL(0), rW.getH(0), rN.getL(0), rN.getH(0)
                        ))
                    }
                }
            }
            transitionParams.takeIf { it.isNotEmpty() }
        }
    }

    init {
        transitionArray.forEachIndexed { index, transitions ->
            transitions.forEachIndexed { target, params ->
                if (params != null) {
                    println("$index,${states[index]} -> $target,${states[target]} to $params")
                }
            }
        }
    }

    override val fakeOdeModel: OdeModel
        get() = OdeModel(
                variables = listOf(
                        OdeModel.Variable("queue", varBounds, thresholds, null, emptyList())
                ),
                parameters = listOf(
                        OdeModel.Parameter("w", weightBounds), OdeModel.Parameter("n", connectionsBounds)
                )
        )
}

fun main(args: Array<String>) {
    runExperiment(ModelTwoParams(), File("/Users/daemontus/Downloads/RED_both.json"))
}