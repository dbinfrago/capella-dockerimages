// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
package org.dbinfrago.papyrus.diagramcache;

import java.util.Collections;
import java.util.List;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.diagram.ui.editparts.ShapeNodeEditPart;
import org.eclipse.gmf.runtime.draw2d.ui.figures.FigureUtilities;
import org.eclipse.gmf.runtime.gef.ui.figures.DefaultSizeNodeFigure;
import org.eclipse.gmf.runtime.gef.ui.figures.NodeFigure;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.uml2.uml.Element;

/**
 * Renders a SysML diagram frame: an outline with a header compartment
 * "{@code <kind> [<model element type>] <diagram name>}".
 *
 * The frame kind (bdd, ibd, stm, uc, ...) is stored in the view's
 * {@code com.eulynx.papyrus.statemachine.labels.frameKind} annotation.
 */
public class DiagramFrameEditPart extends ShapeNodeEditPart {

    public static final String VIEW_TYPE = "PapyrusDiagramFrame";
    static final String KIND_ANNOTATION = "com.eulynx.papyrus.statemachine.labels.frameKind";
    private static final int PADDING = 6;
    private static final int NOTCH = 12;

    public DiagramFrameEditPart(View view) {
        super(view);
    }

    @Override
    protected NodeFigure createNodeFigure() {
        NodeFigure node = new DefaultSizeNodeFigure(100, 100);
        node.setLayoutManager(new org.eclipse.draw2d.StackLayout());
        node.add(new FrameFigure(headerText(getNotationView())));
        return node;
    }

    @Override
    protected List<?> getModelChildren() {
        return Collections.emptyList();
    }

    static String headerText(View view) {
        StringBuilder header = new StringBuilder();
        EAnnotation annotation = view.getEAnnotation(KIND_ANNOTATION);
        if (annotation != null && annotation.getDetails().get("kind") != null) {
            header.append(annotation.getDetails().get("kind")).append(' ');
        }
        Diagram diagram = view.getDiagram();
        EObject element = diagram == null ? null : diagram.getElement();
        if (element != null) {
            header.append('[').append(elementType(element)).append("] ");
        }
        if (diagram != null && diagram.getName() != null) {
            header.append(diagram.getName());
        }
        return header.toString().trim();
    }

    private static String elementType(EObject element) {
        if (element instanceof Element umlElement
                && umlElement.getAppliedStereotype("SysML::Blocks::Block") != null) {
            return "Block";
        }
        return element.eClass().getName();
    }

    private static final class FrameFigure extends Figure {
        private final String header;

        FrameFigure(String header) {
            this.header = header;
        }

        @Override
        protected void paintFigure(Graphics graphics) {
            Rectangle r = getBounds().getCopy();
            r.width -= 1;
            r.height -= 1;
            graphics.setForegroundColor(org.eclipse.draw2d.ColorConstants.black);
            graphics.drawRectangle(r);
            Dimension text = FigureUtilities.getTextExtents(header, graphics.getFont());
            int right = Math.min(r.x + text.width + 2 * PADDING, r.right() - NOTCH);
            int bottom = r.y + text.height + 2 * PADDING;
            graphics.drawPolyline(new int[] {
                r.x, bottom,
                right + NOTCH, bottom,
                right + NOTCH, r.y + NOTCH,
                right, r.y,
            });
            graphics.drawText(header, r.x + PADDING, r.y + PADDING);
        }
    }
}
