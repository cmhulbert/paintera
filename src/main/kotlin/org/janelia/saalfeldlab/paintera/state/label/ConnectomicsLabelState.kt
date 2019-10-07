package org.janelia.saalfeldlab.paintera.state.label

import bdv.viewer.Interpolation
import com.pivovarit.function.ThrowingFunction
import gnu.trove.set.hash.TLongHashSet
import javafx.beans.InvalidationListener
import javafx.beans.property.*
import javafx.event.Event
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import net.imglib2.Interval
import net.imglib2.cache.ref.SoftRefLoaderCache
import net.imglib2.converter.Converter
import net.imglib2.type.label.LabelMultisetType
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.RealType
import net.imglib2.util.Pair
import org.janelia.saalfeldlab.fx.event.DelegateEventHandlers
import org.janelia.saalfeldlab.fx.event.EventFX
import org.janelia.saalfeldlab.fx.event.KeyTracker
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.NamedKeyCombination
import org.janelia.saalfeldlab.paintera.PainteraBaseView
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaYCbCr
import org.janelia.saalfeldlab.paintera.composition.Composite
import org.janelia.saalfeldlab.paintera.config.input.KeyAndMouseBindings
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationMode
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds
import org.janelia.saalfeldlab.paintera.control.selection.SelectedSegments
import org.janelia.saalfeldlab.paintera.data.DataSource
import org.janelia.saalfeldlab.paintera.data.axisorder.AxisOrder
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.meshes.InterruptibleFunction
import org.janelia.saalfeldlab.paintera.meshes.MeshManager
import org.janelia.saalfeldlab.paintera.meshes.MeshManagerWithAssignmentForSegments
import org.janelia.saalfeldlab.paintera.meshes.ShapeKey
import org.janelia.saalfeldlab.paintera.state.*
import org.janelia.saalfeldlab.paintera.stream.ARGBStreamSeedSetter
import org.janelia.saalfeldlab.paintera.stream.HighlightingStreamConverter
import org.janelia.saalfeldlab.paintera.stream.ModalGoldenAngleSaturatedHighlightingARGBStream
import org.janelia.saalfeldlab.paintera.stream.ShowOnlySelectedInStreamToggle
import org.janelia.saalfeldlab.util.Colors
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.concurrent.ExecutorService
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.LongFunction
import java.util.function.Supplier

private typealias VertexNormalPair = Pair<FloatArray, FloatArray>

class ConnectomicsLabelState<D: IntegerType<D>, T>(
	private val backend: ConnectomicsLabelBackend<D, T>,
	meshesGroup: Group,
	meshManagerExecutors: ExecutorService,
	meshWorkersExecutors: ExecutorService): SourceState<D, T> {

	private val maskForLabel = equalsMaskForType(backend.source.dataType)

	val fragmentSegmentAssignment = backend.fragmentSegmentAssignment

	private val lockedSegments = backend.lockedSegments

	private val selectedIds = SelectedIds()

	private val selectedSegments = SelectedSegments(selectedIds, fragmentSegmentAssignment)

	private val idService = backend.idService

	private val labelBlockLookup = backend.labelBlockLookup

	private val stream = ModalGoldenAngleSaturatedHighlightingARGBStream(selectedSegments, lockedSegments)

	private val converter = HighlightingStreamConverter.forType(stream, dataSource.type)

	private val backgroundBlockCaches = Array(backend.source.numMipmapLevels) { level ->
		InterruptibleFunction.fromFunction<Long, Array<Interval>>(ThrowingFunction.unchecked { labelBlockLookup.read(level, it) })
	}

	val meshManager: MeshManager<Long, TLongHashSet> = MeshManagerWithAssignmentForSegments.fromBlockLookup(
		backend.source,
		selectedSegments,
		stream,
		meshesGroup,
		backgroundBlockCaches,
		{ SoftRefLoaderCache<ShapeKey<TLongHashSet>, VertexNormalPair>().withLoader(it) },
		meshManagerExecutors,
		meshWorkersExecutors)

	private val paintHandler = LabelSourceStatePaintHandler(selectedIds)

	private val idSelectorHandler = LabelSourceStateIdSelectorHandler(backend.source, selectedIds, fragmentSegmentAssignment, lockedSegments)

	private val mergeDetachHandler = LabelSourceStateMergeDetachHandler(backend.source, selectedIds, fragmentSegmentAssignment, idService)

	private val commitHandler = CommitHandler(this)

	private val shapeInterpolationMode = backend.source.let {
		if (it is MaskedSource<D, *>)
			ShapeInterpolationMode(it, Runnable { refreshMeshes() }, selectedIds, idService, converter, fragmentSegmentAssignment)
		else
			null
	}

	private val streamSeedSetter = ARGBStreamSeedSetter(stream)

	private val showOnlySelectedInStreamToggle = ShowOnlySelectedInStreamToggle(stream);

	private fun refreshMeshes() {
		// TODO use label block lookup cache instead
		meshManager.invalidateMeshCaches()
		val selection = selectedIds.activeIds
		val lastSelection = selectedIds.lastSelection
		selectedIds.deactivateAll()
		selectedIds.activate(*selection)
		selectedIds.activateAlso(lastSelection)
	}

	override fun getDataSource(): DataSource<D, T> = backend.source

	override fun converter(): HighlightingStreamConverter<T> = converter

	// ARGB composite
	private val _composite: ObjectProperty<Composite<ARGBType, ARGBType>> = SimpleObjectProperty(
		this,
		"composite",
		ARGBCompositeAlphaYCbCr())
	var composite: Composite<ARGBType, ARGBType>
		get() = _composite.get()
		set(composite) = _composite.set(composite)
	override fun compositeProperty(): ObjectProperty<Composite<ARGBType, ARGBType>> = _composite

	// source name
	private val _name = SimpleStringProperty(backend.source.name)
	var name: String
		get() = _name.get()
		set(name) = _name.set(name)
	override fun nameProperty(): StringProperty = _name

	// status text
	private val _statusText = SimpleStringProperty(this, "status text", "")
	override fun statusTextProperty(): StringProperty = _statusText

	// visibility
	private val _isVisible = SimpleBooleanProperty(true)
	var isVisible: Boolean
		get() = _isVisible.get()
		set(visible) = _isVisible.set(visible)
	override fun isVisibleProperty(): BooleanProperty = _isVisible

	// interpolation
	private val _interpolation = SimpleObjectProperty(this, "interpolation", Interpolation.NEARESTNEIGHBOR)
	var interpolation: Interpolation
		get() = _interpolation.get()
		set(interpolation) = _interpolation.set(interpolation)
	override fun interpolationProperty(): ObjectProperty<Interpolation> = _interpolation

	// source dependencies
	override fun dependsOn(): Array<SourceState<*, *>> = arrayOf()

	// axis order
	override fun axisOrderProperty(): ObjectProperty<AxisOrder> = SimpleObjectProperty(AxisOrder.XYZ)

	// flood fill state
	private val floodFillState = SimpleObjectProperty<HasFloodFillState.FloodFillState>()

	// display status
	private val displayStatus: HBox = createDisplayStatus()
	override fun getDisplayStatus(): Node = displayStatus


	override fun stateSpecificGlobalEventHandler(paintera: PainteraBaseView, keyTracker: KeyTracker): EventHandler<Event> {
		LOG.debug("Returning {}-specific global handler", javaClass.simpleName)
		val keyBindings = paintera.keyAndMouseBindings.getConfigFor(this).keyCombinations
		val handler = DelegateEventHandlers.handleAny()
		handler.addEventHandler(
			KeyEvent.KEY_PRESSED,
			EventFX.KEY_PRESSED(
				BindingKeys.REFRESH_MESHES,
				{ e ->
					e.consume()
					LOG.debug("Key event triggered refresh meshes")
					refreshMeshes()
				},
				{ keyBindings[BindingKeys.REFRESH_MESHES]!!.matches(it) })
		)
		handler.addEventHandler(
			KeyEvent.KEY_PRESSED,
			EventFX.KEY_PRESSED(
				BindingKeys.CANCEL_3D_FLOODFILL,
				{ e ->
					e.consume()
					val state = floodFillState.get()
					if (state != null && state.interrupt != null)
						state.interrupt.run()
				},
				{ e -> floodFillState.get() != null && keyBindings[BindingKeys.CANCEL_3D_FLOODFILL]!!.matches(e) })
		)
		handler.addEventHandler(
			KeyEvent.KEY_PRESSED, EventFX.KEY_PRESSED(
				BindingKeys.TOGGLE_NON_SELECTED_LABELS_VISIBILITY,
				{ e ->
					e.consume()
					this.showOnlySelectedInStreamToggle.toggleNonSelectionVisibility()
				},
				{ keyBindings[BindingKeys.TOGGLE_NON_SELECTED_LABELS_VISIBILITY]!!.matches(it) })
		)
		handler.addEventHandler(
			KeyEvent.KEY_PRESSED,
			streamSeedSetter.incrementHandler(Supplier { keyBindings[BindingKeys.ARGB_STREAM_INCREMENT_SEED]!!.primaryCombination})
		)
		handler.addEventHandler(
			KeyEvent.KEY_PRESSED,
			streamSeedSetter.decrementHandler(Supplier { keyBindings[BindingKeys.ARGB_STREAM_DECREMENT_SEED]!!.primaryCombination })
		)
		val listHandler = DelegateEventHandlers.listHandler<Event>()
		listHandler.addHandler(handler)
		listHandler.addHandler(commitHandler.globalHandler(paintera, paintera.keyAndMouseBindings.getConfigFor(this), keyTracker))
		return listHandler
	}

	override fun stateSpecificViewerEventHandler(paintera: PainteraBaseView, keyTracker: KeyTracker): EventHandler<Event> {
		LOG.debug("Returning {}-specific handler", javaClass.simpleName)
		val handler = DelegateEventHandlers.listHandler<Event>()
		handler.addHandler(paintHandler.viewerHandler(paintera, keyTracker))
		handler.addHandler(idSelectorHandler.viewerHandler(paintera, paintera.keyAndMouseBindings.getConfigFor(this), keyTracker))
		handler.addHandler(mergeDetachHandler.viewerHandler(paintera, paintera.keyAndMouseBindings.getConfigFor(this), keyTracker))
		return handler
	}

	override fun stateSpecificViewerEventFilter(paintera: PainteraBaseView, keyTracker: KeyTracker): EventHandler<Event> {
		LOG.debug("Returning {}-specific filter", javaClass.simpleName)
		val filter = DelegateEventHandlers.listHandler<Event>()
		val bindings = paintera.keyAndMouseBindings.getConfigFor(this)
		filter.addHandler(paintHandler.viewerFilter(paintera, keyTracker))
		if (shapeInterpolationMode != null)
			filter.addHandler(shapeInterpolationMode.modeHandler(paintera, keyTracker, bindings))
		return filter
	}

	override fun onRemoval(sourceInfo:SourceInfo) {
		LOG.info("Removed LabelSourceState {}", nameProperty().get())
		meshManager.removeAllMeshes()
		CommitHandler.showCommitDialog(
			this,
			sourceInfo.indexOf(this.dataSource),
			false,
			BiFunction { index, name-> String.format(
				"" +
				"Removing source %d: %s. " +
				"Uncommitted changes to the canvas and/or fragment-segment assignment will be lost if skipped.", index, name)
			},
			false,
			"_Skip")
}

	override fun onShutdown(paintera: PainteraBaseView) {
		CommitHandler.showCommitDialog(
			this,
			paintera.sourceInfo().indexOf(this.dataSource),
			false,
			BiFunction { index, name ->
				"Shutting down Paintera. " +
						"Uncommitted changes to the canvas will be lost for source $index: $name if skipped. " +
						"Uncommitted changes to the fragment-segment-assigment will be stored in the Paintera project (if any) " +
						"but can be committed to the data backend, as well."
			},
			false,
			"_Skip")
	}

	override fun createKeyAndMouseBindings(): KeyAndMouseBindings {
		val bindings = KeyAndMouseBindings()
		return try {
			createKeyAndMouseBindingsImpl(bindings)
		} catch (e: NamedKeyCombination.CombinationMap.KeyCombinationAlreadyInserted) {
			e.printStackTrace()
			bindings
		}
	}

	private fun createDisplayStatus(): HBox {
		val lastSelectedLabelColorRect = Rectangle(13.0, 13.0)
		lastSelectedLabelColorRect.stroke = Color.BLACK

		val lastSelectedLabelColorRectTooltip = Tooltip()
		Tooltip.install(lastSelectedLabelColorRect, lastSelectedLabelColorRectTooltip)

		val lastSelectedIdUpdater = InvalidationListener {
			InvokeOnJavaFXApplicationThread.invoke {
				if (selectedIds.isLastSelectionValid) {
					val lastSelectedLabelId = selectedIds.lastSelection
					val currSelectedColor = Colors.toColor(stream.argb(lastSelectedLabelId))
					lastSelectedLabelColorRect.fill = currSelectedColor
					lastSelectedLabelColorRect.isVisible = true

					val activeIdText = StringBuilder()
					val segmentId = fragmentSegmentAssignment.getSegment(lastSelectedLabelId)
					if (segmentId != lastSelectedLabelId)
						activeIdText.append("Segment: $segmentId").append(". ")
					activeIdText.append("Fragment: $lastSelectedLabelId")
					lastSelectedLabelColorRectTooltip.text = activeIdText.toString()
				}
			}
		}
		selectedIds.addListener(lastSelectedIdUpdater)
		fragmentSegmentAssignment.addListener(lastSelectedIdUpdater)

		// add the same listener to the color stream (for example, the color should change when a new random seed value is set)
		stream.addListener(lastSelectedIdUpdater)

		val paintingProgressIndicator = ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS)
		paintingProgressIndicator.prefWidth = 15.0
		paintingProgressIndicator.prefHeight = 15.0
		paintingProgressIndicator.minWidth = Control.USE_PREF_SIZE
		paintingProgressIndicator.minHeight = Control.USE_PREF_SIZE
		paintingProgressIndicator.isVisible = false

		val paintingProgressIndicatorTooltip = Tooltip()
		paintingProgressIndicator.tooltip = paintingProgressIndicatorTooltip

		val resetProgressIndicatorContextMenu = Runnable {
			val contextMenu = paintingProgressIndicator.contextMenuProperty().get()
			contextMenu?.hide()
			paintingProgressIndicator.contextMenu = null
			paintingProgressIndicator.onMouseClicked = null
			paintingProgressIndicator.cursor = Cursor.DEFAULT
		}

		val setProgressIndicatorContextMenu = Consumer<ContextMenu> { contextMenu ->
			resetProgressIndicatorContextMenu.run()
			paintingProgressIndicator.contextMenu = contextMenu
			paintingProgressIndicator.setOnMouseClicked { event ->
				contextMenu.show(
					paintingProgressIndicator,
					event.screenX,
					event.screenY
				)
			}
			paintingProgressIndicator.cursor = Cursor.HAND
		}

		if (this.dataSource is MaskedSource<*, *>) {
			val maskedSource = this.dataSource as MaskedSource<D, *>
			maskedSource.isApplyingMaskProperty().addListener { _, _, newv ->
				InvokeOnJavaFXApplicationThread.invoke {
					paintingProgressIndicator.isVisible =
						newv!!
					if (newv) {
						val currentMask = maskedSource.getCurrentMask()
						if (currentMask != null)
							paintingProgressIndicatorTooltip.text = "Applying mask to canvas, label ID: " + currentMask.info.value.get()
					}
				}
			}
		}

		this.floodFillState.addListener { obs, oldv, newv ->
			InvokeOnJavaFXApplicationThread.invoke {
				if (newv != null) {
					paintingProgressIndicator.isVisible = true
					paintingProgressIndicatorTooltip.text = "Flood-filling, label ID: " + newv.labelId

					val floodFillContextMenuCancelItem = MenuItem("Cancel")
					if (newv.interrupt != null) {
						floodFillContextMenuCancelItem.setOnAction { event -> newv.interrupt.run() }
					} else {
						floodFillContextMenuCancelItem.isDisable = true
					}
					setProgressIndicatorContextMenu.accept(ContextMenu(floodFillContextMenuCancelItem))
				} else {
					paintingProgressIndicator.isVisible = false
					resetProgressIndicatorContextMenu.run()
				}
			}
		}

		// only necessary if we actually have shape interpolation
		if (this.shapeInterpolationMode != null) {
			val shapeInterpolationModeStatusUpdater = InvalidationListener {
				InvokeOnJavaFXApplicationThread.invoke {
					val modeState = this.shapeInterpolationMode.modeStateProperty().get()
					val activeSection = this.shapeInterpolationMode.activeSectionProperty().get()
					if (modeState != null) {
						when (modeState) {
							ShapeInterpolationMode.ModeState.Select -> statusTextProperty().set("Select #$activeSection")
							ShapeInterpolationMode.ModeState.Interpolate -> statusTextProperty().set("Interpolating")
							ShapeInterpolationMode.ModeState.Preview -> statusTextProperty().set("Preview")
							else -> statusTextProperty().set(null)
						}
					} else {
						statusTextProperty().set(null)
					}
					val showProgressIndicator = modeState == ShapeInterpolationMode.ModeState.Interpolate
					paintingProgressIndicator.isVisible = showProgressIndicator
					paintingProgressIndicatorTooltip.text = if (showProgressIndicator) "Interpolating between sections..." else ""
				}
			}

			this.shapeInterpolationMode.modeStateProperty().addListener(shapeInterpolationModeStatusUpdater)
			this.shapeInterpolationMode.activeSectionProperty().addListener(shapeInterpolationModeStatusUpdater)
		}

		val displayStatus = HBox(5.0, lastSelectedLabelColorRect, paintingProgressIndicator)
		displayStatus.setAlignment(Pos.CENTER_LEFT)
		displayStatus.setPadding(Insets(0.0, 3.0, 0.0, 3.0))

		return displayStatus
	}

	override fun preferencePaneNode(): Node {
		return LabelSourceStatePreferencePaneNode(
			dataSource,
			compositeProperty(),
			converter(),
			meshManager,
			meshManager.managedMeshSettings()
		).node
	}

	companion object {

		private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

		@Throws(NamedKeyCombination.CombinationMap.KeyCombinationAlreadyInserted::class)
		private fun createKeyAndMouseBindingsImpl(bindings: KeyAndMouseBindings): KeyAndMouseBindings {
			val c = bindings.keyCombinations
			with(BindingKeys) {
				c.addCombination(NamedKeyCombination(SELECT_ALL, KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN)))
				c.addCombination(NamedKeyCombination(SELECT_ALL_IN_CURRENT_VIEW, KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)))
				c.addCombination(NamedKeyCombination(LOCK_SEGEMENT, KeyCodeCombination(KeyCode.L)))
				c.addCombination(NamedKeyCombination(NEXT_ID, KeyCodeCombination(KeyCode.N)))
				c.addCombination(NamedKeyCombination(COMMIT_DIALOG, KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN)))
				c.addCombination(NamedKeyCombination(MERGE_ALL_SELECTED, KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN)))
				c.addCombination(NamedKeyCombination(ENTER_SHAPE_INTERPOLATION_MODE, KeyCodeCombination(KeyCode.S)))
				c.addCombination(NamedKeyCombination(EXIT_SHAPE_INTERPOLATION_MODE, KeyCodeCombination(KeyCode.ESCAPE)))
				c.addCombination(NamedKeyCombination(SHAPE_INTERPOLATION_APPLY_MASK, KeyCodeCombination(KeyCode.ENTER)))
				c.addCombination(NamedKeyCombination(SHAPE_INTERPOLATION_EDIT_SELECTION_1, KeyCodeCombination(KeyCode.DIGIT1)))
				c.addCombination(NamedKeyCombination(SHAPE_INTERPOLATION_EDIT_SELECTION_2, KeyCodeCombination(KeyCode.DIGIT2)))
				c.addCombination(NamedKeyCombination(ARGB_STREAM_INCREMENT_SEED, KeyCodeCombination(KeyCode.C)))
				c.addCombination(NamedKeyCombination(ARGB_STREAM_DECREMENT_SEED, KeyCodeCombination(KeyCode.C, KeyCombination.SHIFT_DOWN)))
				c.addCombination(NamedKeyCombination(REFRESH_MESHES, KeyCodeCombination(KeyCode.R)))
				c.addCombination(NamedKeyCombination(CANCEL_3D_FLOODFILL, KeyCodeCombination(KeyCode.ESCAPE)))
				c.addCombination(NamedKeyCombination(TOGGLE_NON_SELECTED_LABELS_VISIBILITY, KeyCodeCombination(KeyCode.V, KeyCombination.SHIFT_DOWN)))
			}
			return bindings
		}

		@SuppressWarnings("unchecked")
		private fun <D> equalsMaskForType(d: D): LongFunction<Converter<D, BoolType>>? {
			return when (d) {
				is LabelMultisetType -> equalMaskForLabelMultisetType() as LongFunction<Converter<D, BoolType>>
				is IntegerType<*> -> equalMaskForIntegerType() as LongFunction<Converter<D, BoolType>>
				is RealType<*> -> equalMaskForRealType() as LongFunction<Converter<D, BoolType>>
				else -> null
			}
		}

		private fun equalMaskForLabelMultisetType(): LongFunction<Converter<LabelMultisetType, BoolType>> = LongFunction {
			Converter { s: LabelMultisetType, t: BoolType -> t.set(s.contains(it)) }
		}

		private fun equalMaskForIntegerType(): LongFunction<Converter<IntegerType<*>, BoolType>> = LongFunction {
			Converter { s: IntegerType<*>, t: BoolType -> t.set(s.getIntegerLong() == it) }
		}

		private fun equalMaskForRealType(): LongFunction<Converter<RealType<*>, BoolType>> = LongFunction {
			Converter { s: RealType<*>, t: BoolType -> t.set(s.getRealDouble() == it.toDouble()) }
		}

	}

	class BindingKeys {
		companion object {
			const val SELECT_ALL = "select all"
			const val SELECT_ALL_IN_CURRENT_VIEW = "select all in current view"
			const val LOCK_SEGEMENT = "lock segment"
			const val NEXT_ID = "next id"
			const val COMMIT_DIALOG = "commit dialog"
			const val MERGE_ALL_SELECTED = "merge all selected"
			const val ENTER_SHAPE_INTERPOLATION_MODE = "shape interpolation: enter mode"
			const val EXIT_SHAPE_INTERPOLATION_MODE = "shape interpolation: exit mode"
			const val SHAPE_INTERPOLATION_APPLY_MASK = "shape interpolation: apply mask"
			const val SHAPE_INTERPOLATION_EDIT_SELECTION_1 = "shape interpolation: edit selection 1"
			const val SHAPE_INTERPOLATION_EDIT_SELECTION_2 = "shape interpolation: edit selection 2"
			const val ARGB_STREAM_INCREMENT_SEED = "argb stream: increment seed"
			const val ARGB_STREAM_DECREMENT_SEED = "argb stream: decrement seed"
			const val REFRESH_MESHES = "refresh meshes"
			const val CANCEL_3D_FLOODFILL = "3d floodfill: cancel"
			const val TOGGLE_NON_SELECTED_LABELS_VISIBILITY = "toggle non-selected labels visibility"
		}
	}


}
