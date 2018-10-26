package cz.muni.fi.sybila

data class Graph(
        val states: Set<Int>,
        val transitions: Map<Int, List<Int>>
)

fun Graph.isBipartite(): Boolean {
    val a = HashSet<Int>()
    val b = HashSet<Int>()

    a.add(states.first())
    var changed = true
    while (changed) {
        changed = false
        val nextA = a.flatMap { transitions[it]!! }.toSet()
        if (nextA - a != nextA) return false
        val newB = b + nextA
        changed = changed || newB != b
        b += nextA
        val nextB = b.flatMap { transitions[it]!! }.toSet()
        if (nextB - b != nextB) return false
        val newA = a + nextB
        changed = changed || newA != a
        a += nextB
    }

    println("1: ${a.sorted()}")
    println("2: ${b.sorted()}")
    return true
}