package org.janelia.saalfeldlab.paintera.meshes;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

public class ShapeKey<T> {

	private final T shapeId;

	private final int scaleIndex;

	private final int simplificationIterations;

	private final double smoothingLambda;

	private final int smoothingIterations;

	private final double minLabelRatio;

	private final long[] min;

	private final long[] max;

	private final ToIntFunction<T> shapeIdHashCode;

	private final BiPredicate<T, Object> shapeIdEquals;

	public ShapeKey(
			final T shapeId,
			final int scaleIndex,
			final int simplificationIterations,
			final double smoothingLambda,
			final int smoothingIterations,
			final double minLabelRatio,
			final long[] min,
			final long[] max) {

		this(
				shapeId,
				scaleIndex,
				simplificationIterations,
				smoothingLambda,
				smoothingIterations,
				minLabelRatio,
				min,
				max,
				Objects::hashCode,
				Objects::equals);
	}

	public ShapeKey(
			final T shapeId,
			final int scaleIndex,
			final int simplificationIterations,
			final double smoothingLambda,
			final int smoothingIterations,
			final double minLabelRatio,
			final long[] min,
			final long[] max,
			final ToIntFunction<T> shapeIdHashCode,
			final BiPredicate<T, Object> shapeIdEquals) {

		this.shapeId = shapeId;
		this.scaleIndex = scaleIndex;
		this.simplificationIterations = simplificationIterations;
		this.smoothingLambda = smoothingLambda;
		this.smoothingIterations = smoothingIterations;
		this.minLabelRatio = minLabelRatio;
		this.min = min;
		this.max = max;
		this.shapeIdHashCode = shapeIdHashCode;
		this.shapeIdEquals = shapeIdEquals;
	}

	@Override
	public String toString() {

		return String.format(
				"{shapeId=%s, scaleIndex=%d, simplifications=%d, smoothingLambda=%.2f, smoothings=%d, minLabelRatio=%.2f, min=%s, max=%s}",
				shapeId,
				scaleIndex,
				simplificationIterations,
				smoothingLambda,
				smoothingIterations,
				minLabelRatio,
				Arrays.toString(min),
				Arrays.toString(max));
	}

	@Override
	public int hashCode() {

		int result = scaleIndex;
		// shapeId may be null, e.g. when using Void as shape Key
		result = 31 * result + shapeIdHashCode.applyAsInt(shapeId);
		result = 31 * result + simplificationIterations;
		result = 31 * result + Double.hashCode(smoothingLambda);
		result = 31 * result + smoothingIterations;
		result = 31 * result + Double.hashCode(minLabelRatio);
		result = 31 * result + Arrays.hashCode(this.min);
		result = 31 * result + Arrays.hashCode(this.max);
		return result;
	}

	@Override
	public boolean equals(final Object obj) {

		if (super.equals(obj))
			return true;

		if (obj instanceof ShapeKey<?>) {
			final ShapeKey<?> other = (ShapeKey<?>)obj;
			return
					shapeIdEquals.test(shapeId, other.shapeId) &&
							scaleIndex == other.scaleIndex &&
							simplificationIterations == other.simplificationIterations &&
							smoothingLambda == other.smoothingLambda &&
							smoothingIterations == other.smoothingIterations &&
							minLabelRatio == other.minLabelRatio &&
							Arrays.equals(min, other.min) &&
							Arrays.equals(max, other.max);
		}

		return false;
	}

	public T shapeId() {

		return shapeId;
	}

	public int scaleIndex() {

		return scaleIndex;
	}

	public int simplificationIterations() {

		return simplificationIterations;
	}

	public double smoothingLambda() {

		return smoothingLambda;
	}

	public int smoothingIterations() {

		return smoothingIterations;
	}

	public double minLabelRatio() {

		return minLabelRatio;
	}

	public long[] min() {

		return min.clone();
	}

	public long[] max() {

		return max.clone();
	}

	public void min(final long[] min) {

		System.arraycopy(this.min, 0, min, 0, min.length);
	}

	public void max(final long[] max) {

		System.arraycopy(this.max, 0, max, 0, max.length);
	}

	public Interval interval() {

		return new FinalInterval(min, max);
	}

}
