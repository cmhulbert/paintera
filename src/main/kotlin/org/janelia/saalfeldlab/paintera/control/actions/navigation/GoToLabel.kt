package org.janelia.saalfeldlab.paintera.control.actions.navigation

import javafx.beans.property.SimpleLongProperty
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.layout.VBox
import javafx.stage.Stage
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.morphology.distance.DistanceTransform
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.type.volatiles.VolatileFloatType
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.actions.verifyPermission
import org.janelia.saalfeldlab.fx.extensions.nullable
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookupKey
import org.janelia.saalfeldlab.paintera.Paintera
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd
import org.janelia.saalfeldlab.paintera.control.actions.*
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.data.n5.N5DataSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.raw.ConnectomicsRawState
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts
import org.janelia.saalfeldlab.util.convert
import org.janelia.saalfeldlab.util.extendValue
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupAllBlocks
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupNoBlocks
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.zeroMin


object GoToLabel : MenuAction("Go to _Label...") {

	init {
		verifyPermission(NavigationActionType.Pan)
		onAction(GoToLabelState()) { showDialog(it) }
	}

	private fun GoToLabelState.showDialog(event: Event?) {
		reset()
		initializeWithCurrentLabel()
		PainteraAlerts.confirmation("Go", "Cancel", true).apply {
			isResizable = true
			Paintera.registerStylesheets(dialogPane)
			title = name?.replace("_", "")
			headerText = "Go to Label"

			dialogPane.content = GoToLabelUI(this@showDialog)
		}.showAndWait().takeIf { it.nullable == ButtonType.OK }?.run {

			val targetLabel = labelProperty.value
			sourceState.selectedIds.activate(targetLabel)
			goToCoordinates(targetLabel)
		}
	}

	private fun GoToLabelState.goToCoordinates(labelId: Long) {

		/* TODO: Start at lowest res level, move up until we find a block that contains */
		when (sourceState.labelBlockLookup) {
			is LabelBlockLookupAllBlocks -> TODO("Warning, will be slow")
			is LabelBlockLookupNoBlocks -> TODO("No LabelBlockLookup Present")
		}

		val blocksWithLabel = sourceState.labelBlockLookup.read(LabelBlockLookupKey(0, labelId))
		val block = blocksWithLabel.first()

		val labelBlockMask = source.getSource(0, 0).interval(block).zeroMin().convert(BoolType()) { input, output ->
			output.set(input.integerLong != labelId)
		}

		val distances = ArrayImgs.floats(*block.dimensionsAsLongArray())

		DistanceTransform.binaryTransform(labelBlockMask, distances, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN)

		var min = Double.MAX_VALUE
		var max = -Double.MAX_VALUE
		lateinit var minPos : LongArray
		lateinit var maxPos : LongArray
		val offsetDistances = Views.translate(distances, *block.minAsLongArray())
		val cursor = offsetDistances.localizingCursor()
		cursor.forEach {
			val value = it.get().toDouble()
			if (value < min) {
				min = value
				minPos = cursor.positionAsLongArray()
			}
			if (value > max) {
				max = value
				maxPos = cursor.positionAsLongArray()
			}
		}

		val (x, y, z) = maxPos
		goToCoordinates(source, viewer, translationController, x.toDouble(), y.toDouble() ,z.toDouble())
	}
}


private fun GoToLabelState.addDistanceMaskAsSource(
	distances: RandomAccessibleInterval<FloatType>,
	min: Double,
	max: Double
): ConnectomicsRawState<FloatType, VolatileFloatType> {

	val maskedSource = source as MaskedSource<*, *>
	val sourceAlignedDistances = distances.extendValue(0.0).interval(maskedSource.getSource(0,0 ))

	val metadataState = (maskedSource.underlyingSource() as? N5DataSource)?.metadataState!!
	return paintera.baseView.addConnectomicsRawSource<FloatType, VolatileFloatType>(
		sourceAlignedDistances,
		metadataState.resolution,
		metadataState.translation,
		min,
		max,
		"blockDistForLabel"
	)!!.apply {
		composite = ARGBCompositeAlphaAdd()
	}
}