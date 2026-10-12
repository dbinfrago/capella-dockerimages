// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
package org.dbinfrago.papyrus.diagramcache;

import org.eclipse.gmf.runtime.diagram.ui.services.editpart.AbstractEditPartProvider;
import org.eclipse.gmf.runtime.notation.View;

/**
 * Provides {@link DiagramFrameEditPart} for SysML diagram frame views
 * ({@code PapyrusDiagramFrame}). Papyrus has no edit part for this view type
 * and would otherwise render "Invalid view - remove from diagram.".
 */
public class DiagramFrameEditPartProvider extends AbstractEditPartProvider {

    @Override
    protected Class<?> getNodeEditPartClass(View view) {
        if (DiagramFrameEditPart.VIEW_TYPE.equals(view.getType())) {
            return DiagramFrameEditPart.class;
        }
        return null;
    }
}
