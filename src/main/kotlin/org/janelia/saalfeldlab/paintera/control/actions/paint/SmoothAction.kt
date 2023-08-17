package org.janelia.saalfeldlab.paintera.control.actions.paint

import com.google.common.util.concurrent.ThreadFactoryBuilder
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleLongProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.event.EventHandler
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.*
import net.imglib2.FinalInterval
import net.imglib2.FinalRealInterval
import net.imglib2.Interval
import net.imglib2.RealInterval
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss
import net.imglib2.algorithm.convolution.kernel.Kernel1D
import net.imglib2.algorithm.convolution.kernel.SeparableKernelConvolution
import net.imglib2.algorithm.gauss3.Gauss3
import net.imglib2.algorithm.lazy.Lazy
import net.imglib2.cache.img.CachedCellImg
import net.imglib2.converter.Converters
import net.imglib2.img.basictypeaccess.AccessFlags
import net.imglib2.img.cell.CellGrid
import net.imglib2.loops.LoopBuilder
import net.imglib2.parallel.Parallelization
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.view.BundleView
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.Tasks
import org.janelia.saalfeldlab.fx.UtilityTask
import org.janelia.saalfeldlab.fx.actions.Action
import org.janelia.saalfeldlab.fx.extensions.component1
import org.janelia.saalfeldlab.fx.extensions.component2
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.fx.extensions.nonnullVal
import org.janelia.saalfeldlab.fx.ui.NumberField
import org.janelia.saalfeldlab.fx.ui.ObjectField.SubmitOn
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.Paintera
import org.janelia.saalfeldlab.paintera.Style.ADD_GLYPH
import org.janelia.saalfeldlab.paintera.control.actions.paint.SmoothActionVerifiedState.Companion.verifyState
import org.janelia.saalfeldlab.paintera.control.modes.PaintLabelMode
import org.janelia.saalfeldlab.paintera.control.modes.PaintLabelMode.statePaintContext
import org.janelia.saalfeldlab.paintera.control.tools.paint.StatePaintContext
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.data.mask.SourceMask
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.RandomAccessibleIntervalBackend
import org.janelia.saalfeldlab.paintera.state.SourceStateBackendN5
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState
import org.janelia.saalfeldlab.paintera.state.metadata.MultiScaleMetadataState
import org.janelia.saalfeldlab.paintera.ui.FontAwesome
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
import org.janelia.saalfeldlab.util.extendZero
import org.janelia.saalfeldlab.util.intersect
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.union
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.collections.set
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.properties.Delegates
import kotlin.reflect.KMutableProperty0
import net.imglib2.type.label.Label as Imglib2Label


open class MenuAction(val label: String) : Action<Event>(Event.ANY) {


	init {
		keysDown = null
		name = label
	}

	val menuItem by lazy {
		MenuItem(label).also {
			it.onAction = EventHandler { this(it) }
			it.isDisable = isValid(null)
		}
	}

}

class SmoothActionVerifiedState {
	internal lateinit var labelSource: ConnectomicsLabelState<*, *>
	internal lateinit var paintContext: StatePaintContext<*, *>
	internal var mipMapLevel by Delegates.notNull<Int>()


	fun <E : Event> Action<E>.verifyState() {
		verify(::labelSource, "Label Source is Active") { paintera.currentSource as? ConnectomicsLabelState<*, *> }
		verify(::paintContext, "Paint Label Mode has StatePaintContext") { statePaintContext }
		verify(::mipMapLevel, "Viewer is Focused") { paintera.activeViewer.get()?.state?.bestMipMapLevel }
	}

	fun <E : Event, T> Action<E>.verify(property: KMutableProperty0<T>, description: String, stateProvider: () -> T?) {
		verify(description) { stateProvider()?.also { state -> property.set(state) } != null }
	}

	companion object {
		fun <E : Event> Action<E>.verifyState(state: SmoothActionVerifiedState) {
			state.run { verifyState() }
		}
	}
}

object SmoothAction : MenuAction("_Smooth") {

	private fun newConvolutionExecutor(): ThreadPoolExecutor {
		val threads = Runtime.getRuntime().availableProcessors()
		return ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), ThreadFactoryBuilder().setNameFormat("gaussian-smoothing-%d").build())
	}

	private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

	private var scopeJob: Job = Job()
	private var smoothScope: CoroutineScope = CoroutineScope(Dispatchers.Default + scopeJob)
	private var smoothTask: UtilityTask<List<Interval>>? = null
	private var convolutionExecutor = newConvolutionExecutor()

	private val replacementLabelProperty = SimpleLongProperty(0)
	private val replacementLabel by replacementLabelProperty.nonnullVal()

	private val kernelSizeProperty = SimpleDoubleProperty()
	private val kernelSize by kernelSizeProperty.nonnullVal()

	private val progressBarProperty = SimpleDoubleProperty()
	private val currentProgressBar by progressBarProperty.nonnull()

	private val progressProperty = SimpleDoubleProperty()
	private var progress by progressProperty.nonnull()

	private val progressStatusProperty = SimpleObjectProperty(ProgressStatus.Empty)
	private var progressStatus by progressStatusProperty.nonnull()

	private var finalizeSmoothing = false
	private var resmooth = false

	private lateinit var updateSmoothMask: suspend ((Boolean) -> List<RealInterval>)

	private val state = SmoothActionVerifiedState()

	private val SmoothActionVerifiedState.defaultKernelSize: Double
		get() {
			val levelResolution = getLevelResolution(mipMapLevel)
			val min = levelResolution.min()
			val max = levelResolution.max()
			return min + (max - min) / 2.0
		}

	init {
		verifyState(state)
		verify("Paint Label Mode is Active") { paintera.currentMode is PaintLabelMode }
		verify("Paintera is not disabled") { !paintera.baseView.isDisabledProperty.get() }
		verify("Mask is in Use") { !state.paintContext.dataSource.isMaskInUseBinding().get() }
		onAction {
			finalizeSmoothing = false
			/* Set lateinit values */
			with(state) {
				kernelSizeProperty.unbind()
				kernelSizeProperty.set(defaultKernelSize)
				progress = 0.0
				startSmoothTask()
				showSmoothDialog()
			}
		}
	}

	private enum class ProgressStatus(val text: String) {
		Smoothing("Smoothing... "),
		Done("        Done "),
		Applying(" Applying... "),
		Empty("             ")
	}


	private val AffineTransform3D.resolution
		get() = doubleArrayOf(this[0, 0], this[1, 1], this[2, 2])

	private fun SmoothActionVerifiedState.showSmoothDialog() {
		Dialog<Boolean>().apply {
			Paintera.registerStylesheets(dialogPane)
			dialogPane.buttonTypes += ButtonType.APPLY
			dialogPane.buttonTypes += ButtonType.CANCEL
			title = name?.replace("_", "")

			val levelResolution: DoubleArray = getLevelResolution(mipMapLevel)
			val replacementLabelField = NumberField.longField(Imglib2Label.BACKGROUND, { it >= Imglib2Label.BACKGROUND }, *SubmitOn.values())
			replacementLabelProperty.bind(replacementLabelField.valueProperty())

			val nextIdButton = Button().apply {
				styleClass += ADD_GLYPH
				graphic = FontAwesome[FontAwesomeIcon.PLUS, 2.0]
				onAction = EventHandler { replacementLabelField.valueProperty().set(labelSource.idService.next()) }
				tooltip = Tooltip("Next New ID")
			}
			val minRes = levelResolution.min()
			val minKernelSize = floor(minRes / 2)
			val maxKernelSize = levelResolution.max() * 10
			val initialKernelSize = defaultKernelSize
			val kernelSizeSlider = Slider(log10(minKernelSize), log10(maxKernelSize), log10(initialKernelSize))
			val kernelSizeField = NumberField.doubleField(initialKernelSize, { it > 0.0 }, *SubmitOn.values())

			/* slider sets field */
			kernelSizeSlider.valueProperty().addListener { _, _, sliderVal ->
				kernelSizeField.valueProperty().set(10.0.pow(sliderVal.toDouble()).roundToLong().toDouble())
			}
			/* field sets slider*/
			kernelSizeField.valueProperty().addListener { _, _, fieldVal ->
				/* Let the user go over if they want to explicitly type a larger number in the field.
				* otherwise, set the slider */
				if (fieldVal.toDouble() <= maxKernelSize)
					kernelSizeSlider.valueProperty().set(log10(fieldVal.toDouble()))
			}

			val prevStableKernelSize = SimpleDoubleProperty(initialKernelSize)

			val stableKernelSizeBinding = Bindings
				.`when`(kernelSizeSlider.valueChangingProperty().not())
				.then(kernelSizeField.valueProperty())
				.otherwise(prevStableKernelSize)

			/* size property binds field */
			kernelSizeProperty.bind(stableKernelSizeBinding)
			val sizeChangeListener = ChangeListener<Number> { _, _, size -> prevStableKernelSize.value = size.toDouble() }
			kernelSizeProperty.addListener(sizeChangeListener)

			val timeline = Timeline()


			dialogPane.content = VBox(10.0).apply {
				isFillWidth = true
				val replacementIdLabel = Label("Replacement Label")
				val kernelSizeLabel = Label("Kernel Size (phyiscal units)")
				replacementIdLabel.alignment = Pos.BOTTOM_RIGHT
				kernelSizeLabel.alignment = Pos.BOTTOM_RIGHT
				children += HBox(10.0, replacementIdLabel, nextIdButton, replacementLabelField.textField).also {
					it.disableProperty().bind(paintera.baseView.isDisabledProperty)
					it.cursorProperty().bind(paintera.baseView.node.cursorProperty())
				}
				children += Separator(Orientation.HORIZONTAL)
				children += HBox(10.0, kernelSizeLabel, kernelSizeField.textField)
				children += HBox(10.0, kernelSizeSlider)
				children += HBox(10.0).also { hbox ->
					hbox.children += Label().apply {
						progressStatusProperty.addListener { _, _, _ ->
							InvokeOnJavaFXApplicationThread {
								textProperty().set(progressStatus.text)
								requestLayout()
							}
						}
					}
					hbox.children += ProgressBar().also { progressBar ->
						progressBarProperty.unbind()
						progressBarProperty.bind(progressBar.progressProperty())
						HBox.setHgrow(progressBar, Priority.ALWAYS)
						progressBar.maxWidth = Double.MAX_VALUE
						timeline.keyFrames.setAll(
							KeyFrame(Duration.ZERO, KeyValue(progressBar.progressProperty(), 0.0)),
							KeyFrame(Duration.seconds(1.0), KeyValue(progressBar.progressProperty(), progressProperty.get()))
						)
						timeline.play()
						progressBar.progressProperty().addListener { _, _, progress ->
							val progress = progress.toDouble()
							progressStatus = when {
								progress == 0.0 -> ProgressStatus.Empty
								progress == 1.0 -> ProgressStatus.Done
								smoothScope.isActive -> ProgressStatus.Smoothing
								else -> ProgressStatus.Applying
							}
						}
						progressProperty.addListener { _, _, progress ->
							timeline.stop()
							if (progress == 0.0) {
								progressBar.progressProperty().set(0.0)
								return@addListener
							}

							/* Don't move backwards while actively smoothing */
							if (progress.toDouble() <= progressBar.progressProperty().get()) return@addListener

							/* If done, move fast. If not done, move slower if kernel size is larger */
							val duration = if (progress == 1.0) Duration.seconds(.25) else {
								val adjustment = (kernelSize - minKernelSize) / (maxKernelSize - minKernelSize)
								Duration.seconds(1.0 + adjustment)
							}
							timeline.keyFrames.setAll(
								KeyFrame(Duration.ZERO, KeyValue(progressBar.progressProperty(), progressBar.progressProperty().get())),
								KeyFrame(duration, KeyValue(progressBar.progressProperty(), progress.toDouble()))
							)
							timeline.play()
						}
					}
				}
				HBox.setHgrow(kernelSizeLabel, Priority.NEVER)
				HBox.setHgrow(kernelSizeSlider, Priority.ALWAYS)
				HBox.setHgrow(kernelSizeField.textField, Priority.ALWAYS)
				HBox.setHgrow(replacementLabelField.textField, Priority.ALWAYS)
			}
			val cleanupOnDialogClose = {
				smoothTask?.cancel()
				kernelSizeProperty.removeListener(sizeChangeListener)
				kernelSizeProperty.unbind()
				replacementLabelProperty.unbind()
				close()
			}
			dialogPane.lookupButton(ButtonType.APPLY).also { applyButton ->
				applyButton.disableProperty().bind(paintera.baseView.isDisabledProperty)
				applyButton.cursorProperty().bind(paintera.baseView.node.cursorProperty())
				applyButton.addEventFilter(ActionEvent.ACTION) { event ->
					//So the dialog doesn't close until the smoothing is done
					event.consume()
					// but listen for when the smoothTask finishes
					smoothTask?.stateProperty()?.addListener { _, _, state ->
						if (state >= Worker.State.SUCCEEDED)
							cleanupOnDialogClose()
					}
					// indicate the smoothTask should try to apply the current smoothing mask to canvas
					progress = 0.0
					finalizeSmoothing = true
				}
			}
			dialogPane.lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION) { _ -> cleanupOnDialogClose() }
			dialogPane.scene.window.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.ESCAPE && (progressStatus == ProgressStatus.Smoothing || scopeJob.children.any { it.isActive })) {
					/* Cancel if still running */
					event.consume()
					cancelActiveSmoothing("Escape Pressed")
				}
			}
			dialogPane.scene.window.setOnCloseRequest { cleanupOnDialogClose() }
		}.show()
	}

	private fun SmoothActionVerifiedState.getLevelResolution(level: Int): DoubleArray {
		val metadataScales = ((labelSource.backend as? SourceStateBackendN5<*, *>)?.getMetadataState() as? MultiScaleMetadataState)?.scaleTransforms?.get(level)
		val resFromRai = (labelSource.backend as? RandomAccessibleIntervalBackend<*, *>)?.resolutions
		return when {
			level == 0 -> labelSource.resolution
			metadataScales != null -> metadataScales.resolution
			resFromRai != null -> resFromRai[level]
			else -> doubleArrayOf(1.0, 1.0, 1.0)
		}
	}

	private val smoothingProperty = SimpleBooleanProperty("Smoothing", "Smooth Action is Running", false)

	/**
	 * Smoothing flag to indicate if a smoothing task is actively running.
	 * Bound to Paintera.isDisabled, so if set to `true` then Paintera will
	 * be "busy" until set to `false` again. This is to block unpermitted state
	 * changes while waiting for smoothing to finish.
	 */
	private var smoothing by smoothingProperty.nonnull()

	private fun SmoothActionVerifiedState.startSmoothTask() {
		val prevScales = paintera.activeViewer.get()!!.screenScales

		val kernelSizeChange: ChangeListener<in Number> = ChangeListener { _, _, _ ->
			cancelActiveSmoothing("Kernel Size Changed")
			resmooth = true
		}

		smoothTask = Tasks.createTask { task ->
			paintera.baseView.disabledPropertyBindings[task] = smoothingProperty
			kernelSizeProperty.addListener(kernelSizeChange)
			paintera.baseView.orthogonalViews().setScreenScales(doubleArrayOf(prevScales[0]))
			smoothing = true
			initializeSmoothLabel()
			smoothing = false
			var intervals: List<Interval>? = null
			while (!task.isCancelled) {
				if (resmooth || finalizeSmoothing) {
					val preview = !finalizeSmoothing
					try {
						smoothing = true
						intervals = runBlocking { updateSmoothMask(preview).map { it.smallestContainingInterval } }
						if (!preview) break
						else requestRepaintOverIntervals(intervals)
					} catch (c: CancellationException) {
						intervals = null
						paintContext.dataSource.resetMasks()
						paintera.baseView.orthogonalViews().requestRepaint()
					} finally {
						smoothing = false
						/* cleanup the executor */
						convolutionExecutor.shutdown()
						/* reset for the next loop */
						resmooth = false
						finalizeSmoothing = false
					}
				} else {
					Thread.sleep(100)
				}
			}
			smoothScope.cancel("Done Smoothing")
			intervals?.also { smoothedIntervals ->
				paintContext.dataSource.apply {
					val applyProgressProperty = SimpleDoubleProperty()
					applyProgressProperty.addListener { _, _, applyProgress -> progress = applyProgress.toDouble() }
					applyMaskOverIntervals(currentMask, smoothedIntervals, applyProgressProperty) { it.integerLong >= 0 }
				}
			} ?: let {
				task.cancel()
				emptyList()
			}
		}.onCancelled { _, _ ->
			paintContext.dataSource.resetMasks()
			paintera.baseView.orthogonalViews().requestRepaint()
		}.onSuccess { _, task ->
			val intervals = task.get()
			requestRepaintOverIntervals(intervals)
			statePaintContext?.refreshMeshes?.invoke()
		}.onEnd {
			paintera.baseView.disabledPropertyBindings -= smoothTask
			kernelSizeProperty.removeListener(kernelSizeChange)
			paintera.baseView.orthogonalViews().setScreenScales(prevScales)
			convolutionExecutor.shutdown()
		}.submit()
	}

	private fun requestRepaintOverIntervals(intervals: List<Interval>? = null) {
		if (intervals.isNullOrEmpty()) {
			paintera.baseView.orthogonalViews().requestRepaint()
			return
		}
		val smoothedInterval = intervals.reduce(Intervals::union)
		val globalSmoothedInterval = state.paintContext.dataSource.getSourceTransformForMask(MaskInfo(0, 0)).estimateBounds(smoothedInterval)
		paintera.baseView.orthogonalViews().requestRepaint(globalSmoothedInterval)
	}

	private fun cancelActiveSmoothing(reason: String) {
		smoothScope.cancel(CancellationException(reason))
		convolutionExecutor.shutdown()
		progress = 0.0


	}

	private fun SmoothActionVerifiedState.initializeSmoothLabel() {

		val maskedSource = paintContext.dataSource as MaskedSource<*, *>
		val labels = paintContext.selectedIds.activeIds


		/* Read from the labelBlockLookup (if already persisted) */
		val scale0 = 0
		val blocksFromSource = labels.toArray().flatMap { paintContext.getBlocksForLabel(scale0, it).toList() }

		/* Read from canvas access (if in canvas) */
		val cellGrid = maskedSource.getCellGrid(0, scale0)
		val cellIntervals = cellGrid.cellIntervals().randomAccess()
		val cellPos = LongArray(cellGrid.numDimensions())
		val blocksFromCanvas = labels.toArray().flatMap {
			maskedSource.getModifiedBlocks(scale0, it).toArray().map { block ->
				cellGrid.getCellGridPositionFlat(block, cellPos)
				FinalInterval(cellIntervals.setPositionAndGet(*cellPos))
			}
		}

		val blocksWithLabel = blocksFromSource + blocksFromCanvas
		if (blocksWithLabel.isEmpty()) return

		val sourceLabels = Converters.convert(
			maskedSource.getReadOnlyDataBackground(0, scale0),
			{ source, output -> output.set(source.realDouble.toLong()) },
			UnsignedLongType()
		)

		val collapsedLabelStack = Views.collapse(
			Views.stack(sourceLabels, maskedSource.getReadOnlyDataCanvas(0, scale0))
		)

		val virtualLabelMask = Converters.convert(collapsedLabelStack, { input, output ->
			for (i in 1 downTo 0) {
				val labelVal = input.get(i.toLong()).get()
				if (labelVal != Imglib2Label.INVALID) {
					output.set(if (labelVal in labels) 1.0 else 0.0)
					break
				}
			}
		}, DoubleType())

		val labelMask = Lazy.generate(virtualLabelMask, cellGrid.cellDimensions, DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE)) { output ->
			val input = virtualLabelMask.interval(output)
			LoopBuilder.setImages(input, output).multiThreaded().forEachPixel { inVal, outVal -> if (inVal.get() == 1.0) outVal.set(1.0) }
		}

		var labelRoi: Interval = FinalInterval(blocksWithLabel[0])
		blocksWithLabel.forEach { labelRoi = labelRoi union it }

		updateSmoothMask = { preview -> smoothMask(labelMask, cellGrid, blocksWithLabel, preview) }
		resmooth = true
	}

	private fun SmoothActionVerifiedState.pruneBlock(blocksWithLabel: List<Interval>): List<RealInterval> {
		val viewsInSourceSpace = viewerIntervalsInSourceSpace()

		/* remove any blocks that don't intersect with them*/
		return blocksWithLabel
			.map { block -> viewsInSourceSpace.firstOrNull { viewer -> !Intervals.isEmpty(viewer.intersect(block)) } }
			.filterNotNull()
			.toList()
	}

	private fun SmoothActionVerifiedState.viewerIntervalsInSourceSpace(): Array<FinalRealInterval> {
		/* get viewer screen intervals for each orthognal view in source space*/
		val viewerAndTransforms = paintera.baseView.orthogonalViews().viewerAndTransforms()
		val viewsInSourceSpace = viewerAndTransforms
			.map {
				val globalToViewerTransform = AffineTransform3D()
				it.viewer().state.getViewerTransform(globalToViewerTransform)
				val width = it.viewer().width
				val height = it.viewer().height
				val screenInterval = FinalInterval(width.toLong(), height.toLong(), 1L)
				val sourceToGlobal = AffineTransform3D()
				labelSource.getDataSource().getSourceTransform(0, 0, sourceToGlobal)
				val viewerToSource = sourceToGlobal.inverse().copy().concatenate(globalToViewerTransform.inverse())
				viewerToSource.estimateBounds(screenInterval)
			}.toTypedArray()
		return viewsInSourceSpace
	}

	private suspend fun SmoothActionVerifiedState.smoothMask(labelMask: CachedCellImg<DoubleType, *>, cellGrid: CellGrid, blocksWithLabel: List<Interval>, preview: Boolean = false): List<RealInterval> {

		val intervalsToSmoothOver = if (preview) pruneBlock(blocksWithLabel) else blocksWithLabel

		val scale0 = 0
		val levelResolution = getLevelResolution(scale0)
		val sigma = DoubleArray(3) { kernelSize / levelResolution[it] }

		if (convolutionExecutor.isShutdown) {
			convolutionExecutor = newConvolutionExecutor()
		}

		val convolution = FastGauss.convolution(sigma)
		if (convolutionExecutor.isShutdown) {
			convolutionExecutor = newConvolutionExecutor()
		}


		val smoothedImg = Lazy.generate(labelMask, cellGrid.cellDimensions, DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE)) { smoothedCell ->
			Parallelization.runWithExecutor(convolutionExecutor) { convolution.process(labelMask.extendZero(), smoothedCell) }
		}

		paintContext.dataSource.resetMasks()
		setNewSourceMask(paintContext.dataSource, MaskInfo(0, scale0))
		val mask = paintContext.dataSource.currentMask

		val smoothOverInterval: suspend CoroutineScope.(RealInterval) -> Job = { slice ->
			launch {
				val labels = BundleView(labelMask).interval(slice).cursor()
				val smoothed = smoothedImg.interval(slice).cursor()
				val maskBundle = BundleView(mask.rai).interval(slice).cursor()

				ensureActive()
				while (smoothed.hasNext()) {
					//This is the slow one, check cancellation immediately before and after
					val smoothness: Double
					try {
						smoothness = smoothed.next().get()
					} catch (e: Exception) {
						throw CancellationException("Gaussian Convolution Shutdown", e).also { cancel(it) }
					}
					val labelVal = labels.next().get()
					val maskVal = maskBundle.next().get()
					if (smoothness < 0.5 && labelVal.get() == 1.0) {
						maskVal.setInteger(replacementLabel)
					} else if (maskVal.get() == replacementLabel) {
						maskVal.setInteger(Imglib2Label.INVALID)
					}
				}
			}
		}


		/*Start smoothing */
		scopeJob = Job()
		smoothScope = CoroutineScope(Dispatchers.Default + scopeJob)
		intervalsToSmoothOver.forEach { smoothScope.smoothOverInterval(it) }

		/* wait and update progress */
		while (scopeJob.children.any { it.isActive }) {
			with(convolutionExecutor) {
				progress = currentProgressBar.let { currentProgress ->
					val remaining = 1.0 - currentProgress
					val numTasksModifier = 1 / log10(taskCount + 10.0)
					/* Max quarter remaining increments*/
					currentProgress + (remaining * numTasksModifier).coerceAtMost(.25)
				}
			}
			delay(50)
			if (scopeJob.isCancelled) {
				progress = 0.0
				throw CancellationException("Smoothing Cancelled")
			}
		}
		progress = 1.0
		return intervalsToSmoothOver
	}

	private fun setNewSourceMask(maskedSource: MaskedSource<*, *>, maskInfo: MaskInfo) {
		val (store, volatileStore) = maskedSource.createMaskStoreWithVolatile(maskInfo.level)
		val mask = SourceMask(maskInfo, store, volatileStore.rai, store.cache, volatileStore.invalidate) { store.shutdown() }
		maskedSource.setMask(mask) { it.integerLong >= 0 }
	}
}
