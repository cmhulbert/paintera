package org.janelia.saalfeldlab.util

import net.imglib2.*
import net.imglib2.converter.Converters
import net.imglib2.converter.read.ConvertedRealRandomAccessible
import net.imglib2.interpolation.InterpolatorFactory
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory
import net.imglib2.realtransform.AffineGet
import net.imglib2.realtransform.RealViews
import net.imglib2.type.BooleanType
import net.imglib2.type.Type
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.RealType
import net.imglib2.util.Intervals
import net.imglib2.view.IntervalView
import net.imglib2.view.RandomAccessibleOnRealRandomAccessible
import net.imglib2.view.Views
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import kotlin.math.floor
import kotlin.math.roundToLong

infix fun Interval.union(other: Interval?): Interval = other?.let { Intervals.union(this, other) } ?: this
infix fun Interval.intersect(other: Interval?): Interval = other?.let { Intervals.intersect(this, other) } ?: this
infix fun RealInterval.union(other: RealInterval?): RealInterval = other?.let { Intervals.union(this, other) } ?: this
infix fun RealInterval.intersect(other: RealInterval?): RealInterval = other?.let { Intervals.intersect(this, other) }
    ?: this

internal fun RealInterval.shape() = maxAsDoubleArray().zip(minAsDoubleArray()).map { (max, min) -> max - min + 1 }.toDoubleArray()
fun <T> RealRandomAccessible<T>.raster(): RandomAccessibleOnRealRandomAccessible<T> = Views.raster(this)
fun <T> RandomAccessible<T>.interval(interval: Interval): IntervalView<T> = Views.interval(this, interval)
fun <T> RandomAccessible<T>.interval(interval: RealInterval): IntervalView<T> = Views.interval(this, interval.smallestContainingInterval)
fun <T> RealRandomAccessible<T>.realInterval(interval: RealInterval): RealRandomAccessibleRealInterval<T> = FinalRealRandomAccessibleRealInterval(this, interval)
operator fun <T> RealRandomAccessible<T>.get(vararg pos: Double): T = getAt(*pos)
operator fun <T> RealRandomAccessible<T>.get(vararg pos: Float): T = getAt(*pos)
operator fun <T> RealRandomAccessible<T>.get(pos: RealLocalizable): T = getAt(pos)
fun <T, F : RandomAccessible<T>> F.interpolate(interpolatorFactory: InterpolatorFactory<T, F>): RealRandomAccessible<T> = Views.interpolate(this, interpolatorFactory)
fun <T> RandomAccessible<T>.interpolateNearestNeighbor(): RealRandomAccessible<T> = interpolate(NearestNeighborInterpolatorFactory())
fun <T> RandomAccessibleInterval<T>.interpolateNearestNeighbor(): RealRandomAccessibleRealInterval<T> = interpolate(NearestNeighborInterpolatorFactory()).realInterval(this)
fun <T> RandomAccessibleInterval<T>.forEach(loop: (T) -> Unit) = Views.iterable(this).forEach(loop)
operator fun <T> RandomAccessible<T>.get(vararg pos: Long): T = getAt(*pos)
operator fun <T> RandomAccessible<T>.get(vararg pos: Int): T = getAt(*pos)
operator fun <T> RandomAccessible<T>.get(pos: Localizable): T = getAt(pos)
fun <T, F : RandomAccessibleInterval<T>> F.extendValue(extension: T) = Views.extendValue(this, extension)!!
fun <T : RealType<T>, F : RandomAccessibleInterval<T>> F.extendValue(extension: Float) = Views.extendValue(this, extension)!!
fun <T : RealType<T>, F : RandomAccessibleInterval<T>> F.extendValue(extension: Double) = Views.extendValue(this, extension)!!
fun <T : IntegerType<T>, F : RandomAccessibleInterval<T>> F.extendValue(extension: Int) = Views.extendValue(this, extension)!!
fun <T : IntegerType<T>, F : RandomAccessibleInterval<T>> F.extendValue(extension: Long) = Views.extendValue(this, extension)!!
fun <T : BooleanType<T>, F : RandomAccessibleInterval<T>> F.extendValue(extension: Boolean) = Views.extendValue(this, extension)!!
fun <T : NumericType<T>, F : RandomAccessibleInterval<T>> F.extendZero() = Views.extendZero(this)!!
fun <T, F : RandomAccessibleInterval<T>> F.expandborder(vararg border: Long) = Views.expandBorder(this, *border)!!

fun <T> RandomAccessible<T>.hyperSlice(dimension: Int = this.numDimensions() - 1, position: Long = 0) = Views.hyperSlice(this, dimension, position)!!
fun <T> RandomAccessibleInterval<T>.hyperSlice(dimension: Int = this.numDimensions() - 1, position: Long = 0) = Views.hyperSlice(this, dimension, position)!!
fun <T> RandomAccessibleInterval<T>.zeroMin() = Views.zeroMin(this)!!
fun <T> RandomAccessible<T>.translate(vararg translation: Long) = Views.translate(this, *translation)!!
fun <T> RandomAccessibleInterval<T>.translate(vararg translation: Long) = Views.translate(this, *translation)!!
fun <T> RealRandomAccessible<T>.affineReal(affine: AffineGet) = RealViews.affineReal(this, affine)!!
fun <T> RealRandomAccessible<T>.affine(affine: AffineGet) = RealViews.affine(this, affine)!!

fun <T, R : Type<R>> RandomAccessible<T>.convert(type: R, converter: (T, R) -> Unit) : RandomAccessible<R> {
    return Converters.convert(this, converter, type )
}
fun <A, B, C : Type<C>> RandomAccessible<A>.convertWith(other: RandomAccessible<B>, type: C, converter: (A, B, C) -> Unit) : RandomAccessible<C> {
    return Converters.convert(this, other, converter, type )
}
fun <T, R : Type<R>> RealRandomAccessible<T>.convert(type: R, converter: (T, R) -> Unit) : RealRandomAccessible<R> {
    return ConvertedRealRandomAccessible(this, converter) { type.copy() }
}
fun <A, B, C : Type<C>> RealRandomAccessible<A>.convertWith(other : RealRandomAccessible<B>, type: C, converter: (A, B, C) -> Unit) : RealRandomAccessible<C> {
    return Converters.convert(this, other, converter, type)
}

/* RealPoint Extensions */

fun RealPoint.floor(): Point {
    val pointVals = LongArray(this.numDimensions())
    for (i in 0 until this.numDimensions()) {
        pointVals[i] = floor(getDoublePosition(i)).toLong()
    }
    return Point(*pointVals)
}

fun RealPoint.ceil(): Point {
    val pointVals = LongArray(this.numDimensions())
    for (i in 0 until this.numDimensions()) {
        pointVals[i] = kotlin.math.ceil(getDoublePosition(i)).toLong()
    }
    return Point(*pointVals)
}

fun RealPoint.round(): Point {
    val pointVals = LongArray(this.numDimensions())
    for (i in 0 until this.numDimensions()) {
        pointVals[i] = getDoublePosition(i).roundToLong()

    }
    return Point(*pointVals)
}

fun RealPoint.toPoint(): Point {
    val pointVals = LongArray(this.numDimensions())
    for (i in 0 until this.numDimensions()) {
        pointVals[i] = getDoublePosition(i).toLong()
    }
    return Point(*pointVals)
}

inline fun <reified T> RealPoint.get(i: Int): T {
    return when (T::class) {
        Double::class -> getDoublePosition(i)
        Float::class -> getFloatPosition(i)
        else -> null
    } as T
}

inline operator fun <reified T> Point.get(i: Int): T {
    return when (T::class) {
        Int::class -> getIntPosition(i)
        Long::class -> getLongPosition(i)
        Float::class -> getFloatPosition(i)
        Double::class -> getDoublePosition(i)
        else -> null
    } as T
}

inline operator fun <reified T> RealPoint.component1() = get<T>(0)
inline operator fun <reified T> RealPoint.component2() = get<T>(1)
inline operator fun <reified T> RealPoint.component3() = get<T>(2)
inline operator fun <reified T> RealPoint.component4() = get<T>(3)
inline operator fun <reified T> RealPoint.component5() = get<T>(4)
inline operator fun <reified T> Point.component1() = this.get<T>(0)
inline operator fun <reified T> Point.component2() = this.get<T>(1)
inline operator fun <reified T> Point.component3() = this.get<T>(2)
inline operator fun <reified T> Point.component4() = this.get<T>(3)
inline operator fun <reified T> Point.component5() = this.get<T>(4)
