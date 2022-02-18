package org.janelia.saalfeldlab.paintera.control.paint

import bdv.fx.viewer.ViewerPanelFX
import bdv.util.Affine3DHelpers
import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleBooleanProperty
import net.imglib2.*
import net.imglib2.cache.Invalidate
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.realtransform.RealViews
import net.imglib2.realtransform.Scale3D
import net.imglib2.type.label.Label
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.volatiles.VolatileUnsignedLongType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.data.mask.SourceMask
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.raster

class ViewerMask private constructor(
    val source: MaskedSource<*, *>,
    val viewer: ViewerPanelFX,
    info: MaskInfo,
    invalidate: Invalidate<*>? = null,
    invalidateVolatile: Invalidate<*>? = null,
    shutdown: Runnable? = null,
    inline val isPaintedForeground: (UnsignedLongType) -> Boolean = { Label.isForeground(it.get()) },
    val paintDepth: Double,
) : SourceMask() {


    val globalToViewerTransform = AffineTransform3D().also { viewer.state.getViewerTransform(it) }
    val labelToGlobalTransform = source.getSourceTransformForMask(info)!!
    val labelToViewerTransform = AffineTransform3D().apply {
        set(globalToViewerTransform.copy().concatenate(labelToGlobalTransform))
    }

    private val depthScale = Affine3DHelpers.extractScale(labelToViewerTransform, 2) // Z paintera.activeOrthoAxis)
    private val paintToViewerScaleTransform = Scale3D(1.0, 1.0, paintDepth *  depthScale)

    private val labelIntervalInSource = source.getDataSource(info.time, info.level)
    private val labelIntervalInInitialViewer = labelToViewerTransform.estimateBounds(labelIntervalInSource)

    val initialLabelToViewerTransform: AffineTransform3D = labelToViewerTransform.copy()

    val viewerImg: RealRandomAccessibleRealInterval<UnsignedLongType>
    val volatileViewerImg: RealRandomAccessibleRealInterval<VolatileUnsignedLongType>
    var viewerImgInSource: RealRandomAccessible<UnsignedLongType>
    var volatileViewerImgInSource: RealRandomAccessible<VolatileUnsignedLongType>
    val viewerRai: RandomAccessibleInterval<UnsignedLongType>

    private val setMaskOnUpdateProperty = SimpleBooleanProperty(false)
    var setMaskOnUpdate by setMaskOnUpdateProperty.nonnull()

    val initialToCurrentViewerTransform = AffineTransform3D()

    val viewerTransformListener: InvalidationListener = InvalidationListener {

        viewer.state.getViewerTransform(globalToViewerTransform)
        labelToViewerTransform.set(globalToViewerTransform.copy().concatenate(labelToGlobalTransform))
        /* Set the transform to convert from initial to current */
        initialToCurrentViewerTransform.set(getInitialToTargetTransform(labelToViewerTransform))

        updateTranslation()
//        paintera.baseView.orthogonalViews().requestRepaint()
        if (setMaskOnUpdate) {
            source.resetMasks(false)
            setViewerMaskOnSource()
        }
    }


    init {
        this.info = info
        this.invalidate = invalidate
        this.invalidateVolatile = invalidateVolatile
        this.shutdown = shutdown
        this.initialLabelToViewerTransform.set(labelToViewerTransform)


        val (sourceAlignedViewerImg, sourceAlignedVolatileViewerImg) = createBackingImages()
        this.viewerImg = sourceAlignedViewerImg
        this.volatileViewerImg = sourceAlignedVolatileViewerImg

        val (sourceImg, volatileSourceImg) = getSourceImages(sourceAlignedViewerImg, sourceAlignedVolatileViewerImg)
        this.viewerImgInSource = sourceImg
        this.volatileViewerImgInSource = volatileSourceImg


        this.raiOverSource = Views.interval(Views.raster(viewerImgInSource), source.getSource(0, info.level))
        this.volatileRaiOverSource = Views.interval(Views.raster(volatileViewerImg), source.getSource(0, info.level))

        this.viewerRai = viewerImg.raster().interval(viewerImg.smallestContainingInterval)
    }

    fun disable() {
        viewer.state.removeListener(viewerTransformListener)
    }

    fun enable() {
        viewer.state.addListener(viewerTransformListener)
    }

    fun getInitialToTargetTransform(targetLabelToViewerTransform: AffineTransform3D): AffineTransform3D {
        return AffineTransform3D().apply {
            /* Set the transform to convert from initial to current */
            set(targetLabelToViewerTransform.copy().concatenate(initialLabelToViewerTransform.inverse()))
        }
    }

    fun updateTranslation() {
        val (sourceImg, volatileSourceImg) = getSourceImages(viewerImg, volatileViewerImg)

        this.viewerImgInSource = sourceImg
        this.volatileViewerImgInSource = volatileSourceImg

        this.raiOverSource = Views.interval(Views.raster(viewerImgInSource), source.getSource(0, info.level))
        this.volatileRaiOverSource = Views.interval(Views.raster(volatileViewerImg), source.getSource(0, info.level))
    }

    fun setViewerMaskOnSource() {
        source.setMask(info, viewerImgInSource, volatileViewerImgInSource, invalidate, invalidateVolatile, shutdown, isPaintedForeground)
    }

    fun setSourceMaskOnSource() {
        source.setMask(this, isPaintedForeground)
    }

    private fun createBackingImages(): Pair<RealRandomAccessibleRealInterval<UnsignedLongType>, RealRandomAccessibleRealInterval<VolatileUnsignedLongType>> {

        val viewerInterval = Intervals.smallestContainingInterval(labelToViewerTransform.estimateBounds(labelIntervalInSource))

        val width = viewerInterval.dimension(0)
        val height = viewerInterval.dimension(1)

        val imgDims = intArrayOf(width.toInt(), height.toInt(), 1)
        val dataToVolatilePair = source.createMaskStoreWithVolatile(CELL_DIMS, imgDims)!!

        return offsetImagesToLabelBounds(dataToVolatilePair.key, dataToVolatilePair.value.rai)
    }

    private fun offsetImagesToLabelBounds(
        viewerCellImg: RandomAccessibleInterval<UnsignedLongType>,
        volatileViewerCellImg: RandomAccessibleInterval<VolatileUnsignedLongType>
    ): Pair<RealRandomAccessibleRealInterval<UnsignedLongType>, RealRandomAccessibleRealInterval<VolatileUnsignedLongType>> {

        val labelBoundsOffset = AffineTransform3D().apply {
            translate(
                labelIntervalInInitialViewer.realMin(0),
                labelIntervalInInitialViewer.realMin(1),
                0.0
            )
        }

        val offsetMin = viewerCellImg.minAsLongArray()
            .mapIndexed() { idx, minVal -> minVal.toDouble() + labelBoundsOffset.get(idx, 3) }
            .toDoubleArray()
        val offsetMax = viewerCellImg.maxAsLongArray()
            .mapIndexed() { idx, maxVal -> maxVal.toDouble() + labelBoundsOffset.get(idx, 3) }
            .toDoubleArray()

        val offsetRealInterval = FinalRealInterval(offsetMin, offsetMax)


//        val volatileWithBorder = Views.expandValue(Views.expandValue(volatileViewerCellImg, VolatileUnsignedLongType(Label.INVALID), -30, -30, 0), VolatileUnsignedLongType(781257), 30, 30, 0)

        val realOffsetViewerImg = RealViews.affine(Views.interpolate(Views.extendValue(viewerCellImg, Label.INVALID), NearestNeighborInterpolatorFactory()), labelBoundsOffset)
        val manualTranslateRealInverseImg = FinalRealRandomAccessibleRealInterval(
            realOffsetViewerImg,
            offsetRealInterval
        )

        val realOffsetVolatileViewerImg = RealViews.affine(Views.interpolate(Views.extendValue(volatileViewerCellImg, VolatileUnsignedLongType(Label.INVALID)), NearestNeighborInterpolatorFactory()), labelBoundsOffset)
//        val realOffsetVolatileViewerImg = RealViews.affine(Views.interpolate(Views.extendValue(volatileWithBorder, VolatileUnsignedLongType(Label.INVALID)), NearestNeighborInterpolatorFactory()), labelBoundsOffset)
        val manualTranslateRealInverseVolatileImg = FinalRealRandomAccessibleRealInterval(
            realOffsetVolatileViewerImg,
            offsetRealInterval
        )

        return manualTranslateRealInverseImg to manualTranslateRealInverseVolatileImg
    }

    private fun getSourceImages(viewerCellImg: RealRandomAccessibleRealInterval<UnsignedLongType>, volatileViewerCellImg: RealRandomAccessibleRealInterval<VolatileUnsignedLongType>):
        Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>> {

        val depthScaledViewerImg = RealViews.affineReal(viewerCellImg, paintToViewerScaleTransform)
        val depthScaledVolatileViewerImg = RealViews.affineReal(volatileViewerCellImg, paintToViewerScaleTransform)

        val sourceImg = RealViews.affineReal(depthScaledViewerImg, initialLabelToViewerTransform.inverse())
        val volatileSourceImg = RealViews.affineReal(depthScaledVolatileViewerImg, initialLabelToViewerTransform.inverse())

        return sourceImg to volatileSourceImg
    }

    companion object {
        private val CELL_DIMS = intArrayOf(64, 64, 1)

        @JvmStatic
        fun MaskedSource<*, *>.setNewViewerMask(maskInfo: MaskInfo, viewer: ViewerPanelFX, brushDepth: Double): ViewerMask {
            val viewerMask = ViewerMask(this, viewer, maskInfo, paintDepth = brushDepth)
            viewerMask.setViewerMaskOnSource()
            viewerMask.enable()
            return viewerMask
        }
    }
}
