// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// GMF hands Batik an AWT font named like the SWT font, e.g. "Segoe UI".
// Batik writes Font.getFamily() into the SVG, and AWT reports "Dialog" for
// every family it has no font file for, also when fontconfig substitutes one
// for SWT (Segoe UI -> Selawik). Viewers do not know 'Dialog' and fall back
// to their default (often a serif) font, so the text segments that SWT laid
// out side by side drift apart or overlap.
//
// Batik keeps a static map from lower-case AWT font names to SVG families
// (dialog -> sans-serif, ...). It is replaced with one that answers every
// other name with the font name GMF asked for plus a generic fallback.
package org.dbinfrago.papyrus.diagramcache;

import java.awt.Font;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.batik.svggen.SVGFont;

final class SvgFontFamilies {

    private static boolean installed;

    private SvgFontFamilies() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Field field = SVGFont.class.getDeclaredField("logicalFontMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> logical = (Map<Object, Object>) field.get(null);
            field.set(null, new FamilyMap(logical));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("SVG font families stay as AWT reports them: " + e);
        }
        installed = true;
    }

    private static final class FamilyMap extends HashMap<Object, Object> {

        private static final long serialVersionUID = 1L;

        FamilyMap(Map<Object, Object> logical) {
            super(logical);
        }

        @Override
        public Object get(Object key) {
            Object value = super.get(key);
            if (value != null || !(key instanceof String name)) {
                return value;
            }
            // Use AWT's spelling if it has the font; the key is lower case
            // and CSS matches family names case-insensitively.
            String family = new Font(name, Font.PLAIN, 1).getFamily();
            if (!family.equalsIgnoreCase(name)) {
                family = name;
            }
            value = "'" + family.replace("'", "\\'") + "', sans-serif";
            put(key, value);
            return value;
        }
    }
}
