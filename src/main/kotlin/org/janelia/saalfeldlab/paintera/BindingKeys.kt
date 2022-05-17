package org.janelia.saalfeldlab.paintera

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCode.*
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination.*
import org.janelia.saalfeldlab.fx.actions.NamedKeyCombination

private infix fun String.byKeyCombo(keyCode: KeyCode) = NamedKeyCombination(this, KeyCodeCombination(keyCode))
private infix fun String.byKeyCombo(combo: KeyCodeCombination) = NamedKeyCombination(this, combo)

private operator fun ArrayList<Modifier>.plus(keyCode: KeyCode) = KeyCodeCombination(keyCode, *this.toTypedArray())
private operator fun ArrayList<Modifier>.plus(modifier: Modifier) = this.apply { add(modifier) }
private operator fun KeyCode.plus(modifiers: ArrayList<Modifier>) = KeyCodeCombination(this, *modifiers.toTypedArray())
private operator fun KeyCode.plus(modifier: Modifier) = KeyCodeCombination(this, modifier)
private operator fun Modifier.plus(keyCode: KeyCode) = KeyCodeCombination(keyCode, this)
private operator fun Modifier.plus(modifier: Modifier) = arrayListOf(this, modifier)


private operator fun Modifier.plus(modifiers: ArrayList<Modifier>) = modifiers.also { it.add(0, this) }

//@formatter:off
object PainteraBaseKeys {
    const val CYCLE_INTERPOLATION_MODES        = "cycle interpolation modes"
    const val CYCLE_CURRENT_SOURCE_FORWARD     = "cycle current source forward"
    const val CYCLE_CURRENT_SOURCE_BACKWARD    = "cycle current source backward"
    const val TOGGLE_CURRENT_SOURCE_VISIBILITY = "toggle current source visibility"
    const val DETACH_VIEWER                    = "detach viewer"
    const val MAXIMIZE_VIEWER                  = "toggle maximize viewer"
    const val DEDICATED_VIEWER_WINDOW          = "toggle dedicated viewer window"
    const val MAXIMIZE_VIEWER_AND_3D           = "toggle maximize viewer and 3D"
    const val SHOW_OPEN_DATASET_MENU           = "show open dataset menu"
    const val CREATE_NEW_LABEL_DATASET         = "create new label dataset"
    const val SHOW_REPL_TABS                   = "open repl"
    const val TOGGLE_FULL_SCREEN               = "toggle full screen"
    const val OPEN_DATA                        = "open data"
    const val SAVE                             = "save"
    const val SAVE_AS                          = "save as"
    const val TOGGLE_MENUBAR_VISIBILITY        = "toggle menubar visibility"
    const val TOGGLE_MENUBAR_MODE              = "toggle menubar mode"
    const val TOGGLE_STATUSBAR_VISIBILITY      = "toggle statusbar visibility"
    const val TOGGLE_STATUSBAR_MODE            = "toggle statusbar mode"
    const val OPEN_README                      = "open readme"
    const val OPEN_KEY_BINDINGS                = "open key bindings"
    const val QUIT                             = "quit"
    const val TOGGLE_SIDE_BAR                  = "toggle side bar"
    const val FILL_CONNECTED_COMPONENTS        = "fill connected components"
    const val THRESHOLDED                      = "thresholded"

    val NAMED_COMBINATIONS = NamedKeyCombination.CombinationMap(
        OPEN_DATA                                   byKeyCombo CONTROL_DOWN + O,
        SAVE                                        byKeyCombo CONTROL_DOWN + S,
        SAVE_AS                                     byKeyCombo CONTROL_DOWN + SHIFT_DOWN + S,
        TOGGLE_MENUBAR_VISIBILITY                   byKeyCombo F2,
        TOGGLE_MENUBAR_MODE                         byKeyCombo SHIFT_DOWN + F2,
        TOGGLE_STATUSBAR_VISIBILITY                 byKeyCombo F3,
        TOGGLE_STATUSBAR_MODE                       byKeyCombo SHIFT_DOWN + F3,
        OPEN_README                                 byKeyCombo F1,
        OPEN_KEY_BINDINGS                           byKeyCombo F4,
        QUIT                                        byKeyCombo CONTROL_DOWN + Q,
        TOGGLE_SIDE_BAR                             byKeyCombo P,
        CYCLE_CURRENT_SOURCE_FORWARD                byKeyCombo CONTROL_DOWN + TAB,
        CYCLE_CURRENT_SOURCE_BACKWARD               byKeyCombo CONTROL_DOWN + SHIFT_DOWN + TAB,
        TOGGLE_CURRENT_SOURCE_VISIBILITY            byKeyCombo V,
        CYCLE_INTERPOLATION_MODES                   byKeyCombo I,
        MAXIMIZE_VIEWER                             byKeyCombo M,
        DETACH_VIEWER                               byKeyCombo K,
        MAXIMIZE_VIEWER_AND_3D                      byKeyCombo SHIFT_DOWN + M,
        CREATE_NEW_LABEL_DATASET                    byKeyCombo CONTROL_DOWN + SHIFT_DOWN + N,
        SHOW_REPL_TABS                              byKeyCombo SHORTCUT_DOWN + ALT_DOWN + T,
        TOGGLE_FULL_SCREEN                          byKeyCombo F11,
    )

    fun namedCombinationsCopy() = NAMED_COMBINATIONS.deepCopy


}

object LabelSourceStateKeys {
    const val SELECT_ALL                                   = "select all"
    const val SELECT_ALL_IN_CURRENT_VIEW                   = "select all in current view"
    const val LOCK_SEGEMENT                                = "lock segment"
    const val NEXT_ID                                      = "next id"
    const val COMMIT_DIALOG                                = "commit dialog"
    const val MERGE_ALL_SELECTED                           = "merge all selected"
    const val ENTER_SHAPE_INTERPOLATION_MODE               = "shape interpolation: enter mode"
    const val EXIT_SHAPE_INTERPOLATION_MODE                = "shape interpolation: exit mode"
    const val SHAPE_INTERPOLATION_TOGGLE_PREVIEW           = "shape interpolation: toggle preview mode"
    const val SHAPE_INTERPOLATION_APPLY_MASK               = "shape interpolation: apply mask"
    const val SHAPE_INTERPOLATION_EDIT_FIRST_SELECTION     = "shape interpolation: edit first selection"
    const val SHAPE_INTERPOLATION_EDIT_LAST_SELECTION      = "shape interpolation: edit last selection"
    const val SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION  = "shape interpolation: edit previous selection"
    const val SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION      = "shape interpolation: edit next selection"
    const val ARGB_STREAM_INCREMENT_SEED                   = "argb stream: increment seed"
    const val ARGB_STREAM_DECREMENT_SEED                   = "argb stream: decrement seed"
    const val REFRESH_MESHES                               = "refresh meshes"
    const val CANCEL_3D_FLOODFILL                          = "3d floodfill: cancel"
    const val TOGGLE_NON_SELECTED_LABELS_VISIBILITY        = "toggle non-selected labels visibility"

    private val namedComboMap = NamedKeyCombination.CombinationMap(
        SELECT_ALL                                  byKeyCombo A + CONTROL_DOWN,
        SELECT_ALL_IN_CURRENT_VIEW                  byKeyCombo CONTROL_DOWN + SHIFT_DOWN + A,
        LOCK_SEGEMENT                               byKeyCombo L,
        NEXT_ID                                     byKeyCombo N,
        COMMIT_DIALOG                               byKeyCombo C + CONTROL_DOWN,
        MERGE_ALL_SELECTED                          byKeyCombo ENTER + CONTROL_DOWN,
        ENTER_SHAPE_INTERPOLATION_MODE              byKeyCombo S,
        EXIT_SHAPE_INTERPOLATION_MODE               byKeyCombo ESCAPE,
        SHAPE_INTERPOLATION_APPLY_MASK              byKeyCombo ENTER,
        SHAPE_INTERPOLATION_EDIT_FIRST_SELECTION    byKeyCombo DIGIT1,
        SHAPE_INTERPOLATION_EDIT_LAST_SELECTION     byKeyCombo DIGIT0,
        SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION byKeyCombo LEFT,
        SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION     byKeyCombo RIGHT,
        SHAPE_INTERPOLATION_TOGGLE_PREVIEW          byKeyCombo CONTROL_DOWN + P ,
        ARGB_STREAM_INCREMENT_SEED                  byKeyCombo C,
        ARGB_STREAM_DECREMENT_SEED                  byKeyCombo C + SHIFT_DOWN,
        REFRESH_MESHES                              byKeyCombo R,
        CANCEL_3D_FLOODFILL                         byKeyCombo ESCAPE,
        TOGGLE_NON_SELECTED_LABELS_VISIBILITY       byKeyCombo V + SHIFT_DOWN
    )

    fun namedCombinationsCopy() = namedComboMap.deepCopy

}

object NavigationKeys {
    const val BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD       = "translate along normal forward"
    const val BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_FAST  = "translate along normal forward fast"
    const val BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_SLOW  = "translate along normal forward slow"
    const val BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD      = "translate along normal backward"
    const val BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_FAST = "translate along normal backward fast"
    const val BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_SLOW = "translate along normal backward slow"
    const val BUTTON_ZOOM_OUT                             = "zoom out"
    const val BUTTON_ZOOM_OUT2                            = "zoom out (alternative)"
    const val BUTTON_ZOOM_IN                              = "zoom in"
    const val BUTTON_ZOOM_IN2                             = "zoom in (alternative)"
    const val SET_ROTATION_AXIS_X                         = "set rotation axis x"
    const val SET_ROTATION_AXIS_Y                         = "set rotation axis y"
    const val SET_ROTATION_AXIS_Z                         = "set rotation axis z"
    const val KEY_ROTATE_LEFT                             = "rotate left"
    const val KEY_ROTATE_LEFT_FAST                        = "rotate left fast"
    const val KEY_ROTATE_LEFT_SLOW                        = "rotate left slow"
    const val KEY_ROTATE_RIGHT                            = "rotate right"
    const val KEY_ROTATE_RIGHT_FAST                       = "rotate right fast"
    const val KEY_ROTATE_RIGHT_SLOW                       = "rotate right slow"
    const val REMOVE_ROTATION                             = "remove rotation"

    private val namedComboMap = NamedKeyCombination.CombinationMap(
        BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD      byKeyCombo COMMA,
        BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_FAST byKeyCombo COMMA + SHIFT_DOWN,
        BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_SLOW byKeyCombo COMMA + CONTROL_DOWN,
        BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD       byKeyCombo PERIOD,
        BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_FAST  byKeyCombo PERIOD + SHIFT_DOWN,
        BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_SLOW  byKeyCombo PERIOD + CONTROL_DOWN,
        BUTTON_ZOOM_OUT                             byKeyCombo MINUS + SHIFT_ANY,
        BUTTON_ZOOM_OUT2                            byKeyCombo DOWN,
        BUTTON_ZOOM_IN                              byKeyCombo EQUALS + SHIFT_ANY,
        BUTTON_ZOOM_IN2                             byKeyCombo UP,
        SET_ROTATION_AXIS_X                         byKeyCombo X,
        SET_ROTATION_AXIS_Y                         byKeyCombo Y,
        SET_ROTATION_AXIS_Z                         byKeyCombo Z,
        KEY_ROTATE_LEFT                             byKeyCombo LEFT,
        KEY_ROTATE_LEFT_FAST                        byKeyCombo LEFT + SHIFT_DOWN,
        KEY_ROTATE_LEFT_SLOW                        byKeyCombo LEFT + CONTROL_DOWN,
        KEY_ROTATE_RIGHT                            byKeyCombo RIGHT,
        KEY_ROTATE_RIGHT_FAST                       byKeyCombo RIGHT + SHIFT_DOWN,
        KEY_ROTATE_RIGHT_SLOW                       byKeyCombo RIGHT + CONTROL_DOWN,
        REMOVE_ROTATION                             byKeyCombo Z + SHIFT_DOWN
    )

    fun namedCombinationsCopy() = namedComboMap.deepCopy
}
//@formatter:on
