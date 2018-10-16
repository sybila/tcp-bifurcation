package cz.muni.fi.sybila

import kotlin.math.sqrt

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
                min(x1*y1, x1*y2, x2*y1, x2*y2),
                max(x1*y1, x1*y2, x2*y1, x2*y2)
        )
    }

    private fun inverse(): Interval {
        if (x1 <= 0 && 0 <= x2) error("Division by zero")
        return Interval(1.0 / x2, 1.0 / x1)
    }

    operator fun div(y: Interval): Interval = this * y.inverse()

    fun extendUp(y: Double) = Interval(x1, max(x2, y))
    fun extendDown(y: Double) = Interval(min(x1, y), x2)
    fun restrictUp(y: Double) = Interval(x1, max(x1, min(x2, y)))
    fun restrictDown(y: Double) = Interval(min(x2, max(x1, y)), x2)

}

private fun isqrt(x: Interval): Interval {
    if (x.x2 <= 0) error("Sqrt of negative")
    // sqrt is monotone and positive
    return Interval(sqrt(x.x1), sqrt(x.x2))
}

fun Double.asI(): Interval = Interval(this, this)

private fun min(vararg x: Double): Double = x.fold(Double.POSITIVE_INFINITY) { a, i -> if (a < i) a else i }
private fun max(vararg x: Double): Double = x.fold(Double.NEGATIVE_INFINITY) { a, i -> if (a > i) a else i }

const val maxQ: Double = 100.0
const val minQ: Double = 50.0
const val c: Double = 1500.0
val k: Double = sqrt(8.0/3.0)
const val B: Double = 300.0
const val R0: Double = 0.1
const val M: Double = 0.5
const val n: Double = 20.0
val p0: Double = Math.pow((M*k)/ (R0 * c/n), 2.0)
const val maxP: Double = 0.1
val w: Double = Math.pow(10.0, -1.33)

fun A(avrQ: Double, nextQ: Double): Double = (1 - w) * avrQ + w * nextQ

fun G(p: Double): Double = if (p > p0) 0.0 else {
    val y = (c/M) * ((M*k)/(sqrt(p) * c / n) - R0)
    if (y < B) y else B
}

fun H(q: Double): Double = when {
    q <= minQ -> 0.0
    q >= maxQ -> 1.0
    else -> maxP * (q - minQ) / (maxQ - minQ)
}

fun next(q: Double) = A(q, G(H(q)))

fun iA(avrQ: Interval, nextQ: Interval): Interval = (1 - w).asI() * avrQ + w.asI() * nextQ

fun iG(p: Interval): Interval = TODO()

fun iH(q: Interval): Interval = when {
    q.x2 <= minQ -> 0.0.asI()
    q.x1 >= maxQ -> 1.0.asI()
    else -> {
        val i = maxP.asI() * (q - minQ.asI()) / (maxQ - minQ).asI()
        var normalized = i.restrictDown(0.0).restrictUp(1.0)
        if (q.x2 >= maxQ) normalized = normalized.extendUp(1.0)
        if (q.x1 <= minQ) normalized = normalized.extendDown(0.0)
        normalized
    }
}

fun main(args: Array<String>) {
    /*var q = 54.25
    while (true) {
        repeat(1000) {
            q = next(q)
            println("$q ")
        }
        break
    }*/
    //println(p0)
    //val prob = (1..100).map { it * 0.0002 }
    //prob.zip(prob.map { G(it) }).printPointSet()

    val q = (1..100).map { it * 0.5 + 25 }
    q.zip(q.map { next(it) }).printPointSet()
}

fun List<Pair<Double, Double>>.printPointSet() = map { "${it.first} ${it.second}".replace('.', ',') }.forEach {
    println(it)
}