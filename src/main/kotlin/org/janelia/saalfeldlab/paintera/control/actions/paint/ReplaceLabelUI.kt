package org.janelia.saalfeldlab.paintera.control.actions.paint

import javafx.beans.property.*
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.cell.TextFieldListCell
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.HBox.setHgrow
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.converter.LongStringConverter
import kotlinx.coroutines.delay
import org.controlsfx.control.SegmentedButton
import org.janelia.saalfeldlab.fx.extensions.createObservableBinding
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.control.actions.paint.ReplaceLabelUI.ReplaceIdSelection.Companion.getReplaceIdSelectionButtons
import org.janelia.saalfeldlab.paintera.control.actions.paint.ReplaceLabelUI.ReplaceIdSelection.entries
import org.janelia.saalfeldlab.paintera.control.actions.paint.ReplaceLabelUI.ReplaceTargetSelection.Companion.getReplaceTargetSelectionButtons
import org.janelia.saalfeldlab.paintera.control.actions.paint.ReplaceLabelUI.ReplaceTargetSelection.entries
import org.janelia.saalfeldlab.paintera.ui.UnsignedLongTextFormatter
import org.janelia.saalfeldlab.paintera.ui.hGrow
import org.janelia.saalfeldlab.paintera.ui.hvGrow

private const val ACTIVE_FRAGMENT_TOOLTIP = "Replace the currently active Fragment"
private const val ACTIVE_FRAGMENTS_TOOLTIP = "Replace all currently active Fragments"
private const val ACTIVE_SEGMENT_TOOLTIP = "Replace all fragments for the currently active Segment"
private const val ACTIVE_SEGMENTS_TOOLTIP = "Replace all fragments from all currently active Segments"
private const val RESET_SELECTION_TOOLTIP = "Clear Fragment and Segment Replacement Lists"

class ReplaceLabelUI(
	val state: ReplaceLabelUIState
) : VBox() {

	private enum class ReplaceIdSelection(
		val text: String,
		val tooltip: String,
		val fragments: ReplaceLabelUIState.() -> LongArray,
		val segments: ReplaceLabelUIState.() -> LongArray
	) {
		ACTIVE_FRAGMENT(
			"Active Fragment",
			ACTIVE_FRAGMENT_TOOLTIP,
			{ longArrayOf(activeFragment) },
			{ longArrayOf() }
		),
		ACTIVE_FRAGMENTS(
			"All Active Fragments",
			ACTIVE_FRAGMENTS_TOOLTIP,
			{ allActiveFragments },
			{ longArrayOf() }
		),
		ACTIVE_SEGMENT(
			"Segment For Active Fragment",
			ACTIVE_SEGMENT_TOOLTIP,
			{ fragmentsForActiveSegment },
			{ longArrayOf(activeSegment) }
		),
		ACTIVE_SEGMENTS(
			"Segments For All Active Fragments",
			ACTIVE_SEGMENTS_TOOLTIP,
			{ fragmentsForAllActiveSegments },
			{ allActiveSegments }
		),
		RESET(
			"Reset",
			RESET_SELECTION_TOOLTIP,
			{ longArrayOf() },
			{ longArrayOf() }
		);

		fun fragmentsToReplace(state: ReplaceLabelUIState) = state.run { fragments() }
		fun segmentsToReplace(state: ReplaceLabelUIState) = state.run { segments() }

		fun button(ui: ReplaceLabelUI) = Button().apply {
			setHgrow(this, Priority.ALWAYS)
			text = this@ReplaceIdSelection.text
			tooltip = Tooltip().apply {
				text = this@ReplaceIdSelection.tooltip
			}
			userData = this@ReplaceIdSelection
			onAction = EventHandler {
				ui.fragmentsToReplace.setAll(*fragmentsToReplace(ui.state).toTypedArray())
				ui.segmentsToReplace.setAll(*segmentsToReplace(ui.state).toTypedArray())
			}
		}

		companion object {
			fun ReplaceLabelUI.getReplaceIdSelectionButtons() = entries.filter { it != RESET }.map { it.button(this) }
		}
	}

	private enum class ReplaceTargetSelection(val text: String, val tooltip: String, val label: Long? = null) {
		REPLACE("Replace Label", "Specify Replacement Label"),
		DELETE("Delete Label", "Replace Label with 0", 0);

		val ReplaceLabelUI.button: ToggleButton
			get() = ToggleButton().apply {
				text = this@ReplaceTargetSelection.text
				tooltip = Tooltip(this@ReplaceTargetSelection.tooltip)
				userData = this@ReplaceTargetSelection
				onAction = EventHandler {
					replaceWithIdFormatter.value = label
				}
			}

		companion object {
			fun ReplaceLabelUI.getReplaceTargetSelectionButtons() = entries.map { it.run { button } }
		}
	}

	private val fragmentsToReplace = state.fragmentsToReplace
	private val segmentsToReplace = FXCollections.observableArrayList<Long>()

	private val replaceIdButtonBar = let {
		val buttons = getReplaceIdSelectionButtons().toTypedArray()
		/* default replace ID behavior is ACTIVE_SEGMENT */
		buttons.first { it.userData == ReplaceIdSelection.ACTIVE_SEGMENT }.fire()
		HBox(*buttons)
	}


	private val replaceActionButtonBar = let {
		val buttons = getReplaceTargetSelectionButtons().toTypedArray()
		/* default replace action is DELETE */
		buttons.first { toggle -> toggle.userData == ReplaceTargetSelection.DELETE }.isSelected = true
		SegmentedButton(*buttons)
	}

	private val deleteToggled = replaceActionButtonBar.toggleGroup
		.selectedToggleProperty()
		.createObservableBinding { it.value?.userData == ReplaceTargetSelection.DELETE }

	private val replaceWithIdFormatter = UnsignedLongTextFormatter().also {
		state.replacementLabel.unbind()
		state.replacementLabel.bind(it.valueProperty())
	}
	private val replaceWithIdField = TextField().hGrow {
		promptText = "ID to Replace Fragment/Segment IDs with... "
		textFormatter = replaceWithIdFormatter
		disableProperty().bind(deleteToggled)
	}


	private val fragmentsListView = ListView<Long>(fragmentsToReplace).apply {
		isEditable = true
		cellFactory = TextFieldListCell.forListView(LongStringConverter())
		selectionModel.selectionMode = SelectionMode.MULTIPLE
		onKeyPressed = EventHandler {
			if (!(it.code == KeyCode.DELETE || it.code == KeyCode.BACK_SPACE))return@EventHandler
			selectionModel.selectedItems.toTypedArray().forEach { removeFragment(it) }
			it.consume()
		}
	}

	private val addFragmentField = TextField().hGrow {
		promptText = "Add a Fragment ID to Replace..."
		textFormatter = UnsignedLongTextFormatter()
		onAction = EventHandler { submitFragmentHandler() }
	}

	private val segmentsListView = ListView<Long>(segmentsToReplace).apply {
		isEditable = true
		cellFactory = TextFieldListCell.forListView(LongStringConverter())
		selectionModel.selectionMode = SelectionMode.MULTIPLE
		onKeyPressed = EventHandler {
			if (!(it.code == KeyCode.DELETE || it.code == KeyCode.BACK_SPACE)) return@EventHandler
			selectionModel.selectedItems.toTypedArray().forEach { removeSegment(it) }
			it.consume()
		}
	}
	private val addSegmentField = TextField().hGrow {
		promptText = "Add a Segment ID to Replace..."
		textFormatter = UnsignedLongTextFormatter()
		onAction = EventHandler { submitSegmentHandler() }
	}

	private val submitSegmentHandler: () -> Unit = {
		addSegmentField.run {
			commitValue()
			(textFormatter as? UnsignedLongTextFormatter)?.run {
				value?.let { addSegment(it) }
				value = null
			}
		}
	}

	private fun removeSegment(segment: Long) {
		segmentsToReplace -= segment
		val removedFragments = state.fragmentsForSegment(segment)
		fragmentsToReplace.removeIf {  it in removedFragments }
	}

	private fun removeFragment(fragment: Long) {
		fragmentsToReplace -= fragment
	}

	private val submitFragmentHandler: () -> Unit = {
		addFragmentField.run {
			commitValue()
			(textFormatter as? UnsignedLongTextFormatter)?.run {
				value?.let { addFragment(it) }
				value = null
			}
		}
	}

	private fun addSegment(segment: Long) {
		fragmentsToReplace += state.fragmentsForSegment(segment)
			.filter { it !in fragmentsToReplace }
			.toSet()
		segment.takeIf { it !in segmentsToReplace }?.let { segmentsToReplace += it }
	}

	private fun addFragment(fragment: Long) {
		fragment.takeIf { it !in fragmentsToReplace }?.let { fragmentsToReplace += it }
	}

	private val setNextNewID = EventHandler<ActionEvent> {
		replaceWithIdFormatter.value = state.nextId()
	}

	init {
		hvGrow()
		spacing = 10.0
		padding = Insets(10.0)
		children += VBox().apply {
			spacing = 5.0
			children += HBox().apply {
				children += Label("Replace Labels By: ").also { alignment = Pos.BOTTOM_CENTER }
				children += Pane().hGrow()
				children += ReplaceIdSelection.RESET.button(this@ReplaceLabelUI).apply {
					onAction = onAction?.let { oldAction ->
						// wrap to also set the target value to 0L on reset
						EventHandler {
							oldAction.handle(it)
							replaceWithIdFormatter.value = 0
						}
					}
				}
			}
			children += replaceIdButtonBar.hGrow()
		}

		children += VBox().apply {
			spacing = 5.0
			children += Label("Delete Or Replace ")
			children += HBox().apply {
				spacing = 5.0
				children += replaceActionButtonBar
				children += Pane().hGrow()
				children += Label("Activate Label After Replacement?").apply {
					disableProperty().bind(deleteToggled)
				}
				children += CheckBox().apply {
					state.activeReplacementLabel.bind(selectedProperty())
					disableProperty().bind(deleteToggled)
					deleteToggled.subscribe { it ->
						if (it) isSelected = false
					}
				}
			}
			children += HBox().hGrow {
				padding = Insets(5.0, 0.0, 0.0, 0.0)
				spacing = 5.0
				children += Label("Replace With ID: ")
				children += replaceWithIdField.hGrow()
				children += Button("Next New ID").apply {
					onAction = setNextNewID
					disableProperty().bind(deleteToggled)
				}
			}
		}
		children += HBox().hvGrow {
			children += VBox().hvGrow {
				alignment = Pos.CENTER
				children += Label("Fragments to Replace")
				children += fragmentsListView.hvGrow()
				children += HBox().hGrow {
					children += addFragmentField
					children += Button("Add Fragment ID").apply {
						onAction = EventHandler { submitFragmentHandler() }
					}
				}
			}
			children += VBox().hvGrow {
				alignment = Pos.CENTER
				children += Label("Segments to Replace")
				children += segmentsListView.hvGrow()
				children += HBox().hGrow {
					children += addSegmentField
					children += Button("Add Segment ID").apply {
						onAction = EventHandler { submitSegmentHandler() }

					}
				}
			}
		}
		children += HBox().hGrow {
			spacing = 10.0
			children += Label().hGrow().apply {
				alignment = Pos.CENTER_LEFT
				textProperty().bind(state.progressTextProperty)
				maxWidthProperty().bind(this@hGrow.widthProperty().createObservableBinding { it.doubleValue() * .2 })
			}
			children += ProgressBar().hGrow {
				maxWidth = Double.MAX_VALUE
				progressProperty().bind(state.progressProperty)
			}
		}
	}
}

fun main() {
	InvokeOnJavaFXApplicationThread {

		val state = object : ReplaceLabelUIState {
			override val activeFragment: Long
				get() = 123
			override val activeSegment: Long
				get() = 456
			override val allActiveFragments: LongArray
				get() = longArrayOf(1, 2, 3, 4)
			override val allActiveSegments: LongArray
				get() = longArrayOf(1, 2, 3)
			override val fragmentsForActiveSegment: LongArray
				get() = longArrayOf(1, 2)
			override val fragmentsForAllActiveSegments: LongArray
				get() = longArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
			override val activeReplacementLabel = SimpleBooleanProperty(false)

			override val progressProperty = SimpleDoubleProperty()
			override val progressTextProperty = SimpleStringProperty()

			override val fragmentsToReplace: ObservableList<Long> = FXCollections.observableArrayList()
			override val replacementLabel: LongProperty = SimpleLongProperty()

			private val fragSegMap = mapOf(
				1L to longArrayOf(1,2,3,4),
				2L to longArrayOf(21,22,23,24),
				3L to longArrayOf(31,32,33,34),
				4L to longArrayOf(41,42,43,44),
			)

			override fun fragmentsForSegment(segment: Long): LongArray {
				return fragSegMap[segment] ?: LongArray((0..10).random()) { (0..100L).random() }
			}

			var next = 0L

			override fun nextId(): Long {
				return ++next
			}
		}

		val root = VBox()
		root.apply {
			children += Button("Reload").apply {
				onAction = EventHandler {
					root.children.removeIf { it is ReplaceLabelUI }
					root.children.add(ReplaceLabelUI(state))
				}
			}
			children += ReplaceLabelUI(state)
		}

		InvokeOnJavaFXApplicationThread {
			delay(200)
			var prev = state.progressProperty.value
			while( prev < 1.0 ) {
				prev = prev + .05
				state.progressProperty.set(prev)
				delay(200)
			}
		}


		val scene = Scene(root)
		val stage = Stage()
		stage.scene = scene
		stage.show()
	}
}

