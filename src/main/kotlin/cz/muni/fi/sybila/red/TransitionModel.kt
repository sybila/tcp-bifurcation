package cz.muni.fi.sybila.red

import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.model.OdeModel
import cz.muni.fi.sybila.SolverModel

/**
 * A simple base class which requires an array of parametrised transitions and then transforms them
 * to proper transition objects.
 *
 * It also constructs basic state space thresholds and states.
 */
abstract class TransitionModel(
        solver: RectangleSolver,
        private val thresholdCount: Int = 1000,
        protected val varBounds: Pair<Double, Double> = 300.0 to 500.0,
        constants: Constants = ConstDefault
) : SolverModel<RParams>, Constants by constants, IntervalSolver<RParams> by solver, Solver<RParams> by solver {

    protected val thresholds = run {
        val (min, max) = varBounds
        val step = (max - min) / (thresholdCount)
        (0 until thresholdCount).map { i -> min + step*i } //+ max
    }

    protected val states: List<IR> = run {
        thresholds.dropLast(1).zip(thresholds.drop(1)).map { irOf(it.first, it.second) }
    }

    protected abstract val transitionArray: Array<Array<RParams?>>
    abstract val fakeOdeModel: OdeModel

    override val stateCount: Int = states.size

    override fun Formula.Atom.Float.eval(): StateMap<RParams> { error("unimplemented") }
    override fun Formula.Atom.Transition.eval(): StateMap<RParams> { error("unimplemented") }
    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<RParams>> = this.successors(!timeFlow)


    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<RParams>> {
        val source = this
        return if (timeFlow) {
            states.indices.mapNotNull { target ->
                transitionArray[source][target]?.let { Transition(target, DirectionFormula.Atom.True, it) }
            }.iterator()
        } else {
            states.indices.mapNotNull { target ->
                transitionArray[target][source]?.let { Transition(target, DirectionFormula.Atom.True, it) }
            }.iterator()
        }
    }

}