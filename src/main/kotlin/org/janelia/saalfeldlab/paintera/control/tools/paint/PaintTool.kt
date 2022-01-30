package org.janelia.saalfeldlab.paintera.control.tools.paint

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import net.imglib2.converter.Converter
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.extensions.nullableVal
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds
import org.janelia.saalfeldlab.paintera.control.tools.ViewerTool
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.state.BrushProperties
import org.janelia.saalfeldlab.paintera.state.FloodFillState
import org.janelia.saalfeldlab.paintera.state.LabelSourceState
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState

abstract class PaintTool(val activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>) : ViewerTool() {

    val brushProperties = BrushProperties()

    val activeStateProperty = SimpleObjectProperty<SourceState<*, *>?>()
    protected val activeState by activeStateProperty.nullableVal()

    private val sourceStateBindings = activeSourceStateProperty.createValueBinding { getValidSourceState(it) }
    val activeSourceToSourceStateContextBinding = activeSourceStateProperty.createValueBinding { binding -> createPaintStateContext(binding) }

    private fun createPaintStateContext(source: SourceState<*, *>?) = when (source) {
        is LabelSourceState<*, *> -> LabelSourceStatePaintContext(source)
        is ConnectomicsLabelState<*, *> -> {
            (source.dataSource as? MaskedSource<*, *>)?.let {
                ConnectomicsLabelStatePaintContext(source)
            }
        }
        else -> null
    }

    val statePaintContext by activeSourceToSourceStateContextBinding.nullableVal()

    override fun activate() {
        super.activate()
        activeStateProperty.bind(sourceStateBindings)
    }

    override fun deactivate() {
        activeStateProperty.unbind()
        activeStateProperty.set(null)
        super.deactivate()
    }


    fun changeBrushDepth(sign: Double) {
        val newDepth = brushProperties.brushDepth + if (sign > 0) -1 else 1
        brushProperties.brushDepth = newDepth.coerceIn(1.0, 2.0)
    }

    companion object {
        private fun getValidSourceState(source: SourceState<*, *>?) = source?.let {
            //TODO Caleb: The current paint handlers allow LabelSourceState,
            // so even though it is marked for deprecation, is still is required here (for now)
            (it as? ConnectomicsLabelState<*, *>) ?: (it as? LabelSourceState<*, *>)
        }
    }
}

interface StatePaintContext<D : IntegerType<D>> {
    val dataSource: MaskedSource<D, *>
    val assignment: FragmentSegmentAssignment
    val isVisibleProperty: SimpleBooleanProperty
    val setFloodFillState: (FloodFillState) -> Unit
    val selectedIds: SelectedIds
    val paintSelection: () -> Long?

    fun getMaskForLabel(label: Long): Converter<D, BoolType>
    fun nextId() : Long
}


private data class LabelSourceStatePaintContext<D : IntegerType<D>>(val state: LabelSourceState<D, *>) : StatePaintContext<D> {

    override val dataSource = state.dataSource as MaskedSource<D, *>
    override val assignment = state.assignment()!!
    override val isVisibleProperty = SimpleBooleanProperty().apply { bind(state.isVisibleProperty) }
    override val setFloodFillState: (FloodFillState?) -> Unit = { state.setFloodFillState(it) }
    override val selectedIds = state.selectedIds()!!
    override val paintSelection = { selectedIds.lastSelection.takeIf { Label.regular(it) } }

    override fun getMaskForLabel(label: Long): Converter<D, BoolType> = state.getMaskForLabel(label)
    override fun nextId() = state.nextId()
}

private data class ConnectomicsLabelStatePaintContext<D : IntegerType<D>>(val state: ConnectomicsLabelState<D, *>) : StatePaintContext<D> {
    override val dataSource: MaskedSource<D, *> = state.dataSource as MaskedSource<D, *>
    override val assignment = state.fragmentSegmentAssignment
    override val isVisibleProperty = SimpleBooleanProperty().apply { bind(state.isVisibleProperty) }
    override val setFloodFillState: (FloodFillState?) -> Unit = { state.floodFillState.set(it) }
    override val selectedIds = state.selectedIds
    override val paintSelection = { selectedIds.lastSelection.takeIf { Label.regular(it) } }

    override fun getMaskForLabel(label: Long): Converter<D, BoolType> = state.maskForLabel.apply(label)
    override fun nextId() = state.nextId()
}
