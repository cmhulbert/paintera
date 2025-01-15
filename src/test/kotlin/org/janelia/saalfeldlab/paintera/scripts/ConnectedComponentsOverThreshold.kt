package org.janelia.saalfeldlab.paintera.scripts

import javafx.scene.Group
import net.imglib2.algorithm.labeling.ConnectedComponents
import net.imglib2.algorithm.labeling.ConnectedComponents.StructuringElement
import net.imglib2.cache.img.CachedCellImg
import net.imglib2.cache.img.DiskCachedCellImgFactory
import net.imglib2.loops.LoopBuilder
import net.imglib2.parallel.TaskExecutors
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.volatiles.VolatileUnsignedLongType
import net.imglib2.view.Views
import org.janelia.saalfeldlab.labels.blocks.n5.LabelBlockLookupFromN5Relative
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.janelia.saalfeldlab.n5.universe.N5Factory
import org.janelia.saalfeldlab.paintera.ApplicationTestUtils
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState
import org.janelia.saalfeldlab.paintera.state.label.n5.N5BackendPainteraDataset
import org.janelia.saalfeldlab.paintera.state.metadata.MetadataUtils
import org.janelia.saalfeldlab.util.convert
import org.janelia.saalfeldlab.util.extendValue
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupAllBlocks
import org.janelia.saalfeldlab.util.interval
import org.janelia.saalfeldlab.util.n5.N5Data
import org.janelia.saalfeldlab.util.zeroMin
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

fun main() {

	mergeSegmentations()
}

private fun mergeSegmentations() {
	val writer = "/Users/hulbertc/data/jrc_fly-vnc-1.zarr/jrc_fly-vnc-1.zarr"
	val group = "/438000/segmentation"
	val datasets = listOf("er", "ld", "mito", "nucleus", "pm", "vs", "all_mem", "organelle")

	val imgs = mutableListOf< CachedCellImg<UnsignedLongType, *>>()
	val n5 = N5Factory.createWriter(writer)
	for (dataset in datasets) {
		imgs += N5Utils.open<UnsignedLongType>(n5, "$group/$dataset")
	}
	val ras = imgs.map { it.randomAccess() }
	val curId = AtomicLong(ras.size.toLong())
	val idMaps = ras.associate { it to mutableMapOf<Long, Long>() }
	val mergedLabels = DiskCachedCellImgFactory<UnsignedLongType>(UnsignedLongType(0)).create(imgs[0]) { chunk ->
		val cursor = chunk.localizingCursor()
		while (cursor.hasNext()) {
			cursor.next()
			for (ra in ras) {
				val id = ra.setPositionAndGet(cursor).get()
				if (id > 0L) {
					val newId = idMaps[ra]!!.computeIfAbsent(id) { curId.andIncrement }
					cursor.get().set(newId)
					break
				}
			}
		}
		println("Chunk @ ${chunk.minAsLongArray().contentToString()} - ${chunk.maxAsLongArray().contentToString()} Done!")
	}
	val fragmentSegmentMap = mutableMapOf<Long, Set<Long>>()
	for ((segment, fragments) in idMaps.values.withIndex()) {
		fragmentSegmentMap[segment.toLong()] = fragments.values.toSet()
	}

	val maxId = curId.get()
	val mergedLabelsGroup = "$group/merged_labels"
	N5Data.createEmptyLabelDataset(
		n5,
		mergedLabelsGroup,
		imgs[0].dimensionsAsLongArray(),
		imgs[0].cellGrid.cellDimensions,
		doubleArrayOf(8.0, 8.0, 8.0),
		doubleArrayOf(0.0, 0.0, 0.0),
		arrayOf(
//			doubleArrayOf(1.0, 1.0, 1.0)
		),
		"pixel",
		null,
		false,
		false
	)

	val metadataState = MetadataUtils.createMetadataState(n5, mergedLabelsGroup)!!
	val backend = N5BackendPainteraDataset(metadataState, Executors.newWorkStealingPool(), true)

//	MaskedSource()
//
//	ConnectomicsLabelState(
//		backend,
//		Group()
//
//	)
//




//	paintera.baseView.addConnectomicsLabelSource<UnsignedLongType, VolatileUnsignedLongType>(
//		mergedLabels,
//		doubleArrayOf(8.0, 8.0, 8.0),
//		doubleArrayOf(0.0, 0.0, 0.0),
//		maxId,
//		"merged labels",
//		LabelBlockLookupFromN5Relative()
//	)

}

private fun createAllSegmentations() {
	val writer = "/Users/hulbertc/data/jrc_fly-vnc-1.zarr/jrc_fly-vnc-1.zarr"
	val group = "438000"
	val datasets = listOf("all_mem", "er", "ld", "mito", "nucleus", "organelle", "pm", "vs")

	for (dataset in datasets) {
		print("Creating Segementation for /$group/$dataset ...")
		createSegmentation(writer, group, dataset)
		println("Done.")
	}

}

private fun createSegmentation(writer: String, group: String, dataset: String, save : Boolean = true, show: Boolean = false) {
	val n5 = N5Factory.createWriter(writer)
	val img = N5Utils.open<UnsignedByteType>(n5, "/$group/$dataset")
	val blockSize = img.cellGrid.cellDimensions
	val threshold = (img.type.maxValue / 2).toInt()
	val mask = img.extendValue(0).convert(BoolType()) { input, output -> output.set(input.get() >= threshold) }.interval(img)
	val connectedComponents = DiskCachedCellImgFactory<UnsignedLongType>(UnsignedLongType(0)).create(mask)
	val executor = Executors.newWorkStealingPool()
	ConnectedComponents.labelAllConnectedComponents(
		Views.expandZero(mask, 1, 1, 1).zeroMin(),
		Views.expandZero(connectedComponents, 1, 1, 1).zeroMin(),
		StructuringElement.FOUR_CONNECTED,
		executor
	)

	LoopBuilder.setImages(mask, Views.bundle(connectedComponents).interval(connectedComponents))
		.multiThreaded(TaskExecutors.forExecutorService(executor))
		.forEachPixel { maskVal, ccVal ->
			if (!maskVal.get())
				ccVal.get().set(0)
		}

	if (show) {
		ApplicationTestUtils.painteraTestApp()
		paintera.baseView.addConnectomicsLabelSource<UnsignedLongType, VolatileUnsignedLongType>(
			connectedComponents,
			doubleArrayOf(8.0, 8.0, 8.0),
			doubleArrayOf(0.0, 0.0, 0.0),
			1,
			"$dataset",
			LabelBlockLookupAllBlocks(
				arrayOf(img.dimensionsAsLongArray()),
				arrayOf(blockSize)
			)
		).also { state ->
			state.converter().getStream().overrideAlpha.put(0, 0)
			state.converter().alphaProperty().set(1)
			state.meshManager.managedSettings.meshesEnabledProperty.set(false)
		}
	}

	if (save)
		N5Utils.save(connectedComponents, n5, "/$group/segmentation/$dataset", blockSize, GzipCompression(), executor)
}