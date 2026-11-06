package com.caverock.androidsvg;

import java.util.IdentityHashMap;
import java.util.Map;

/** Package access is intentional: this workaround targets AndroidSVG 1.4's text measurement. */
public final class SvgTextBoundsCompat {
    private SvgTextBoundsCompat() {}

    public static void prepare(SVG document, int width, int height, RenderOptions options) {
        Map<SVG.Style, SVG.SvgPaint[]> paints = new IdentityHashMap<>();
        try {
            boolean hasSpans = collect(document.getRootElement(), paints);
            if (!hasSpans) return;
            if (document.hasCSSRules()) {
                for (CSSParser.Rule rule : document.getCSSRules()) mask(rule.style, paints);
            }
            if (options != null && options.hasCss()) {
                for (CSSParser.Rule rule : options.css.getRules()) mask(rule.style, paints);
            }
            // Gradients need the root text bounds even during the pass that calculates those bounds.
            // A paint-only pass populates the same geometry without requesting missing bounds.
            if (!paints.isEmpty()) {
                document.renderToPicture(width, height, options);
                alignSingleSpanBounds(document.getRootElement());
            }
        } finally {
            for (Map.Entry<SVG.Style, SVG.SvgPaint[]> entry : paints.entrySet()) {
                entry.getKey().fill = entry.getValue()[0];
                entry.getKey().stroke = entry.getValue()[1];
            }
        }
    }

    private static boolean collect(SVG.SvgObject object, Map<SVG.Style, SVG.SvgPaint[]> paints) {
        boolean spans = object instanceof SVG.TSpan || object instanceof SVG.TRef || object instanceof SVG.TextPath;
        if (object instanceof SVG.SvgElementBase) {
            SVG.SvgElementBase element = (SVG.SvgElementBase) object;
            mask(element.baseStyle, paints);
            mask(element.style, paints);
        }
        if (object instanceof SVG.SvgContainer) {
            for (SVG.SvgObject child : ((SVG.SvgContainer) object).getChildren()) spans |= collect(child, paints);
        }
        return spans;
    }

    private static void mask(SVG.Style style, Map<SVG.Style, SVG.SvgPaint[]> paints) {
        if (style == null || paints.containsKey(style) ||
                !(style.fill instanceof SVG.PaintReference || style.stroke instanceof SVG.PaintReference)) return;
        paints.put(style, new SVG.SvgPaint[] {style.fill, style.stroke});
        if (style.fill instanceof SVG.PaintReference) style.fill = SVG.Colour.BLACK;
        if (style.stroke instanceof SVG.PaintReference) style.stroke = SVG.Colour.BLACK;
    }

    private static void alignSingleSpanBounds(SVG.SvgObject object) {
        if (object instanceof SVG.Text) {
            SVG.Text text = (SVG.Text) object;
            if (text.boundingBox != null && empty(text.x) && empty(text.y) && empty(text.dx) && empty(text.dy)) {
                SVG.TSpan span = null;
                for (SVG.SvgObject child : text.getChildren()) {
                    if (child instanceof SVG.TextSequence && ((SVG.TextSequence) child).text.trim().isEmpty()) continue;
                    if (span != null || !(child instanceof SVG.TSpan)) { span = null; break; }
                    span = (SVG.TSpan) child;
                }
                if (span != null && plainSpan(span) && pixelCoordinate(span.x) && pixelCoordinate(span.y) && empty(span.dx) && empty(span.dy)) {
                    // 1.4 measures this common single-span form at the implicit text origin (0, 0).
                    text.boundingBox.minX += span.x.get(0).floatValue();
                    text.boundingBox.minY += span.y.get(0).floatValue();
                }
            }
        }
        if (object instanceof SVG.SvgContainer) {
            for (SVG.SvgObject child : ((SVG.SvgContainer) object).getChildren()) alignSingleSpanBounds(child);
        }
    }

    private static boolean empty(java.util.List<?> values) { return values == null || values.isEmpty(); }

    private static boolean pixelCoordinate(java.util.List<SVG.Length> values) {
        return values != null && values.size() == 1 && values.get(0).unit == SVG.Unit.px;
    }

    private static boolean plainSpan(SVG.TSpan span) {
        for (SVG.SvgObject child : span.getChildren()) if (!(child instanceof SVG.TextSequence)) return false;
        return true;
    }
}
