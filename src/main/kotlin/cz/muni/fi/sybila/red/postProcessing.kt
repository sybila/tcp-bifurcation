package cz.muni.fi.sybila.red

import com.github.sybila.Count
import com.github.sybila.checker.Model
import com.github.sybila.checker.map.mutable.HashStateMap

/**
 * Extract oscillating (bipartite) components from a parametrised component set.
 *
 * WARNING: if there are two components, one oscillating and one not oscillating, overall result is not
 * oscillating. Also, sinks are considered as oscillating, so extract them first.
 *
 * @return oscillating vs. arbitrary components
 */
fun <T: Any> Map<Int, T>.extractOscillation(ts: Model<T>): Pair<Map<Int, T>, Map<Int, T>> {
    val map = this
    ts.run {
        var notBipartite = ff
        val remaining = HashMap(map)
        println("Extract oscillation...")
        while (remaining.isNotEmpty()) {
            /*

                Bipartite graph detection divides the graph into two subgroups.
                If for some parameter the groups overlap, remove this parameter from consideration.

                Since we always start with only one pivot, we have to repeatedly test until we cover
                all nodes since the graph does not need to be continuous.

             */
            var thisGroup = HashStateMap(ff)
            var otherGroup = HashStateMap(ff)
            val first = remaining.entries.first()
            var frontier: List<Pair<Int, T>> = listOf(first.key to first.value)
            while (frontier.isNotEmpty()) {
                // first, mark all frontier nodes as resolved - i.e. they don't need reprocessing
                frontier.forEach { (k, v) ->
                    val reduced = remaining[k]?.and(v.not())
                    if (reduced?.isNotSat() != false) remaining.remove(k) else remaining[k] = reduced
                }
                // then save all frontier nodes to this group
                frontier.forEach { (k, v) ->
                    thisGroup.setOrUnion(k, v)
                }
                // compute new frontier - avoid states which have already been selected into OTHER group
                // but allow nodes from this group - these are the ones that break the property
                frontier = frontier.asSequence().flatMap { (k, v) ->
                    k.successors(true).asSequence().mapNotNull { (t, _, bound) ->
                        (t to (v and bound and otherGroup[t].not())).takeIf { it.second.isSat() }
                    }
                }.filterNotNull().groupBy { it.first }.map { (k, values) ->
                    if (values.isEmpty()) null else {
                        k to values.fold(values[0].second) { a, b -> a or b.second }
                    }
                }.filterNotNull().toList()
                // finally, swap groups for next iteration
                val swap = thisGroup
                thisGroup = otherGroup
                otherGroup = swap
            }
            for (s in 0 until stateCount) {
                val inBothGroups = thisGroup[s] and otherGroup[s]
                if (inBothGroups.isSat()) {
                    notBipartite = notBipartite or inBothGroups
                }
            }
        }
        val isBipartite = notBipartite.not()
        val bipartiteMap = map.entries.mapNotNull { (k, v) ->
            (k to (v and isBipartite)).takeIf { it.second.isSat() }
        }.toMap()
        val notBipartiteMap = map.entries.mapNotNull { (k, v) ->
            (k to (v and notBipartite)).takeIf { it.second.isSat() }
        }.toMap()

        return bipartiteMap to notBipartiteMap
    }
}

/**
 * Filter components into two groups according to size. The size is determined as a number of states
 * specified in a threshold.
 *
 * @return small components and big components
 */
fun <T: Any> Map<Int, T>.extractSmallComponents(ts: Model<T>, threshold: Int): Pair<Map<Int, T>, Map<Int, T>> {
    val map = this
    println("Extract small components")
    ts.run {
        val count = Count(ts)
        map.values.forEach { count.push(it) }
        val smallComponentParams = (1..threshold).fold(ff) { colors, c ->
            colors or count[c]
        }
        val bigComponentParams = smallComponentParams.not()
        val smallComponent = map.mapNotNull { (s, p) ->
            (s to (p and smallComponentParams)).takeIf { it.second.isSat() }
        }.toMap()
        val bigComponent = map.mapNotNull { (s, p) ->
            (s to (p and bigComponentParams)).takeIf { it.second.isSat() }
        }.toMap()
        return smallComponent to bigComponent
    }
}