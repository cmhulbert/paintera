package org.janelia.saalfeldlab.fx.actions

import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import org.janelia.saalfeldlab.paintera.control.actions.ActionType
import org.janelia.saalfeldlab.paintera.control.tools.Tool
import org.janelia.saalfeldlab.paintera.paintera
import java.util.function.Consumer


open class PainteraActionSet(val actionType: ActionType, name: String, apply: (ActionSet.() -> Unit)? = null) : ActionSet(name, keyTracker, apply) {

    constructor(actionType: ActionType, name: String, apply: Consumer<ActionSet>?) : this(actionType, name, { apply?.accept(this) })

    private fun actionTypeAllowed(): Boolean {
        return allowedActionsProperty.get().isAllowed(actionType)
    }

    override fun <E : Event> preInvokeCheck(action: Action<E>, event: E): Boolean {
        return actionTypeAllowed() && super.preInvokeCheck(action, event)
    }

    companion object {
        private val allowedActionsProperty = paintera.baseView.allowedActionsProperty()
        private val keyTracker = paintera.keyTracker

        @JvmStatic
        fun newMouseAction(eventType: EventType<MouseEvent>) = MouseAction(eventType).also { it.keyTracker = keyTracker }
        @JvmStatic
        fun newKeyAction(eventType: EventType<KeyEvent>) = KeyAction(eventType).also { it.keyTracker = keyTracker }
        @JvmStatic
        fun newAction(eventType: EventType<Event>) = Action(eventType).also { it.keyTracker = keyTracker }
    }
}

fun Node.installTool(tool: Tool) {
    tool.actionSets.forEach { installActionSet(it) }
}

fun Node.removeTool(tool: Tool) {
    tool.actionSets.forEach { removeActionSet(it) }
}
