package org.janelia.saalfeldlab.paintera.control.tools.paint

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import bdv.fx.viewer.ViewerPanelFX
import bdv.fx.viewer.render.RenderUnitState
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.application.Platform
import javafx.beans.Observable
import javafx.beans.property.*
import javafx.beans.value.ChangeListener
import javafx.scene.control.ButtonBase
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.MouseEvent.MOUSE_CLICKED
import javafx.scene.input.MouseEvent.MOUSE_MOVED
import javafx.scene.input.ScrollEvent.SCROLL
import javafx.scene.layout.Pane
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import kotlinx.coroutines.*
import net.imglib2.FinalInterval
import net.imglib2.Interval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.RealInterval
import net.imglib2.algorithm.labeling.ConnectedComponents
import net.imglib2.algorithm.labeling.ConnectedComponents.StructuringElement
import net.imglib2.converter.Converters
import net.imglib2.histogram.Real1dBinMapper
import net.imglib2.img.array.ArrayImgs
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory
import net.imglib2.iterator.IntervalIterator
import net.imglib2.loops.LoopBuilder
import net.imglib2.realtransform.*
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.type.volatiles.VolatileFloatType
import net.imglib2.type.volatiles.VolatileUnsignedLongType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.apache.commons.io.output.NullPrintStream
import org.janelia.saalfeldlab.fx.Tasks
import org.janelia.saalfeldlab.fx.UtilityTask
import org.janelia.saalfeldlab.fx.actions.ActionSet.Companion.installActionSet
import org.janelia.saalfeldlab.fx.actions.painteraActionSet
import org.janelia.saalfeldlab.fx.actions.painteraDragActionSet
import org.janelia.saalfeldlab.fx.actions.painteraMidiActionSet
import org.janelia.saalfeldlab.fx.actions.verifyPainteraNotDisabled
import org.janelia.saalfeldlab.fx.extensions.*
import org.janelia.saalfeldlab.fx.midi.MidiButtonEvent
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.DeviceManager
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys
import org.janelia.saalfeldlab.paintera.Paintera
import org.janelia.saalfeldlab.paintera.cache.SamEmbeddingLoaderCache
import org.janelia.saalfeldlab.paintera.cache.SamEmbeddingLoaderCache.createOrtSessionTask
import org.janelia.saalfeldlab.paintera.cache.SamEmbeddingLoaderCache.getSamRenderState
import org.janelia.saalfeldlab.paintera.cache.SamEmbeddingLoaderCache.ortEnv
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.fx.ui.CircleScaleView
import org.janelia.saalfeldlab.fx.ui.GlyphScaleView
import org.janelia.saalfeldlab.paintera.control.modes.NavigationControlMode.waitForEvent
import org.janelia.saalfeldlab.paintera.control.modes.PaintLabelMode
import org.janelia.saalfeldlab.paintera.control.modes.ToolMode
import org.janelia.saalfeldlab.paintera.control.paint.ViewerMask
import org.janelia.saalfeldlab.paintera.control.paint.ViewerMask.Companion.createViewerMask
import org.janelia.saalfeldlab.paintera.control.tools.REQUIRES_ACTIVE_VIEWER
import org.janelia.saalfeldlab.paintera.control.tools.paint.SamPredictor.SamPoint
import org.janelia.saalfeldlab.paintera.control.tools.paint.SamPredictor.SparsePrediction
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.predicate.threshold.Bounds
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.asRealInterval
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.extendBy
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import org.janelia.saalfeldlab.paintera.util.algorithms.otsuThresholdPrediction
import org.janelia.saalfeldlab.util.*
import paintera.net.imglib2.view.BundleView
import java.util.concurrent.CancellationException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue
import kotlin.collections.set
import kotlin.math.*
import kotlin.properties.Delegates


open class SamTool(activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>, mode: ToolMode? = null) : PaintTool(activeSourceStateProperty, mode) {

	override val graphic = { GlyphScaleView(FontAwesomeIconView().also { it.styleClass += "sam-select" }) }
	override val name = "Segment Anything"
	override val keyTrigger = LabelSourceStateKeys.SEGMENT_ANYTHING__TOGGLE_MODE

	override val toolBarButton: ButtonBase
		get() {
			return super.toolBarButton.apply {
				properties[REQUIRES_ACTIVE_VIEWER] = true
			}
		}


	internal var currentLabelToPaint: Long = Label.INVALID
	private val isLabelValid get() = currentLabelToPaint != Label.INVALID

	private val controlModeProperty = SimpleBooleanProperty(false)
	private var controlMode by controlModeProperty.nonnull()

	private val controlPointLabelProperty = SimpleObjectProperty(SamPredictor.SparseLabel.IN)
	private var controlPointLabel: SamPredictor.SparseLabel by controlPointLabelProperty.nonnull()

	override val actionSets by LazyForeignValue({ activeViewerAndTransforms }) {
		mutableListOf(
			*super.actionSets.toTypedArray(),
			*getSamActions().filterNotNull().toTypedArray(),
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

	//TODO Caleb: document this; it stops `cleanup()` from removing the wrapped overlay of the prediction
	var unwrapResult = true

	private var setViewer: ViewerPanelFX? = null

	private val imgWidth: Int
		get() = ceil(setViewer!!.width * screenScale).toInt()
	private val imgHeight: Int
		get() = ceil(setViewer!!.height * screenScale).toInt()

	internal var viewerMask: ViewerMask? = null
		get() {
			if (field == null) {
				field = maskedSource!!.createViewerMask(
					MaskInfo(0, setViewer!!.state.bestMipMapLevel),
					setViewer!!
				)
				originalBackingImage = field?.viewerImg?.wrappedSource
				originalWritableBackingImage = field?.viewerImg?.writableSource
				originalVolatileBackingImage = field?.volatileViewerImg?.wrappedSource
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
			originalBackingImage = field?.viewerImg?.wrappedSource
			originalWritableBackingImage = field?.viewerImg?.writableSource
			originalVolatileBackingImage = field?.volatileViewerImg?.wrappedSource
			originalWritableVolatileBackingImage = field?.volatileViewerImg?.writableSource
		}

	private var predictionTask: UtilityTask<Unit>? = null

	internal val lastPredictionProperty = SimpleObjectProperty<SamTaskInfo?>(null)
	var lastPrediction by lastPredictionProperty.nullable()
		private set

	var temporaryPrompt = true

	private var thresholdBounds = Bounds(-40.0, 30.0)
	private var threshold = 0.0
		set(value) {
			field = value.coerceIn(thresholdBounds.min, thresholdBounds.max)
		}

	init {
		setCursorWhenDoneApplying = ChangeListener { observable, _, _ ->
			observable.removeListener(setCursorWhenDoneApplying)
		}
	}

	private val isBusyProperty = SimpleBooleanProperty(false)

	private var isBusy by isBusyProperty.nonnull()

	private var screenScale by Delegates.notNull<Double>()

	private val predictionQueue = LinkedBlockingQueue<Pair<SamPredictor.PredictionRequest, Boolean>>(1)

	private var currentPredictionRequest: Pair<SamPredictor.PredictionRequest, Boolean>? = null
		set(value) = synchronized(predictionQueue) {
			predictionQueue.clear()
			value?.let { (request, _) ->
				predictionQueue.put(value)
				if (!temporaryPrompt)
					request.drawPrompt()
			}
			field = value
		}

	internal lateinit var renderState: RenderUnitState

	override fun activate() {
		mode?.apply {
			modeActionsBar.show(false)
			modeToolsBar.show(false)
			toolActionsBar
		}
		super.activate()
		if (mode is PaintLabelMode) {
			PaintLabelMode.disableUnfocusedViewers()
		}
		controlMode = false
		controlPointLabel = SamPredictor.SparseLabel.IN
		initializeSam()
		/* Trigger initial prediction request when activating the tool */
		setViewer?.let { viewer ->
			if (maskProvided || !viewer.isMouseInside) return@let

			Platform.runLater { statusProperty.set("Predicting...") }
			val x = viewer.mouseXProperty.get().toLong()
			val y = viewer.mouseYProperty.get().toLong()

			temporaryPrompt = true
			requestPrediction(listOf(SamPoint(x * screenScale, y * screenScale, SamPredictor.SparseLabel.IN)))
		}
	}

	override fun deactivate() {
		cleanup()
		if (mode is PaintLabelMode) {
			PaintLabelMode.enableAllViewers()
		}
		super.deactivate()
	}

	internal fun initializeSam(renderUnitState: RenderUnitState? = null) {
		unwrapResult = true
		controlMode = false
		threshold = 0.0
		setCurrentLabelToSelection()
		statePaintContext?.selectedIds?.apply { addListener(selectedIdListener) }
		setViewer = activeViewer //TODO Caleb: We should try not to use the viewer directly
		screenScale = renderUnitState?.calculateTargetScreenScaleFactor() ?: calculateTargetScreenScaleFactor(setViewer!!)
		statusProperty.set("Preparing SAM")
		paintera.baseView.disabledPropertyBindings[this] = isBusyProperty
		renderState = renderUnitState ?: activeViewer!!.getSamRenderState()
		embeddingRequest = SamEmbeddingLoaderCache.request(renderState)
	}

	internal fun cleanup() {
		clearPromptDrawings()
		currentLabelToPaint = Label.INVALID
		predictionTask?.cancel()
		predictionTask = null
		if (unwrapResult) {
			if (!maskProvided) {
				maskedSource?.resetMasks()
			} else {
				currentViewerMask?.updateBackingImages(
					originalBackingImage!! to originalVolatileBackingImage!!,
					originalWritableBackingImage!! to originalWritableVolatileBackingImage!!
				)
			}
		}
		setViewer?.children?.removeIf { SAM_POINT_STYLE in it.styleClass }
		paintera.baseView.disabledPropertyBindings -= this
		lastPrediction?.maskInterval?.let { currentViewerMask?.requestRepaint(it) }
		viewerMask = null
		controlMode = false
		controlPointLabel = SamPredictor.SparseLabel.IN
		embeddingRequest = null
	}

	protected open fun setCurrentLabelToSelection() {
		currentLabelToPaint = statePaintContext?.paintSelection?.invoke() ?: Label.INVALID
	}

	private fun getSamActions() = arrayOf(
		painteraActionSet("sam selections", PaintActionType.Paint, ignoreDisable = true) {
			/* Handle Painting */
			MOUSE_CLICKED(MouseButton.PRIMARY) {
				name = "apply last segmentation result to canvas"
				verifyEventNotNull()
				verifyPainteraNotDisabled()
				verify("cannot be in control mode") { !controlMode }
				verify(" label is not valid ") { isLabelValid }
				onAction { applyPrediction() }
			}
			KEY_PRESSED(KeyCode.D) {
				name = "view prediction"
				verifyEventNotNull()
				verifyPainteraNotDisabled()
				verify { Paintera.debugMode }
				verify("no current prediction ") { currentPrediction != null }
				var toggle = true
				onAction {

					val highResPrediction = currentPrediction!!.image
					val lowResPrediction = currentPrediction!!.lowResImage

					val name: String
					val maskRai = if (toggle) {
						toggle = false
						name = "high res"
						highResPrediction
					} else {
						toggle = true
						name = "low res"
						lowResPrediction
					}

					val (max, mean, std) = maskRai.let {
						var sum = 0.0
						var sumSquared = 0.0
						var max = Float.MIN_VALUE
						it.forEach { float ->
							val floatVal = float.get()
							sum += floatVal
							sumSquared += floatVal.pow(2)
							if (max < floatVal) max = floatVal
						}
						val area = Intervals.numElements(it)
						val mean = sum / area
						val stddev = sqrt(sumSquared / area - mean.pow(2))
						doubleArrayOf(max.toDouble(), mean, stddev)
					}
					val min = (mean - std).toFloat()
					val zeroMinValue = maskRai.convert(FloatType()) { input, output -> output.set(input.get() - min) }
					val predictionSource = paintera.baseView.addConnectomicsRawSource<FloatType, VolatileFloatType>(
						zeroMinValue.let {
							val prediction3D = Views.addDimension(it)
							val interval3D = Intervals.createMinMax(*it.minAsLongArray(), 0, *it.maxAsLongArray(), 0)
							prediction3D.interval(interval3D)
						},
						doubleArrayOf(1.0, 1.0, 1.0),
						doubleArrayOf(0.0, 0.0, 0.0),
						0.0, max - min,
						"$name prediction"
					)

					val transform = object : AffineTransform3D() {
						override fun set(value: Double, row: Int, column: Int) {
							super.set(value, row, column)
							predictionSource.backend.updateTransform(this)
							setViewer!!.requestRepaint()
						}
					}

					viewerMask!!.getInitialGlobalViewerInterval(setViewer!!.width, setViewer!!.height).also {

						val width = it.realMax(0) - it.realMin(0)
						val height = it.realMax(1) - it.realMin(1)
						val depth = it.realMax(2) - it.realMin(2)
						transform.set(
							*AffineTransform3D()
								.concatenate(Translation3D(it.realMin(0), it.realMin(1), it.realMin(2)))
								.concatenate(Scale3D(width / maskRai.shape()[0], height / maskRai.shape()[1], depth))
								.concatenate(Translation3D(.5, .5, 0.0)) //half-pixel offset
								.inverse()
								.rowPackedCopy
						)
					}
					predictionSource.backend.updateTransform(transform)
					predictionSource.composite = ARGBCompositeAlphaAdd()
					setViewer!!.requestRepaint()
				}
			}

			val controlPointLabelToggleGroup = ToggleGroup().also {
				it.selectedToggleProperty().addListener { _, _, selected ->
					controlMode = selected != null
				}
			}
			KEY_PRESSED(KeyCode.CONTROL) {
				val iconClsBinding = controlModeProperty.createNonNullValueBinding { if (it) "toggle-on" else "toggle-off" }
				val iconCls by iconClsBinding.nonnullVal()
				graphic = {
					val icon = FontAwesomeIconView().also {
						it.styleClass.addAll(iconCls)
						it.id = iconCls
						"asdfasd f".stripLeading()
						iconClsBinding.addListener { _, old, new ->
							it.styleClass.removeAll(old)
							it.styleClass.add(new)
						}
					}
					GlyphScaleView(icon).apply {
						styleClass += "ignore-disable"
					}
				}
				onAction { event ->
					if (event != null) {
						/* If triggered by key down, always on when down*/
						controlMode = true
						controlPointLabelToggleGroup.selectedToggle?.isSelected = false
						controlPointLabelToggleGroup.selectToggle(null)
						controlPointLabel = SamPredictor.SparseLabel.IN
					} else {
						/* If triggered programmatically, negate the current state */
						controlMode = !controlMode
						if (!controlMode) {
							controlPointLabel = SamPredictor.SparseLabel.IN
							resetPromptAndPrediction()
							controlPointLabelToggleGroup.selectedToggle?.isSelected = false
							controlPointLabelToggleGroup.selectToggle(null)
						}
					}
				}
			}
			KEY_RELEASED(KeyCode.CONTROL) {
				onAction { controlMode = false }
			}

			SCROLL {
				verify("scroll size at least 1 pixel") { max(it!!.deltaX.absoluteValue, it.deltaY.absoluteValue) > 1.0 }
				verify { controlMode }
				verifyEventNotNull()
				verifyPainteraNotDisabled()
				onAction { scroll ->
					/* ScrollEvent deltas are internally multiplied to correspond to some estimate of pixels-per-unit-scroll.
					* For example, on the platform I'm using now, it's `40` for both x and y. But our threshold is NOT
					* in pixel units, so we divide by the multiplier, and specify our own.  */
					val delta = with(scroll!!) {
						when {
							deltaY.absoluteValue > deltaX.absoluteValue -> deltaY / multiplierY
							else -> deltaX / multiplierX
						}
					}
					val increment = (thresholdBounds.max - thresholdBounds.min) / 100.0
					threshold += delta * increment
					(currentPredictionRequest?.first as? SparsePrediction)?.points?.let {
						requestPrediction(it, false)
					}
				}
			}

			MOUSE_MOVED {
				name = "prediction overlay"
				verifyEventNotNull()
				verifyPainteraNotDisabled()
				verify("Must be a temporary prompt, or not in controlMode ") { temporaryPrompt || !controlMode }
				verify("Label is not valid") { isLabelValid }
				onAction {
					clearPromptDrawings()
					temporaryPrompt = true
					requestPrediction(listOf(SamPoint(it!!.x * screenScale, it.y * screenScale, SamPredictor.SparseLabel.IN)))
				}
			}

			/* Handle Include Points */
			MOUSE_CLICKED(MouseButton.PRIMARY) {
				name = "add include point"
				graphic = {
					val circle = Circle().apply {
						styleClass += "sam-point-in"
					}
					CircleScaleView(circle).apply {
						styleClass += listOf("sam-point", "ignore-disable")
						properties["TOGGLE_GROUP"] = controlPointLabelToggleGroup
					}
				}
				verifyPainteraNotDisabled()
				verify { controlMode || it == null }
				onAction {
					CoroutineScope(Dispatchers.IO).launch {
						/* If no event, triggered via button; enter control mode, set Label to IN */
						it ?: let {
							if ((controlPointLabelToggleGroup.selectedToggle as? ToggleButton)?.id == name) {
								controlPointLabel = SamPredictor.SparseLabel.IN
								controlMode = true
							} else {
								resetPromptAndPrediction()
								return@launch
							}
						}

						/*If not in control mode, Label for this Action is always IN*/
						if (!controlMode)
							controlPointLabel = SamPredictor.SparseLabel.IN

						/* If no event, triggered via button, wait for click before continuing */
						(it ?: viewerMask!!.viewer.waitForEvent<MouseEvent>(MOUSE_CLICKED))?.let { event ->
							val points = currentPredictionRequest?.first.addPoints(SamPoint(event.x * screenScale, event.y * screenScale, controlPointLabel))
							temporaryPrompt = false
							requestPrediction(points)
						}
					}
				}
			}

			MOUSE_CLICKED(MouseButton.SECONDARY) {
				name = "add exclude point"
				graphic = {
					val circle = Circle().apply {
						styleClass += "sam-point-out"
					}
					CircleScaleView(circle).apply {
						styleClass += listOf("sam-point", "ignore-disable")
						properties["TOGGLE_GROUP"] = controlPointLabelToggleGroup
					}
				}
				verifyPainteraNotDisabled()
				verify { controlMode || it == null }
				onAction {
					CoroutineScope(Dispatchers.IO).launch {
						it ?: let {
							if ((controlPointLabelToggleGroup.selectedToggle as? ToggleButton)?.id == name) {
								controlPointLabel = SamPredictor.SparseLabel.OUT
								controlMode = true
							} else {
								resetPromptAndPrediction()
								return@launch
							}
						}

						/* If no event, triggered via button, wait for click before continuing */
						(it ?: viewerMask!!.viewer.waitForEvent<MouseEvent>(MOUSE_CLICKED))?.let { event ->
							val points = currentPredictionRequest?.first.addPoints(SamPoint(event.x * screenScale, event.y * screenScale, SamPredictor.SparseLabel.OUT))
							temporaryPrompt = false
							requestPrediction(points)
						}
					}
				}
			}

			KEY_PRESSED(KeyCode.DELETE, KeyCode.BACK_SPACE) {
				name = "Reset Prompt"
				graphic = { GlyphScaleView(FontAwesomeIconView(FontAwesomeIcon.REFRESH).apply { styleClass += "reset" }) }
				onAction {
					resetPromptAndPrediction()
				}
			}

			KEY_PRESSED(KeyCode.ENTER) {
				name = "apply last segmentation result to canvas"
				graphic = { GlyphScaleView(FontAwesomeIconView().apply { styleClass += "accept" }) }
				verifyPainteraNotDisabled()
				verify(" label is not valid ") { isLabelValid }
				onAction {
					applyPrediction()
					currentPredictionRequest = null
				}
			}

			KEY_PRESSED(LabelSourceStateKeys.CANCEL) {
				name = "exit SAM tool"
				graphic = { GlyphScaleView(FontAwesomeIconView().apply { styleClass += "reject" }).apply {styleClass += "ignore-disable"} }
				onAction { mode?.switchTool(mode.defaultTool) }
			}
		},

		DeviceManager.xTouchMini?.let { device ->
			activeViewerProperty.get()?.viewer()?.let { viewer ->
				painteraMidiActionSet("midi sam tool actions", device, viewer, PaintActionType.Paint) {
					MidiButtonEvent.BUTTON_PRESED(8) {
						onAction { controlMode = true }
					}
					MidiButtonEvent.BUTTON_RELEASED(8) {
						onAction { controlMode = false }
					}
				}
			}
		},

		painteraDragActionSet("box prediction request", PaintActionType.Paint, ignoreDisable = true, consumeMouseClicked = true) {
			onDrag { mouse ->
				val (minX, maxX) = (if (startX < mouse.x) startX to mouse.x else mouse.x to startX)
				val (minY, maxY) = (if (startY < mouse.y) startY to mouse.y else mouse.y to startY)

				val topLeft = SamPoint(minX * screenScale, minY * screenScale, SamPredictor.SparseLabel.TOP_LEFT_BOX)
				val bottomRight = SamPoint(maxX * screenScale, maxY * screenScale, SamPredictor.SparseLabel.BOTTOM_RIGHT_BOX)
				val points = setBoxPrompt(topLeft, bottomRight)
				temporaryPrompt = false
				requestPrediction(points)
			}
		}
	)

	private fun resetPromptAndPrediction() {
		clearPromptDrawings()
		currentPredictionRequest = null
		viewerMask?.apply {
			val newImgs = newBackingImages()
			updateBackingImages(newImgs, newImgs)
			requestRepaint()
		}
	}

	private fun SamPredictor.PredictionRequest?.addPoints(vararg newPoints: SamPoint): List<SamPoint> {
		return (this as? SparsePrediction)?.points?.let { oldPoints ->
			if (!temporaryPrompt)
				oldPoints.toMutableList().also { it.addAll(newPoints) }
			else null
		} ?: newPoints.toMutableList()
	}

	private fun SamPredictor.PredictionRequest?.removePoints(vararg removePoints: SamPoint, removeBox: Boolean = false): MutableList<SamPoint> {
		if (temporaryPrompt) {
			return mutableListOf()
		}
		return (this as? SparsePrediction)?.points
			?.filterNot { it in removePoints || removeBox && it.label > SamPredictor.SparseLabel.IN }
			?.toMutableList() ?: mutableListOf()
	}

	fun setBoxPrompt(topLeft: SamPoint, bottomRight: SamPoint): List<SamPoint> {
		return currentPredictionRequest?.first.removePoints(removeBox = true).also {
			it += topLeft
			it += bottomRight
		}
	}

	fun setBoxPromptFromImageInterval(box: RealInterval): List<SamPoint> {
		val (minX, minY) = box.minAsDoubleArray()
		val (maxX, maxY) = box.maxAsDoubleArray()
		val topLeft = SamPoint(minX * screenScale, minY * screenScale, SamPredictor.SparseLabel.TOP_LEFT_BOX)
		val bottomRight = SamPoint(maxX * screenScale, maxY * screenScale, SamPredictor.SparseLabel.BOTTOM_RIGHT_BOX)
		return setBoxPrompt(topLeft, bottomRight)
	}

	fun addPointFromImagePosition(imgX: Double, imgY: Double, label: SamPredictor.SparseLabel): List<SamPoint> {
		val samPoint = SamPoint(imgX * screenScale, imgY * screenScale, label)
		return currentPredictionRequest?.first.addPoints(samPoint)
	}

	private fun Pane.drawCircle(point: SamPoint) {
		InvokeOnJavaFXApplicationThread {
			children += Circle(5.0).apply {
				translateX = point.x / screenScale - width / 2
				translateY = point.y / screenScale - height / 2
				styleClass += SamPointStyle[point.label]

				/* If clicked again, remove it */
				painteraActionSet("remove-circle", ignoreDisable = true) {
					MOUSE_CLICKED {
						onAction {
							children -= this@apply
							requestPrediction(currentPredictionRequest?.first.removePoints(point))
						}
					}
				}.also { installActionSet(it) }
			}
		}
	}

	private fun SamPredictor.PredictionRequest.drawPrompt() {
		clearPromptDrawings()
		when (this) {
			is SparsePrediction -> drawPromptPoints(points)
			is SamPredictor.MaskPrediction -> LOG.warn { "MaskPrediction for SAM not currently implemented" }
		}
	}

	private fun drawPromptPoints(points: List<SamPoint>) {
		setViewer?.apply {
			points.filter { point ->
				/* Draw circles for IN and OUT points */
				if (point.label > SamPredictor.SparseLabel.IN) {
					true
				} else {
					drawCircle(point)
					false
				}
			}.toList().let { boxPoints ->
				/* draw box if present */
				val topLeft = boxPoints.find { it.label == SamPredictor.SparseLabel.TOP_LEFT_BOX }
				val bottomRight = boxPoints.find { it.label == SamPredictor.SparseLabel.BOTTOM_RIGHT_BOX }
				if (topLeft != null && bottomRight != null) {
					drawBox(topLeft, bottomRight)
				}
			}
		}
	}

	private fun clearPromptDrawings() {
		setViewer?.let { viewer ->
			InvokeOnJavaFXApplicationThread {
				viewer.children.removeIf {
					it.styleClass.any { style -> style == SAM_POINT_STYLE || style == SAM_BOX_OVERLAY_STYLE }
				}
			}
		}
	}

	private fun Pane.drawBox(topLeft: SamPoint, bottomRight: SamPoint) {
		InvokeOnJavaFXApplicationThread {
			val boxOverlay = children
				.filterIsInstance<Rectangle>()
				.firstOrNull { it.styleClass.contains(SAM_BOX_OVERLAY_STYLE) }
				?: Rectangle().also {
					it.isMouseTransparent = true
					it.styleClass += SAM_BOX_OVERLAY_STYLE
					children += it
				}

			val maxX = bottomRight.x / screenScale
			val maxY = bottomRight.y / screenScale
			val minX = topLeft.x / screenScale
			val minY = topLeft.y / screenScale
			boxOverlay.width = maxX - minX
			boxOverlay.height = maxY - minY
			boxOverlay.translateX = maxX - (boxOverlay.width + width) / 2
			boxOverlay.translateY = maxY - (boxOverlay.height + height) / 2
		}
	}


	open fun applyPrediction() {
		lastPrediction?.submitPrediction()
		clearPromptDrawings()
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
					.setImages(originalWritableBackingImage!!.interval(maskInterval), currentMask.viewerImg.wrappedSource.interval(maskInterval))
					.multiThreaded()
					.forEachPixel { originalImage, currentImage ->
						originalImage.set(currentImage.get())
					}
				LoopBuilder
					.setImages(originalWritableVolatileBackingImage!!.interval(maskInterval), currentMask.volatileViewerImg.wrappedSource.interval(maskInterval))
					.multiThreaded()
					.forEachPixel { originalImage, currentImage ->
						originalImage.isValid = currentImage.isValid
						originalImage.get().set(currentImage.get())
					}
				currentMask.updateBackingImages(originalBackingImage!! to originalVolatileBackingImage!!)
			}
		}
	}

	fun requestPrediction(predictionRequest: SamPredictor.PredictionRequest, estimateThreshold: Boolean = true) {
		when (predictionRequest) {
			is SparsePrediction -> {
				requestPrediction(predictionRequest.points, estimateThreshold)
			}

			is SamPredictor.MaskPrediction -> LOG.warn { "Mask Prediction not supported in SAM tool" }
		}

	}

	open fun requestPrediction(promptPoints: List<SamPoint>, estimateThreshold: Boolean = true) {
		if (predictionTask == null || predictionTask?.isCancelled == true) {
			startPredictionTask()
		}
		currentPredictionRequest = SamPredictor.points(promptPoints.toList()) to estimateThreshold
	}

	enum class MaskPriority {
		MASK,
		PREDICTION
	}

	var maskPriority = MaskPriority.PREDICTION

	private var embeddingRequest: Deferred<OnnxTensor>? = null

	private var currentPrediction: SamPredictor.SamPrediction? = null

	private fun startPredictionTask() {
		val maskSource = maskedSource ?: return
		val task = Tasks.createTask { task ->
			val session = createOrtSessionTask.get()
			val imageEmbedding = try {
				runBlocking {
					isBusy = true
					embeddingRequest!!.await()
				}
			} catch (e: InterruptedException) {
				if (!task.isCancelled) throw e
				return@createTask
			} catch (e: CancellationException) {
				return@createTask
			} finally {
				isBusy = false
			}
			val predictor = SamPredictor(ortEnv, session, imageEmbedding, imgWidth to imgHeight)
			while (!task.isCancelled) {
				val predictionPair = predictionQueue.take()
				val (predictionRequest, estimateThreshold) = predictionPair
				val points = (predictionRequest as SparsePrediction).points

				val newPredictionRequest = estimateThreshold || currentPrediction == null
				if (newPredictionRequest) {
					currentPrediction = runPredictionWithRetry(predictor, predictionRequest)
				}
				val prediction = currentPrediction!!
				val predictionLabel = currentLabelToPaint

				if (estimateThreshold) {
					/* If there is only a box (no points) then use the sub-interval of the box to estimate the threshold.
					*   In all other cases, estimate threshold based on the entire image. */
					val estimateOverBox = if (points.all { it.label > SamPredictor.SparseLabel.IN }) intervalOfBox(points, prediction) else null
					setBestEstimatedThreshold(estimateOverBox)
				}

				val paintMask = viewerMask!!

				val minPoint = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE)
				val maxPoint = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)

				val predictedImage = currentPrediction!!.image

				var noneAccepted = true
				val thresholdFilter = Converters.convert(
					BundleView(predictedImage),
					{ predictionMaskRA, output ->
						val predictionType = predictionMaskRA.get()
						val predictionValue = predictionType.get()
						val accept = predictionValue >= threshold
						output.set(accept)
						if (accept) {
							noneAccepted = false
							val pos = predictionMaskRA.positionAsLongArray()
							minPoint[0] = min(minPoint[0], pos[0])
							minPoint[1] = min(minPoint[1], pos[1])

							maxPoint[0] = max(maxPoint[0], pos[0])
							maxPoint[1] = max(maxPoint[1], pos[1])
						}
					},
					BoolType()
				)

				val connectedComponents: RandomAccessibleInterval<UnsignedLongType> = ArrayImgs.unsignedLongs(*predictedImage.dimensionsAsLongArray())
				/* FIXME: This is annoying, but I don't see a better way around it at the moment.
				*   `labelAllConnectedComponents` can be interrupted, but doing so causes an
				*   internal method to `printStackTrace()` on the error. So even when
				*   It's intentionally and interrupted and handeled, the consol still logs the
				*   stacktrace to stderr. We temporarily wrap stderr to swalleow it.
				*   When [https://github.com/imglib/imglib2-algorithm/issues/98] is resolved,
				*   hopefully this will be as well */
				val stdErr = System.err
				System.setErr(NullPrintStream())
				try {
					ConnectedComponents.labelAllConnectedComponents(
						thresholdFilter,
						connectedComponents,
						StructuringElement.FOUR_CONNECTED
					)
				} catch (e: InterruptedException) {
					System.setErr(stdErr)
					LOG.debug(e) { "Connected Components Interrupted During SAM" }
					task.cancel()
					continue
				} finally {
					System.setErr(stdErr)
				}


				val previousPredictionInterval = lastPrediction?.maskInterval?.extendBy(1.0)?.smallestContainingInterval
				if (noneAccepted) {
					paintMask.requestRepaint(previousPredictionInterval)
					lastPrediction = null
					continue
				}
				val acceptedComponents = points.asSequence()
					.filter { it.label == SamPredictor.SparseLabel.IN }
					.map { it.x.toLong() to it.y.toLong() }
					.filter { (x, y) -> thresholdFilter.getAt(x, y).get() }
					.map { (x, y) -> connectedComponents.getAt(x, y).get() }
					.toMutableSet()

				points.firstOrNull { it.label == SamPredictor.SparseLabel.TOP_LEFT_BOX }?.let { topLeft ->
					points.firstOrNull { it.label == SamPredictor.SparseLabel.BOTTOM_RIGHT_BOX }?.let { bottomRight ->

						val minPos = longArrayOf(topLeft.x.toLong(), topLeft.y.toLong())
						val maxPos = longArrayOf(bottomRight.x.toLong(), bottomRight.y.toLong())
						val boxIterator = IntervalIterator(FinalInterval(minPos, maxPos))
						val posInBox = LongArray(2)
						while (boxIterator.hasNext()) {
							boxIterator.fwd()
							boxIterator.localize(posInBox)
							if (thresholdFilter.getAt(*posInBox).get()) {
								acceptedComponents += connectedComponents.getAt(*posInBox).get()
							}
						}
					}
				}

				val selectedComponents = Converters.convertRAI(
					connectedComponents,
					{ source, output ->
						output.set(if (source.get() in acceptedComponents) 1.0f else 0.0f)
					},
					FloatType()
				)

				val (width, height) = predictedImage.dimensionsAsLongArray()
				val predictionToViewerScale = Scale2D(setViewer!!.width / width, setViewer!!.height / height)
				val halfPixelOffset = Translation2D(.5, .5)
				val translationToViewer = Translation2D(*paintMask.displayPointToInitialMaskPoint(0, 0).positionAsDoubleArray())
				val predictionToViewerTransform = AffineTransform2D().concatenate(translationToViewer).concatenate(predictionToViewerScale).concatenate(halfPixelOffset)
				val maskAlignedSelectedComponents = selectedComponents
					.extendValue(0.0)
					.interpolate(NLinearInterpolatorFactory())
					.affineReal(predictionToViewerTransform)
					.convert(UnsignedLongType(Label.INVALID)) { source, output -> output.set(if (source.get() > .8) predictionLabel else Label.INVALID) }
					.addDimension()
					.raster()
					.interval(paintMask.viewerImg)


				val compositeMask = originalBackingImage!!
					.extendValue(Label.INVALID)
					.convertWith(maskAlignedSelectedComponents, UnsignedLongType(Label.INVALID)) { original, prediction, composite ->
						val getCompositeVal = {
							val predictedVal = prediction.get()
							if (predictedVal == predictionLabel) predictionLabel else original.get()
						}
						val compositeVal =
							if (maskPriority == MaskPriority.PREDICTION) getCompositeVal()
							else original.get().takeIf { it != Label.INVALID } ?: getCompositeVal()
						composite.set(compositeVal)
					}.interval(maskAlignedSelectedComponents)


				val compositeVolatileMask = originalVolatileBackingImage!!
					.extendValue(VolatileUnsignedLongType(Label.INVALID))
					.convertWith(maskAlignedSelectedComponents, VolatileUnsignedLongType(Label.INVALID)) { original, overlay, composite ->
						val setCompositeVal = {
							var checkOriginal = false
							val overlayVal = overlay.get()
							if (overlayVal == predictionLabel) {
								composite.get().set(predictionLabel)
								composite.isValid = true
							} else checkOriginal = true
							if (checkOriginal) {
								if (original.isValid) {
									composite.set(original)
									composite.isValid = true
								} else composite.isValid = false
								composite.isValid = true
							}
						}
						if (maskPriority == MaskPriority.PREDICTION || !original.isValid) {
							setCompositeVal()
						} else {
							val originalVal = original.get().get()
							if (originalVal != Label.INVALID) {
								composite.set(originalVal)
								composite.isValid = true
							} else setCompositeVal()
						}
					}.interval(maskAlignedSelectedComponents)

				paintMask.updateBackingImages(
					compositeMask to compositeVolatileMask,
					writableSourceImages = originalBackingImage to originalVolatileBackingImage
				)

				val predictionInterval3D = Intervals.createMinMax(*minPoint, 0, *maxPoint, 0)
				val predictionIntervalInViewerSpace = predictionToViewerTransform.estimateBounds(predictionInterval3D).smallestContainingInterval

				paintMask.requestRepaint(predictionIntervalInViewerSpace union previousPredictionInterval)
				lastPrediction = SamTaskInfo(maskSource, predictionIntervalInViewerSpace, imageEmbedding, predictionRequest)
			}
		}
		predictionTask = task
		task.submit(SAM_TASK_SERVICE)
	}

	private fun setBestEstimatedThreshold(interval: Interval? = null) {
		/* [-40..30] seems from testing like a reasonable range to include the vast majority of
		*  prediction values, excluding perhaps some extreme outliers (which imo is desirable) */
		val binMapper = Real1dBinMapper<FloatType>(-40.0, 30.0, 256, false)
		val histogram = LongArray(binMapper.binCount.toInt())

		val threshPredictInterval = interval?.intersect(currentPrediction?.image)?.let { intersection ->
			if (Intervals.isEmpty(intersection)) null else intersection
		}

		val predictionRAI = threshPredictInterval?.let { currentPrediction!!.image.interval(it) } ?: currentPrediction!!.image
		LoopBuilder.setImages(predictionRAI)
			.forEachPixel {
				val binIdx = binMapper.map(it).toInt()
				if (binIdx != -1)
					histogram[binIdx]++
			}


		val binVar = FloatType()
		val minThreshold = histogram.indexOfFirst { it > 0 }.let {
			if (it == -1) return@let thresholdBounds.min
			binMapper.getLowerBound(it.toLong(), binVar)
			binVar.get().toDouble()
		}
		val maxThreshold = histogram.indexOfLast { it > 0 }.let {
			if (it == -1) return@let thresholdBounds.max
			binMapper.getUpperBound(it.toLong(), binVar)
			binVar.get().toDouble()
		}
		val otsuIdx = otsuThresholdPrediction(histogram)
		binMapper.getUpperBound(otsuIdx, binVar)

		thresholdBounds = Bounds(minThreshold, maxThreshold)
		threshold = binVar.get().toDouble()
	}

	private fun intervalOfBox(points: List<SamPoint>, samPrediction: SamPredictor.SamPrediction, lowRes: Boolean = false): FinalInterval? {
		return points.filter { it.label > SamPredictor.SparseLabel.IN }.let {
			if (it.size == 2) {
				val scale = if (lowRes) samPrediction.lowToHighResScale else 1.0
				val (x1, y1) = it[0].run { (x / scale).toLong() to (y / scale).toLong() }
				val (x2, y2) = it[1].run { (x / scale).toLong() to (y / scale).toLong() }
				FinalInterval(longArrayOf(x1, y1), longArrayOf(x2, y2))
			} else null
		}
	}

	private fun runPredictionWithRetry(predictor: SamPredictor, vararg predictionRequest: SamPredictor.PredictionRequest): SamPredictor.SamPrediction {
		/* FIXME: This is a bit hacky, but works for now until a better solution is found.
		*   Some explenation. When running the SAM predictions, occasionally the following OrtException is thrown:
		*   [E:onnxruntime:, sequential_executor.cc:494 ExecuteKernel]
		*       Non-zero status code returned while running Resize node.
		*       Name:'/Resize_1' Status Message: upsamplebase.h:334 ScalesValidation Scale value should be greater than 0.
		*   This seems to only happen infrequently, and only when installed via conda (not the platform installer, or running from source).
		*   The temporary solution here is to just call it again, recursively, until it succeeds. I have not yet seen this
		*   to be a problem in practice, but ideally it wil be unnecessary in the future. Either by the underlying issue
		*   no longer occuring, or finding a better solution. */
		return try {
			predictor.predict(*predictionRequest)
		} catch (e: OrtException) {
			LOG.trace { "${e.message}" }
			runPredictionWithRetry(predictor, *predictionRequest)
		}
	}

	companion object {

		const val SAM_POINT_STYLE = "sam-point"
		const val SAM_BOX_OVERLAY_STYLE = "sam-box-overlay"

		private enum class SamPointStyle(val styles: Array<String>) {
			Include(arrayOf(SAM_POINT_STYLE, "sam-include")),
			Exclude(arrayOf(SAM_POINT_STYLE, "sam-exclude"));

			companion object {

				operator fun get(label: SamPredictor.SparseLabel) = when (label) {
					SamPredictor.SparseLabel.IN -> Include.styles
					SamPredictor.SparseLabel.OUT -> Exclude.styles
					else -> emptyArray()
				}
			}
		}

		private val LOG = KotlinLogging.logger { }

		internal val SAM_TASK_SERVICE = ForkJoinPool.ForkJoinWorkerThreadFactory { pool: ForkJoinPool ->
			val worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
			worker.isDaemon = true
			worker.priority = 4
			worker.name = "sam-task-" + worker.poolIndex
			worker
		}.let { factory ->
			ForkJoinPool(max(4.0, 4 * (Runtime.getRuntime().availableProcessors()).toDouble()).toInt(), factory, null, false)
		}


	private fun calculateTargetScreenScaleFactor(viewer: ViewerPanelFX): Double {
		val highestScreenScale = viewer.renderUnit.screenScalesProperty.get().max()
		return calculateTargetScreenScaleFactor(viewer.width, viewer.height, highestScreenScale)
	}

		private fun RenderUnitState.calculateTargetScreenScaleFactor(): Double {
			val maxScreenScale = paintera.properties.screenScalesConfig.screenScalesProperty().get().scalesCopy.max()
			return calculateTargetScreenScaleFactor(width.toDouble(), height.toDouble(), maxScreenScale)
		}

		/**
		 * Calculates the target screen scale factor based on the highest screen scale and the viewer's dimensions.
		 * The resulting scale factor will always be the smallest of either:
		 *  1. the highest explicitly specified factor, or
		 *  2. [SamPredictor.MAX_DIM_TARGET] / `max(width, height)`
		 *
		 *  This means if the `scaleFactor * maxEdge` is less than [SamPredictor.MAX_DIM_TARGET] it will be used,
		 *  but if the `scaleFactor * maxEdge` is still larger than [SamPredictor.MAX_DIM_TARGET], then a more
		 *  aggressive scale factor will be returned. See [SamPredictor.MAX_DIM_TARGET] for more information.
		 *
		 * @return The calculated scale factor.
		 */
		private fun calculateTargetScreenScaleFactor(width: Double, height: Double, highestScreenScale: Double): Double {
			val maxEdge = max(ceil(width * highestScreenScale), ceil(height * highestScreenScale))
			return min(highestScreenScale, SamPredictor.MAX_DIM_TARGET / maxEdge)
		}


		data class SamTaskInfo(val maskedSource: MaskedSource<*, *>, val maskInterval: Interval, val embedding: OnnxTensor, val predictionRequest: SamPredictor.PredictionRequest)
	}
}
