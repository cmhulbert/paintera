package org.janelia.saalfeldlab.util

import net.imglib2.Interval
import net.imglib2.RandomAccessible
import net.imglib2.RealInterval
import net.imglib2.RealRandomAccessible
import net.imglib2.util.Intervals
import net.imglib2.view.IntervalView
import net.imglib2.view.RandomAccessibleOnRealRandomAccessible
import net.imglib2.view.Views
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval

infix fun Interval.union(other: Interval?): Interval = other?.let { Intervals.union(this, other) } ?: this

infix fun RealInterval.union(other: RealInterval?): RealInterval = other?.let { Intervals.union(this, other) } ?: this

fun RealInterval.shape() = maxAsDoubleArray().zip(minAsDoubleArray()).map { (max, min) -> max - min }.toDoubleArray()

fun <T> RealRandomAccessible<T>.raster(): RandomAccessibleOnRealRandomAccessible<T> = Views.raster(this)

fun <T> RandomAccessible<T>.interval(interval: Interval): IntervalView<T> = Views.interval(this, interval)

fun <T> RealRandomAccessible<T>.interval(interval: RealInterval): IntervalView<T> = this.raster().interval(interval.smallestContainingInterval)
