package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.ode.model.OdeModel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class Algorithm<T: Any>(
        private val config: Config,
        private val allStates: ExplicitOdeFragment<T>,
        private val odeModel: OdeModel
) : Closeable {

    private val count = Count(allStates)
    private val store = ComponentStore(allStates)

    private val executor = Executors.newFixedThreadPool(config.parallelism)
    private val pending = ArrayList<Future<*>>()

    fun computeComponents(): ResultSet {
        startAction(TrueOperator(allStates.asSingletonPartition()).compute())
        blockWhilePending()

        val result = store.getComponentMapping(count).mapIndexed { i, map -> "${i+1} attractor(s)" to listOf(map) }.toMap()

        return allStates.exportResults(odeModel, result)
    }

    private fun runAction(universe: StateMap<T>) {
        allStates.restrictTo(universe).run {
            val universeSize = universe.entries().asSequence().map { it.second.volume() }.sum()
            config.logStream?.println("Universe size: $universeSize")
            val channel = this.asSingletonChannel()
            val pivots = pivot.choose(universe).asOperator()

            channel.run {
                val forward = reachForward(pivots)
                val backward = intersect(reachBackward(pivots), forward)
                val forwardNotBackward = complement(forward, backward).compute()

                val reachableComponentParams = allParams(forwardNotBackward)
                store.push(forward.compute(), reachableComponentParams.not())

                if (reachableComponentParams.isSat()) {
                    startAction(forwardNotBackward)
                }

                val backwardFromForward = reachBackward(forward)
                val cantReachForward = complement(universe.asOperator(), backwardFromForward).compute()
                val unreachableComponentsParams = allParams(cantReachForward)

                if (unreachableComponentsParams.isSat()) {
                    count.push(unreachableComponentsParams)
                    startAction(cantReachForward)
                }
            }

        }
    }

    private fun startAction(universe: StateMap<T>) {
        synchronized(pending) {
            pending.add(executor.submit {
                runAction(universe)
            })
        }
    }

    private fun blockWhilePending() {
        do {
            val waited = synchronized(pending) {
                pending.firstOrNull()
            }?.let { waitFor ->
                waitFor.get()
                synchronized(pending) { pending.remove(waitFor)}
                Unit
            }
        } while (waited != null)
    }

    override fun close() {
        executor.shutdown()
    }
}