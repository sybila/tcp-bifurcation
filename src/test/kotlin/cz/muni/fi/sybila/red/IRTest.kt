package cz.muni.fi.sybila.red

import org.junit.Test
import kotlin.test.assertEquals

class IRTest {

    private fun rectangleOf(vararg coords: Double) = IR(coords)

    @Test
    fun addition1D() {
        val r1 = rectangleOf(-1.0, 2.0)
        val r2 = rectangleOf(-4.0, 1.0)
        assertEquals(rectangleOf(-5.0, 3.0), r1.plus(r2))
    }

    @Test
    fun addition2D() {
        val r1 = rectangleOf(-1.0, 2.0, 1.0, 2.0)
        val r2 = rectangleOf(-4.0, -2.0, -4.0, 3.0)
        assertEquals(rectangleOf(-5.0, 0.0, -3.0, 5.0), r1.plus(r2))
    }

    @Test
    fun subtraction1D() {
        val r1 = rectangleOf(-1.0, 2.0)
        val r2 = rectangleOf(-1.0, 4.0)
        assertEquals(rectangleOf(-5.0, 3.0), r1.minus(r2))
    }

    @Test
    fun subtraction2D() {
        val r1 = rectangleOf(-1.0, 2.0, 1.0, 2.0)
        val r2 = rectangleOf(2.0, 4.0, -3.0, 4.0)
        assertEquals(rectangleOf(-5.0, 0.0, -3.0, 5.0), r1.minus(r2))
    }

    @Test
    fun multiply1D() {
        val r1 = rectangleOf(-1.0, 2.0)
        val r2 = rectangleOf(-4.0, 1.0)
        assertEquals(rectangleOf(-8.0, 4.0), r1.times(r2))
    }

    @Test
    fun multiply2D() {
        val r1 = rectangleOf(-1.0, 2.0, 1.0, 2.0)
        val r2 = rectangleOf(-4.0, -2.0, -4.0, 3.0)
        assertEquals(rectangleOf(-8.0, 4.0, -8.0, 6.0), r1.times(r2))
    }

    @Test
    fun divide1D() {
        val r1 = rectangleOf(-1.0, 2.0)
        val r2 = rectangleOf(-4.0, 1.0)
        assertEquals(setOf(rectangleOf(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)), r1.divide(r2).toSet())
        val r3 = rectangleOf(1.0, 2.0)
        val r4 = rectangleOf(-2.0, -1.0)
        assertEquals(listOf(rectangleOf(-2.0, -0.5)), r3.divide(r4))
    }

    @Test
    fun intersect1D() {
        val r1 = rectangleOf(Double.NEGATIVE_INFINITY, 44.0)
        val bounds = rectangleOf(1.0, 5.0)
        assertEquals(bounds, r1.intersect(bounds))
        val r2 = rectangleOf(0.0, 3.0)
        assertEquals(rectangleOf(1.0, 3.0), r2.intersect(bounds))
    }

    @Test
    fun round1D() {
        val r1 = rectangleOf(1.123, 3.44)
        assertEquals(rectangleOf(1.1, 3.5), r1.roundTo(1))
    }

}