package io.github.wesleym.mappa.internal.render;

import org.junit.jupiter.api.Test;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgGraphics2DTest {

	@Test
	void wrapsAWellFormedDocument() {
		SvgGraphics2D g = new SvgGraphics2D(120, 80);
		g.setColor(new Color(0x336699));
		g.fillRect(0, 0, 120, 80);
		String svg = g.document();
		assertTrue(svg.startsWith("<svg"));
		assertTrue(svg.contains("width=\"120\""));
		assertTrue(svg.contains("</svg>"));
		assertTrue(svg.contains("fill=\"#336699\""));
	}

	@Test
	void solidFillFoldsColourAndCompositeAlpha() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
		g.setColor(new Color(255, 0, 0, 128));   // 128/255 ≈ 0.502, × 0.5 composite ≈ 0.251
		g.fill(new Rectangle2D.Double(0, 0, 10, 10));
		assertTrue(g.document().contains("fill-opacity=\"0.251\""), g.document());
	}

	@Test
	void gradientFillEmitsALinearGradientDef() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		g.setPaint(new GradientPaint(0, 0, Color.RED, 10, 10, Color.BLUE));
		g.fill(new Rectangle2D.Double(0, 0, 10, 10));
		String svg = g.document();
		assertTrue(svg.contains("<defs>"));
		assertTrue(svg.contains("<linearGradient"));
		assertTrue(svg.contains("stop-color=\"#FF0000\""));
		assertTrue(svg.contains("url(#g1)"));
	}

	@Test
	void gradientStrokeAlsoReferencesTheGradient() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		g.setPaint(new GradientPaint(0, 0, Color.RED, 10, 0, Color.BLUE));
		g.setStroke(new BasicStroke(2));
		g.draw(new Line2D.Double(0, 0, 10, 0));
		assertTrue(g.document().contains("stroke=\"url(#g1)\""), g.document());
	}

	@Test
	void strokeAttributesCoverCapsJoinsAndDashes() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 4, 2 }, 0));
		g.drawLine(0, 0, 10, 10);
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_BEVEL));
		g.draw(new Rectangle2D.Double(0, 0, 5, 5));
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g.drawOval(0, 0, 5, 5);
		String svg = g.document();
		assertTrue(svg.contains("stroke-linecap=\"round\"") && svg.contains("stroke-dasharray=\"4,2\""));
		assertTrue(svg.contains("stroke-linecap=\"square\"") && svg.contains("stroke-linejoin=\"bevel\""));
		assertTrue(svg.contains("stroke-linecap=\"butt\"") && svg.contains("stroke-linejoin=\"miter\""));
	}

	@Test
	void evenOddShapeEmitsFillRule() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
		p.append(new Rectangle2D.Double(0, 0, 20, 20), false);
		p.append(new Rectangle2D.Double(5, 5, 10, 10), false);
		g.fill(p);
		assertTrue(g.document().contains("fill-rule=\"evenodd\""));
	}

	@Test
	void transformScalesStrokeWidthAndCoordinates() {
		SvgGraphics2D g = new SvgGraphics2D(50, 50);
		g.translate(10.0, 5.0);
		g.scale(2, 2);
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(2));
		g.drawLine(0, 0, 5, 0);
		// 2px stroke under a 2× scale renders at width 4; the moveto lands at the translated origin (10,5).
		String svg = g.document();
		assertTrue(svg.contains("stroke-width=\"4\""), svg);
		assertTrue(svg.contains("M10 5"), svg);
	}

	@Test
	void textIsEmittedAsOutlinedPathsNotLiteralText() {
		SvgGraphics2D g = new SvgGraphics2D(80, 40);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.setColor(Color.BLACK);
		g.drawString("Ab", 4, 20);
		String svg = g.document();
		assertFalse(svg.contains("<text"));
		assertTrue(svg.contains("<path"));
	}

	@Test
	void tracksStateAndDerivedGraphicsShareTheDocument() {
		SvgGraphics2D g = new SvgGraphics2D(30, 30);
		g.setClip(2, 2, 10, 10);
		assertEquals(new Rectangle(2, 2, 10, 10), g.getClipBounds());
		g.clip(new Rectangle(0, 0, 5, 5));
		assertNotNull(g.getClip());
		assertNotNull(g.getFontMetrics(g.getFont()));
		assertNotNull(g.getFontRenderContext());
		assertNotNull(g.getTransform());

		Graphics2D child = (Graphics2D) g.create();
		child.setColor(Color.BLACK);
		child.fillOval(0, 0, 4, 4);   // drawn on the shared body
		child.dispose();
		assertTrue(g.document().contains("<path"), "child draw lands in the parent document");
	}

	@Test
	void guardsAndNonBasicStrokeAndInertSurface() {
		SvgGraphics2D g = new SvgGraphics2D(40, 40);
		Color before = g.getColor();
		g.setColor(null);                       // ignored
		assertEquals(before, g.getColor());
		g.setFont(null);                        // ignored
		assertNotNull(g.getFont());
		g.drawString("", 0, 0);                 // empty → no path
		g.drawString((String) null, 0, 0);      // null → no path
		assertFalse(g.document().contains("<path"), "no text drawn yet");

		g.setPaint(Color.BLUE);                 // setPaint with a solid colour updates the current colour too
		assertEquals(Color.BLUE, g.getColor());
		g.setStroke(shape -> shape);            // a non-BasicStroke → width falls back to the transform scale
		g.draw(new Rectangle2D.Double(0, 0, 5, 5));
		assertTrue(g.document().contains("stroke-width"));

		// Inert surface the renderer never calls, present to satisfy Graphics2D — must not throw.
		g.setBackground(Color.WHITE);
		assertEquals(Color.WHITE, g.getBackground());
		g.setPaintMode();
		g.setXORMode(Color.RED);
		g.rotate(0.1);
		g.rotate(0.1, 1, 1);
		g.shear(0.1, 0.1);
		g.setTransform(g.getTransform());
		g.setRenderingHints(java.util.Map.of());
		g.addRenderingHints(java.util.Map.of());
		assertNotNull(g.getRenderingHints());
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, null);
		assertNull(g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING));
		assertTrue(g.drawImage(null, 0, 0, null));
		assertTrue(g.drawImage(null, 0, 0, 1, 1, null));
		assertFalse(g.hit(new Rectangle(0, 0, 1, 1), new Rectangle2D.Double(0, 0, 1, 1), false));
		assertNull(g.getDeviceConfiguration());
		g.copyArea(0, 0, 1, 1, 1, 1);
		g.drawString("x", 1, 1);                // the int overload delegates to the float one
		assertTrue(g.document().contains("<path"));
	}

	@Test
	void numberFormattingPaintFallbackAndClipDefaults() {
		SvgGraphics2D g = new SvgGraphics2D(20, 20);
		assertNull(g.getClipBounds(), "no clip set yet");
		g.setColor(Color.BLACK);
		g.drawPolygon(new int[0], new int[0], 0);    // empty → n<=0 guard
		g.drawPolyline(new int[0], new int[0], 0);
		g.draw(new Line2D.Double(0.0004, 0, 5, 0));   // rounds to "0.000" → trailing-zero and trailing-dot trims
		g.draw(new Line2D.Double(1.5, 2.25, 3.125, 4));   // fractional coordinates keep their decimals
		String svg = g.document();
		assertTrue(svg.contains("1.5") && svg.contains("2.25") && svg.contains("3.125"), svg);
		// A paint that is neither a solid colour nor a gradient falls back to the current colour.
		BufferedImage tile = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		g.setPaint(new java.awt.TexturePaint(tile, new Rectangle2D.Double(0, 0, 2, 2)));
		g.fill(new Rectangle2D.Double(0, 0, 4, 4));
		assertTrue(g.document().contains("<path"));
	}

	@Test
	void nonAlphaCompositeCountsAsFullyOpaque() {
		SvgGraphics2D g = new SvgGraphics2D(10, 10);
		g.setColor(Color.BLACK);
		g.setComposite(new java.awt.Composite() {
			public java.awt.CompositeContext createContext(java.awt.image.ColorModel src, java.awt.image.ColorModel dst,
					java.awt.RenderingHints hints) {
				return null;
			}
		});
		g.fill(new Rectangle2D.Double(0, 0, 4, 4));
		assertTrue(g.document().contains("fill-opacity=\"1\""), g.document());
	}

	@Test
	void emptyShapesAndNegativeCoordinates() {
		SvgGraphics2D g = new SvgGraphics2D(20, 20);
		g.setColor(Color.BLACK);
		g.fill(new Path2D.Double());   // empty geometry → no element
		g.draw(new Path2D.Double());
		assertEquals(1, g.document().split("<path").length, "empty shapes emit nothing");
		g.drawLine(-3, 2, 7, 2);       // negative + integer coordinate formatting
		assertTrue(g.document().contains("M-3 2"), g.document());
	}

	@Test
	void convenienceShapesAllSerialise() {
		SvgGraphics2D g = new SvgGraphics2D(60, 60);
		g.setColor(Color.DARK_GRAY);
		g.fillRoundRect(0, 0, 20, 12, 6, 6);
		g.drawRoundRect(0, 0, 20, 12, 6, 6);
		g.fillOval(2, 2, 8, 8);
		g.drawRect(0, 0, 10, 10);
		g.fillPolygon(new int[] { 0, 5, 10 }, new int[] { 0, 8, 0 }, 3);
		g.drawPolygon(new int[] { 0, 5, 10 }, new int[] { 0, 8, 0 }, 3);
		g.drawPolyline(new int[] { 0, 5, 10 }, new int[] { 0, 8, 0 }, 3);
		g.clearRect(0, 0, 5, 5);
		g.fill(new Ellipse2D.Double(0, 0, 3, 3));
		assertTrue(g.document().split("<path").length > 6, "each shape became a path");
	}
}
