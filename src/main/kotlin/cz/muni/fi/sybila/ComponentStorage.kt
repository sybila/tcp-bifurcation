package cz.muni.fi.sybila

import com.github.sybila.Count
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.HashStateMap

class ComponentStorage<T: Any>(
        private val solver: Solver<T>
) {

    val components: MutableMap<Int, T> = HashMap()

    fun push(component: StateMap<T>, bound: T) {
        synchronized(this) {
            solver.run {
                for ((k, v) in component.entries()) {
                    val newParams = (components.getOrDefault(k, ff) or (v and bound))
                    if (newParams.isSat()) {
                        components[k] = newParams
                    }
                }
            }
        }
    }

    fun getComponentMapping(count: Count<T>): List<StateMap<T>> {
        solver.run {
            val result = ArrayList<StateMap<T>>(count.size)
            for (c in 0 until count.size) {
                val levelParams = count[c]
                //val levelMap = components.filter { (_, v) -> (v and levelParams).isSat() }
                val levelMap = components.map { (k, v) -> k to (v and levelParams) }.filter { it.second.isSat() }.toMap()
                result.add(HashStateMap(ff, levelMap))
            }
            return result
        }
    }

}