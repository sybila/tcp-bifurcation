package cz.muni.fi.sybila

import com.github.sybila.checker.Solver


/**
 * Interval implements a simple
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

