package org.janelia.saalfeldlab.paintera.control.tools.paint

import bdv.fx.viewer.ViewerPanelFX
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet
import org.janelia.saalfeldlab.fx.extensions.LazyForeignValue
import org.janelia.saalfeldlab.paintera.control.ControlUtils
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.paint.FloodFill
import org.janelia.saalfeldlab.paintera.meshes.MeshSettings
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.ui.overlays.CursorOverlayWithText

class Fill3DTool(activeSourceStateProperty: SimpleObjectProperty<SourceState<*, *>?>) : PaintTool(activeSourceStateProperty) {

    private class Fill3DOverlay(viewer: ViewerPanelFX, override val overlayText : String = "Fill 3D") : CursorOverlayWithText(viewer)

    override val graphicProperty: SimpleObjectProperty<Node>
        get() = TODO("Not yet implemented")

    val fill by LazyForeignValue({ activeViewer to statePaintContext }) {
        with(it.second!!) {
            FloodFill(
                activeViewer,
                dataSource,
                assignment,
                { paintera.baseView.orthogonalViews().requestRepaint() },
                { MeshSettings.Defaults.Values.isVisible },
                setFloodFillState
            )
        }
    }

    private val overlay by LazyForeignValue({ activeViewer }) { Fill3DOverlay(it!!) }


    override fun activate() {
        super.activate()
        activeViewer?.apply { overlay.setPosition(mouseXProperty.get(), mouseYProperty.get())}
        overlay.visible = true
    }

    override fun deactivate() {
        overlay.visible = false
        super.deactivate()
    }

    override val actionSets: List<ActionSet> = listOf(
        PainteraActionSet(PaintActionType.SetBrushDepth, "change brush depth") {
            action(ScrollEvent.SCROLL) {
                keysDown(KeyCode.F, KeyCode.SHIFT)
                onAction { changeBrushDepth(-ControlUtils.getBiggestScroll(it)) }
            }
        },
        PainteraActionSet(PaintActionType.Fill, "fill") {
            mouseAction(MouseEvent.MOUSE_PRESSED) {
                keysDown(KeyCode.F, KeyCode.SHIFT)
                verify { it.isPrimaryButtonDown }
                onAction { fill.fillAt(it.x, it.y, statePaintContext?.paintSelection) }
            }
        }
    )
}
