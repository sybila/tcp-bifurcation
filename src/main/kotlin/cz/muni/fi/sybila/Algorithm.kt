package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.ContinuousStateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.OrOperator
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import cz.muni.fi.sybila.output.exportResults
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Modification of terminal component detection algorithm with support for
 * a) component postprocessing
 * b) initial universe restriction
 * c) sink pruning
 */
internal open class Algorithm<T: Any>(
        protected val config: Config,
        protected val allStates: ExplicitOdeFragment<T>,
        private val initialUniverse: StateMap<T>? = null,
        private val postProcessor: OnComponent<T>? = null
) : Closeable {

    val count = Count(allStates)
    val store = ComponentStorage(allStates)

    private val executor = Executors.newFixedThreadPool(config.parallelism)
    protected val pending = ArrayList<Future<*>>()

    fun computeComponents() {
        startAction(initialUniverse ?: TrueOperator(allStates.asSingletonPartition()).compute())
        blockWhilePending()
    }

    protected open fun runAction(universe: StateMap<T>) {
        allStates.restrictTo(universe).run {
            val universeSize = universe.entries().asSequence().count()
            config.logStream?.println("Universe size: $universeSize")
            val channel = this.asSingletonChannel()
            val pivots = pivot.choose(universe).asOperator()

            channel.run {
                val forward = reachForward(pivots)
                val backward = intersect(reachBackward(pivots), forward)
                val forwardNotBackward = complement(forward, backward).compute()

                val reachableComponentParams = allParams(forwardNotBackward)

                postProcessor?.let { post ->
                    val component = forward.compute().entries().asSequence().mapNotNull { (s, p) ->
                        (s to (p and reachableComponentParams)).takeIf { it.second.isSat() }
                    }.toMap()
                    post.onComponent(component)
                }
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

    protected fun startAction(universe: StateMap<T>) {
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

    interface OnComponent<T: Any> {
        fun onComponent(component: Map<Int, T>)
    }

}
