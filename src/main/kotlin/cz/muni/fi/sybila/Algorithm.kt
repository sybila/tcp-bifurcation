package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.*
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.OrOperator
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.OdeModel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class Algorithm<T: Any>(
        private val config: Config,
        private val allStates: ExplicitOdeFragment<T>,
        private val initialUniverse: StateMap<T>? = null
) : Closeable {

    private val count = Count(allStates)
    private val store = ComponentStore(allStates)

    private val executor = Executors.newFixedThreadPool(config.parallelism)
    private val pending = ArrayList<Future<*>>()
    val sinks = HashStateMap(allStates.ff)

    fun computeComponents(): StateMap<T> {
        startAction(initialUniverse ?: TrueOperator(allStates.asSingletonPartition()).compute())
        blockWhilePending()

        val components = store.getComponentMapping(count)
        val channel = allStates.asSingletonChannel()
        /*println("Max components: ${count.size}")
        println("States in a component:  ${components.last().entries().asSequence().count()}")
        val map = components.last()
        allStates.run {
            for ((s, p) in map.entries().asSequence().toList().sortedBy { it.first }) {
                println("$s -> ${s.successors(true).asSequence().map { it.target }.toList()}")
            }
        }
        val states = map.entries().asSequence().map { it.first }.toSet()
        val transitions = states.map { source -> source to allStates.run { source.successors(true).asSequence().map { it.target }.toList() } }.toMap()
        val g = Graph(states, transitions)
        println("Is bipartite: ${g.isBipartite()}")*/
        println("Sinks: ${sinks.entries().asSequence().toList()}")
        return components.fold<StateMap<T>, Operator<T>>(components[0].asOperator()) { a, b -> OrOperator(a, b.asOperator(), channel) }.compute()
        //val result = store.getComponentMapping(count).mapIndexed { i, map -> "${i+1} attractor(s)" to listOf(map) }.toMap()

        //return allStates.exportResults(odeModel, result)
    }

    private fun runAction(universe: StateMap<T>) {
        allStates.restrictTo(universe).run {
            val universeSize = universe.entries().asSequence().count()
            if (universeSize == 1) {
                // this is obviously a terminal component, don't do all that other stuff...
                val (s, p) = universe.entries().next()
                store.push(universe, p)
                sinks.setOrUnion(s, p)
                return@run
            }
            config.logStream?.println("Universe size: $universeSize")
            val channel = this.asSingletonChannel()
            val pivots = pivot.choose(universe).asOperator()

            channel.run {
                val forward = reachForward(pivots)
                val backward = intersect(reachBackward(pivots), forward)
                val forwardNotBackward = complement(forward, backward).compute()

                val reachableComponentParams = allParams(forwardNotBackward)
                /*println("Pivot: ${pivots.compute().entries().asSequence().toList()}")
                println("Forward: ${forward.compute().entries().asSequence().toList()}")
                println("Backward: ${backward.compute().entries().asSequence().toList()}")
                println("Forward not backward: ${forwardNotBackward.entries().asSequence().toList()}")
                println("Component: ${forward.compute().entries().asSequence().toList()} $reachableComponentParams ${reachableComponentParams.not()}")*/
                //println("Component: ${forward.compute().entries().asSequence().map { it.first to (it.second and reachableComponentParams.not()) }.filter { it.second.isSat() }.toList()}")
                // Detect sink states and put them aside
                forward.compute().entries().forEach { (s, p) ->
                    //println("succ $s: ${s.successors(true).asSequence().map { it.target to it.bound.not() }.toList()}")
                    val isSink = s.successors(true).asSequence().fold(p) { sinkParams, next ->
                        sinkParams and (if (next.target == s) tt else next.bound.not())
                    }
                    //println("Sink: $s for $isSink")
                    if (isSink.isSat()) sinks.setOrUnion(s, isSink)
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
