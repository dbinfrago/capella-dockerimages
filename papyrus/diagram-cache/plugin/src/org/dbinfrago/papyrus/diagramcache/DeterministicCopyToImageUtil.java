// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// GMF's DiagramGenerator collects the connections to paint in HashSets of
// edit parts. Their iteration order follows identity hash codes and differs
// between JVM runs, so the SVG paint order, the generated clipPath ids and
// the graphics-state attributes change although the diagram did not.
// Painting connections in the order of the notation edges makes the export
// byte-for-byte reproducible.
package org.dbinfrago.papyrus.diagramcache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.ConnectionEditPart;
import org.eclipse.gmf.runtime.diagram.ui.editparts.DiagramEditPart;
import org.eclipse.gmf.runtime.diagram.ui.editparts.IGraphicalEditPart;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.clipboard.DiagramGenerator;
import org.eclipse.gmf.runtime.diagram.ui.render.clipboard.DiagramSVGGenerator;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;

class DeterministicCopyToImageUtil extends CopyToImageUtil {

    @Override
    protected DiagramGenerator getDiagramGenerator(DiagramEditPart diagramEP,
            ImageFileFormat format) {
        if (format.equals(ImageFileFormat.SVG)) {
            return new OrderedSVGGenerator(diagramEP);
        }
        return super.getDiagramGenerator(diagramEP, format);
    }

    private static final class OrderedSVGGenerator extends DiagramSVGGenerator {

        private final Comparator<ConnectionEditPart> order;

        OrderedSVGGenerator(DiagramEditPart diagramEP) {
            super(diagramEP);
            Map<Object, Integer> edgeIndex = new HashMap<>();
            Diagram diagram = diagramEP.getDiagramView();
            List<?> edges = diagram.getEdges();
            for (int i = 0; i < edges.size(); i++) {
                edgeIndex.put(edges.get(i), i);
            }
            // Edges missing from the notation (none expected) go last, by id.
            order = Comparator
                .comparingInt((ConnectionEditPart c) ->
                    edgeIndex.getOrDefault(c.getModel(), Integer.MAX_VALUE))
                .thenComparing(c -> fragment(c.getModel()));
        }

        @Override
        protected Collection<ConnectionEditPart> findConnectionsToPaint(
                IGraphicalEditPart editPart) {
            List<ConnectionEditPart> connections =
                new ArrayList<>(super.findConnectionsToPaint(editPart));
            connections.sort(order);
            return connections;
        }

        private static String fragment(Object model) {
            if (model instanceof EObject object) {
                Resource resource = object.eResource();
                if (resource != null) {
                    return resource.getURIFragment(object);
                }
            }
            return "";
        }
    }
}
