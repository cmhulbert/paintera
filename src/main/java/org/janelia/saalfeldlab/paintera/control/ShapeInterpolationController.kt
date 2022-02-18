package org.janelia.saalfeldlab.paintera.control

import bdv.fx.viewer.ViewerPanelFX
import bdv.util.Affine3DHelpers
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleLongProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.concurrent.Task
import javafx.concurrent.Worker
import javafx.scene.paint.Color
import javafx.util.Duration
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.imglib2.*
import net.imglib2.algorithm.morphology.distance.DistanceTransform
import net.imglib2.algorithm.morphology.distance.DistanceTransform.DISTANCE_TYPE
import net.imglib2.converter.Converters
import net.imglib2.converter.RealRandomArrayAccessible
import net.imglib2.converter.logical.Logical
import net.imglib2.converter.read.BiConvertedRealRandomAccessible
import net.imglib2.img.array.ArrayImg
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory
import net.imglib2.loops.LoopBuilder
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.realtransform.RealViews
import net.imglib2.realtransform.Scale3D
import net.imglib2.realtransform.Translation3D
import net.imglib2.type.BooleanType
import net.imglib2.type.NativeType
import net.imglib2.type.label.Label
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.type.volatiles.VolatileUnsignedLongType
import net.imglib2.ui.TransformListener
import net.imglib2.util.*
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.Tasks.Companion.createTask
import org.janelia.saalfeldlab.fx.extensions.*
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.Paintera.Companion.getPaintera
import org.janelia.saalfeldlab.paintera.PainteraBaseView
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment
import org.janelia.saalfeldlab.paintera.control.paint.FloodFill2D
import org.janelia.saalfeldlab.paintera.control.paint.PaintClickOrDragController
import org.janelia.saalfeldlab.paintera.control.paint.PaintUtils
import org.janelia.saalfeldlab.paintera.control.paint.ViewerMask
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds
import org.janelia.saalfeldlab.paintera.data.DataSource
import org.janelia.saalfeldlab.paintera.data.PredicateDataSource.PredicateConverter
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.data.mask.exception.MaskInUse
import org.janelia.saalfeldlab.paintera.id.IdService
import org.janelia.saalfeldlab.paintera.stream.AbstractHighlightingARGBStream
import org.janelia.saalfeldlab.paintera.stream.HighlightingStreamConverter
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.extendAndTransformBoundingBox
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import org.janelia.saalfeldlab.util.Colors
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.raster
import org.janelia.saalfeldlab.util.union
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Arrays
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.math.roundToLong

class ShapeInterpolationController<D : IntegerType<D>?>(
    val source: MaskedSource<D, *>,
    private val refreshMeshes: Runnable,
    val selectedIds: SelectedIds,
    val idService: IdService,
    val converter: HighlightingStreamConverter<*>,
    private val assignment: FragmentSegmentAssignment
) {
    enum class ModeState {
        Select, Interpolate, Preview, Off
    }

    val isBusyProperty = SimpleBooleanProperty(false)
    var isBusy by isBusyProperty.nonnull()

    var activeSelectionAlpha = (AbstractHighlightingARGBStream.DEFAULT_ACTIVE_FRAGMENT_ALPHA ushr 24) / 255.0
    private var activeViewer: ViewerPanelFX? = null
    var lastSelectedId: Long = 0
    var selectionId: Long = 0
    private val doneApplyingMaskListener = ChangeListener<Boolean> { _, _, newv -> if (!newv!!) InvokeOnJavaFXApplicationThread { doneApplyingMask() } }
    internal val currentViewerMaskProperty: ObjectProperty<ViewerMask?> = SimpleObjectProperty(null)
    internal var currentViewerMask by currentViewerMaskProperty.nullable()
    private var requestRepaintInterval: RealInterval? = null

    private fun removeSection(depth: Double) {
        sectionsAndInterpolants.removeSectionAtDepth(depth)
    }

    fun paint(sourcePaintInterval: Interval, viewerPaintInterval: Interval?) {
        isBusy = true
        addSelection(sourcePaintInterval, viewerIntervalOverSelection = viewerPaintInterval)
        requestRepaintForInterpolatedMask()
    }

    fun deleteCurrentSection() {
        sectionsAndInterpolants.removeSectionAtDepth(currentDepth)?.let { section ->
            isBusy = true
            interpolateBetweenSections(true)
            var repaintInterval = section.globalBoundingBox
            listOf(sectionsAndInterpolants.getPreviousSection(currentDepth), sectionsAndInterpolants.getNextSection(currentDepth)).forEach {
                repaintInterval = repaintInterval union it?.globalBoundingBox
            }
            requestRepaintForInterpolatedMask(unionWith = repaintInterval)
        }
    }

    infix fun RealPoint.subtract(other: RealPoint): RealPoint {
        val res = RealPoint(this.numDimensions())
        val resArray = DoubleArray(this.positionAsDoubleArray().size)
        LinAlgHelpers.subtract(this.positionAsDoubleArray(), other.positionAsDoubleArray(), resArray)
        res.setPosition(resArray)
        return res
    }

    @JvmOverloads
    fun addSelection(sourceIntervalOverSelection: Interval, keepInterpolation: Boolean = true, z: Double = currentDepth, viewerIntervalOverSelection: Interval? = null) {
        val globalTransform = paintera().manager().transform

        if (!keepInterpolation && sectionsAndInterpolants.getSectionAtDepth(z) != null) {
            removeSection(z)
            updateFillAndInterpolantsCompositeMask()
            val section = SectionInfo(
                currentViewerMask!!,
                globalTransform,
                viewerIntervalOverSelection!!
            )
            sectionsAndInterpolants.add(z, section)
        }
        if (sectionsAndInterpolants.getSectionAtDepth(z) == null) {
            val section = SectionInfo(currentViewerMask!!, globalTransform, viewerIntervalOverSelection!!)
            section.addSelection(viewerIntervalOverSelection!!)
            sectionsAndInterpolants.add(z, section)
        } else {
            sectionsAndInterpolants.getSectionAtDepth(z)!!.addSelection(viewerIntervalOverSelection!!)
            sectionsAndInterpolants.clearInterpolantsAroundSection(z)
        }
        interpolateBetweenSections(true)
    }

    private val maskLevel: Int
        get() = currentViewerMask?.info?.level ?: currentBestMipMapLevel

    var currentFillValuePropery = SimpleLongProperty(0)
    private val sectionsAndInterpolants = SectionsAndInterpolant()
    val sortedSectionDepths: List<Double>
        get() = runBlocking {
            sectionsAndInterpolants.asFlow()
                .filter { it.isSection }
                .map { it.sectionDepth }
                .toList()
        }

    var sectionDepthProperty: ObjectProperty<Double> = SimpleObjectProperty()
    var sectionDepth: Double by sectionDepthProperty.nonnull()

    val modeStateProperty: ObjectProperty<ModeState> = SimpleObjectProperty(ModeState.Off)
    var modeState: ModeState by modeStateProperty.nonnull()
    private var interpolator: Task<Unit>? = null
    private val requestRepaintAfterInterpolation = AtomicBoolean(false)
    private val onInterpolationFinished = {
        modeState = ModeState.Preview
        synchronized(requestRepaintAfterInterpolation) {
            if (requestRepaintAfterInterpolation.getAndSet(false)) {
                requestRepaintForInterpolatedMask(true)
            }
        }
    }
    private var compositeFillAndInterpolationImgs: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? = null

    fun numSections(): Int {
        return sectionsAndInterpolants.sections.size
    }

    private val sectionAtCurrentDepthBinding = sectionDepthProperty.createValueBinding(sectionsAndInterpolants) { sectionsAndInterpolants.getSectionAtDepth(it) }
    val sectionAtCurrentDepth by sectionAtCurrentDepthBinding.nullableVal()

    private val viewerTransformDepthUpdater = TransformListener<AffineTransform3D> {
        updateDepth()
    }

    fun enterMode(viewer: ViewerPanelFX?) {
        if (isModeOn) {
            LOG.trace("Already in shape interpolation mode")
            return
        }
        LOG.debug("Entering shape interpolation mode")
        activeViewer = viewer
        disableUnfocusedViewers()

        /* Store all the previous activated Ids*/
        lastSelectedId = assignment.getSegment(selectedIds.lastSelection)
        if (lastSelectedId == Label.INVALID) lastSelectedId = idService.next()
        selectNewInterpolationId()
        initialMaskDisplayTransform = maskDisplayTransformIgnoreScaling
        activeViewer!!.addTransformListener(viewerTransformDepthUpdater)
        updateDepth()
        modeState = ModeState.Select

        sectionAtCurrentDepthBinding.addListener { _, old, new ->
            old?.mask?.setMaskOnUpdate = false
            new?.mask?.setMaskOnUpdate = false
        }
    }

    fun updateDepth() {
        sectionDepth = currentDepth
    }

    private val currentDepth: Double by LazyForeignValue({ maskDisplayTransformIgnoreScaling.toString() }) {
        val initialSlice = DoubleArray(3)
        val currentSlice = DoubleArray(3)
        initialMaskDisplayTransform!!.apply(initialSlice, initialSlice)
        maskDisplayTransformIgnoreScaling.apply(currentSlice, currentSlice)
        val sliceDistance = initialSlice[2] - currentSlice[2]
        BigDecimal(sliceDistance).setScale(5, RoundingMode.HALF_EVEN).toDouble()
    }

    fun exitMode(completed: Boolean) {
        if (!isModeOn) {
            LOG.info("Not in shape interpolation mode")
            return
        }
        LOG.info("Exiting shape interpolation mode")
        enableAllViewers()

        // extra cleanup if the mode is aborted
        if (!completed) {
            interruptInterpolation()
            resetMask(true)
        }


        /* Reset the selection state */
        converter.removeColor(lastSelectedId)
        converter.removeColor(selectionId)
        selectedIds.deactivate(selectionId)
        selectedIds.activateAlso(lastSelectedId)
        modeState = ModeState.Off
        sectionsAndInterpolants.forEach {
            if (it.isSection) {
                it.getSection().mask.disable()
            }
        }
        sectionsAndInterpolants.clearInterpolants()
        sectionDepth = 0.0
        currentViewerMask = null
        interpolator = null
        compositeFillAndInterpolationImgs = null
        lastSelectedId = Label.INVALID
        selectionId = Label.INVALID

        activeViewer!!.removeTransformListener(viewerTransformDepthUpdater)
        activeViewer = null
    }

    val isModeOn: Boolean
        get() = modeState != ModeState.Off

    private val currentBestMipMapLevel: Int
        get() {
            val viewerState = activeViewer!!.state
            val screenScaleTransform = AffineTransform3D()
            activeViewer!!.renderUnit.getScreenScaleTransform(0, screenScaleTransform)
            return viewerState.getBestMipMapLevel(screenScaleTransform, source)
        }

    private fun resetMask(clearFillMask: Boolean) {
        try {
            source.resetMasks(clearFillMask)
        } catch (e: MaskInUse) {
            LOG.error("Mask is in use.", e)
        }
    }

    private fun disableUnfocusedViewers() {
        val orthoViews = paintera().orthogonalViews()
        orthoViews.views().stream().filter(Predicate.not { obj: ViewerPanelFX? -> activeViewer!! == obj }).forEach { viewer: ViewerPanelFX? -> orthoViews.disableView(viewer) }
    }

    private fun enableAllViewers() {
        val orthoViews = paintera().orthogonalViews()
        orthoViews.views().forEach(Consumer { viewer: ViewerPanelFX? -> orthoViews.enableView(viewer) })
    }

    fun interpolateAllSections() {
        if (!preview) {
            return
        }
        interpolateBetweenSections(false)
    }

    fun togglePreviewMode() {
        preview = !preview
        interpolateBetweenSections(true)
//        if (!preview) {
//            sectionsAndInterpolants.clearInterpolants()
//            updateFillAndInterpolantsCompositeMask()
//        } else {
//        }
        requestRepaintForInterpolatedMask(unionWith = sectionsAndInterpolants.sections.map { it.globalBoundingBox }.reduce(Intervals::union))
    }

    @Synchronized
    fun interpolateBetweenSections(onlyMissing: Boolean) {
        if (sectionsAndInterpolants.sections.size < 2) {
            updateFillAndInterpolantsCompositeMask()
            return
        }

        modeState = ModeState.Interpolate
        if (!onlyMissing) {
            sectionsAndInterpolants.removeAllInterpolants()
        }
        if (interpolator != null) {
            interpolator!!.cancel()
        }

        interpolator = createTask<Unit> { task ->
            synchronized(this) {
                var updateInterval: RealInterval? = null
                try {
                    for (idx in sectionsAndInterpolants.size - 1 downTo 1) {
                        if (task.isCancelled) {
                            return@createTask
                        }
                        val either1 = sectionsAndInterpolants[idx - 1]
                        if (either1.isSection) {
                            val either2 = sectionsAndInterpolants[idx]
                            if (either2.isSection) {
                                val section1 = either1.getSection()
                                val section2 = either2.getSection()
                                val interpolatedImgs = interpolateBetweenTwoSections(section1, section2)
                                sectionsAndInterpolants.add(idx, interpolatedImgs)
                                updateInterval = section1.globalBoundingBox union section2.globalBoundingBox union updateInterval
                            }
                        }
                    }
                    requestRepaintInterval = requestRepaintInterval?.let { it union updateInterval } ?: updateInterval
                    setInterpolatedMasks()
                } catch (e: MaskInUse) {
                    LOG.error("Label source already has an active mask")
                }
            }
        }
            .onCancelled { _, _ -> LOG.debug("Interpolation Cancelled") }
            .onSuccess { _, _ -> onInterpolationFinished() }
            .onEnd { interpolator = null }
            .submit()
    }

    val previousSectionDepthIdx: Int
        get() {
            val depths = sortedSectionDepths
            for (i in depths.indices) {
                if (depths[i] >= currentDepth) {
                    return (i - 1).coerceAtLeast(0)
                }
            }
            return depths.size - 1
        }
    val nextSectionDepthIdx: Int
        get() {
            val depths = sortedSectionDepths
            for (i in depths.indices) {
                if (depths[i] > currentDepth) {
                    return i
                }
            }
            return numSections() - 1
        }

    fun editSelection(idx: Int) {
        val sectionInfo = sectionsAndInterpolants[idx * 2].getSection()
        selectAndMoveToSection(sectionInfo)
    }

    fun selectAndMoveToSection(sectionInfo: SectionInfo) {
        InvokeOnJavaFXApplicationThread {
            paintera().manager().setTransform(sectionInfo.globalTransform, Duration(300.0)) {
                paintera().manager().transform = sectionInfo.globalTransform
                updateDepth()
                modeState = ModeState.Select
            }
        }
    }

    @JvmOverloads
    fun applyMask(exit: Boolean = true): Boolean {
        if (numSections() < 2) {
            return false
        }
        if (modeState == ModeState.Interpolate) {
            // wait until the interpolation is done
            interpolator!!.get()
        }
        assert(modeState == ModeState.Preview)
        val sourceToGlobalTransform = source.getSourceTransformForMask(currentViewerMask!!.info)
        val sectionsUnionSourceInterval = sectionsAndInterpolants.stream()
            .filter { obj: SectionOrInterpolant -> obj.isSection }
            .map { obj: SectionOrInterpolant -> obj.getSection() }
            .map { s: SectionInfo -> s.globalBoundingBox }
            .map { sourceToGlobalTransform.inverse().estimateBounds(it) }
            .reduce { intervalA, intervalB -> Intervals.union(intervalA, intervalB) }
            .map { it.smallestContainingInterval }
            .get()
        LOG.info("Applying interpolated mask using bounding box of size {}", Intervals.dimensionsAsLongArray(sectionsUnionSourceInterval))
        if (Label.regular(lastSelectedId)) {
            val maskInfoWithLastSelectedLabelId = MaskInfo(
                source.currentMask.info.time,
                source.currentMask.info.level,
                UnsignedLongType(lastSelectedId)
            )
            resetMask(false)
            try {
                val interpolatedMaskImgsA = Converters.convert(
                    compositeFillAndInterpolationImgs!!.a,
                    { input: UnsignedLongType, output: UnsignedLongType ->
                        val originalLabel = input.long
                        output.set(if (originalLabel == selectionId) lastSelectedId else input.get())
                    },
                    UnsignedLongType()
                )
                val interpolatedMaskImgsB = Converters.convert(
                    compositeFillAndInterpolationImgs!!.b,
                    { input: VolatileUnsignedLongType, out: VolatileUnsignedLongType ->
                        val isValid = input.isValid
                        out.isValid = isValid
                        if (isValid) {
                            val originalLabel = input.get().get()
                            out.get().set(if (originalLabel == selectionId) lastSelectedId else originalLabel)
                        }
                    },
                    VolatileUnsignedLongType()
                )
                source.setMask(
                    maskInfoWithLastSelectedLabelId,
                    Views.interpolate(interpolatedMaskImgsA.raster(), NearestNeighborInterpolatorFactory()),
                    Views.interpolate(interpolatedMaskImgsB.raster(), NearestNeighborInterpolatorFactory()),
                    null, null, null, FOREGROUND_CHECK
                )
            } catch (e: MaskInUse) {
                e.printStackTrace()
            }
        }
        source.isApplyingMaskProperty.addListener(doneApplyingMaskListener)
        source.applyMask(source.currentMask, sectionsUnionSourceInterval, FOREGROUND_CHECK)
        if (exit) {
            exitMode(true)
        }
        return true
    }

    private fun selectNewInterpolationId() {
        /* Grab the color of the previously active ID. We will make our selection ID color slightly different, to indicate selection. */
        val packedLastARGB = converter.stream.argb(lastSelectedId)
        val originalColor = Colors.toColor(packedLastARGB)
        val fillLabelColor = Color(originalColor.red, originalColor.green, originalColor.blue, activeSelectionAlpha)
        selectionId = idService.nextTemporary()
        converter.setColor(selectionId, fillLabelColor, true)
        selectedIds.activateAlso(selectionId, lastSelectedId)
    }

    private fun doneApplyingMask() {
        source.isApplyingMaskProperty.removeListener(doneApplyingMaskListener)
        // generate mesh for the interpolated shape
        refreshMeshes.run()
    }

    @Throws(MaskInUse::class)
    private fun setInterpolatedMasks() {
        synchronized(source) {
            resetMask(false)
            /* If preview is on, hide all except the first and last fill mask */
            val fillMasks: MutableList<RealRandomAccessible<UnsignedLongType>> = mutableListOf()
            val volatileFillMasks: MutableList<RealRandomAccessible<VolatileUnsignedLongType>> = mutableListOf()
            val sections = sectionsAndInterpolants.sections
            sections.forEachIndexed { idx, section ->
                if ((idx == 0 || idx == sections.size - 1) || !preview) {
                    fillMasks += section.mask.viewerImgInSource
                    volatileFillMasks += section.mask.volatileViewerImgInSource
                }
            }
            val compositeFillMask = RealRandomArrayAccessible(fillMasks, { sources: List<UnsignedLongType>, output: UnsignedLongType ->
                var result: Long = 0
                for (input in sources) {
                    val iVal = input.get()
                    if (iVal > 0 || iVal == Label.TRANSPARENT) {
                        result = iVal
                        break;
                    }
                }
                if (output.get() != result) {
                    output.set(result)
                }
            }, UnsignedLongType())


            val volatileCompositeFillMask = RealRandomArrayAccessible(volatileFillMasks, { sources: List<VolatileUnsignedLongType>, output: VolatileUnsignedLongType ->
                var result: Long = 0
                var valid = true
                val outType = output.get()
                if (outType.get() != 0L) {
                    val t = 0
                }
                for (input in sources) {
                    val iVal = input.get().get()
                    if (iVal > 0 || iVal == Label.TRANSPARENT) {
                        result = iVal
                        valid = valid and input.isValid
                        break;
                    }
                }
                if (outType.get() != result) {
                    output.set(result)
                }
                output.isValid = valid
            }, VolatileUnsignedLongType())

            val interpolants = sectionsAndInterpolants.interpolants
            val dataMasks: MutableList<RealRandomAccessible<UnsignedLongType>> = mutableListOf()
            val volatileMasks: MutableList<RealRandomAccessible<VolatileUnsignedLongType>> = mutableListOf()
            if (preview) {
                interpolants.forEach {
                    dataMasks += it.a
                    volatileMasks += it.b
                }
            }


            val interpolatedArrayMask = RealRandomArrayAccessible(dataMasks, { sources: List<UnsignedLongType>, output: UnsignedLongType ->
                var acc: Long = 0
                for (input in sources) {
                    acc += input.get()
                }
                if (output.get() != acc) {
                    output.set(acc)
                }
            }, UnsignedLongType())
            val volatileInterpolatedArrayMask = RealRandomArrayAccessible(volatileMasks, { sources: List<VolatileUnsignedLongType>, output: VolatileUnsignedLongType ->
                var acc: Long = 0
                var valid = true
                val outType = output.get()
                if (outType.get() != 0L) {
                    val t = 0
                }
                for (input in sources) {
                    acc += input.get().get()
                    valid = valid and input.isValid
                }
                if (outType.get() != acc) {
                    output.set(acc)
                }
                output.isValid = valid
            }, VolatileUnsignedLongType())
            val compositeMask = BiConvertedRealRandomAccessible(compositeFillMask, interpolatedArrayMask, { a: UnsignedLongType, b: UnsignedLongType, c: UnsignedLongType ->
                val aVal = a.get()
                if (aVal > 0 || aVal == Label.TRANSPARENT) {
                    c.set(a)
                } else {
                    c.set(b)
                }
            }, UnsignedLongType())
            val compositeVolatileMask = BiConvertedRealRandomAccessible(volatileCompositeFillMask, volatileInterpolatedArrayMask, { a: VolatileUnsignedLongType, b: VolatileUnsignedLongType, c: VolatileUnsignedLongType ->
                val aVal = a.get().get()
                if (a.isValid && (aVal > 0 || aVal == Label.TRANSPARENT)) {
                    c.set(a)
                    c.isValid = true
                } else {
                    c.set(b)
                }
            }, VolatileUnsignedLongType())

            /* Set the interpolatedMaskImgs to the composite fill+interpolation RAIs*/
            compositeFillAndInterpolationImgs = ValuePair(compositeMask, compositeVolatileMask)
            val currentMask = currentViewerMask!!
            val currentInfo = currentMask.info
            currentMask.setMaskOnUpdate = false
            source.setMask(
                currentInfo,
                compositeMask,
                compositeVolatileMask,
                null,
                null,
                null,
                FOREGROUND_CHECK
            )
        }
    }

    private fun requestRepaintForInterpolatedMask(force: Boolean = false, unionWith: RealInterval? = null) {
        synchronized(requestRepaintAfterInterpolation) {
            if (!force && interpolator != null && (interpolator!!.state in listOf(Worker.State.READY, Worker.State.SCHEDULED, Worker.State.RUNNING))) {
                requestRepaintInterval = requestRepaintInterval?.let { it union unionWith } ?: unionWith
                requestRepaintAfterInterpolation.set(true)
                return
            }
        }
        requestRepaintInterval = requestRepaintInterval?.let { it union unionWith } ?: unionWith
        requestRepaintInterval?.let {
            val sourceToGlobal = source.getSourceTransformForMask(currentViewerMask!!.info)
            val extendedSourceInterval = extendAndTransformBoundingBox(it.smallestContainingInterval, sourceToGlobal.inverse(), 1.0)
            val extendedGlobalInterval = sourceToGlobal.estimateBounds(extendedSourceInterval).smallestContainingInterval
            paintera().orthogonalViews().requestRepaint(extendedGlobalInterval)
        }
        requestRepaintInterval = null
        isBusy = false
    }

    private fun requestRepaintForSourceInterval(repaintIntervalInSource: RealInterval) {
        try {
            val maxExtentInGlobal = source.getSourceTransformForMask(currentViewerMask!!.info).estimateBounds(repaintIntervalInSource)
            paintera().orthogonalViews().requestRepaint(maxExtentInGlobal)
        } catch (e: Exception) {
            LOG.error("Error requesting refresh over interval. Requesting complete refresh. ", e)
            paintera().orthogonalViews().requestRepaint()
        }
    }

    private fun interruptInterpolation() {
        if (interpolator != null) {
            if (interpolator!!.isRunning) {
                interpolator!!.cancel()
            }
            try {
                interpolator?.get()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            }
        }
    }

    fun selectObject(x: Double, y: Double, deactivateOthers: Boolean) {
        isBusy = true
//        returnOrCreateMask()
        val maskValue = getMaskValue(x, y)
        if (maskValue.get() == Label.OUTSIDE) return

        // ignore the background label
        val dataValue = getDataValue(x, y)
        if (!FOREGROUND_CHECK.test(UnsignedLongType(dataValue!!.integerLong))) return
        val wasSelected = FOREGROUND_CHECK.test(maskValue)
        LOG.debug("Object was clicked: deactivateOthers={}, wasSelected={}", deactivateOthers, wasSelected)
        var additionalRepaintInterval: RealInterval? = null
        if (deactivateOthers) {
            var numObjectsCleared = 0
            val depthInMaskDisplay = currentDepth
            if (sectionsAndInterpolants.getSectionAtDepth(depthInMaskDisplay) != null) {
                /* we are at a section, clear all selections */
                clearCurrentSection()?.apply {
                    numObjectsCleared = numObjects
                    additionalRepaintInterval = bounds
                }
            } else {
                /* we aren't in a section, so remove the interpolation if there is one. */
                sectionsAndInterpolants.removeIfInterpolantAt(depthInMaskDisplay)
                updateFillAndInterpolantsCompositeMask()
            }
            if (numObjectsCleared > 1 || !wasSelected) {
                val fillIdAndInterval = runFloodFillToSelect(x, y)
                addSelection(fillIdAndInterval.b, false)
                additionalRepaintInterval = additionalRepaintInterval?.let { Intervals.union(it, fillIdAndInterval.b) } ?: fillIdAndInterval.b
            }
        } else {
            val fillIdAndInterval: Pair<Long, Interval> = if (wasSelected) {
                runFloodFillToDeselect(x, y)
            } else {
                runFloodFillToSelect(x, y)
            }
            additionalRepaintInterval = fillIdAndInterval.b
            addSelection(fillIdAndInterval.b)
        }
        interpolateBetweenSections(true)
        requestRepaintForInterpolatedMask(unionWith = additionalRepaintInterval)
    }

    private fun updateFillAndInterpolantsCompositeMask() {
        when (sectionsAndInterpolants.sections.size) {
            0 -> {
                source.resetMasks()
                paintera().orthogonalViews().requestRepaint()
            }
            1 -> {
                val lastRemainingSection = sectionsAndInterpolants.sections[0]
                source.resetMasks()
                lastRemainingSection.mask.setMaskOnUpdate = true
                lastRemainingSection.mask.setViewerMaskOnSource()
            }
            else -> {
                try {
                    setInterpolatedMasks()
                } catch (e: MaskInUse) {
                    LOG.error("Label source already has an active mask")
                }
            }

        }
    }

    internal data class ClearedSection(val numObjects: Int, val bounds: RealInterval)

    private fun clearCurrentSection(): ClearedSection? {
        return sectionAtCurrentDepth?.let {
            val clearedSection = deselectSelectionsInSection(it)
            sectionsAndInterpolants.clearInterpolantsAroundSection(currentDepth)
            interpolateBetweenSections(true)
            clearedSection
        }
    }

    internal fun deselectSelectionsInSection(section: SectionInfo): ClearedSection? {
        var deselectedInterval: Interval? = null
        val selectionIntervals = section.selectionIntervals
        var distinctObjects = 0
        for (labelInterval in selectionIntervals) {
            val labelToViewerTransform = maskDisplayTransform
            val selectionCursor = Views.interval(currentViewerMask!!.viewerRai, labelInterval.smallestContainingInterval).localizingCursor()
            while (selectionCursor.hasNext()) {
                val maskVal = selectionCursor.next()
                if (maskVal.get() > 0) {
                    val labelPos = selectionCursor.positionAsDoubleArray()
                    val viewerPos = selectionCursor.positionAsDoubleArray()
                    labelToViewerTransform.apply(labelPos, viewerPos)
                    val idAndInterval = runFloodFillToDeselect(viewerPos[0], viewerPos[1])
                    if (deselectedInterval == null) {
                        deselectedInterval = idAndInterval.b
                    } else {
                        deselectedInterval = deselectedInterval union idAndInterval.b
                    }
                    distinctObjects++
                }
            }
        }
        section.clearSelections()
        return deselectedInterval?.let {
            ClearedSection(distinctObjects, section.mask.globalToViewerTransform.inverse().estimateBounds(deselectedInterval))
        }
    }

    /**
     * Flood-fills the mask using a new fill value to mark the object as selected.
     *
     * @param x
     * @param y
     * @return the fill value of the selected object and the affected interval in source coordinates
     */
    private fun runFloodFillToSelect(x: Double, y: Double): Pair<Long, Interval> {
        currentFillValuePropery.set(currentFillValuePropery.longValue() + 1)
        val fillValue = currentFillValuePropery.longValue()
        val fillDepth = determineFillDepth()
        LOG.debug("Flood-filling to select object: fill value={}, depth={}", fillValue, fillDepth)
        val affectedInterval = FloodFill2D.fillMaskAt(x, y, activeViewer, currentViewerMask, source, assignment, fillValue, fillDepth)
        return ValuePair(fillValue, affectedInterval)
    }

    /**
     * Flood-fills the mask using the background value to remove the object from the selection.
     *
     * @param x
     * @param y
     * @return the fill value of the deselected object
     */
    private fun runFloodFillToDeselect(x: Double, y: Double): Pair<Long, Interval> {
        // set the predicate to accept only the fill value at the clicked location to avoid deselecting adjacent objects.
        val maskValue = getMaskValue(x, y).get()
        val predicate = Converters.convert(
            currentViewerMask!!.viewerRai,
            { input, output -> output.set(input.integerLong == maskValue) },
            BoolType()
        )
        val fillDepth = determineFillDepth()
        LOG.debug("Flood-filling to deselect object: old value={}, depth={}", maskValue, fillDepth)
        val fillInterval = FloodFill2D.fillMaskAt<UnsignedLongType>(x, y, activeViewer, currentViewerMask, predicate, maskTransform, Label.BACKGROUND, fillDepth)
        return ValuePair(maskValue, fillInterval)
    }

    private fun determineFillDepth(): Double {
        val normalAxis = PaintUtils.labelAxisCorrespondingToViewerAxis(maskTransform, displayTransform, 2)
        val calculatedFillDepth = if (normalAxis < 0) DEFAULT_FILL_DEPTH else DEFAULT_FILL_DEPTH_ORTHOGONAL
        return calculatedFillDepth
    }

    private fun getMaskValue(x: Double, y: Double): UnsignedLongType {
        /* FIXME, don't convert to source and use `rai`, just use display coords and grab from `viewerImg` */
        val sourcePos = getSourceCoordinates(x, y)
        val maskAccess: RandomAccess<UnsignedLongType> = Views.extendValue(currentViewerMask!!.rai, UnsignedLongType(Label.OUTSIDE)).randomAccess()
        for (d in 0 until sourcePos.numDimensions()) {
            maskAccess.setPosition(sourcePos.getDoublePosition(d).roundToLong(), d)
        }
        return maskAccess.get()
    }

    private fun getDataValue(x: Double, y: Double): D {
        val sourcePos = getSourceCoordinates(x, y)
        val time = activeViewer!!.state.timepoint
        val data = source.getDataSource(time, maskLevel)
        val dataAccess = data.randomAccess()
        for (d in 0 until sourcePos.numDimensions()) {
            dataAccess.setPosition(Math.round(sourcePos.getDoublePosition(d)), d)
        }
        return dataAccess.get()
    }

    private val maskTransform: AffineTransform3D
        get() {
            val maskTransform = AffineTransform3D()
            val time = activeViewer!!.state.timepoint
            source.getSourceTransform(time, maskLevel, maskTransform)
            return maskTransform
        }
    private val displayTransform: AffineTransform3D
        get() {
            val viewerTransform = AffineTransform3D()
            activeViewer!!.state.getViewerTransform(viewerTransform)
            return viewerTransform
        }
    private val maskDisplayTransform: AffineTransform3D
        get() = displayTransform.concatenate(maskTransform)


    /**
     * Returns the transformation to bring the mask to the current viewer plane.
     * Ignores the scaling in the viewer and in the mask.
     *
     * @return
     */
    private val maskDisplayTransformIgnoreScaling: AffineTransform3D
        get() {
            val viewerTransform = displayTransform
            // undo scaling in the viewer
            val viewerScale = DoubleArray(viewerTransform.numDimensions())
            Arrays.setAll(viewerScale) { d: Int -> Affine3DHelpers.extractScale(viewerTransform, d) }
            val scalingTransform = Scale3D(*viewerScale)
            // neutralize mask scaling if there is any
            val time = activeViewer!!.state.timepoint
            scalingTransform.concatenate(Scale3D(*DataSource.getScale(source, time, currentBestMipMapLevel)))
            // build the resulting transform
            return viewerTransform.preConcatenate(scalingTransform.inverse()).concatenate(maskTransform)
        }

    private fun getSourceCoordinates(x: Double, y: Double): RealPoint {
        val maskTransform = maskTransform
        val sourcePos = RealPoint(maskTransform.numDimensions())
        activeViewer!!.displayToSourceCoordinates(x, y, maskTransform, sourcePos)
        return sourcePos
    }

    private fun getDisplayCoordinates(sourcePos: RealPoint): DoubleArray {
        val maskDisplayTransform = maskDisplayTransform
        val displayPos = RealPoint(maskDisplayTransform.numDimensions())
        maskDisplayTransform.apply(sourcePos, displayPos)
        assert(Util.isApproxEqual(displayPos.getDoublePosition(2), 0.0, 1e-10))
        return doubleArrayOf(displayPos.getDoublePosition(0), displayPos.getDoublePosition(1))
    }

    fun generateMask(paintController: PaintClickOrDragController): ViewerMask {
        currentViewerMask = paintController.generateViewerMask(submitMask = false)

        /* if we are between sections, we need to copy the interpolated data into the mask before we return it */

        /* get union of adjacent sections bounding boxes */
        val prevSection = sectionsAndInterpolants.getPreviousSection(currentDepth)
        val nextSection = sectionsAndInterpolants.getNextSection(currentDepth)
        if (prevSection != null && nextSection != null && preview) { //FIXME: && keepInterpolation) {

            /* Fill interpolation into paintMask */
            val globalIntervalOverAdjacentSections = Intervals.union(prevSection.globalBoundingBox, nextSection.globalBoundingBox)
            val adjacentSelectionIntervalInDisplaySpace = currentViewerMask!!.globalToViewerTransform.estimateBounds(globalIntervalOverAdjacentSections)

            val minZSlice = adjacentSelectionIntervalInDisplaySpace.minAsDoubleArray().also { it[2] = 0.0 }
            val maxZSlice = adjacentSelectionIntervalInDisplaySpace.maxAsDoubleArray().also { it[2] = 0.0 }

            val adjacentSectionsUnionSliceIntervalInDisplaySpace = FinalRealInterval(minZSlice, maxZSlice)
            val existingInterpolationMask = sectionsAndInterpolants.getInterpolantsAround(prevSection).b

            val interpolationMaskInViewer = RealViews.affine(existingInterpolationMask!!.a, currentViewerMask!!.labelToViewerTransform)
            val interpolatedMaskView = Views.interval(interpolationMaskInViewer, adjacentSectionsUnionSliceIntervalInDisplaySpace.smallestContainingInterval)
            val fillMaskOverInterval = Views.interval(currentViewerMask!!.viewerRai, adjacentSectionsUnionSliceIntervalInDisplaySpace.smallestContainingInterval)

            LoopBuilder.setImages(interpolatedMaskView, fillMaskOverInterval)
                .forEachPixel { interpolationType, fillMaskType ->
                    val fillMaskVal = fillMaskType.get()
                    val iVal = interpolationType.get()
                    if (fillMaskVal <= 0 && fillMaskVal != Label.TRANSPARENT && iVal > 0) {
                        fillMaskType.set(iVal)
                    }
                }


            val globalTransform = paintera().manager().transform
            val section = SectionInfo(
                currentViewerMask!!,
                globalTransform,
                adjacentSectionsUnionSliceIntervalInDisplaySpace
            )
            /* remove the old interpolant*/
            sectionsAndInterpolants.removeIfInterpolantAt(currentDepth)
            /* add the new section */
            sectionsAndInterpolants.add(currentDepth, section)
        }

        return currentViewerMask!!
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
        private const val DEFAULT_FILL_DEPTH = 2.0
        private const val DEFAULT_FILL_DEPTH_ORTHOGONAL = 1.0
        private val MASK_COLOR = Color.web("00CCFF")
        private val FOREGROUND_CHECK = Predicate { t: UnsignedLongType -> Label.isForeground(t.get()) }
        private fun paintera(): PainteraBaseView {
            return getPaintera().baseView
        }

        @Throws(MaskInUse::class)
        private fun interpolateBetweenTwoSections(
            section1: SectionInfo,
            section2: SectionInfo
        ): Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? {
            val sectionInfoPair = arrayOf(section1, section2)

            // get the two sections as 2D images
            val sectionPair: Array<RandomAccessibleInterval<UnsignedLongType>?> = arrayOfNulls(2)


            val section1To2Initial = section1.mask.getInitialToTargetTransform(section2.mask.initialLabelToViewerTransform)
            val section2To1Initial = section1To2Initial.inverse().copy()

            val section1InCurrent = section1.run { mask.initialToCurrentViewerTransform.estimateBounds(maskBoundingBox) }
            val section2InCurrentViaSection1 = section1.mask.initialToCurrentViewerTransform.concatenate(section2To1Initial).estimateBounds(section2.maskBoundingBox)

            val section2InCurrent = section2.run { mask.initialToCurrentViewerTransform.estimateBounds(maskBoundingBox) }
            val section1InCurrentViaSection2 = section1.mask.initialToCurrentViewerTransform.concatenate(section2To1Initial).estimateBounds(section1.maskBoundingBox)

            val unionForSection1InCurrent = section1InCurrent union section2InCurrentViaSection1
            val unionForSection2InCurrent = section1InCurrentViaSection2 union section2InCurrent

            for ((idx, unionInCurrent) in (0..1).zip(arrayOf(unionForSection1InCurrent, unionForSection2InCurrent))) {
                with(sectionInfoPair[idx]) {
                    with(mask) {

                        val unionInInitial = initialToCurrentViewerTransform.inverse().estimateBounds(unionInCurrent)
                        val unionInInitialZeroDepth = FinalRealInterval(
                            unionInInitial.minAsDoubleArray().also { it[2] = 0.0 },
                            unionInInitial.maxAsDoubleArray().also { it[2] = 0.0 },
                        )

                        val transform = if (idx == 0) AffineTransform3D() else section2To1Initial
                        val unionInSection1Initial = transform.estimateBounds(unionInInitialZeroDepth)
                        val sectionImg = RealViews.affineReal(viewerImg, transform)
                            .raster()
                            .interval(unionInSection1Initial.smallestContainingInterval)

                        val zeroMin = Views.zeroMin(Views.hyperSlice(sectionImg, 2, unionInSection1Initial.realMin(2).roundToLong()))
                        sectionPair[idx] = zeroMin
                    }
                }
            }

            /* Get the union boundingBox in each masks current transform */
//            val section1InCurrent = section1.run { mask.initialToCurrentViewerTransform.estimateBounds(maskBoundingBox) }
//            val section2InCurrent = section2.run { mask.initialToCurrentViewerTransform.estimateBounds(maskBoundingBox) }
//            val sectionBoxUnionMaskInCurrent = section1InCurrent union section2InCurrent
//
//
//            for (i in 0..1) {
//                with(sectionInfoPair[i]) {
//                    with(mask) {
//
//                        val unionInInitial = initialToCurrentViewerTransform.inverse().estimateBounds(sectionBoxUnionMaskInCurrent)
//                        val unionInInitialZeroDepth = FinalRealInterval(
//                            unionInInitial.minAsDoubleArray().also { it[2] = 0.0 },
//                            unionInInitial.maxAsDoubleArray().also { it[2] = 0.0 },
//                        )
//
//                        val sectionImg = viewerImg
//                            .raster()
//                            .interval(unionInInitialZeroDepth.smallestContainingInterval)
//
//                        sectionPair[i] = Views.zeroMin(Views.hyperSlice(sectionImg, 2, 0))
//                    }s
//                }
//            }

            // compute distance transform on both sections
            val distanceTransformPair: Array<RandomAccessibleInterval<FloatType>?> = arrayOfNulls(2)
            for (i in 0..1) {
                if (Thread.currentThread().isInterrupted) return null
                distanceTransformPair[i] = ArrayImgFactory(FloatType()).create(sectionPair[i]).also {
                    val binarySection = Converters.convert(sectionPair[i], PredicateConverter(FOREGROUND_CHECK), BoolType())
                    computeSignedDistanceTransform(binarySection, it, DISTANCE_TYPE.EUCLIDIAN)
                }
            }
            val currentDistanceBetweenSections = computeDistanceBetweenSections(sectionInfoPair[0], sectionInfoPair[1])
            val depthScaleCurrentToInitial = Affine3DHelpers.extractScale(sectionInfoPair[0].mask.initialToCurrentViewerTransform.inverse(), 2)
            val initialDistanceBetweenSections = currentDistanceBetweenSections * depthScaleCurrentToInitial

            val sectionBoxUnionInFirstInitial = sectionInfoPair[0].mask.initialToCurrentViewerTransform.inverse().estimateBounds(unionForSection1InCurrent)

            val transformToSource = AffineTransform3D()
            transformToSource
                .concatenate(sectionInfoPair[0].mask.initialLabelToViewerTransform.inverse())
                .concatenate(Translation3D(sectionBoxUnionInFirstInitial.realMin(0), sectionBoxUnionInFirstInitial.realMin(1), 0.0))

            val interpolatedShapeMask = getInterpolatedDistanceTransformMask(
                distanceTransformPair[0] as ArrayImg<FloatType, *>,
                distanceTransformPair[1] as ArrayImg<FloatType, *>,
                initialDistanceBetweenSections,
                UnsignedLongType(1),
                transformToSource
            )

            val volatileInterpolatedShapeMask = getInterpolatedDistanceTransformMask(
                distanceTransformPair[0] as ArrayImg<FloatType, *>,
                distanceTransformPair[1] as ArrayImg<FloatType, *>,
                initialDistanceBetweenSections,
                VolatileUnsignedLongType(1),
                transformToSource
            )

            return if (Thread.currentThread().isInterrupted) null else ValuePair(interpolatedShapeMask, volatileInterpolatedShapeMask)
        }

        private fun <R, B : BooleanType<B>> computeSignedDistanceTransform(
            mask: RandomAccessibleInterval<B>,
            target: RandomAccessibleInterval<R>,
            distanceType: DISTANCE_TYPE,
            vararg weights: Double
        ) where R : RealType<R>?, R : NativeType<R>? {
            val distanceInside: RandomAccessibleInterval<R> = ArrayImgFactory(Util.getTypeFromInterval(target)).create(target)
            DistanceTransform.binaryTransform(mask, target, distanceType, *weights)
            DistanceTransform.binaryTransform(Logical.complement(mask), distanceInside, distanceType, *weights)
            LoopBuilder.setImages(target, distanceInside, target).forEachPixel(LoopBuilder.TriConsumer { outside: R, inside: R, result: R ->
                when (distanceType) {
                    DISTANCE_TYPE.EUCLIDIAN -> result!!.setReal(Math.sqrt(outside!!.realDouble) - Math.sqrt(inside!!.realDouble))
                    DISTANCE_TYPE.L1 -> result!!.setReal(outside!!.realDouble - inside!!.realDouble)
                }
            })
        }

        private fun <R : RealType<R>, T> getInterpolatedDistanceTransformMask(
            dt1: RandomAccessibleInterval<R>,
            dt2: RandomAccessibleInterval<R>,
            distance: Double,
            targetValue: T,
            transformToSource: AffineTransform3D
        ): RealRandomAccessible<T> where T : NativeType<T>, T : RealType<T> {
            val distanceTransformStack = Views.stack(dt1, dt2)
            val extendValue = Util.getTypeFromInterval(distanceTransformStack)!!.createVariable()
            extendValue!!.setReal(extendValue.maxValue)
            val interpolatedDistanceTransform = Views.interpolate(
                Views.extendValue(distanceTransformStack, extendValue),
                NLinearInterpolatorFactory()
            )
            val scaledInterpolatedDistanceTransform: RealRandomAccessible<R> = RealViews.affineReal(
                interpolatedDistanceTransform,
                Scale3D(1.0, 1.0, distance)
            )
            val emptyValue = targetValue.createVariable()

            val interpolatedShape: RealRandomAccessible<T> = Converters.convert(
                scaledInterpolatedDistanceTransform,
                { input: R, output: T -> output.set(if (input.realDouble <= 0) targetValue else emptyValue) },
                targetValue.createVariable()
            )
            return RealViews.affineReal(interpolatedShape, transformToSource)
        }

        private fun computeDistanceBetweenSections(s1: SectionInfo, s2: SectionInfo): Double {

            val s1OriginInCurrent = DoubleArray(3)
            val s2OriginInCurrentViaS1 = DoubleArray(3)

            s1.mask.initialToCurrentViewerTransform.apply(s1OriginInCurrent, s1OriginInCurrent)

            val s2InitialTos1InitialTransform = s2.mask.getInitialToTargetTransform(s1.mask.initialLabelToViewerTransform)
            s2InitialTos1InitialTransform.apply(s2OriginInCurrentViaS1, s2OriginInCurrentViaS1)
            s1.mask.initialToCurrentViewerTransform.apply(s2OriginInCurrentViaS1, s2OriginInCurrentViaS1)

            return s2OriginInCurrentViaS1[2] - s1OriginInCurrent[2]
        }
    }

    private class SectionOrInterpolant {
        private val sectionAndDepth: Pair<Double, SectionInfo>?
        private val interpolant: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>?

        constructor(depth: Double, section: SectionInfo) {
            sectionAndDepth = ValuePair(depth, section)
            interpolant = null
        }

        constructor(interpolant: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>?) {
            sectionAndDepth = null
            this.interpolant = interpolant
        }

        val isSection: Boolean
            get() = sectionAndDepth != null

        fun isInterpolant(): Boolean {
            return interpolant != null
        }

        fun getSection(): SectionInfo {
            return sectionAndDepth!!.b
        }

        val sectionDepth: Double
            get() = sectionAndDepth!!.a

        fun getInterpolant(): Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? {
            return interpolant
        }

        override fun equals(other: Any?): Boolean {
            return equalsSection(other) || equalsInterpolant(other)
        }

        private fun equalsSection(other: Any?): Boolean {
            return isSection && getSection() == other
        }

        private fun equalsInterpolant(other: Any?): Boolean {
            return isInterpolant() && getInterpolant() == other
        }
    }

    private class SectionsAndInterpolant : ObservableList<SectionOrInterpolant> by FXCollections.observableArrayList() {

        fun removeSection(section: SectionInfo): Boolean {
            for (idx in indices) {
                if (idx >= 0 && idx <= size - 1 && get(idx).equals(section)) {
                    removeIfInterpolant(idx + 1)
                    removeAt(idx).getSection().also { it.mask.disable() }
                    removeIfInterpolant(idx - 1)
                    return true
                }
            }
            return false
        }

        fun removeSectionAtDepth(depth: Double): SectionInfo? {
            return getSectionAtDepth(depth)?.also {
                removeSection(it)
            }
        }

        fun removeIfInterpolant(idx: Int): Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? {
            return if (idx >= 0 && idx <= size - 1 && get(idx).isInterpolant()) {
                removeAt(idx).getInterpolant()
            } else null
        }

        fun clearInterpolants() {
            for (idx in this.indices.reversed()) {
                if (get(idx).isInterpolant()) {
                    removeAt(idx)
                }
            }
        }

        fun add(depth: Double, section: SectionInfo) {
            for (idx in this.indices) {
                if (get(idx).isSection && get(idx).sectionDepth > depth) {
                    add(idx, SectionOrInterpolant(depth, section))
                    removeIfInterpolant(idx - 1)
                    return
                }
            }
            add(SectionOrInterpolant(depth, section))
        }

        fun add(idx: Int, interpolant: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>?) {
            add(idx, SectionOrInterpolant(interpolant))
        }

        fun removeAllInterpolants() {
            for (i in size - 1 downTo 0) {
                removeIfInterpolant(i)
            }
        }

        fun getSectionAtDepth(depth: Double): SectionInfo? {
            for (sectionOrInterpolant in this) {
                if (sectionOrInterpolant.isSection && sectionOrInterpolant.sectionDepth == depth) {
                    return sectionOrInterpolant.getSection()
                }
            }
            return null
        }

        fun getPreviousSection(depth: Double): SectionInfo? {
            var prevSection: SectionInfo? = null
            for (sectionOrInterpolant in this) {
                if (sectionOrInterpolant.isSection) {
                    prevSection = if (sectionOrInterpolant.sectionDepth < depth) {
                        sectionOrInterpolant.getSection()
                    } else {
                        break
                    }
                }
            }
            return prevSection
        }

        fun getNextSection(depth: Double): SectionInfo? {
            for (sectionOrInterpolant in this) {
                if (sectionOrInterpolant.isSection && sectionOrInterpolant.sectionDepth > depth) {
                    return sectionOrInterpolant.getSection()
                }
            }
            return null
        }

        fun getInterpolantsAround(section: SectionInfo?): Pair<Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>?, Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>?> {
            var interpolantAfter: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? = null
            var interpolantBefore: Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>? = null
            for (idx in 0 until size - 1) {
                if (get(idx).equals(section)) {
                    if (get(idx + 1).isInterpolant()) {
                        interpolantAfter = get(idx + 1).getInterpolant()
                        if (idx - 1 >= 0 && get(idx - 1).isInterpolant()) {
                            interpolantBefore = get(idx - 1).getInterpolant()
                        }
                    }
                    break
                }
            }
            return ValuePair(interpolantBefore, interpolantAfter)
        }

        val sections: List<SectionInfo>
            get() = stream()
                .filter { it.isSection }
                .map { it.getSection() }
                .collect(Collectors.toList())
        val interpolants: List<Pair<RealRandomAccessible<UnsignedLongType>, RealRandomAccessible<VolatileUnsignedLongType>>>
            get() = stream()
                .filter { it.isInterpolant() }
                .map { it.getInterpolant()!! }
                .collect(Collectors.toList())

        fun clearInterpolantsAroundSection(z: Double) {
            for (idx in this.indices) {
                if (get(idx).isSection && get(idx).sectionDepth == z) {
                    removeIfInterpolant(idx + 1)
                    removeIfInterpolant(idx - 1)
                    return
                }
            }
        }

        fun removeIfInterpolantAt(depthInMaskDisplay: Double) {
            for (idx in this.indices) {
                if (get(idx).isSection && get(idx).sectionDepth > depthInMaskDisplay) {
                    removeIfInterpolant(idx - 1)
                    return
                }
            }
        }
    }

    var initialMaskDisplayTransform: AffineTransform3D? = null
    var preview = true

    class SectionInfo(
        var mask: ViewerMask,
        val globalTransform: AffineTransform3D,
        selectionInterval: RealInterval
    ) {
        internal val maskBoundingBox: RealInterval get() = computeBoundingBoxInInitialMask()
        internal val sourceBoundingBox: RealInterval get() = mask.initialLabelToViewerTransform.inverse().estimateBounds(maskBoundingBox)
        internal val globalBoundingBox: RealInterval get() = mask.source.getSourceTransformForMask(mask.info).estimateBounds(sourceBoundingBox.smallestContainingInterval)

        private val selectionIntervalsInInitialSpace: MutableList<RealInterval> = mutableListOf()

        val selectionIntervals: List<RealInterval>
            get() = selectionIntervalsInInitialSpace.map { mask.initialToCurrentViewerTransform.estimateBounds(it) }.toList()

        init {
            addSelection(selectionInterval)
        }

        private fun computeBoundingBoxInInitialMask(): RealInterval {
            return selectionIntervalsInInitialSpace.reduce { l, r -> l union r }
        }

        fun clearSelections() {
            selectionIntervalsInInitialSpace.clear()
        }

        fun addSelection(selectionInterval: RealInterval) {
            val selectionInInitialSpace = mask.initialToCurrentViewerTransform.inverse().estimateBounds(selectionInterval).run {
                /* Zero out the depth, since the viewerMask is effectively 2D */
                FinalRealInterval(
                    minAsDoubleArray().also { it[2] = 0.0 },
                    maxAsDoubleArray().also { it[2] = 0.0 },
                )
            }
            selectionIntervalsInInitialSpace.add(selectionInInitialSpace)
        }
    }
}
