package net.imglib2.converter;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.paint.Color;
import net.imglib2.Volatile;
import net.imglib2.display.ColorConverter;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.composite.RealComposite;
import org.janelia.saalfeldlab.util.Colors;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class ARGBCompositeColorConverter<R extends RealType<R>, C extends RealComposite<R>, V extends Volatile<C>> implements
		Converter<V, ARGBType>
{
	protected final DoubleProperty alpha = new SimpleDoubleProperty(1.0);

	protected final DoubleProperty[] min;

	protected final DoubleProperty[] max;

	protected final DoubleProperty[] channelAlpha;

	protected final int numChannels;

	protected final ObjectProperty<ARGBType>[] color;

	protected int A;

	protected final double[] scaleR;

	protected final double[] scaleG;

	protected final double[] scaleB;

	protected int black;

	protected double alphaSum;

	protected double reciprocalAlphaSum;

	public ARGBCompositeColorConverter(final int numChannels)
	{
		this(numChannels, 0, 255);
	}

	public ARGBCompositeColorConverter(final int numChannels, final double min, final double max)
	{
		final double step = 360.0 / numChannels;
		this.min = IntStream
				.range(0, numChannels)
				.mapToObj(channel -> new SimpleDoubleProperty(this, "min channel " + channel, min))
				.toArray(DoubleProperty[]::new);
		this.max = IntStream
				.range(0, numChannels)
				.mapToObj(channel -> new SimpleDoubleProperty(this, "max channel " + channel, max))
				.toArray(DoubleProperty[]::new);
		this.channelAlpha = IntStream
				.range(0, numChannels)
				.mapToObj(channel -> new SimpleDoubleProperty(this, "alpha channel " + channel, 1.0))
				.toArray(DoubleProperty[]::new);
		this.color = IntStream
				.range(0, numChannels)
				.mapToObj(channel -> new SimpleObjectProperty(this, "color channel " + channel, Colors.toARGBType(Color.hsb(step * channel, 1.0, 1.0))))
				.toArray(ObjectProperty[]::new);
		this.numChannels = numChannels;
		this.scaleR = new double[numChannels];
		this.scaleG = new double[numChannels];
		this.scaleB = new double[numChannels];

		Stream.of(this.min).forEach(m -> m.addListener((obs, oldv, newv) -> update()));
		Stream.of(this.max).forEach(m -> m.addListener((obs, oldv, newv) -> update()));
		Stream.of(this.channelAlpha).forEach(m -> m.addListener((obs, oldv, newv) -> update()));
		Stream.of(this.color).forEach(c -> c.addListener((obs, oldv, newv) -> update()));
		this.alpha.addListener((obs, oldv, newv) -> update());

		update();
	}

	public DoubleProperty minProperty(int channel)
	{
		return min[channel];
	}

	public DoubleProperty maxProperty(int channel)
	{
		return max[channel];
	}

	public DoubleProperty alphaProperty()
	{
		return this.alpha;
	}

	private void update()
	{
		A = (int) Math.min(Math.max(Math.round(255 * alphaProperty().get()), 0), 255);
		alphaSum = 0.0;
		for (int channel = 0; channel < numChannels; ++channel) {
			final double scale = 1.0 / (max[channel].get() - min[channel].get());
			final int value = color[channel].get().get();
			final double a = channelAlpha[channel].get();
			scaleR[channel] = ARGBType.red(value) * scale * a;
			scaleG[channel] = ARGBType.green(value) * scale * a;
			scaleB[channel] = ARGBType.blue(value) * scale * a;
			alphaSum += a;
		}
		this.reciprocalAlphaSum = 1.0 / alphaSum;
		black = ARGBType.rgba(0, 0, 0, A);
	}

	public static <
		R extends RealType<R>,
		C extends RealComposite<R>,
		V extends Volatile<C>> ARGBCompositeColorConverter<R, C, V> imp0(final int numChannels)
	{
		return new InvertingImp0<>(numChannels);
	}

	public static <
			R extends RealType<R>,
			C extends RealComposite<R>,
			V extends Volatile<C>> ARGBCompositeColorConverter<R, C, V> imp1(final int numChannels)
	{
		return new InvertingImp0<>(numChannels);
	}

	public static <
			R extends RealType<R>,
			C extends RealComposite<R>,
			V extends Volatile<C>> ARGBCompositeColorConverter<R, C, V> imp0(final int numChannels, double min, double max)
	{
		return new InvertingImp0<>(numChannels, min, max);
	}

	public static <
			R extends RealType<R>,
			C extends RealComposite<R>,
			V extends Volatile<C>> ARGBCompositeColorConverter<R, C, V> imp1(final int numChannels, double min, double max)
	{
		return new InvertingImp0<>(numChannels, min, max);
	}

	private static class InvertingImp0<
			R extends RealType<R>,
			C extends RealComposite<R>,
			V extends Volatile<C>> extends ARGBCompositeColorConverter<R, C, V>
	{

		public InvertingImp0(final int numChannels)
		{
			super(numChannels);
		}

		public InvertingImp0(final int numChannels, final double min, final double max)
		{
			super(numChannels, min, max);
		}

		@Override
		public void convert(final V input, final ARGBType output)
		{
			double rd = 0.0;
			double gd = 0.0;
			double bd = 0.0;
			double alphaSum = 0.0;
			final C c = input.get();
			for (int channel = 0; channel < numChannels; ++channel) {
				final double v = c.get(channel).getRealDouble() - min[channel].get();
				rd += scaleR[channel] * v;
				gd += scaleG[channel] * v;
				bd += scaleB[channel] * v;
			}
			final int r0 = (int) (rd * reciprocalAlphaSum + 0.5);
			final int g0 = (int) (gd * reciprocalAlphaSum + 0.5);
			final int b0 = (int) (bd * reciprocalAlphaSum + 0.5);
			final int r = Math.min(255, Math.max(r0, 0));
			final int g = Math.min(255, Math.max(g0, 0));
			final int b = Math.min(255, Math.max(b0, 0));
			output.set(ARGBType.rgba(r, g, b, A));
		}
	}

	private static class InvertingImp1<
			R extends RealType<R>,
			C extends RealComposite<R>,
			V extends Volatile<C>> extends ARGBCompositeColorConverter<R, C, V>
	{

		public InvertingImp1(final int numChannels)
		{
			super(numChannels);
		}

		public InvertingImp1(final int numChannels, final double min, final double max)
		{
			super(numChannels, min, max);
		}

		@Override
		public void convert(final V input, final ARGBType output)
		{
			double rd = 0.0;
			double gd = 0.0;
			double bd = 0.0;
			double alphaSum = 0.0;
			final C c = input.get();
			for (int channel = 0; channel < numChannels; ++channel) {
				final double v = c.get(channel).getRealDouble() - min[channel].get();
				rd += scaleR[channel] * v;
				gd += scaleG[channel] * v;
				bd += scaleB[channel] * v;
			}
			final int r0 = (int) (rd * reciprocalAlphaSum + 0.5);
			final int g0 = (int) (gd * reciprocalAlphaSum + 0.5);
			final int b0 = (int) (bd * reciprocalAlphaSum + 0.5);
			final int r = Math.min(255, Math.max(r0, 0));
			final int g = Math.min(255, Math.max(g0, 0));
			final int b = Math.min(255, Math.max(b0, 0));
			output.set(ARGBType.rgba(r, g, b, A));
		}
	}
}
