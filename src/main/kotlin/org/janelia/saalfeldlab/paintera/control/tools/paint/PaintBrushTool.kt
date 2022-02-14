package org.janelia.saalfeldlab.paintera.control.tools.paint

import javafx.beans.Observable
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent.*
import javafx.scene.input.ScrollEvent
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet
import org.janelia.saalfeldlab.fx.extensions.LazyForeignMap
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.fx.extensions.nonnullVal
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.control.ControlUtils
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.paint.PaintActions2D
import org.janelia.saalfeldlab.paintera.control.paint.PaintClickOrDragController
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState

class PaintBrushTool(activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>) : PaintTool(activeSourceStateProperty) {

    internal val currentLabelToPaintProperty = SimpleObjectProperty(Label.INVALID)
    internal var currentLabelToPaint by currentLabelToPaintProperty.nonnull()

    internal val isLabelValidProperty = currentLabelToPaintProperty.createValueBinding { it != Label.INVALID }.apply {
        addListener { _, _, _ ->
            paint2D?.setOverlayValidState()
            paintera.baseView.orthogonalViews().requestRepaint()
        }
    }
    internal val isLabelValid by isLabelValidProperty.nonnullVal()


    val paintClickOrDrag by LazyForeignMap({ activeViewer to statePaintContext }) {
        it.first?.let { viewer ->
            it.second?.let {
                PaintClickOrDragController(
                    paintera.baseView,
                    viewer,
                    this::currentLabelToPaint,
                    brushProperties::brushRadius,
                    brushProperties::brushDepth
                )
            }
        }
    }

    override val graphicProperty: SimpleObjectProperty<Node>
        get() = TODO("Not yet implemented")

    private val filterSpaceHeldDown = EventHandler<KeyEvent> {
        if (paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.SPACE)) {
            it.consume()
        }
    }

    val paint2D by LazyForeignMap({ activeViewerProperty.get() }) {
        it?.let {
            PaintActions2D(it.viewer(), paintera.baseView.manager()).apply {
                brushRadiusProperty().bindBidirectional(brushProperties.brushRadiusProperty)
                brushRadiusScaleProperty().bindBidirectional(brushProperties.brushRadiusScaleProperty)
                brushDepthProperty().bindBidirectional(brushProperties.brushDepthProperty)
            }
        }
    }

    override val actionSets: List<ActionSet> = listOf(
        getBrushActions(),
        *getPaintActions(),
    )

    override val statusProperty = SimpleStringProperty().apply {
        val labelNumToString: (Long) -> String = {
            when (it) {
                Label.BACKGROUND -> "BACKGROUND"
                Label.TRANSPARENT -> "TRANSPARENT"
                Label.INVALID -> "INVALID"
                Label.OUTSIDE -> "OUTSIDE"
                Label.MAX_ID -> "MAX_ID"
                else -> "$it"
            }
        }
        bind(currentLabelToPaintProperty.createValueBinding() { "Painting Label: ${labelNumToString(it)}" })
    }

    private val selectedIdListener: (obs: Observable) -> Unit = {
        statePaintContext?.selectedIds?.lastSelection?.let { currentLabelToPaint = it }
    }

    override fun activate() {
        super.activate()
        if (paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.SHIFT, KeyCode.SPACE)) {
            currentLabelToPaint = Label.BACKGROUND
        } else {
            setCurrentLabelToSelection()
            statePaintContext?.selectedIds?.apply { addListener(selectedIdListener) }
        }
        paint2D?.apply {
            activeViewer?.apply {
                setOverlayValidState()
                setBrushOverlayVisible(true, mouseXProperty.get(), mouseYProperty.get())
            }
        }
    }

    override fun deactivate() {
        paint2D?.hideBrushOverlay()
        activeViewerProperty.get()?.viewer()?.removeEventFilter(KEY_PRESSED, filterSpaceHeldDown)
        currentLabelToPaint = Label.INVALID
        super.deactivate()
    }

    private fun PaintActions2D.setOverlayValidState() {
        setBrushOverlayValid(isLabelValid, if (isLabelValid) null else "No Id Selected")
    }

    private fun setCurrentLabelToSelection() {
        currentLabelToPaint = statePaintContext?.paintSelection?.invoke() ?: Label.INVALID
    }

    private fun getPaintActions() = arrayOf(
        PainteraActionSet(PaintActionType.Paint, "paint label") {
            /* Handle Painting */
            MOUSE_PRESSED(MouseButton.PRIMARY) {
                name = "start selection paint"
                filter = true
                verify { isLabelValid }
                onAction { paintClickOrDrag?.startPaint(it) }
            }
            MOUSE_RELEASED(MouseButton.PRIMARY, released = true) {
                name = "end selection paint"
                filter = true
                onAction { paintClickOrDrag?.submitPaint() }
            }

            /* Handle Erasing */
            MOUSE_PRESSED(MouseButton.SECONDARY) {
                name = "start erase"
                filter = true
                onAction {
                    currentLabelToPaint = Label.TRANSPARENT
                    paintClickOrDrag?.startPaint(it)
                }
            }
            MOUSE_RELEASED(MouseButton.SECONDARY, released = true) {
                name = "end erase"
                filter = true
                onAction {
                    if (paintera.keyTracker.areKeysDown(KeyCode.SHIFT)) {
                        currentLabelToPaint = Label.BACKGROUND
                    } else {
                        setCurrentLabelToSelection()
                    }
                    paintClickOrDrag?.submitPaint()
                }
            }


            /* Handle Common Mouse Move/Drag Actions*/
            MOUSE_DRAGGED {
                verify { isLabelValid }
                onAction { paintClickOrDrag?.extendPaint(it) }
            }
            MOUSE_MOVED {
                verify { isLabelValid }
                onAction { paintClickOrDrag?.extendPaint(it) }
            }

            /* Handle Background Label Toggle */
            KEY_PRESSED(KeyCode.SPACE, KeyCode.SHIFT) {
                name = "select background"
                onAction { selectBackground() }
            }
            KEY_RELEASED {
                name = "deselect background"
                keysReleased(KeyCode.SHIFT)
                onAction { deselectBackground() }
            }
        }
    )

    internal fun deselectBackground() {
        statePaintContext?.selectedIds?.apply { addListener(selectedIdListener) }
        setCurrentLabelToSelection()
    }

    internal fun selectBackground() {
        statePaintContext?.selectedIds?.apply { removeListener(selectedIdListener) }
        currentLabelToPaint = Label.BACKGROUND
    }

    private fun getBrushActions() = PainteraActionSet(PaintActionType.SetBrush, "change brush") {
        ScrollEvent.SCROLL(KeyCode.SPACE) {
            name = "brush size"
            onAction { paint2D?.changeBrushRadius(it.deltaY) }
        }
        ScrollEvent.SCROLL(KeyCode.SPACE, KeyCode.SHIFT) {
            name = "brush depth"
            onAction { changeBrushDepth(-ControlUtils.getBiggestScroll(it)) }
        }
    }
}
