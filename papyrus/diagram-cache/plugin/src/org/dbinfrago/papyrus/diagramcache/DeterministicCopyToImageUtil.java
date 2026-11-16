// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// GMF's DiagramGenerator collects the connections to paint in HashSets of
// edit parts. Their iteration order follows identity hash codes and differs
// between JVM runs, so the SVG paint order, the generated clipPath ids and
// the graphics-state attributes change although the diagram did not.
// Painting connections in the order of the notation edges makes the export
// byte-for-byte reproducible.
//
// Sequence diagram edit policies queue asyncExec tasks that fail on the
// Interaction's EAnnotations. They run with the annotations detached; the
// labels are computed and painted with them, see InteractionAnnotations.
// SVG font families are written as GMF requested them, see SvgFontFamilies.
package org.dbinfrago.papyrus.diagramcache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.ConnectionEditPart;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.editparts.DiagramEditPart;
import org.eclipse.gmf.runtime.diagram.ui.editparts.IGraphicalEditPart;
import org.eclipse.gmf.runtime.diagram.ui.editparts.ITextAwareEditPart;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.clipboard.DiagramGenerator;
import org.eclipse.gmf.runtime.diagram.ui.render.clipboard.DiagramSVGGenerator;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

class DeterministicCopyToImageUtil extends CopyToImageUtil {

    /** Annotations of the diagram being exported. */
    private InteractionAnnotations annotations;

    @Override
    @SuppressWarnings("rawtypes")
    public List copyToImage(Diagram diagram, IPath destination, ImageFileFormat format,
            IProgressMonitor monitor, PreferencesHint preferencesHint)
            throws CoreException {
        annotations = InteractionAnnotations.of(diagram);
        try {
            List result = super.copyToImage(diagram, destination, format, monitor,
                preferencesHint);
            // Run the tasks queued while painting now, so that their
            // failures cannot surface in the next diagram's export.
            annotations.detach();
            drainAsyncQueue();
            return result;
        } finally {
            annotations.close();
            annotations = null;
        }
    }

    @Override
    public DiagramEditPart createDiagramEditPart(Diagram diagram, Shell shell,
            PreferencesHint preferencesHint) {
        // GMF dispatches the event loop once the edit parts exist. This task
        // is queued before the edit policies queue theirs, so it detaches the
        // annotations before those run.
        Display.getCurrent().asyncExec(annotations::detach);
        DiagramEditPart diagramEP = super.createDiagramEditPart(diagram, shell,
            preferencesHint);
        drainAsyncQueue();
        annotations.restore();
        // The tasks change the model, and labels refreshed meanwhile were
        // computed without the annotations.
        List<Object> parts = new ArrayList<>(diagramEP.getViewer().getEditPartRegistry().values());
        for (Object part : parts) {
            if (part instanceof ITextAwareEditPart label) {
                label.refresh();
            }
        }
        diagramEP.getViewer().flush();
        return diagramEP;
    }

    private static void drainAsyncQueue() {
        Display display = Display.getCurrent();
        if (display != null) {
            while (display.readAndDispatch()) {
                // keep dispatching
            }
        }
    }

    @Override
    protected DiagramGenerator getDiagramGenerator(DiagramEditPart diagramEP,
            ImageFileFormat format) {
        if (format.equals(ImageFileFormat.SVG)) {
            SvgFontFamilies.install();
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
