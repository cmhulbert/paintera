package org.janelia.saalfeldlab.paintera.state;

import groovy.util.Eval.x
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import net.imglib2.FinalInterval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.Volatile
import net.imglib2.algorithm.fill.FloodFill
import net.imglib2.algorithm.labeling.ConnectedComponents
import net.imglib2.algorithm.lazy.Lazy
import net.imglib2.algorithm.neighborhood.DiamondShape
import net.imglib2.algorithm.neighborhood.Shape
import net.imglib2.cache.img.CachedCellImg
import net.imglib2.cache.img.DiskCachedCellImgFactory
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.NativeType
import net.imglib2.type.Type
import net.imglib2.type.label.Label.BACKGROUND
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.painteraActionSet
import org.janelia.saalfeldlab.paintera.control.modes.RawSourceMode
import org.janelia.saalfeldlab.paintera.control.tools.paint.Fill3DTool
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.util.convert
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupAllBlocks
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.translate
import org.janelia.saalfeldlab.util.zeroMin
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiPredicate
import kotlin.collections.flatten
import kotlin.to

class ThresholdSourceMode : RawSourceMode() {
	override val modeActions = listOf<ActionSet>(createConnectedComponentSourceAction())

	companion object {


		tailrec fun merge(
			lists: Set<Set<Long>>,
			results: MutableSet<Set<Long>> = mutableSetOf()
		): Set<Set<Long>> {
			if (lists.isEmpty()) return results

			val merged = lists.first().toMutableSet()
			val remaining = mutableSetOf<Set<Long>>()

			// Iterate through the rest of the lists to check for intersections
			for (list in lists.drop(1)) {
				if (merged.any { it in list }) {
					merged.addAll(list)
				} else {
					remaining.add(list)
				}
			}

			results.add(merged.toSet())

			return merge(remaining, results)
		}
	}


	fun <D, T> createConnectedComponentSourceAction(): ActionSet where
			D : IntegerType<D>, D : NativeType<D>, T : Volatile<D>, T : Type<T> {
		return painteraActionSet("Create Connected Component Source") {
			KEY_PRESSED(KeyCode.SHIFT, KeyCode.C) {
				onAction {
					val executor = Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() * 2)
					var source = paintera.baseView.sourceInfo().currentState().get() as? ThresholdingSourceState<*, *> ?: return@onAction
					val dataSource = source.dataSource.getDataSource(0, 0)
					val rai = ((source.dataSource.type)?.let {
						dataSource
					} ?: let {
						source.dataSource.getDataSource(0, 0).convert(UnsignedLongType()) { input, output ->
							output.set(input.integerLong)
						}.interval(dataSource)
					})


					val border = LongArray(dataSource.numDimensions()) { 1L }
					val connectedComponents = DiskCachedCellImgFactory<D>(UnsignedLongType(0) as D).create(rai) { block ->
						val blockIdx = block.minAsLongArray().zip(block.dimensionsAsLongArray()).fold(1L) { acc, (l, r) -> acc * ((l + 1) * (r + 1)) }
						val thresholdAtBlock = dataSource.interval(block).zeroMin()
						val zeroMinBlock = block.zeroMin()
						val expandedInput = Views.expandZero(thresholdAtBlock, *border).zeroMin()
						val expandedBlock = Views.expandZero(zeroMinBlock, *border).zeroMin()
						ConnectedComponents.labelAllConnectedComponents(
							expandedInput,
							expandedBlock,
							ConnectedComponents.StructuringElement.FOUR_CONNECTED,
							executor
						)

						LoopBuilder.setImages(thresholdAtBlock, zeroMinBlock).forEachPixel { threshold, component ->
							val id = when {
								!threshold.get() -> 0L
								else -> component.integerLong + blockIdx
							}
							component.integer = id.toInt()
						}
					}

					val connectedFragmentTasks = mutableListOf<Future<Set<Set<Long>>?>>()

					val cells = connectedComponents.cellGrid.cellIntervals()
					for (cell in cells) {
						break
						val block = FinalInterval(cell)
						val task = executor.submit(Callable<Set<Set<Long>>> {
							var connectedFragments: MutableSet<MutableSet<Long>>? = null
							for (i in 0 until block.numDimensions()) {
								if (block.min(i) == 0L)
									continue
								val faceSize = block.dimensionsAsLongArray().also { it[i] = 1 }
								val ourFace = Intervals.createMinSize(
									*block.minAsLongArray(),
									*faceSize,
								)
								val prevFace = Intervals.createMinSize(
									*ourFace.minAsLongArray().also { it[i] = it[i] - 1L },
									*faceSize
								)

								LoopBuilder.setImages(connectedComponents.interval(ourFace), Views.bundle(connectedComponents).interval(prevFace)).forEachPixel { l, r ->
									val lId = l.integerLong
									if (lId != 0L) {
										val rId = r.get().integerLong
										if (rId != 0L) {
											connectedFragments = (connectedFragments ?: hashSetOf<MutableSet<Long>>()).also { it.add(sortedSetOf(lId, rId)) }
										}
									}
								}
							}
							connectedFragments
						})
						connectedFragmentTasks += task
					}

					/* get all lists */
					val chunks = connectedFragmentTasks.mapNotNull { it.get() }.flatten().chunked(Runtime.getRuntime().availableProcessors() * 2)
					connectedFragmentTasks.clear()
					for (set in chunks) {
						connectedFragmentTasks += executor.submit<Set<Set<Long>>> {
							merge(set.toHashSet())
						}
					}

					val mergedComponents = merge(connectedFragmentTasks.mapNotNull { it.get() }.flatten().toHashSet())
					val reducedComponentMap = mutableMapOf<Long, Long>()
					for (component in mergedComponents) {
						if (component.isEmpty()) continue
						val fragments = component.sorted()
						val min = fragments.first()
						for (id in fragments.drop(1)) {
							reducedComponentMap[id] = min
						}
					}

					val minMappedComponents = connectedComponents.convert(connectedComponents.type) { input, output ->
						val id = reducedComponentMap.get(input.integerLong) ?: input.integerLong
						output.setInteger(id)
					}.interval(connectedComponents)


					val transform = source.dataSource.getSourceTransformCopy(0, 0)
					val resolution = doubleArrayOf(transform.get(0, 0), transform.get(1, 1), transform.get(2, 2))
					val offset = transform.translation.also { translation ->
						(source.underlyingSource as? SourceStateWithBackend<*, *>)?.virtualCrop?.minAsDoubleArray()?.let { crop ->
							for (i in 0 until translation.size) {
								translation[i] += crop[i] * resolution[i]
							}
						}
					}
					with(paintera.baseView) {
						sourceInfo().trackSources()
							.firstOrNull { it.name == "Fragments" || it.name == "Components" }
							?.let { sourceInfo().removeSource(it) }

						addConnectomicsLabelSource<D, T>(
							connectedComponents,
							resolution,
							offset,
							1L,
							"Fragments",
							LabelBlockLookupAllBlocks.fromSource(source.dataSource)
						).also {
							it.meshManager.managedSettings.meshesEnabledProperty.set(false)
							it.converter().getStream().overrideAlpha.put(BACKGROUND, 0)
						}
						addConnectomicsLabelSource<D, T>(
							minMappedComponents,
							resolution,
							offset,
							1L,
							"Components",
							LabelBlockLookupAllBlocks.fromSource(source.dataSource)
						).also {
							it.meshManager.managedSettings.meshesEnabledProperty.set(false)
							it.converter().getStream().overrideAlpha.put(BACKGROUND, 0)
						}
					}
				}
			}
		}
	}
}

fun main() {
	println(listOf(1,2,3,4,5).take(10).toTypedArray().contentToString())
	println(listOf(1,2,3,4,5).drop(2).toTypedArray().contentToString())
	println(listOf(1,2,3,4,5).drop(10).toTypedArray().contentToString())
}