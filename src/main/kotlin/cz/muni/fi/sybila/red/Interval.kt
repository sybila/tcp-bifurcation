package cz.muni.fi.sybila.red

import java.lang.IllegalStateException
import java.util.*

/**
 * Multi-dimensional implementation of interval arithmetic primitives
 */
class IR(
        private val coordinates: DoubleArray
) {

    private val dimensions: Int
        get() = coordinates.size / 2

    init {
        for (i in 0 until dimensions) {
            if (coordinates[2*i+1] < coordinates[2*i]) error("Invalid interval rectangle: ${Arrays.toString(coordinates)}")
        }
    }

    fun getL(dim: Int) = coordinates[2*dim]
    fun getH(dim: Int) = coordinates[2*dim+1]

    /**
     * Interval rectangle addition - simply add the intervals together.
     */
    infix fun plus(y: IR): IR {
        return IR(DoubleArray(2*dimensions) { i ->
            coordinates[i] + y.coordinates[i]
        })
    }

    /**
     * Instead of direct subtraction, we provide an additive inverse. This should work without loosing precision.
     * Just remember to swap the coordinates.
     */
    private fun additiveInverse(): IR {
        return IR(DoubleArray(2*dimensions) { i ->
            val dim = i / 2
            if (i%2 == 0) -coordinates[2*dim+1] else -coordinates[2*dim]
        })
    }

    /**
     * Subtraction using additive inverse.
     */
    infix fun minus(y: IR): IR = this.plus(y.additiveInverse())

    /**
     * Interval rectangle multiplication - just like normal interval multiplication but extended to multiple
     * dimensions
     */
    infix fun times(y: IR): IR {
        return IR(DoubleArray(2*dimensions) { i ->
            val dim = i / 2
            val x1 = coordinates[2*dim]; val x2 = coordinates[2*dim+1]
            val y1 = y.coordinates[2*dim]; val y2 = y.coordinates[2*dim+1]
            if (i%2 == 0) {
                min(x1 * y1, x1 * y2, x2 * y1, x2 * y2)
            } else {
                max(x1 * y1, x1 * y2, x2 * y1, x2 * y2)
            }
        })
    }

    /**
     * Multiplicative inverse of a rectangle is potentially a set of rectangles, since it can contain zero
     * and then we have to split by zero.
     */
    private fun multiplicativeInverse(): List<IR> {
        return (0 until dimensions).fold(listOf(this)) { list, dim ->
            list.flatMap { r ->
                val x1 = r.coordinates[2*dim]; val x2 = r.coordinates[2*dim+1]
                if (x1 == 0.0 && x2 == 0.0) {
                    // Division by pure zero, we are screwed
                    listOf(IR(r.coordinates.clone().apply {
                        set(2*dim, Double.NEGATIVE_INFINITY); set(2*dim+1, Double.POSITIVE_INFINITY)
                    }))
                } else if (x1 == 0.0) {
                    // 0.0 .... x2
                    listOf(IR(r.coordinates.clone().apply {
                        set(2*dim, 1.0/x2); set(2*dim+1, Double.POSITIVE_INFINITY)
                    }))
                } else if (x2 == 0.0) {
                    // x1 .... 0.0
                    listOf(IR(r.coordinates.clone().apply {
                        set(2*dim, Double.NEGATIVE_INFINITY); set(2*dim+1, 1.0/x1)
                    }))
                } else if (x1 < 0.0 && 0.0 < x2) {
                    // x1 ... 0.0 ... x2 - SPLIT!
                    listOf( IR(r.coordinates.clone().apply {
                        set(2*dim, Double.NEGATIVE_INFINITY); set(2*dim+1, 1.0/x1)
                    }),             IR(r.coordinates.clone().apply {
                        set(2*dim, 1.0/x2); set(2*dim+1, Double.POSITIVE_INFINITY)
                    }))
                } else {
                    // 0.0 ... x1 ... x2 or x1 ... x2 ... 0.0 - switch values
                    listOf( IR(r.coordinates.clone().apply {
                        set(2*dim, 1.0/x2); set(2*dim+1, 1.0/x1)
                    }))
                }
            }
        }
    }

    /**
     * Standard division - can create multiple rectangles if div by zero occurs.
     */
    infix fun divide(y: IR): List<IR> {
        return y.multiplicativeInverse().map { this.times(it) }
    }

    /**
     * Intersect this interval rectangle with given bounds rectangle. Can return null if result is empty.
     */
    infix fun intersect(y: IR): IR? {
        return try {
            IR(DoubleArray(2*dimensions) { i ->
                if (i%2 == 0) max(coordinates[i], y.coordinates[i]) else min(coordinates[i], y.coordinates[i])
            })
        } catch (e: IllegalStateException) {
            // rectangle is empty!
            return null
        }
    }

    fun roundTo(places: Int): IR {
        val precision = Math.pow(10.0, places.toDouble())
        return IR(DoubleArray(2*dimensions) { i ->
            if (i%2 == 0) coordinates[i].roundDown(precision) else coordinates[i].roundUp(precision)
        })
    }

    private fun Double.roundUp(precision: Double) = Math.ceil(this * precision) / precision
    private fun Double.roundDown(precision: Double) = Math.floor(this * precision) / precision

    fun contains(dim: Int, num: Double) = coordinates[2*dim] <= num && num <= coordinates[2*dim+1]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IR

        if (!Arrays.equals(coordinates, other.coordinates)) return false

        return true
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(coordinates)
    }

    override fun toString(): String = Arrays.toString(coordinates)
}

fun irOf(vararg coordinates: Double) = IR(coordinates)
fun Double.toIR() = irOf(this, this)

/**
 * Simple implementation of interval arithmetic. Not very fast, but it'll do.
 */
data class Interval(
        val x1: Double, val x2: Double
) {

    init {
        if (x2 < x1) error("Invalid interval $x1 .. $x2")
    }

    operator fun plus(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(x1 + y1, x2 + y2)
    }

    operator fun minus(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(x1 - y2, x2 - y1)
    }

    operator fun times(y: Interval): Interval {
        val (y1, y2) = y
        return Interval(
                min(x1 * y1, x1 * y2, x2 * y1, x2 * y2),
                max(x1 * y1, x1 * y2, x2 * y1, x2 * y2)
        )
    }

    private fun inverse(): Interval {
        //if (x1 <= 0 && 0 <= x2) error("Division by zero")
        val iX1 = 1.0 / x2
        val iX2 = 1.0 / x1
        return if (iX1 > iX2) {
            Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        } else {
            Interval(1.0 / x2, 1.0 / x1)
        }
    }

    operator fun div(y: Interval): Interval = this * y.inverse()

    fun restrictUp(y: Double) = Interval(min(x1, y), max(min(x1, y), min(x2, y)))
    fun restrictDown(y: Double) = Interval(max(x1, y), x2)

    override fun toString(): String {
        return "[$x1, $x2]"
    }

    fun intersects(other: Interval): Boolean = if (this.x1 <= other.x1) {
        this.x2 >= other.x1
    } else {
        other.x2 >= this.x1
    }

    fun union(other: Interval): Interval = Interval(min(this.x1, other.x1), max(this.x2, other.x2))

}

fun Double.asI(): Interval = Interval(this, this)
//fun Double.asI(delta: Double): Interval = Interval(this - delta, this + delta)

private fun min(vararg x: Double): Double = x.fold(Double.POSITIVE_INFINITY) { a, i -> if (a < i) a else i }
private fun max(vararg x: Double): Double = x.fold(Double.NEGATIVE_INFINITY) { a, i -> if (a > i) a else i }

