package io.github.wesleym.mappa.internal.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal, dependency-free {@link Graphics2D} that records the subset of drawing the diagram renderer uses
 * into a vector SVG document. Text is emitted as outlined glyph paths, so the export carries no font
 * dependency and never reflows on a viewer that lacks the font — the same fidelity Batik's {@code textAsShapes}
 * gave, in pure JDK.
 *
 * <p>Scope is deliberately the renderer's static {@code draw} path: solid/gradient fills, stroked shapes,
 * outlined text, alpha composites. Clipping is tracked but not enforced — the renderer only clips column rows
 * to their box, and an exported box is sized to fit every row, so the clip never cuts anything.
 */
final class SvgGraphics2D extends Graphics2D {

	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
	private static final BufferedImage METRICS_IMAGE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

	private final int width;
	private final int height;
	private final StringBuilder body;
	private final StringBuilder defs;
	private final int[] gradientId;   // shared counter, boxed in an array so create() copies share it

	private AffineTransform transform = new AffineTransform();
	private Paint paint = Color.BLACK;
	private Color color = Color.BLACK;
	private Color background = Color.WHITE;
	private Stroke stroke = new BasicStroke();
	private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private Composite composite = AlphaComposite.SrcOver;
	private Shape clip;

	SvgGraphics2D(int width, int height) {
		this.width = width;
		this.height = height;
		this.body = new StringBuilder();
		this.defs = new StringBuilder();
		this.gradientId = new int[1];
	}

	private SvgGraphics2D(SvgGraphics2D parent) {
		this.width = parent.width;
		this.height = parent.height;
		this.body = parent.body;
		this.defs = parent.defs;
		this.gradientId = parent.gradientId;
		this.transform = new AffineTransform(parent.transform);
		this.paint = parent.paint;
		this.color = parent.color;
		this.background = parent.background;
		this.stroke = parent.stroke;
		this.font = parent.font;
		this.composite = parent.composite;
		this.clip = parent.clip;
	}

	/** Finalises the recorded elements into a standalone SVG document string. */
	String document() {
		StringBuilder svg = new StringBuilder(256 + body.length() + defs.length());
		svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
				.append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ')
				.append(height).append("\">\n");
		if (defs.length() > 0) {
			svg.append("<defs>\n").append(defs).append("</defs>\n");
		}
		svg.append(body).append("</svg>\n");
		return svg.toString();
	}

	// ---- fills and strokes ---------------------------------------------------------------------------

	@Override
	public void fill(Shape s) {
		String d = pathData(s);
		if (d.isEmpty()) {
			return;
		}
		body.append("<path d=\"").append(d).append("\" fill=\"").append(paintRef())
				.append("\" fill-opacity=\"").append(paintOpacity()).append('"').append(fillRule(s)).append("/>\n");
	}

	@Override
	public void draw(Shape s) {
		String d = pathData(s);
		if (d.isEmpty()) {
			return;
		}
		body.append("<path d=\"").append(d).append("\" fill=\"none\" ").append(strokeAttrs()).append("/>\n");
	}

	@Override
	public void drawString(String str, float x, float y) {
		if (str == null || str.isEmpty()) {
			return;
		}
		GlyphVector gv = font.createGlyphVector(FRC, str);
		fill(gv.getOutline(x, y));
	}

	@Override
	public void drawString(String str, int x, int y) {
		drawString(str, (float) x, (float) y);
	}

	@Override
	public void drawGlyphVector(GlyphVector g, float x, float y) {
		fill(g.getOutline(x, y));
	}

	// ---- convenience shapes → fill/draw --------------------------------------------------------------

	@Override
	public void drawLine(int x1, int y1, int x2, int y2) {
		draw(new Line2D.Double(x1, y1, x2, y2));
	}

	@Override
	public void fillRect(int x, int y, int w, int h) {
		fill(new Rectangle2D.Double(x, y, w, h));
	}

	@Override
	public void drawRect(int x, int y, int w, int h) {
		draw(new Rectangle2D.Double(x, y, w, h));
	}

	@Override
	public void clearRect(int x, int y, int w, int h) {
		Paint saved = paint;
		Color savedColor = color;
		setColor(background);
		fillRect(x, y, w, h);
		paint = saved;
		color = savedColor;
	}

	@Override
	public void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH) {
		fill(new RoundRectangle2D.Double(x, y, w, h, arcW, arcH));
	}

	@Override
	public void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH) {
		draw(new RoundRectangle2D.Double(x, y, w, h, arcW, arcH));
	}

	@Override
	public void fillOval(int x, int y, int w, int h) {
		fill(new Ellipse2D.Double(x, y, w, h));
	}

	@Override
	public void drawOval(int x, int y, int w, int h) {
		draw(new Ellipse2D.Double(x, y, w, h));
	}

	@Override
	public void fillPolygon(int[] xs, int[] ys, int n) {
		fill(polygon(xs, ys, n));
	}

	@Override
	public void drawPolygon(int[] xs, int[] ys, int n) {
		draw(polygon(xs, ys, n));
	}

	@Override
	public void drawPolyline(int[] xs, int[] ys, int n) {
		java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
		if (n > 0) {
			p.moveTo(xs[0], ys[0]);
			for (int i = 1; i < n; i++) {
				p.lineTo(xs[i], ys[i]);
			}
		}
		draw(p);
	}

	private static Shape polygon(int[] xs, int[] ys, int n) {
		java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
		if (n > 0) {
			p.moveTo(xs[0], ys[0]);
			for (int i = 1; i < n; i++) {
				p.lineTo(xs[i], ys[i]);
			}
			p.closePath();
		}
		return p;
	}

	// ---- serialisation helpers -----------------------------------------------------------------------

	private String pathData(Shape s) {
		PathIterator it = s.getPathIterator(transform);   // applies the current transform to every coordinate
		StringBuilder d = new StringBuilder();
		double[] c = new double[6];
		while (!it.isDone()) {
			switch (it.currentSegment(c)) {
				case PathIterator.SEG_MOVETO -> d.append('M').append(n(c[0])).append(' ').append(n(c[1])).append(' ');
				case PathIterator.SEG_LINETO -> d.append('L').append(n(c[0])).append(' ').append(n(c[1])).append(' ');
				case PathIterator.SEG_QUADTO -> d.append('Q').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
						.append(n(c[2])).append(' ').append(n(c[3])).append(' ');
				case PathIterator.SEG_CUBICTO -> d.append('C').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
						.append(n(c[2])).append(' ').append(n(c[3])).append(' ')
						.append(n(c[4])).append(' ').append(n(c[5])).append(' ');
				case PathIterator.SEG_CLOSE -> d.append("Z ");
				default -> { }
			}
			it.next();
		}
		return d.toString().trim();
	}

	private String fillRule(Shape s) {
		return s.getPathIterator(null).getWindingRule() == PathIterator.WIND_EVEN_ODD
				? " fill-rule=\"evenodd\"" : "";
	}

	// The current paint as an SVG reference: a solid colour hex, or a freshly-registered linear-gradient url().
	// Used for both fill and stroke, so a gradient-stroked edge (directional mode) serialises faithfully.
	private String paintRef() {
		if (paint instanceof GradientPaint g) {
			Point2D p1 = transform.transform(g.getPoint1(), null);
			Point2D p2 = transform.transform(g.getPoint2(), null);
			int id = ++gradientId[0];
			defs.append("<linearGradient id=\"g").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"")
					.append(n(p1.getX())).append("\" y1=\"").append(n(p1.getY())).append("\" x2=\"")
					.append(n(p2.getX())).append("\" y2=\"").append(n(p2.getY())).append("\">")
					.append(stop(0, g.getColor1())).append(stop(1, g.getColor2()))
					.append("</linearGradient>\n");
			return "url(#g" + id + ")";
		}
		return hex(paint instanceof Color c ? c : color);
	}

	// Element opacity = the composite alpha, times the solid colour's own alpha. A gradient's stops carry
	// their colours' alpha themselves, so only the composite applies at the element.
	private String paintOpacity() {
		double a = composite instanceof AlphaComposite ac ? ac.getAlpha() : 1.0;
		if (!(paint instanceof GradientPaint)) {
			a *= (paint instanceof Color c ? c : color).getAlpha() / 255.0;
		}
		return n(a);
	}

	private String stop(int offset, Color c) {
		return "<stop offset=\"" + offset + "\" stop-color=\"" + hex(c)
				+ "\" stop-opacity=\"" + n(c.getAlpha() / 255.0) + "\"/>";
	}

	private String strokeAttrs() {
		StringBuilder sb = new StringBuilder();
		sb.append("stroke=\"").append(paintRef()).append("\" stroke-opacity=\"").append(paintOpacity())
				.append('"');
		double scale = Math.sqrt(Math.abs(transform.getDeterminant()));
		if (stroke instanceof BasicStroke bs) {
			sb.append(" stroke-width=\"").append(n(Math.max(bs.getLineWidth(), 0.01f) * scale)).append('"');
			sb.append(" stroke-linecap=\"").append(cap(bs.getEndCap())).append('"');
			sb.append(" stroke-linejoin=\"").append(join(bs.getLineJoin())).append('"');
			float[] dash = bs.getDashArray();
			if (dash != null && dash.length > 0) {
				sb.append(" stroke-dasharray=\"");
				for (int i = 0; i < dash.length; i++) {
					sb.append(i == 0 ? "" : ",").append(n(dash[i] * scale));
				}
				sb.append('"');
			}
		}
		else {
			sb.append(" stroke-width=\"").append(n(scale)).append('"');
		}
		return sb.toString();
	}

	private static String cap(int endCap) {
		return switch (endCap) {
			case BasicStroke.CAP_ROUND -> "round";
			case BasicStroke.CAP_SQUARE -> "square";
			default -> "butt";
		};
	}

	private static String join(int lineJoin) {
		return switch (lineJoin) {
			case BasicStroke.JOIN_ROUND -> "round";
			case BasicStroke.JOIN_BEVEL -> "bevel";
			default -> "miter";
		};
	}

	private static String hex(Color c) {
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	// Compact number: trim trailing zeros, drop a bare ".0", cap at 3 decimals.
	private static String n(double v) {
		if (v == Math.rint(v) && !Double.isInfinite(v)) {
			return Long.toString((long) v);
		}
		String s = String.format(Locale.ROOT, "%.3f", v);
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 0 && s.charAt(end - 1) == '.') {
			end--;
		}
		return s.substring(0, end);
	}

	// ---- state ---------------------------------------------------------------------------------------

	@Override
	public void setColor(Color c) {
		if (c != null) {
			this.color = c;
			this.paint = c;
		}
	}

	@Override
	public Color getColor() {
		return color;
	}

	@Override
	public void setPaint(Paint p) {
		this.paint = p;
		if (p instanceof Color c) {
			this.color = c;
		}
	}

	@Override
	public Paint getPaint() {
		return paint;
	}

	@Override
	public void setStroke(Stroke s) {
		this.stroke = s;
	}

	@Override
	public Stroke getStroke() {
		return stroke;
	}

	@Override
	public void setComposite(Composite c) {
		this.composite = c;
	}

	@Override
	public Composite getComposite() {
		return composite;
	}

	@Override
	public void setFont(Font f) {
		if (f != null) {
			this.font = f;
		}
	}

	@Override
	public Font getFont() {
		return font;
	}

	@Override
	public void setBackground(Color c) {
		this.background = c;
	}

	@Override
	public Color getBackground() {
		return background;
	}

	@Override
	public FontMetrics getFontMetrics(Font f) {
		return METRICS_IMAGE.createGraphics().getFontMetrics(f);
	}

	@Override
	public FontRenderContext getFontRenderContext() {
		return FRC;
	}

	// ---- transform -----------------------------------------------------------------------------------

	@Override
	public void translate(int x, int y) {
		transform.translate(x, y);
	}

	@Override
	public void translate(double x, double y) {
		transform.translate(x, y);
	}

	@Override
	public void scale(double sx, double sy) {
		transform.scale(sx, sy);
	}

	@Override
	public void rotate(double theta) {
		transform.rotate(theta);
	}

	@Override
	public void rotate(double theta, double x, double y) {
		transform.rotate(theta, x, y);
	}

	@Override
	public void shear(double shx, double shy) {
		transform.shear(shx, shy);
	}

	@Override
	public void transform(AffineTransform tx) {
		transform.concatenate(tx);
	}

	@Override
	public void setTransform(AffineTransform tx) {
		transform = new AffineTransform(tx);
	}

	@Override
	public AffineTransform getTransform() {
		return new AffineTransform(transform);
	}

	// ---- clip (tracked, not enforced — see class doc) ------------------------------------------------

	@Override
	public Shape getClip() {
		return clip;
	}

	@Override
	public void setClip(Shape c) {
		this.clip = c;
	}

	@Override
	public void setClip(int x, int y, int w, int h) {
		this.clip = new Rectangle(x, y, w, h);
	}

	@Override
	public void clipRect(int x, int y, int w, int h) {
		this.clip = new Rectangle(x, y, w, h);
	}

	@Override
	public void clip(Shape s) {
		this.clip = s;
	}

	@Override
	public Rectangle getClipBounds() {
		return clip == null ? null : clip.getBounds();
	}

	// ---- rendering hints (ignored — output is resolution-independent vector) -------------------------

	@Override
	public void setRenderingHint(RenderingHints.Key key, Object value) { }

	@Override
	public Object getRenderingHint(RenderingHints.Key key) {
		return null;
	}

	@Override
	public void setRenderingHints(Map<?, ?> hints) { }

	@Override
	public void addRenderingHints(Map<?, ?> hints) { }

	@Override
	public RenderingHints getRenderingHints() {
		return new RenderingHints(null);
	}

	// ---- lifecycle -----------------------------------------------------------------------------------

	@Override
	public Graphics create() {
		return new SvgGraphics2D(this);
	}

	@Override
	public void dispose() { }

	// ---- unsupported / no-op surface (never invoked by the diagram renderer) -------------------------

	@Override
	public void setPaintMode() { }

	@Override
	public void setXORMode(Color c) { }

	@Override
	public void copyArea(int x, int y, int w, int h, int dx, int dy) { }

	@Override
	public void drawArc(int x, int y, int w, int h, int start, int extent) { }

	@Override
	public void fillArc(int x, int y, int w, int h, int start, int extent) { }

	@Override
	public boolean drawImage(Image img, int x, int y, ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2,
			ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2,
			Color bg, ImageObserver o) {
		return true;
	}

	@Override
	public boolean drawImage(Image img, AffineTransform xform, ImageObserver o) {
		return true;
	}

	@Override
	public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { }

	@Override
	public void drawRenderedImage(RenderedImage img, AffineTransform xform) { }

	@Override
	public void drawRenderableImage(RenderableImage img, AffineTransform xform) { }

	@Override
	public void drawString(AttributedCharacterIterator iterator, int x, int y) { }

	@Override
	public void drawString(AttributedCharacterIterator iterator, float x, float y) { }

	@Override
	public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
		return false;
	}

	@Override
	public GraphicsConfiguration getDeviceConfiguration() {
		return null;
	}
}
