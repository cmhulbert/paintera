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
import javafx.scene.input.MouseEvent.*
import javafx.scene.input.ScrollEvent
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet
import org.janelia.saalfeldlab.fx.extensions.LazyForeignMap
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.extensions.nonnullVal
import org.janelia.saalfeldlab.fx.extensions.nullable
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.control.ControlUtils
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.paint.PaintActions2D
import org.janelia.saalfeldlab.paintera.control.paint.PaintClickOrDragController
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState

class PaintBrushTool(activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>) : PaintTool(activeSourceStateProperty) {

    internal val currentLabelToPaintProperty = SimpleObjectProperty<Long?>(null)
    internal var currentLabelToPaint by currentLabelToPaintProperty.nullable()

    internal val isLabelValidProperty = currentLabelToPaintProperty.createValueBinding { label -> label?.let { it != Label.INVALID } ?: false }.apply {
        addListener { _, _, isValid ->
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
        val labelNumToString: (Long?) -> String = {
            when (it) {
                null -> "null"
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
        currentLabelToPaint = null
        super.deactivate()
    }

    private fun PaintActions2D.setOverlayValidState() {
        setBrushOverlayValid(isLabelValid, if (isLabelValid) null else "No Id Selected")
    }

    private fun setCurrentLabelToSelection() {
        currentLabelToPaint = statePaintContext?.paintSelection?.invoke()
    }

    private fun getPaintActions() = arrayOf(
        PainteraActionSet(PaintActionType.Paint, "paint selection label") {

//            SELECT BACKGROUND NOT TRIGGERING MultiBoxOverlayConfig.Visibility.ON SHIFT PRESS!!!
            KEY_PRESSED(KeyCode.SHIFT, KeyCode.SPACE) {
                name = "select background"
                onAction {
                    statePaintContext?.selectedIds?.apply { removeListener(selectedIdListener) }
                    currentLabelToPaint = Label.BACKGROUND
                }
            }
            KEY_RELEASED {
                name = "deselect background"
                keysReleased(KeyCode.SHIFT)
                onAction {
                    statePaintContext?.selectedIds?.apply { addListener(selectedIdListener) }
                    setCurrentLabelToSelection()
                }
            }
            /* Handle Painting */
            MOUSE_PRESSED {
                name = "start paint"
                primaryButton()
                onAction {
                    paintClickOrDrag?.startPaint(it)
                }
            }
            MOUSE_RELEASED {
                name = "end paint"
                primaryButton()
                onAction {
                    paintClickOrDrag?.submitPaint()
                }
            }
            /* Handle Common Mouse Move/Drag Actions*/
            MOUSE_DRAGGED {
                onAction { paintClickOrDrag?.extendPaint(it) }
            }
            MOUSE_MOVED {
                onAction { paintClickOrDrag?.extendPaint(it) }
            }

        },
        /* Handle Erasing */
        PainteraActionSet(PaintActionType.Paint, "erase selection label") {
            MOUSE_PRESSED {
                name = "start erase"
                secondaryButton()
                onAction {
                    currentLabelToPaint = Label.TRANSPARENT
                    paintClickOrDrag?.startPaint(it)
                }
            }
            MOUSE_RELEASED {
                name = "end erase"
                secondaryButton()
                onAction {
                    if (paintera.keyTracker.areKeysDown(KeyCode.SHIFT)) {
                        currentLabelToPaint = Label.BACKGROUND
                    } else {
                        setCurrentLabelToSelection()
                    }
                    paintClickOrDrag?.submitPaint()
                }
            }
        }


//        PainteraActionSet(PaintActionType.Paint, "paint selection label") {
//            val listener: (obs: Observable) -> Unit = {
//                statePaintContext?.selectedIds?.lastSelection?.let { currentLabelToPaint = it }
//            }
//            verifyAll(ANY) { it.button == MouseButton.PRIMARY && !it.isSecondaryButtonDown }
//            MOUSE_PRESSED(KeyCode.SPACE) {
//                verify { isLabelValidProperty.get() }
//                onAction {
//                    statePaintContext?.selectedIds?.apply { addListener(listener) }
//                    paintClickOrDrag?.startPaint(it)
//                }
//            }
//            MOUSE_DRAGGED(KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_MOVED(KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_RELEASED(KeyCode.SPACE) {
//                onAction {
//                    paintClickOrDrag?.submitPaint()
//                    statePaintContext?.selectedIds?.apply { removeListener(listener) }
//                }
//            }
//            KEY_PRESSED(KeyCode.SPACE, KeyCode.SHIFT) {
//                name = "background label"
//                onAction {
//                    statePaintContext?.selectedIds?.apply { removeListener(listener) }
//                    currentLabelToPaint = Label.BACKGROUND
//                }
//            }
//            KEY_RELEASED(KeyCode.SPACE) {
//                keysReleased(KeyCode.SHIFT)
//                onAction {
//                    statePaintContext?.selectedIds?.apply { addListener(listener) }
//                    setCurrentLabelToSelection()
//                }
//
//            }
//        },
//        PainteraActionSet(PaintActionType.Paint, "paint background label") {
//            verifyAll(ANY) { it.button == MouseButton.PRIMARY && !it.isSecondaryButtonDown }
//            MOUSE_PRESSED(KeyCode.SHIFT, KeyCode.SPACE) {
//                onAction {
//                    currentLabelToPaint = Label.BACKGROUND
//                    paintClickOrDrag?.startPaint(it)
//                }
//            }
//            MOUSE_DRAGGED(KeyCode.SHIFT, KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_MOVED(KeyCode.SHIFT, KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_RELEASED(KeyCode.SHIFT, KeyCode.SPACE) {
//                onAction {
//                    setCurrentLabelToSelection()
//                    paintClickOrDrag?.submitPaint()
//                }
//            }
//        },
//        PainteraActionSet(PaintActionType.Erase, "erase") {
//            verifyAll(ANY) { it.button == MouseButton.SECONDARY && !it.isPrimaryButtonDown }
//            MOUSE_PRESSED(KeyCode.SPACE) {
//                onAction {
//                    currentLabelToPaint = Label.TRANSPARENT
//                    paintClickOrDrag?.startPaint(it)
//                }
//            }
//            MOUSE_DRAGGED(KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_MOVED(KeyCode.SPACE) {
//                onAction { paintClickOrDrag?.extendPaint(it) }
//            }
//            MOUSE_RELEASED(KeyCode.SPACE) {
//                onAction {
//                    paintClickOrDrag?.submitPaint()
//                    setCurrentLabelToSelection()
//                }
//            }
//        }
    )

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
