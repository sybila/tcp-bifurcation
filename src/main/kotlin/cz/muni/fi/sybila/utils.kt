package cz.muni.fi.sybila

import com.github.sybila.*
import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.ode.generator.rect.Rectangle
import cz.muni.fi.sybila.deadlock.IParams
import cz.muni.fi.sybila.deadlock.IntRect

interface SolverModel<P : Any> : Model<P>, Solver<P>

fun <T: Any> Model<T>.exportSuccessors(): Array<List<Transition<T>>> {
    val step = (stateCount / 500).coerceAtLeast(100)
    return Array(stateCount) { s ->
        //if (s % step == 0) println("Computing successor relation $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
}

fun <T: Any> Model<T>.exportPredecessors(): Array<List<Transition<T>>> {
    val step = (stateCount / 500).coerceAtLeast(100)
    return Array(stateCount) { s ->
        //if (s % step == 0) println("Computing predecessor relation $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }
}

fun <T: Any> naivePivotChooserFactory(): (Solver<T>) -> PivotChooser<T> = { NaivePivotChooser(it) }
fun <T: Any> structureAndCardinalityPivotChooserFactory(): (ExplicitOdeFragment<T>) -> PivotChooser<T> = { StructureAndCardinalityPivotChooser(it) }

fun SolverModel<MutableSet<Rectangle>>.makeExplicit(
        config: Config
): ExplicitOdeFragment<MutableSet<Rectangle>> {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    val pivotChooser: (ExplicitOdeFragment<MutableSet<Rectangle>>) -> PivotChooser<MutableSet<Rectangle>> = if (config.disableHeuristic) {
        { fragment -> NaivePivotChooser(fragment) }
    } else {
        { fragment -> StructureAndCardinalityPivotChooser(fragment) }
    }

    return ExplicitOdeFragment(this, stateCount, pivotChooser, successors, predecessors)
}

fun SolverModel<MutableSet<IntRect>>.makeExplicitInt(
        config: Config
): ExplicitOdeFragment<MutableSet<IntRect>> {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    val pivotChooser: (ExplicitOdeFragment<IParams>) -> PivotChooser<IParams> = if (config.disableHeuristic) {
        { fragment -> NaivePivotChooser(fragment) }
    } else {
        { fragment -> StructureAndCardinalityPivotChooser(fragment) }
    }

    return ExplicitOdeFragment(this, stateCount, pivotChooser, successors, predecessors)
}

/*
fun <T: Any> ExplicitOdeFragment<T>.runAnalysis(config: Config, initialUniverse: StateMap<T>? = null): StateMap<T> {
    val algorithm = Algorithm(config, this, initialUniverse)

    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { config.logStream?.println("Search elapsed: ${System.currentTimeMillis() - start}ms") }
    }
}

fun <T: Any> ExplicitOdeFragment<T>.runAnalysisWithSinks(config: Config, initialUniverse: StateMap<T>? = null): Pair<StateMap<T>, StateMap<T>> {
    val algorithm = Algorithm(config, this, initialUniverse)

    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { config.logStream?.println("Search elapsed: ${System.currentTimeMillis() - start}ms") } to it.sinks
    }
}*/