package cz.muni.fi.sybila.deadlock

import com.github.sybila.checker.MutableStateMap
import com.github.sybila.checker.Solver
import com.github.sybila.checker.solver.SolverStats
import com.github.sybila.ode.generator.IntervalSolver
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

typealias IParams = MutableSet<IntRect>

class IntRect(
        private val coordinates: IntArray
) {

    fun intersect(other: IntRect, into: IntArray): IntRect? {
        for (i in 0 until (this.coordinates.size / 2)) {
            val iL = 2*i
            val iH = 2*i+1
            val low = max(coordinates[iL], other.coordinates[iL])
            val high = min(coordinates[iH], other.coordinates[iH])
            if (low > high) return null
            else {
                into[iL] = low
                into[iH] = high
            }
        }
        return IntRect(into)
    }

    fun newArray(): IntArray = IntArray(coordinates.size)

    private fun encloses(other: IntRect): Boolean {
        for (i in coordinates.indices) {
            if (i % 2 == 0 && coordinates[i] >= other.coordinates[i]) return false
            if (i % 2 == 1 && coordinates[i] <= other.coordinates[i]) return false
        }
        return true
    }

    /**
     * If possible, merge these two rectangles. If not possible, return null.
     */
    operator fun plus(other: IntRect): IntRect? {
        if (this.encloses(other)) return this
        if (other.encloses(this)) return other
        var mergeDimension = -1
        var mergeLow = Int.MIN_VALUE
        var mergeHigh = Int.MAX_VALUE
        for (dim in 0 until (coordinates.size/2)) {
            val l1 = coordinates[2*dim]
            val l2 = other.coordinates[2*dim]
            val h1 = coordinates[2*dim+1]
            val h2 = other.coordinates[2*dim+1]
            if (l1 == l2 && h1 == h2) {
                //this dimension won't change
                continue
            } else if (h2+1 < l1 || h1+1 < l2) {
                // l1..h1 ... l2..h2 || l2..h2 ... l1..h1 - we can't merge them, they are completely separate
                return null
            } else {
                //we have a possible merge dimension
                if (mergeDimension != -1) {
                    //more than one merge dimension, abort
                    return null
                } else {
                    mergeDimension = dim
                    mergeLow = min(l1, l2)
                    mergeHigh = max(h1, h2)
                }
            }
        }
        //if rectangles are equal, they are processed in encloses section - if we reach this point, merge must be valid
        val newCoordinates = coordinates.copyOf()
        newCoordinates[2*mergeDimension] = mergeLow
        newCoordinates[2*mergeDimension+1] = mergeHigh
        return IntRect(newCoordinates)
    }

    /**
     * Create a set of smaller rectangles that together form a result of subtraction of given rectangle.
     */
    operator fun minus(other: IntRect): MutableSet<IntRect> {
        val workingCoordinates = coordinates.copyOf()
        val results = HashSet<IntRect>()
        for (dim in 0 until (coordinates.size/2)) {
            val l1 = coordinates[2*dim]
            val l2 = other.coordinates[2*dim]
            val h1 = coordinates[2*dim+1]
            val h2 = other.coordinates[2*dim+1]
            if (l1 >= l2 && h1 <= h2) {
                //this dimension has a clean cut, no rectangles are created
                continue
            } else if (h2 < l1 || h1 < l2) {
                // l1..h1 ... l2..h2 || l2..h2 ... l1..h1 - these rectangles are completely separate, nothing should be cut
                return mutableSetOf(this)
            } else {
                if (l1 < l2) {
                    //there is an overlap on the lower side, create cut-rectangle and subtract it from working coordinates
                    val newCoordinates = workingCoordinates.copyOf()
                    newCoordinates[2*dim] = l1
                    newCoordinates[2*dim+1] = l2 - 1
                    results.add(IntRect(newCoordinates))
                    workingCoordinates[2*dim] = l2
                }
                if (h1 > h2) {
                    //there is an overlap on the upper side, create cut-rectangle and subtract it from working coordinates
                    val newCoordinates = workingCoordinates.copyOf()
                    newCoordinates[2*dim] = h2 + 1
                    newCoordinates[2*dim+1] = h1
                    results.add(IntRect(newCoordinates))
                    workingCoordinates[2*dim+1] = h2
                }
            }
        }
        return results
    }

    override fun equals(other: Any?): Boolean = other is IntRect && Arrays.equals(coordinates, other.coordinates)

    override fun hashCode(): Int = Arrays.hashCode(coordinates)

    override fun toString(): String = Arrays.toString(coordinates)

    fun byteSize(): Int = 4 + 4 * coordinates.size

    fun writeToBuffer(buffer: ByteBuffer) {
        buffer.putInt(coordinates.size)
        coordinates.forEach { buffer.putInt(it) }
    }

    fun asParams(): MutableSet<IntRect> = mutableSetOf(this)

    fun volume(): Double {
        var vol = 1.0
        for (dim in 0 until coordinates.size/2) {
            vol *= coordinates[2*dim + 1] - coordinates[2*dim]
        }
        return vol
    }

    fun asIntervals(): Array<DoubleArray> {
        return Array(coordinates.size / 2) { i ->
            doubleArrayOf(coordinates[2*i]-0.1, coordinates[2*i + 1]+0.1)
        }
    }

}

fun intRectFromBuffer(buffer: ByteBuffer): IntRect {
    val size = buffer.int
    val coordinates = IntArray(size)
    for (i in 0 until size) {
        coordinates[i] = buffer.int
    }
    return IntRect(coordinates)
}

fun iRectOf(vararg ints: Int): IntRect {
    return IntRect(ints)
}


class IntRectSolver(
        private val bounds: IntRect
) : Solver<MutableSet<IntRect>>, IntervalSolver<MutableSet<IntRect>> {

    override val ff: MutableSet<IntRect> = mutableSetOf()
    override val tt: MutableSet<IntRect> = mutableSetOf(bounds)

    private val cache = ThreadLocal<IntArray?>()

    private var coreSize = AtomicInteger(2)

    override fun MutableSet<IntRect>.and(other: MutableSet<IntRect>): MutableSet<IntRect> {
        return if (this.isEmpty()) this
        else if (other.isEmpty()) other
        else if (this == tt) other
        else if (other == tt) this
        else {
            val newItems = HashSet<IntRect>()
            for (item1 in this) {
                for (item2 in other) {
                    var c = cache.get()
                    if (c == null) c = item1.newArray()
                    val r = item1.intersect(item2, c)
                    //val r = item1 * item2
                    if (r != null) {
                        cache.set(null)
                        newItems.add(r)
                    } else {
                        cache.set(c)
                    }
                }
            }
            newItems
        }
    }

    override fun MutableSet<IntRect>.isSat(): Boolean {
        SolverStats.solverCall()
        return this.isNotEmpty()
    }

    override fun MutableSet<IntRect>.not(): MutableSet<IntRect> {
        return if (this.isEmpty()) {
            tt
        } else if (this == tt) {
            ff
        } else {
            //!(a | b) <=> (!a & !b) <=> ((a1 | a2) && (b1 | b2))
            this.map { bounds - it }.fold(tt) { a, i -> a and i }.toMutableSet()
        }
    }

    override fun MutableSet<IntRect>.andNot(other: MutableSet<IntRect>): Boolean {
        //(a | b) && !(c | d) = (a | b) && (c1 | c2) && (d1 | d2)
        return if (this == ff) false
        else if (other == tt) false
        else {
            other.fold<IntRect, Collection<IntRect>>(this) { acc, rect ->
                acc.flatMap { (it - rect) }
            }.any()
        }
    }

    override fun MutableSet<IntRect>.or(other: MutableSet<IntRect>): MutableSet<IntRect> {
        return if (this.isEmpty()) other
        else if (other.isEmpty()) this
        else if (this == tt || other == tt) tt
        else {
            val result = HashSet<IntRect>()
            result.addAll(this)
            result.addAll(other)
            result.minimize()
            result
        }
    }

    override fun MutableSet<IntRect>.minimize() {
        if (4 * this.size < coreSize.get()) return
        do {
            var merged = false
            search@ for (c in this) {
                for (i in this) {
                    if (i == c) continue

                    val union = i + c
                    if (union != null) {
                        this.remove(i)
                        this.remove(c)
                        this.add(union)
                        merged = true
                        break@search
                    }
                }
            }
            //if (this.size < 2) return
        } while (merged)
        coreSize.set(this.size)
    }

    override fun MutableSet<IntRect>.prettyPrint(): String = toString()

    override fun ByteBuffer.putColors(colors: MutableSet<IntRect>): ByteBuffer {
        this.putInt(colors.size)
        colors.forEach { it.writeToBuffer(this) }
        return this
    }

    override fun ByteBuffer.getColors(): MutableSet<IntRect> {
        return (0 until this.int).map { intRectFromBuffer(this) }.toMutableSet()
    }

    override fun MutableSet<IntRect>.byteSize(): Int {
        //assumption: All rectangles have the same size
        val rectangleSize = this.firstOrNull()?.byteSize() ?: 0
        return 4 + rectangleSize * this.size
    }

    override fun MutableSet<IntRect>.transferTo(solver: Solver<MutableSet<IntRect>>): MutableSet<IntRect> {
        //rectangle is immutable, all we need to copy is the set
        return HashSet(this)
    }


    override fun MutableStateMap<MutableSet<IntRect>>.setOrUnion(state: Int, value: MutableSet<IntRect>): Boolean {
        return if (value.isNotSat()) false
        else if (state !in this && value.isSat()) {
            this[state] = value
            true
        } else {
            val current = this[state]
            if (value andNot current) {
                this[state] = value or current
                true
            } else false
        }
    }

    override fun MutableSet<IntRect>.asIntervals(): Array<Array<DoubleArray>> {
        return this.map { it.asIntervals() }.toTypedArray()
    }

}