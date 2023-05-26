package org.janelia.saalfeldlab.paintera.control.tools.paint

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import bdv.fx.viewer.ViewerPanelFX
import bdv.fx.viewer.render.RenderUnit
import bdv.util.volatiles.SharedQueue
import bdv.viewer.Interpolation
import bdv.viewer.SourceAndConverter
import bdv.viewer.render.AccumulateProjectorARGB
import com.amazonaws.util.Base64
import com.google.common.util.concurrent.ThreadFactoryBuilder
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.application.Platform
import javafx.beans.Observable
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.embed.swing.SwingFXUtils
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent.MOUSE_CLICKED
import javafx.scene.input.MouseEvent.MOUSE_MOVED
import javafx.scene.input.ScrollEvent
import javafx.scene.shape.Circle
import net.imglib2.Interval
import net.imglib2.Point
import net.imglib2.RandomAccessibleInterval
import net.imglib2.RealPoint
import net.imglib2.algorithm.labeling.ConnectedComponents
import net.imglib2.algorithm.labeling.ConnectedComponents.StructuringElement
import net.imglib2.algorithm.morphology.distance.DistanceTransform
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgs
import net.imglib2.loops.LoopBuilder
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.realtransform.Scale3D
import net.imglib2.realtransform.Translation3D
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.type.volatiles.VolatileUnsignedLongType
import net.imglib2.view.Views
import org.apache.http.HttpException
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.janelia.saalfeldlab.fx.Tasks
import org.janelia.saalfeldlab.fx.UtilityTask
import org.janelia.saalfeldlab.fx.actions.painteraActionSet
import org.janelia.saalfeldlab.fx.actions.verifyPainteraNotDisabled
import org.janelia.saalfeldlab.fx.extensions.LazyForeignValue
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.fx.extensions.nullable
import org.janelia.saalfeldlab.fx.extensions.position
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.PainteraBaseView
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.modes.ToolMode
import org.janelia.saalfeldlab.paintera.control.paint.ViewerMask
import org.janelia.saalfeldlab.paintera.control.paint.ViewerMask.Companion.createViewerMask
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.asRealInterval
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import org.janelia.saalfeldlab.util.*
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.invoke.MethodHandles
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.properties.Delegates

private const val H_ONNX_MODEL = "../paintera_sam/sam_vit_h_4b8939.onnx"

private const val SAM_SERVICE_INTERNAL = "http://saalfelds-gpu3/embedded_model"
private const val SAM_SERVICE = "http://gpu3.saalfeldlab.org/embedded_model"

open class SamTool(activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>, mode: ToolMode? = null) : PaintTool(activeSourceStateProperty, mode) {

    override val graphic = { FontAwesomeIconView().also { it.styleClass += listOf("toolbar-tool", "sam-select") } }
    override val name = "SAM"
    override val keyTrigger = listOf(KeyCode.A)


    private val currentLabelToPaintProperty = SimpleObjectProperty(Label.INVALID)
    internal var currentLabelToPaint: Long by currentLabelToPaintProperty.nonnull()
    private val isLabelValid get() = currentLabelToPaint != Label.INVALID

    override val actionSets by LazyForeignValue({ activeViewerAndTransforms }) {
        mutableListOf(
            *super.actionSets.toTypedArray(),
            *getSamActions(),
        )
    }

    override val statusProperty = SimpleStringProperty()

    private val selectedIdListener: (obs: Observable) -> Unit = {
        statePaintContext?.selectedIds?.lastSelection?.let { currentLabelToPaint = it }
    }

    /* lateinit so we can self-reference, so it removes itself after being triggered. */
    private lateinit var setCursorWhenDoneApplying: ChangeListener<Boolean>
    internal val maskedSource: MaskedSource<*, *>?
        get() = activeSourceStateProperty.get()?.dataSource as? MaskedSource<*, *>

    private var currentViewerMask: ViewerMask? = null
    private var originalBackingImage: RandomAccessibleInterval<UnsignedLongType>? = null
    private var originalWritableBackingImage: RandomAccessibleInterval<UnsignedLongType>? = null
    private var originalVolatileBackingImage: RandomAccessibleInterval<VolatileUnsignedLongType>? = null
    private var originalWritableVolatileBackingImage: RandomAccessibleInterval<VolatileUnsignedLongType>? = null
    private var maskProvided = false

    private var setViewer: ViewerPanelFX? = null

    internal var viewerMask: ViewerMask? = null
        get() {
            if (field == null) {
                field = maskedSource!!.createViewerMask(
                    MaskInfo(0, setViewer!!.state.bestMipMapLevel),
                    setViewer!!
                )
                originalBackingImage = field?.viewerImg?.source
                originalWritableBackingImage = field?.viewerImg?.writableSource
                originalVolatileBackingImage = field?.volatileViewerImg?.source
                originalWritableVolatileBackingImage = field?.volatileViewerImg?.writableSource
                maskProvided = false
            }
            currentViewerMask = field
            return field!!
        }
        set(value) {
            field = value
            maskProvided = value != null
            currentViewerMask = field
            originalBackingImage = field?.viewerImg?.source
            originalWritableBackingImage = field?.viewerImg?.writableSource
            originalVolatileBackingImage = field?.volatileViewerImg?.source
            originalWritableVolatileBackingImage = field?.volatileViewerImg?.writableSource
        }

    private var predictionTask: UtilityTask<Unit>? = null

    private val lastPredictionProperty = SimpleObjectProperty<SamTaskInfo?>(null)
    var lastPrediction by lastPredictionProperty.nullable()
        private set
    private val includePoints = mutableListOf<Point>()

    private val excludePoints = mutableListOf<Point>()

    private var threshold = 2.5
        set(value) {
            field = value.coerceAtLeast(0.0)
        }

    init {
        setCursorWhenDoneApplying = ChangeListener { observable, _, isApplying ->
            observable.removeListener(setCursorWhenDoneApplying)
        }
    }

    private val isBusyProperty = SimpleBooleanProperty(false)

    private var isBusy by isBusyProperty.nonnull()

    private var screenScale by Delegates.notNull<Double>()

    private var originalScales: DoubleArray? = null

    private var predictionImagePngInputStream = PipedInputStream()
    private var predictionImagePngOutputStream = PipedOutputStream(predictionImagePngInputStream)
    override fun activate() {
        super.activate()
        threshold = 5.0
        setCurrentLabelToSelection()
        statePaintContext?.selectedIds?.apply { addListener(selectedIdListener) }
        setViewer = activeViewer
        screenScale = calculateTargetScreenScaleFactor()
        originalScales = setViewer?.renderUnit?.screenScalesProperty?.get()?.copyOf()
        setViewer?.setScreenScales(doubleArrayOf(screenScale))
        statusProperty.set("Preparing SAM")
        paintera.baseView.disabledPropertyBindings[this] = isBusyProperty
        Tasks.createTask {
            predictionImagePngInputStream = PipedInputStream()
            predictionImagePngOutputStream = PipedOutputStream(predictionImagePngInputStream)
            saveActiveViewerImageFromRenderer()
            providedEmbedding ?: getImageEmbeddingTask()
            setViewer?.let { viewer ->
                if (viewer.isMouseInside) {
                    Platform.runLater { statusProperty.set("Predicting...") }
                    val x = viewer.mouseXProperty.get().toLong()
                    val y = viewer.mouseYProperty.get().toLong()
                    includePoints.clear()
                    excludePoints.clear()
                    includePoints += Point(x, y)
                    Platform.runLater { viewer.children.removeIf { SamPointStyle.POINT in it.styleClass } }
                    requestPrediction(includePoints, excludePoints)
                }
            }
        }.onSuccess { _, _ ->
            Platform.runLater { statusProperty.set("Ready") }
        }.onCancelled { _, _ ->
            Platform.runLater { statusProperty.set("Cancelled") }
            deactivate()
        }.submit(SAM_TASK_SERVICE)
    }

    override fun deactivate() {
        currentLabelToPaint = Label.INVALID
        predictionTask?.cancel()
        predictionTask = null
        if (!maskProvided) {
            maskedSource?.resetMasks()
        } else {
            currentViewerMask?.updateBackingImages(
                originalBackingImage!! to originalVolatileBackingImage!!,
                originalWritableBackingImage!! to originalWritableVolatileBackingImage!!
            )
        }
        currentViewerMask?.viewer?.requestRepaint()
        currentViewerMask?.viewer?.children?.removeIf { SamPointStyle.POINT in it.styleClass }
        viewerMask = null
        paintera.baseView.disabledPropertyBindings -= this
        setViewer?.setScreenScales(originalScales)
        originalScales = null
        super.deactivate()
    }

    protected open fun setCurrentLabelToSelection() {
        currentLabelToPaint = statePaintContext?.paintSelection?.invoke() ?: Label.INVALID
    }

    private fun getSamActions() = arrayOf(painteraActionSet("sam selections", PaintActionType.Paint, ignoreDisable = true) {
        /* Handle Painting */
        MOUSE_CLICKED(MouseButton.PRIMARY) {
            name = "apply last segmentation result to canvas"
            consume = false
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            verify("Control cannot be down") { it?.isControlDown == false } /* If control is down, we are in point selection mode */
            verify(" label is not valid ") { isLabelValid }
            onAction {
                lastPrediction?.submitPrediction()
                threshold = 2.5
                clearInsideOutsideCircles()
            }
        }
        KEY_PRESSED(KeyCode.ENTER) {
            name = "key apply last segmentation result to canvas"
            consume = false
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            verify(" label is not valid ") { isLabelValid }
            onAction {
                lastPrediction?.submitPrediction()
                threshold = 2.5
                clearInsideOutsideCircles()
            }
        }

        ScrollEvent.SCROLL(KeyCode.CONTROL) {
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            onAction {
                val delta = arrayOf(it!!.deltaX, it.deltaY).maxBy { it.absoluteValue }
                threshold += (delta.sign * .5)
                requestPrediction(includePoints, excludePoints)
            }
        }

        MOUSE_MOVED {
            name = "prediction overlay"
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            verify("Control cannot be down") { it?.isControlDown == false } /* If control is down, we are in point selection mode */
            verify("Label is not valid") { isLabelValid }
            onAction {
                includePoints.clear()
                excludePoints.clear()
                clearInsideOutsideCircles()
                includePoints += it!!.position.toPoint()
                requestPrediction(includePoints, excludePoints)
            }
        }

        /* Handle Erasing */
        MOUSE_CLICKED(MouseButton.PRIMARY, withKeysDown = arrayOf(KeyCode.CONTROL)) {
            name = "include point"
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            onAction {
                includePoints += it!!.position.toPoint()
                setViewer?.let { viewer ->
                    Platform.runLater {
                        viewer.children += Circle(5.0).apply {
                            translateX = it!!.x - viewer.width / 2
                            translateY = it.y - viewer.height / 2
                            styleClass += SamPointStyle.POINT
                            styleClass += SamPointStyle.INCLUDE
                        }
                    }
                }
                requestPrediction(includePoints, excludePoints)
            }
        }

        MOUSE_CLICKED(MouseButton.SECONDARY, withKeysDown = arrayOf(KeyCode.CONTROL)) {
            name = "exclude point"
            verifyEventNotNull()
            verifyPainteraNotDisabled()
            onAction {
                excludePoints += it!!.position.toPoint()
                setViewer?.let { viewer ->
                    Platform.runLater {
                        viewer.children += Circle(5.0).apply {
                            translateX = it!!.x - viewer.width / 2
                            translateY = it.y - viewer.height / 2
                            styleClass += SamPointStyle.POINT
                            styleClass += SamPointStyle.EXCLUDE
                        }
                    }
                }
                requestPrediction(includePoints, excludePoints)
            }
        }
    })

    private fun clearInsideOutsideCircles() = setViewer?.let { viewer ->
        Platform.runLater { viewer.children.removeIf { child -> SamPointStyle.POINT in child.styleClass } }
    }

    private fun SamTaskInfo.submitPrediction() {
        val (maskedSource, maskInterval) = this
        (maskedSource.currentMask as? ViewerMask)?.let { currentMask ->
            if (!maskProvided) {
                val sourceInterval = IntervalHelpers.extendAndTransformBoundingBox(maskInterval.asRealInterval, currentMask.initialMaskToSourceWithDepthTransform, .5)
                maskedSource.applyMask(currentMask, sourceInterval.smallestContainingInterval, MaskedSource.VALID_LABEL_CHECK)
                viewerMask = null
            } else {
                LoopBuilder
                    .setImages(originalWritableBackingImage!!.interval(maskInterval), currentMask.viewerImg.source.interval(maskInterval))
                    .multiThreaded()
                    .forEachPixel { originalImage, currentImage ->
                        originalImage.set(currentImage.get())
                    }
                LoopBuilder
                    .setImages(originalWritableVolatileBackingImage!!.interval(maskInterval), currentMask.volatileViewerImg.source.interval(maskInterval))
                    .multiThreaded()
                    .forEachPixel { originalImage, currentImage ->
                        originalImage.isValid = currentImage.isValid
                        originalImage.get().set(currentImage.get())
                    }
                currentMask.updateBackingImages(originalBackingImage!! to originalVolatileBackingImage!!)
            }
        }
    }

    protected lateinit var getImageEmbeddingTask: UtilityTask<OnnxTensor>

    private val predictionQueue = LinkedBlockingQueue<PredictionRequest>(1)

    private data class PredictionRequest(val includePoints: List<Point>, val excludePoints: List<Point>)

    private fun requestPrediction(includePoints: List<Point>, excludePoints: List<Point>) {
        if (predictionTask == null || predictionTask?.isCancelled == true) {
            startPredictionTask()
        }
        val include = MutableList(includePoints.size) { includePoints[it] }
        val exclude = MutableList(excludePoints.size) { excludePoints[it] }
        synchronized(predictionQueue) {
            predictionQueue.clear()
            predictionQueue.put(PredictionRequest(include, exclude))
        }
    }

    private val pngCompleteLock = ReentrantLock()
    private val condition = pngCompleteLock.newCondition()

    private fun getImageEmbeddingTask() {
        Tasks.createTask {
            isBusy = true
            val entityBuilder = MultipartEntityBuilder.create()
            entityBuilder.addBinaryBody("image", predictionImagePngInputStream, ContentType.APPLICATION_OCTET_STREAM, "null")

            val client = HttpClients.createDefault()
            val post = HttpPost(SAM_SERVICE)
            post.entity = entityBuilder.build()

            val response = client.execute(post)
            val entity = response.entity
            EntityUtils.toByteArray(entity).let {
                val decodedEmbedding: ByteArray
                try {
                    decodedEmbedding = Base64.decode(it)
                } catch (e: IllegalArgumentException) {
                    throw HttpException(String(it))
                }
                val directBuffer = ByteBuffer.allocateDirect(decodedEmbedding.size)
                directBuffer.put(decodedEmbedding, 0, decodedEmbedding.size)
                directBuffer.position(0);
                val floatBuffEmbedding = directBuffer.asFloatBuffer()
                floatBuffEmbedding.position(0)
                OnnxTensor.createTensor(ortEnv, floatBuffEmbedding, longArrayOf(1, 256, 64, 64))!!
            }
        }.onEnd {
            isBusy = false
        }.onFailed { _, task ->
            mode?.switchTool(mode.defaultTool)
        }.also {
            getImageEmbeddingTask = it
            it.submit(SAM_TASK_SERVICE)
        }
    }

    private fun Point.scaledPoint(scale: Double): Point {
        return Point((getDoublePosition(0) * scale).toInt(), (getDoublePosition(1) * scale).toInt())
    }

    private fun RealPoint.scaledPoint(scale: Double): RealPoint {
        return RealPoint((getDoublePosition(0) * scale), (getDoublePosition(1) * scale))
    }

    internal var providedEmbedding: OnnxTensor? = null

    private fun startPredictionTask() {
        val maskSource = maskedSource ?: return
        val task = Tasks.createTask { task ->
            val session = createOrtSessionTask.get()
            val embedding = providedEmbedding ?: getImageEmbeddingTask.get()

            while (!task.isCancelled) {
                val (pointsIn, pointsOut) = predictionQueue.take()

                val coordsArray = FloatArray(2 * (pointsIn.size + pointsOut.size))
                val labels = FloatArray(coordsArray.size / 2)
                var idx = 0

                mapOf(pointsIn to 1f, pointsOut to 0f).forEach { (points, label) ->
                    points.forEach {
                        val convertedCoord = convertCoordinate(RealPoint(it.scaledPoint(screenScale)))
                        labels[idx / 2] = label
                        coordsArray[idx++] = convertedCoord.getFloatPosition(0)
                        coordsArray[idx++] = convertedCoord.getFloatPosition(1)
                    }
                }

                val coordsBuffer = FloatBuffer.wrap(coordsArray)
                val onnxCoords = OnnxTensor.createTensor(ortEnv, coordsBuffer, longArrayOf(1, labels.size.toLong(), 2))

                val labelsBuffer = FloatBuffer.wrap(labels.map { it }.toFloatArray())
                val onnxLabels = OnnxTensor.createTensor(ortEnv, labelsBuffer, longArrayOf(1, labels.size.toLong()))

                /* NOTE: This is (height, width) */
                val onnxImgSize = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatArrayOf(imgHeight!!, imgWidth!!)), longArrayOf(2))

                val maskInput = OnnxTensor.createTensor(ortEnv, ByteBuffer.allocateDirect(1 * 1 * 256 * 256 * 4).asFloatBuffer(), longArrayOf(1, 1, 256, 256))
                val hasMaskInput = OnnxTensor.createTensor(ortEnv, ByteBuffer.allocateDirect(4).asFloatBuffer(), longArrayOf(1))
                session.run(
                    mapOf<String, OnnxTensorLike>(
                        "image_embeddings" to embedding,
                        "point_coords" to onnxCoords,
                        "point_labels" to onnxLabels,
                        "orig_im_size" to onnxImgSize,
                        "mask_input" to maskInput,
                        "has_mask_input" to hasMaskInput,
                    )
                ).use {

                    val mask = it.get("masks").get() as OnnxTensor

                    val maskImg = ArrayImgs.floats(mask.floatBuffer.array(), imgWidth!!.toLong(), imgHeight!!.toLong())
                    val predictionMask = Views.addDimension(maskImg, 0, 0)

                    val paintMask = viewerMask!!
                    val predictionMaskInterval = RealPoint(imgWidth!!.toDouble(), imgHeight!!.toDouble())
                        .scaledPoint(1.0 / screenScale)
                        .toPoint()
                        .let { scaledPoint ->
                            paintMask.getScreenInterval(scaledPoint[0], scaledPoint[1])
                        }

                    val filter = Converters.convert(
                        predictionMask as RandomAccessibleInterval<FloatType>,
                        { source, output -> output.set(source.get() >= threshold) },
                        BoolType()
                    )

                    val connectedComponents: RandomAccessibleInterval<UnsignedLongType> = ArrayImgs.unsignedLongs(*predictionMask.dimensionsAsLongArray())
                    ConnectedComponents.labelAllConnectedComponents(
                        filter,
                        connectedComponents,
                        StructuringElement.FOUR_CONNECTED
                    )

                    val componentsUnderPointsIn = pointsIn
                        .map { point -> point.scaledPoint(screenScale) }
                        .filter { point -> filter.getAt(*point.positionAsLongArray(), 0).get() }
                        .map { point -> connectedComponents.getAt(*point.positionAsLongArray(), 0).get() }
                        .toSet()
                    val selectedComponents = Converters.convertRAI(
                        connectedComponents,
                        { source, output -> output.set(source.get() in componentsUnderPointsIn) },
                        BoolType()
                    )

                    val distanceFromSelectedComponents: RandomAccessibleInterval<DoubleType> = ArrayImgs.doubles(*selectedComponents.dimensionsAsLongArray())
                    DistanceTransform.binaryTransform(selectedComponents, distanceFromSelectedComponents, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN, SAM_TASK_SERVICE, 2)

                    val maskAlignedSelectedComponents = selectedComponents
//                    val maskAlignedSelectedComponents = distanceFromSelectedComponents
                        .extendValue(Label.INVALID)
//                        .extendZero()
                        .interpolateNearestNeighbor()
                        .affineReal(
                            AffineTransform3D()
                                .concatenate(Translation3D(*predictionMaskInterval.minAsDoubleArray()))
                                .concatenate(Scale3D(screenScale, screenScale, 2.0).inverse())
                        ).raster().interval(paintMask.viewerImg)


                    val compositeMask = Converters.convertRAI(
                        originalBackingImage, maskAlignedSelectedComponents,
                        { original, overlay, composite ->
                            val overlayVal = overlay.get()
                            composite.set(
//                                if (overlayVal > 0.0 && overlayVal < 5.0) currentLabelToPaint else original.get()
                                if (overlayVal) currentLabelToPaint else original.get()
                            )
                        },
                        UnsignedLongType(Label.INVALID)
                    )

                    val compositeVolatileMask = Converters.convertRAI(
                        originalVolatileBackingImage, maskAlignedSelectedComponents,
                        { original, overlay, composite ->
                            var checkOriginal = false
                            val overlayVal = overlay.get()
//                            if (overlayVal > 0.0 && overlayVal < 5.0) {
                            if (overlayVal) {
                                composite.get().set(currentLabelToPaint)
                                composite.isValid = true
                            } else checkOriginal = true
                            if (checkOriginal) {
                                if (original.isValid) {
                                    composite.set(original)
                                    composite.isValid = true
                                } else composite.isValid = false
                                composite.isValid = true
                            }
                        },
                        VolatileUnsignedLongType(Label.INVALID)
                    )

                    paintMask.updateBackingImages(
                        compositeMask to compositeVolatileMask,
                        writableSourceImages = originalBackingImage to originalVolatileBackingImage
                    )

                    paintMask.requestRepaint(predictionMaskInterval)
                    lastPredictionProperty.set(SamTaskInfo(maskSource, predictionMaskInterval))
                }
            }
        }
        predictionTask = task
        task.submit(SAM_TASK_SERVICE)
    }

    private var imgWidth: Float? = null
    private var imgHeight: Float? = null

    private fun calculateTargetScreenScaleFactor(): Double {
        val currentScreenScale = setViewer!!.renderUnit.screenScalesProperty.get()!![0]
        val (width, height) = setViewer!!.width to setViewer!!.height
        val maxEdge = max(width, height) * currentScreenScale
        return min(currentScreenScale, 1024.0 / maxEdge)
    }

    private fun convertCoordinate(coord: RealPoint): RealPoint {
        val (height, width) = imgHeight!! to imgWidth!!
        val x = coord.getFloatPosition(0)
        val y = coord.getFloatPosition(1)
        val target = 1024
        val scale = target * (1.0 / max(height, width))
        val (scaledWidth, scaledHeight) = ((width * scale) + 0.5).toInt() to ((height * scale) + 0.5).toInt()
        val (scaledX, scaledY) = x * (scaledWidth / width) to y * (scaledHeight / height)

        coord.setPosition(floatArrayOf(scaledX, scaledY))

        return coord
    }

    private fun saveActiveViewerImageFromRenderer() {
        setViewer?.let { viewer ->
            val width = viewer.width
            val height = viewer.height

            val threadGroup = ThreadGroup(this.toString())
            val sharedQueue = SharedQueue(PainteraBaseView.reasonableNumFetcherThreads(), 50)

            val renderUnit = object : RenderUnit(
                threadGroup,
                viewer::getState,
                { Interpolation.NLINEAR },
                AccumulateProjectorARGB.factory,
                sharedQueue,
                30 * 1000000L,
                1,
                Executors.newSingleThreadExecutor()
            ) {

                override fun paint() {
                    val viewerTransform = AffineTransform3D()
                    var timepoint = 0
                    val sacs = mutableListOf<SourceAndConverter<*>>()
                    synchronized(this) {
                        if (renderer != null && renderTarget != null && viewerState.get().isVisible) {
                            val viewerState = viewerState.get()
                            synchronized(viewerState) {
                                viewerState.getViewerTransform(viewerTransform)
                                timepoint = viewerState.timepoint
                                val activeSourceToSkip = activeState?.sourceAndConverter?.spimSource
                                viewerState.sources.forEach {
                                    if (it.spimSource != activeSourceToSkip) {
                                        sacs += it
                                    }
                                }
                            }

                        }

                    }

                    val renderedScreenScaleIndex = renderer.paint(sacs, timepoint, viewerTransform, interpolation, null)
                    if (renderedScreenScaleIndex != -1) {
                        val screenInterval = renderer.lastRenderedScreenInterval
                        val renderTargetRealInterval = renderer.lastRenderTargetRealInterval

                        val image = renderTarget.pendingImage
                        renderResultProperty.set(RenderResult(image, screenInterval, renderTargetRealInterval, renderedScreenScaleIndex))
                    }
                }
            }
            renderUnit.setScreenScales(doubleArrayOf(screenScale))
            renderUnit.setDimensions(width.toLong(), height.toLong())
            renderUnit.renderedImageProperty.addListener { _, _, result ->
                result.image?.let { img ->
                    imgWidth = img.width.toFloat()
                    imgHeight = img.height.toFloat()


                    ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", predictionImagePngOutputStream)
                    predictionImagePngOutputStream.close()
                }
            }
            renderUnit.requestRepaint()
        }
    }

    companion object {
        private object SamPointStyle {
            const val POINT = "sam-point"
            const val INCLUDE = "sam-include-point"
            const val EXCLUDE = "sam-exclude-point"
        }

        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

        private val SAM_TASK_SERVICE = Executors.newCachedThreadPool(
            ThreadFactoryBuilder()
                .setNameFormat("sam-task-%d")
                .setDaemon(true)
                .build()
        )

        private lateinit var ortEnv: OrtEnvironment
        private val createOrtSessionTask = Tasks.createTask {
            ortEnv = OrtEnvironment.getEnvironment()
            val session = ortEnv.createSession(H_ONNX_MODEL)
            session
        }.submit()


        data class SamTaskInfo(val maskedSource: MaskedSource<*, *>, val maskInterval: Interval)
    }
}
