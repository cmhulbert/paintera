package org.janelia.saalfeldlab.paintera.control.tools

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.scene.Cursor
import javafx.scene.Node
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.installTool
import org.janelia.saalfeldlab.fx.actions.removeTool
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.extensions.nullableVal
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.paintera.PainteraDefaultHandlers.Companion.currentFocusHolder
import org.janelia.saalfeldlab.paintera.paintera

interface Tool {

    fun activate() {}
    fun deactivate() {}

    val statusProperty: StringProperty
    val cursorProperty: SimpleObjectProperty<Cursor>
        get() = SimpleObjectProperty(Cursor.DEFAULT)
    val graphicProperty: SimpleObjectProperty<Node>
    /* each action could have a:
    *   - cursor
    *   - graphic
    *   - shortcut */

    val actionSets: List<ActionSet>
}

abstract class ViewerTool : Tool {

    override fun activate() {
        activeViewerProperty.bind(paintera.baseView.orthogonalViews().currentFocusHolder())
        activeViewerAndTransforms?.viewer()?.installTool(this)
    }

    override fun deactivate() {
        activeViewerAndTransforms?.viewer()?.removeTool(this)
        activeViewerProperty.unbind()
        activeViewerProperty.set(null)
    }

    override val statusProperty = SimpleStringProperty()

    val activeViewerProperty = SimpleObjectProperty<OrthogonalViews.ViewerAndTransforms?>().apply {
        addListener { _, old, new ->
            if (old != new) {
                old?.viewer()?.removeTool(this@ViewerTool)
                new?.viewer()?.installTool(this@ViewerTool)
            }
        }
    }

    val activeViewerAndTransforms by activeViewerProperty.nullableVal()
    val activeViewer by activeViewerProperty.createValueBinding { it?.viewer() }.nullableVal()
}

