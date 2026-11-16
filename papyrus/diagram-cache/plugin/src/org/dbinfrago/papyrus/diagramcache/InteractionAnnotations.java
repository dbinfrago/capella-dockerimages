// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// Papyrus 7.1 (org.eclipse.papyrus.uml.diagram.sequence 7.3.0) casts every
// object of Interaction.eAllContents() to uml.Element in
// ComputeOwnerHelper.updateOwnedByInteractionOperand. GridManagementEditPolicy
// queues that method with asyncExec when a sequence diagram edit part is
// activated, so an EAnnotation anywhere inside the Interaction (e.g. added by
// a modelling add-on) fails the export with a ClassCastException.
//
// The annotations must be in place while labels are computed: add-on label
// providers read them (e.g. the EULYNX duration link label). They are
// detached only while queued tasks run (see DeterministicCopyToImageUtil)
// and restored before the diagram is painted. Both happen without
// notifications, so no edit part reacts to the change; the model is never
// saved.
package org.dbinfrago.papyrus.diagramcache;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.uml2.uml.Interaction;

final class InteractionAnnotations {

    private record Slot(EModelElement owner, int index, EAnnotation annotation) {}

    private final List<Slot> slots;
    private boolean detached;
    private boolean closed;

    private InteractionAnnotations(List<Slot> slots) {
        this.slots = slots;
    }

    /** Collects all EAnnotations inside the diagram's Interaction, if any. */
    static InteractionAnnotations of(Diagram diagram) {
        List<Slot> slots = new ArrayList<>();
        if (diagram.getElement() instanceof Interaction interaction) {
            collect(interaction, slots);
            for (var it = interaction.eAllContents(); it.hasNext();) {
                EObject next = it.next();
                if (next instanceof EAnnotation) {
                    it.prune();
                } else if (next instanceof EModelElement element) {
                    collect(element, slots);
                }
            }
        }
        return new InteractionAnnotations(slots);
    }

    /** Removes the annotations from their owners, unless {@link #close() closed}. */
    void detach() {
        if (detached || closed) {
            return;
        }
        // Remove from the back so the recorded indexes stay valid on restore.
        for (int i = slots.size() - 1; i >= 0; i--) {
            Slot slot = slots.get(i);
            silently(slot, () -> slot.owner().getEAnnotations().remove(slot.index()));
        }
        detached = true;
    }

    /** Puts detached annotations back at their original positions. */
    void restore() {
        if (!detached) {
            return;
        }
        for (Slot slot : slots) {
            silently(slot, () -> slot.owner().getEAnnotations().add(slot.index(), slot.annotation()));
        }
        detached = false;
    }

    /** Restores the annotations; a still queued {@link #detach()} then does nothing. */
    void close() {
        restore();
        closed = true;
    }

    private static void collect(EModelElement element, List<Slot> slots) {
        List<EAnnotation> annotations = element.getEAnnotations();
        for (int i = 0; i < annotations.size(); i++) {
            slots.add(new Slot(element, i, annotations.get(i)));
        }
    }

    /** Runs {@code change} without notifying the owner's or the annotation's adapters. */
    private static void silently(Slot slot, Runnable change) {
        boolean ownerDelivers = slot.owner().eDeliver();
        boolean annotationDelivers = slot.annotation().eDeliver();
        slot.owner().eSetDeliver(false);
        slot.annotation().eSetDeliver(false);
        try {
            change.run();
        } finally {
            slot.owner().eSetDeliver(ownerDelivers);
            slot.annotation().eSetDeliver(annotationDelivers);
        }
    }
}
