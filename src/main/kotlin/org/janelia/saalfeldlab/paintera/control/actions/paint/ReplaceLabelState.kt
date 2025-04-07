package org.janelia.saalfeldlab.paintera.control.actions.paint

import javafx.beans.property.*
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.Event
import net.imglib2.Volatile
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.RealType
import org.janelia.saalfeldlab.fx.actions.Action
import org.janelia.saalfeldlab.fx.actions.verifiable
import org.janelia.saalfeldlab.paintera.control.actions.state.MaskedSourceActionState
import org.janelia.saalfeldlab.paintera.control.modes.PaintLabelMode
import org.janelia.saalfeldlab.paintera.control.tools.paint.StatePaintContext
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState

interface ReplaceLabelUIState {


	val activeFragment: Long
	val activeSegment: Long
	val allActiveFragments: LongArray
	val allActiveSegments: LongArray
	val fragmentsForActiveSegment: LongArray
	val fragmentsForAllActiveSegments: LongArray

	val fragmentsToReplace: ObservableList<Long>
	val replacementLabelProperty: ObjectProperty<Long>
	val activateReplacementLabelProperty: BooleanProperty
	val progressProperty: DoubleProperty
	val progressTextProperty: StringProperty

	fun fragmentsForSegment(segment: Long): LongArray
	fun nextId(): Long

}

class ReplaceLabelState<D, T> :
	MaskedSourceActionState.ActiveSource<ConnectomicsLabelState<D, T>, D, T>(),
	ReplaceLabelUIState
		where D : IntegerType<D>, T : RealType<T>, T : Volatile<D> {
	internal var paintContext by verifiable("Paint Label Mode has StatePaintContext") {
		(paintera.currentMode as? PaintLabelMode)?.statePaintContext as? StatePaintContext<*, *>
	}

	internal val assignment
		get() = paintContext.assignment

	private val selectedIds
		get() = paintContext.selectedIds

	override val activeFragment
		get() = selectedIds.lastSelection

	override val activeSegment
		get() = assignment.getSegment(activeFragment)

	override val fragmentsForActiveSegment: LongArray
		get() = assignment.getFragments(activeSegment).toArray()

	override val allActiveFragments: LongArray
		get() = selectedIds.activeIds.toArray()

	override val allActiveSegments
		get() = allActiveFragments.asSequence()
			.map { assignment.getSegment(it) }
			.toSet()
			.toLongArray()

	override val fragmentsForAllActiveSegments
		get() = allActiveSegments.asSequence()
			.flatMap { assignment.getFragments(it).toArray().asSequence() }
			.toSet()
			.toLongArray()

	override val progressProperty = SimpleDoubleProperty()
	override val progressTextProperty = SimpleStringProperty()

	override val fragmentsToReplace: ObservableList<Long> = FXCollections.observableArrayList()
	override val replacementLabelProperty: ObjectProperty<Long> = SimpleObjectProperty(0L)
	override val activateReplacementLabelProperty: BooleanProperty = SimpleBooleanProperty(false)

	override fun fragmentsForSegment(segment: Long): LongArray {
		return assignment.getFragments(segment).toArray()
	}

	override fun nextId() = sourceState.nextId()

	override fun <E : Event> verifyState(action: Action<E>) = with(action) {
		super.verifyState(action)
		verify("Mask not in use") { !paintContext.dataSource.isMaskInUseBinding().get() }
	}

	internal fun initializeForMode(mode: Mode) {
		when (mode) {
			Mode.Delete -> {
				replacementLabelProperty.value = 0L
				activateReplacementLabelProperty.value = false
			}

			Mode.Replace -> {
				activateReplacementLabelProperty.value = true
				replacementLabelProperty.value = paintContext.selectedIds.lastSelection
			}

			Mode.All -> Unit // Defaults are fine
		}
	}

	enum class Mode {
		Replace,
		Delete,
		All;
	}
}