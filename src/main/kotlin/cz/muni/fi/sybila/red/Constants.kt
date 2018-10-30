package cz.muni.fi.sybila.red

interface Constants {

    val b: Double
    val qL: Double
    val qU: Double
    val m: Double
    val pMax: Double
    val n: Double
    val d: Double
    val c: Double
    val k: Double
    val w: Double

}

object ConstDefault : Constants {

    override val b: Double = 3750.0
    override val qL: Double = 250.0
    override val qU: Double = 750.0
    override val m: Double = 4000.0
    override val pMax: Double = 0.1
    override val n: Double = 250.0
    override val d: Double = 0.1
    override val c: Double = 75000000.0
    override val k: Double = Math.sqrt(3.0/2.0)
    override val w: Double = 0.15

}

data class Const(
        override val b: Double = ConstDefault.b,
        override val qL: Double = ConstDefault.qL,
        override val qU: Double = ConstDefault.qU,
        override val m: Double = ConstDefault.m,
        override val pMax: Double = ConstDefault.pMax,
        override val n: Double = ConstDefault.n,
        override val d: Double = ConstDefault.d,
        override val c: Double = ConstDefault.c,
        override val k: Double = ConstDefault.k,
        override val w: Double = ConstDefault.w
) : Constants