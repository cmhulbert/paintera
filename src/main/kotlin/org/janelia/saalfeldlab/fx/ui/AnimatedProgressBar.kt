package org.janelia.saalfeldlab.fx.ui

import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.beans.property.DoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.control.ProgressBar
import javafx.util.Duration
import org.janelia.saalfeldlab.fx.extensions.nonnull

open class AnimatedProgressBar : ProgressBar() {

	companion object {
		private const val END_CUE = "END"
	}

	private val timeline = Timeline()

	var reversible = false
	var baseDuration: Duration = Duration.seconds(1.0)

	val progressTargetProperty: DoubleProperty = SimpleDoubleProperty().apply {
		subscribe { progress ->
			updateTimeline(value)
		}
	}
	var progressTarget by progressTargetProperty.nonnull()

	private var lastUpdateTime : Long? = null
	private var runningAverageBetweenUpdates = 0.0


	protected open fun updateTimeline(newTarget: Double) {

		val thisPortion = lastUpdateTime?.let { System.currentTimeMillis() - it }?.div(2.0) ?: 0.0
		runningAverageBetweenUpdates = runningAverageBetweenUpdates / 2.0 + thisPortion
		lastUpdateTime = System.currentTimeMillis()

		timeline.stop()
		timeline.jumpTo(END_CUE)
		val progressProperty = progressProperty()
		if (newTarget == 0.0) {
			progressProperty.value = 0.0
			return
		}


		if (!reversible && newTarget <= progressProperty.get()) return

		val resultDuration =
			if (newTarget >= 1.0) Duration.seconds(.25)
			else baseDuration.add(Duration.millis(runningAverageBetweenUpdates))

		timeline.keyFrames.setAll(
			KeyFrame(Duration.ZERO, KeyValue(progressProperty, progressProperty.value)),
			KeyFrame(resultDuration, KeyValue(progressProperty, newTarget))
		)
		timeline.cuePoints[END_CUE] = resultDuration
		timeline.play()
	}

	fun finish() {
		timeline.stop()
		progressProperty().unbind()
		progressProperty().value = 1.0
		timeline.jumpTo(END_CUE)
	}

	fun stop() = timeline.stop()
}