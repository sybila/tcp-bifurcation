package cz.muni.fi.sybila.red

/**
 * A simple simulation of the RED model.
 */
class ModelSimulation(
        constants: Constants
) : Constants by constants {

    private val pL = Math.pow((n*m*k)/(d*c + b*m), 2.0)
    private val pU = Math.pow((n*m*k)/(d*c), 2.0)


    fun dropProbability(q: Double) = when (q) {
        in 0.0..qL -> 0.0
        in qL..qU -> ((q - qL) / (qU - qL)) * pMax
        in qU..b -> 1.0
        else -> error("Queue size $q not in [0..B]")
    }.also { if (it !in 0.0..1.0) error("Drop probability $it not it [0..1]") }

    fun nextQueue(p: Double) = when (p) {
        in 0.0..pL -> b
        in pL..pU -> (n*k)/Math.sqrt(p) - (c*d)/m
        in pU..1.0 -> 0.0
        else -> error("Probability $p not in [0..1]")
    }.also { if (it !in 0.0..b) error("Queue size $it not in [0..B]") }

    fun average(q: Double) = (1.0 - w) * q + w * nextQueue(dropProbability(q))

}

fun main(args: Array<String>) {
    var q = 268.0
    val sim = ModelSimulation(Const(n = 340.0))
    repeat(1000) {
        q = sim.average(q)
        println(q)
    }
}