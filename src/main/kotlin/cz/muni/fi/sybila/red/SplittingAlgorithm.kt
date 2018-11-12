package cz.muni.fi.sybila.red

import com.github.sybila.Config
import com.github.sybila.ExplicitOdeFragment
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.ode.generator.rect.Rectangle
import cz.muni.fi.sybila.Algorithm

internal class SplittingAlgorithm(
        config: Config,
        allStates: ExplicitOdeFragment<RParams>,
        initialUniverse: StateMap<RParams>?,
        postProcessor: OnComponent<RParams>?,
        private val splitThreshold: Double
) : Algorithm<RParams>(config, allStates, initialUniverse, postProcessor) {

    override fun runAction(universe: StateMap<RParams>) {
        allStates.run {
            val size = synchronized(pending) { pending.size }
            val allParams = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
            if (allParams.volume() > splitThreshold && size < config.parallelism) {
                println("Not enough parallelism. Split universe")
                val nParams = allParams.first().asIntervals().size
                val bounds = DoubleArray(2*nParams) { i ->
                    if (i % 2 == 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                }
                for (rect in allParams) {
                    val intervals = rect.asIntervals()
                    for (d in 0 until nParams) {
                        bounds[2*d] = kotlin.math.min(bounds[2*d], intervals[d][0])
                        bounds[2*d+1] = kotlin.math.max(bounds[2*d+1], intervals[d][1])
                    }
                }
                val splitThresholds = DoubleArray(nParams) { i -> (bounds[2*i]+bounds[2*i+1]) / 2 }
                val universeMasks = ArrayList<RParams>()
                for (mask in 0 until (1.shl(nParams))) {
                    val maskRectangle = DoubleArray(2*nParams) { i -> bounds[i] }
                    for (d in 0 until nParams) {
                        val up = (mask.shr(d) % 2 == 1)
                        if (up) {
                            maskRectangle[2*d] = splitThresholds[d]
                        } else {
                            maskRectangle[2*d+1] = splitThresholds[d]
                        }
                    }
                    universeMasks.add(Rectangle(maskRectangle).asParams())
                }

                for (paramMask in universeMasks) {
                    startAction(HashStateMap(ff, universe.entries().asSequence().mapNotNull { (s, p) ->
                        (s to (p and paramMask)).takeIf { it.second.isSat() }
                    }.toMap()))
                }
            } else {
                super.runAction(universe)
            }
        }
    }
}