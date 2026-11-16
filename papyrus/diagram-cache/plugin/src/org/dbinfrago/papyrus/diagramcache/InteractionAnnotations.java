// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// Papyrus 7.1 (org.eclipse.papyrus.uml.diagram.sequence 7.3.0) casts every
// object of Interaction.eAllContents() to uml.Element in
// ComputeOwnerHelper.updateOwnedByInteractionOperand. GridManagementEditPolicy
// queues that method with asyncExec when a sequence diagram edit part is
// activated, so an EAnnotation anywhere inside the Interaction (e.g. added by
// a modelling add-on) fails the export with a ClassCastException. The
// annotations are detached while the diagram is rendered and restored
// afterwards. The model is never saved.
package org.dbinfrago.papyrus.diagramcache;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.uml2.uml.Interaction;

final class InteractionAnnotations {

    private record Slot(EModelElement owner, int index, EAnnotation annotation) {}

    private final TransactionalEditingDomain domain;
    private final List<Slot> slots;

    private InteractionAnnotations(TransactionalEditingDomain domain, List<Slot> slots) {
        this.domain = domain;
        this.slots = slots;
    }

    /** Detaches all EAnnotations inside the diagram's Interaction, if any. */
    static InteractionAnnotations detach(Diagram diagram) {
        if (!(diagram.getElement() instanceof Interaction interaction)) {
            return new InteractionAnnotations(null, List.of());
        }
        List<Slot> slots = new ArrayList<>();
        collect(interaction, slots);
        for (var it = interaction.eAllContents(); it.hasNext();) {
            EObject next = it.next();
            if (next instanceof EAnnotation) {
                it.prune();
            } else if (next instanceof EModelElement element) {
                collect(element, slots);
            }
        }
        if (slots.isEmpty()) {
            return new InteractionAnnotations(null, List.of());
        }
        TransactionalEditingDomain domain = TransactionUtil.getEditingDomain(interaction);
        // Remove from the back so the recorded indexes stay valid on restore.
        run(domain, () -> {
            for (int i = slots.size() - 1; i >= 0; i--) {
                Slot slot = slots.get(i);
                slot.owner().getEAnnotations().remove(slot.index());
            }
        });
        return new InteractionAnnotations(domain, slots);
    }

    /** Puts the detached annotations back at their original positions. */
    void restore() {
        if (slots.isEmpty()) {
            return;
        }
        run(domain, () -> {
            for (Slot slot : slots) {
                slot.owner().getEAnnotations().add(slot.index(), slot.annotation());
            }
        });
    }

    private static void collect(EModelElement element, List<Slot> slots) {
        List<EAnnotation> annotations = element.getEAnnotations();
        for (int i = 0; i < annotations.size(); i++) {
            slots.add(new Slot(element, i, annotations.get(i)));
        }
    }

    private static void run(TransactionalEditingDomain domain, Runnable change) {
        if (domain == null) {
            change.run();
            return;
        }
        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override
            protected void doExecute() {
                change.run();
            }
        });
    }
}
