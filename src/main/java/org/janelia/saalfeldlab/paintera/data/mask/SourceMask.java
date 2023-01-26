package org.janelia.saalfeldlab.paintera.data.mask;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Invalidate;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.volatiles.VolatileUnsignedLongType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.function.Predicate;

public class SourceMask implements Mask {

	protected MaskInfo info;
	protected RandomAccessibleInterval<UnsignedLongType> raiOverSource;
	protected RandomAccessibleInterval<VolatileUnsignedLongType> volatileRaiOverSource;
	protected Invalidate<?> invalidate;
	protected Invalidate<?> invalidateVolatile;
	protected Runnable shutdown;

	protected SourceMask() {

	}

	protected SourceMask(MaskInfo info) {

		this.info = info;
	}

	public SourceMask(
			final MaskInfo info,
			final RandomAccessibleInterval<UnsignedLongType> rai,
			final RandomAccessibleInterval<VolatileUnsignedLongType> volatileRai,
			final Invalidate<?> invalidate,
			final Invalidate<?> invalidateVolatile,
			final Runnable shutdown) {

		this.info = info;
		this.raiOverSource = rai;
		this.volatileRaiOverSource = volatileRai;
		this.invalidate = invalidate;
		this.invalidateVolatile = invalidateVolatile;
		this.shutdown = shutdown;
	}

	@Override public MaskInfo getInfo() {

		return info;
	}

	@Override public RandomAccessibleInterval<UnsignedLongType> getRai() {

		return raiOverSource;
	}

	@Override public RandomAccessibleInterval<VolatileUnsignedLongType> getVolatileRai() {

		return volatileRaiOverSource;
	}

	@Override public Invalidate<?> getInvalidate() {

		return invalidate;
	}

	@Override public Invalidate<?> getInvalidateVolatile() {

		return invalidateVolatile;
	}

	@Override public Runnable getShutdown() {

		return shutdown;
	}

	public <C extends IntegerType<C>> void applyMaskToCanvas(
			final RandomAccessibleInterval<C> canvas,
			final Predicate<UnsignedLongType> acceptAsPainted) {

		final IntervalView<UnsignedLongType> maskOverCanvas = Views.interval(getRai(), canvas);
		final long value = getInfo().value.get();

		LoopBuilder.setImages(maskOverCanvas, canvas).multiThreaded().forEachPixel((maskVal, canvasVal) -> {
			if (acceptAsPainted.test(maskVal))
				canvasVal.setInteger(value);
		});
	}
}
