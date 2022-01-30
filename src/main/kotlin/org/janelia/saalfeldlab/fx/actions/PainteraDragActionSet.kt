package org.janelia.saalfeldlab.fx.actions

import javafx.event.Event
import org.janelia.saalfeldlab.paintera.control.actions.ActionType
import org.janelia.saalfeldlab.paintera.paintera

class PainteraDragActionSet @JvmOverloads constructor(val actionType: ActionType, name: String, filter : Boolean = true, apply: (DragActionSet.() -> Unit)? = null) : DragActionSet(name, paintera.keyTracker, filter, apply) {

    override fun <E : Event> preInvokeCheck(action: Action<E>, event: E): Boolean {
        return paintera.baseView.allowedActionsProperty().get().isAllowed(actionType) && super.preInvokeCheck(action, event)
    }
}
