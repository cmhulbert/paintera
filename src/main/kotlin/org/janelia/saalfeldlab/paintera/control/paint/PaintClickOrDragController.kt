package org.janelia.saalfeldlab.paintera.control.paint

import bdv.fx.viewer.ViewerPanelFX
import javafx.scene.input.MouseEvent
import net.imglib2.FinalInterval
import net.imglib2.Interval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.label.Label
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.util.Intervals
import net.imglib2.util.LinAlgHelpers
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.ui.Exceptions.Companion.exceptionAlert
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.Constants
import org.janelia.saalfeldlab.paintera.PainteraBaseView
import org.janelia.saalfeldlab.paintera.data.mask.Mask
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.exception.PainteraException
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.extendAndTransformBoundingBox
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.function.Predicate

infix fun Interval.union(other: Interval): Interval = Intervals.union(this, other)

operator fun Interval.plus(other: Interval?): Interval {
    return other?.let {
        Intervals.union(this, other)
    } ?: FinalInterval(this)
}

private data class Postion(var x: Double = 0.0, var y: Double = 0.0) {

    constructor(mouseEvent: MouseEvent) : this(mouseEvent.x, mouseEvent.y)

    fun update(x: Double, y: Double) {
        this.x = x
        this.y = y
    }

    fun update(mouseEvent: MouseEvent) {
        mouseEvent.apply { update(x, y) }
    }

    private fun toDoubleArray() = doubleArrayOf(x, y)

    override fun toString() = "<Position: ($x, $y)>"

    infix fun linalgSubtract(other: Postion): DoubleArray {
        val thisDoubleArray = toDoubleArray()
        LinAlgHelpers.subtract(thisDoubleArray, other.toDoubleArray(), thisDoubleArray)
        return thisDoubleArray
    }

    operator fun plusAssign(normalizedDragPos: DoubleArray) {
        val xy = this.toDoubleArray() inPlaceAdd normalizedDragPos
        this.x = xy[0]
        this.y = xy[1]
    }

    operator fun minusAssign(normalizedDragPos: DoubleArray) {
        val xy = this.toDoubleArray() inPlaceSubtract normalizedDragPos
        this.x = xy[0]
        this.y = xy[1]
    }

    private infix fun DoubleArray.inPlaceSubtract(other: DoubleArray): DoubleArray {
        LinAlgHelpers.subtract(this, other, this)
        return this
    }

    private infix fun DoubleArray.inPlaceAdd(other: DoubleArray): DoubleArray {
        LinAlgHelpers.add(this, other, this)
        return this
    }
}


class PaintClickOrDragController(
    private val paintera: PainteraBaseView,
    private val viewer: ViewerPanelFX,
    private val paintId: () -> Long?,
    private val brushRadius: () -> Double,
    private val brushDepth: () -> Double
) {

    fun submitPaint() {
        synchronized(this) {
            when {
                !isPainting -> LOG.debug("Not currently painting -- will not do anything")
                paintIntoThis == null -> LOG.debug("No current source available -- will not do anything")
                else -> try {
                    paintIntoThis!!.applyMask(mask, interval, FOREGROUND_CHECK)
                } catch (e: Exception) {
                    InvokeOnJavaFXApplicationThread { exceptionAlert(Constants.NAME, "Exception when trying to submit mask.", e).show() }
                } finally {
                    release()
                }
            }
        }
    }

    class IllegalIdForPainting(val id: Long?) : PainteraException("Cannot paint this id: $id")

    @get:Synchronized
    private var isPainting = false

    private var mask: Mask<UnsignedLongType>? = null

    private var paintIntoThis: MaskedSource<*, *>? = null
    private var fillLabel: Long = 0
    private var interval: Interval? = null
    private val labelToGlobalTransform = AffineTransform3D()
    private val labelToViewerTransform = AffineTransform3D()
    private val globalToViewerTransform = AffineTransform3D()
    private val position = Postion()

    fun startPaint(event: MouseEvent) {
        LOG.debug("Starting New Paint", event)
        if (isPainting) {
            LOG.debug("Already painting -- will not start new paint.")
            return
        }
        synchronized(this) {
            try {
                (paintera.sourceInfo().currentSourceProperty().get() as? MaskedSource<*, *>)?.let { currentSource ->
                    val screenScaleTransform = AffineTransform3D().also {
                        viewer.renderUnit.getScreenScaleTransform(0, it)
                    }
                    val viewerTransform = AffineTransform3D()
                    val state = viewer.state
                    val level = synchronized(state) {
                        state.getViewerTransform(viewerTransform)
                        state.getBestMipMapLevel(screenScaleTransform, currentSource)
                    }
                    currentSource.getSourceTransform(0, level, labelToGlobalTransform)
                    labelToViewerTransform.set(viewerTransform.copy().concatenate(labelToGlobalTransform))
                    globalToViewerTransform.set(viewerTransform)
                    val id = paintId() ?: throw IllegalIdForPainting(null)
                    mask = currentSource.generateMask(MaskInfo(0, level, UnsignedLongType(id)), FOREGROUND_CHECK)
                    isPainting = true
                    fillLabel = 1
                    interval = null
                    paintIntoThis = currentSource
                    position.update(event)
                    paint(position)
                }
            } catch (e: Exception) {
                // Ensure we never enter a painting state when an exception occurs
                release()
                InvokeOnJavaFXApplicationThread { exceptionAlert(Constants.NAME, "Unable to paint.", e).show() }
            }
        }
    }

    fun extendPaint(event: MouseEvent) {
        if (!isPainting) {
            LOG.debug("Not currently painting -- will not paint")
            return
        }
        synchronized(this) {
            try {
                val targetPosition = Postion(event)
                if (targetPosition != position) {
                    LOG.debug("Drag: paint at screen from $position to $targetPosition")
                    var draggedDistance = 0.0
                    val normalizedDragPos = (targetPosition linalgSubtract position).also {
                        //NOTE: Calculate distance before noramlizing
                        draggedDistance = LinAlgHelpers.length(it)
                        LinAlgHelpers.normalize(it)
                    }
                    LOG.debug("Number of paintings triggered {}", draggedDistance + 1)
                    repeat(draggedDistance.toInt() + 1) {
                        paint(position)
                        position += normalizedDragPos
                    }
                    LOG.debug("Painting ${draggedDistance + 1} times with radius ${brushRadius()} took a total of {}ms")
                }
            } finally {
                position.update(event)
            }
        }
    }


    @get:Synchronized
    private val maskIfIsPaintingOrNull: RandomAccessibleInterval<UnsignedLongType>?
        get() = if (isPainting && mask != null) mask!!.mask else null

    @Synchronized
    private fun paint(pos: Postion) = with(pos) { paint(x, y) }

    @Synchronized
    private fun paint(viewerX: Double, viewerY: Double) {
        LOG.trace("At {} {}", viewerX, viewerY)
        when {
            !isPainting -> LOG.debug("Not currently activated for painting, returning without action").also { return }
            maskIfIsPaintingOrNull == null -> LOG.debug("Current mask is null, returning without action").also { return }
        }

        val mask = maskIfIsPaintingOrNull!!
        val orthoAxis = when (viewer) {
            paintera.orthogonalViews().topLeft.viewer() -> 2
            paintera.orthogonalViews().topRight.viewer() -> 0
            else -> 1
        }
        val trackedInterval = Paint2D.paint(
            Views.extendValue(mask, UnsignedLongType(Label.INVALID)),
            fillLabel,
            orthoAxis,
            viewerX,
            viewerY,
            brushRadius(),
            brushDepth(),
            labelToViewerTransform,
            globalToViewerTransform,
            labelToGlobalTransform
        )
        interval = trackedInterval + interval
        ++fillLabel
        val trackedIntervalInGlobalSpace = extendAndTransformBoundingBox(trackedInterval, labelToGlobalTransform, 0.5)
        paintera.orthogonalViews().requestRepaint(trackedIntervalInGlobalSpace)
    }

    private fun release() {
        mask = null
        isPainting = false
        interval = null
        paintIntoThis = null
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
        private val FOREGROUND_CHECK = Predicate { t: UnsignedLongType -> Label.isForeground(t.get()) }
    }
}
