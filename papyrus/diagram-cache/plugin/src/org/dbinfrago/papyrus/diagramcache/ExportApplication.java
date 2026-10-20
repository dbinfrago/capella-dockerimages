// SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
// SPDX-License-Identifier: Apache-2.0
//
// Headless Papyrus diagram exporter for the Papyrus diagram cache.
//
// Exports every GMF diagram of a Papyrus model (.di/.notation) and every
// Sirius representation of the model's .aird (if present) as SVG and writes
// one result record per diagram to <out>/export-result.json. Papyrus tables
// are not rendered here; the papyrus-diagram-cache driver renders them.
//
// Invoked via:
//   papyrus -nosplash -consoleLog \
//     -application org.dbinfrago.papyrus.diagramcache.export \
//     -data <workspace> -project <name> -di <project-relative .di> \
//     -out <absolute output directory>
//
// GMF and Sirius rendering paint on an SWT Display, so a (virtual) X
// display is required.
package org.dbinfrago.papyrus.diagramcache;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.papyrus.infra.core.resource.ModelSet;
import org.eclipse.papyrus.infra.core.resource.ModelsReader;
import org.eclipse.papyrus.infra.core.resource.EditingDomainServiceFactory;
import org.eclipse.papyrus.infra.core.services.ExtensionServicesRegistry;
import org.eclipse.papyrus.infra.core.editor.ModelSetServiceFactory;
import org.eclipse.papyrus.infra.core.services.ServiceDescriptor;
import org.eclipse.papyrus.infra.core.services.ServiceStartKind;
import org.eclipse.papyrus.infra.core.services.ServiceMultiException;
import org.eclipse.papyrus.infra.core.services.ServicesRegistry;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.ui.business.api.dialect.DialectUIManager;
import org.eclipse.sirius.ui.business.api.dialect.ExportFormat;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.WorkbenchAdvisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ExportApplication implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] argv = (String[]) context.getArguments()
            .get(IApplicationContext.APPLICATION_ARGS);
        Map<String, String> args = parseArgs(argv);
        String projectName = required(args, "-project");
        String diRel = required(args, "-di");
        Path outPath = Paths.get(required(args, "-out")).toAbsolutePath();
        Files.createDirectories(outPath);

        // Papyrus UI bundles (label providers, read-only handling) are
        // activated while rendering and require a running workbench. Run one
        // without opening any window and export from postStartup().
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        Display display = PlatformUI.createDisplay();
        try {
            PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor() {
                @Override
                public String getInitialWindowPerspectiveId() {
                    return null;
                }

                @Override
                public boolean openWindows() {
                    return true;
                }

                @Override
                public void postStartup() {
                    try {
                        result[0] = export(projectName, diRel, outPath);
                    } catch (Exception e) {
                        failure[0] = e;
                    } finally {
                        PlatformUI.getWorkbench().close();
                    }
                }
            });
        } finally {
            display.dispose();
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        return result[0] == null ? IApplication.EXIT_OK : result[0];
    }

    @Override
    public void stop() { /* nothing to clean up; we exit on return */ }

    private static Object export(String projectName, String diRel, Path outPath)
            throws Exception {
        NullProgressMonitor monitor = new NullProgressMonitor();

        // 1. Make the project known to the workspace.
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        root.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        IProject project = root.getProject(projectName);
        if (!project.exists()) {
            project.create(workspace.loadProjectDescription(
                root.getLocation().append(projectName).append(".project")), monitor);
        }
        project.open(monitor);
        IFile di = project.getFile(diRel);
        if (!di.exists()) {
            System.err.println("di not found: " + diRel);
            return Integer.valueOf(2);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        exportGmfDiagrams(di, outPath, records, monitor);
        exportSiriusRepresentations(di, outPath, records, monitor);

        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        try (Writer w = Files.newBufferedWriter(
                outPath.resolve("export-result.json"), StandardCharsets.UTF_8)) {
            gson.toJson(records, w);
        }
        return IApplication.EXIT_OK;
    }

    // ------------------------------------------------------------------ GMF

    private static void exportGmfDiagrams(IFile di, Path outPath,
            List<Map<String, Object>> records, NullProgressMonitor monitor)
            throws Exception {
        ServicesRegistry registry;
        try {
            registry = new ExtensionServicesRegistry();
        } catch (Exception e) {
            System.err.println("falling back to plain ServicesRegistry: " + e);
            registry = new ServicesRegistry();
        }
        ServiceDescriptor modelSetDescriptor = new ServiceDescriptor(
            ModelSet.class, ModelSetServiceFactory.class.getName(),
            ServiceStartKind.STARTUP, 10);
        modelSetDescriptor.setServiceTypeKind(
            ServiceDescriptor.ServiceTypeKind.serviceFactory);
        registry.add(modelSetDescriptor);
        ServiceDescriptor domainDescriptor = new ServiceDescriptor(
            TransactionalEditingDomain.class,
            EditingDomainServiceFactory.class.getName(),
            ServiceStartKind.STARTUP, 10,
            Collections.singletonList(ModelSet.class.getName()));
        domainDescriptor.setServiceTypeKind(
            ServiceDescriptor.ServiceTypeKind.serviceFactory);
        registry.add(domainDescriptor);
        registry.startServicesByClassKeys(ModelSet.class, TransactionalEditingDomain.class);

        ModelSet modelSet = registry.getService(ModelSet.class);
        new ModelsReader().readModel(modelSet);
        URI diUri = URI.createPlatformResourceURI(di.getFullPath().toString(), true);
        modelSet.loadModels(diUri);
        try {
            registry.startRegistry();
        } catch (ServiceMultiException e) {
            // Editor/UI services cannot start without a workbench; the model
            // services needed for rendering are already running.
            System.err.println("ignoring service start failures: " + e.getMessage());
        }

        URI notationUri = diUri.trimFileExtension().appendFileExtension("notation");
        Resource notation = modelSet.getResource(notationUri, true);
        List<Diagram> diagrams = new ArrayList<>();
        for (EObject content : notation.getContents()) {
            if (content instanceof Diagram diagram && diagram.getElement() != null) {
                diagrams.add(diagram);
            }
        }
        System.out.println("found " + diagrams.size() + " GMF diagrams to export");
        CopyToImageUtil copier = new DeterministicCopyToImageUtil();
        for (Diagram diagram : diagrams) {
            String uuid = notation.getURIFragment(diagram);
            IPath target = org.eclipse.core.runtime.Path.fromOSString(
                outPath.resolve(uuid + ".svg").toString());
            String error = null;
            try {
                copier.copyToImage(diagram, target, ImageFileFormat.SVG, monitor,
                    PreferencesHint.USE_DEFAULTS);
            } catch (Exception e) {
                error = describe(e);
            }
            records.add(record(uuid, "gmf", diagram.getName(), diagram.getType(),
                null, error));
            log(uuid, diagram.getName(), error);
        }

        try {
            modelSet.unload();
        } catch (Exception e) {
            System.err.println("unloading the model set failed: " + describe(e));
        }
        try {
            registry.disposeRegistry();
        } catch (Exception e) {
            System.err.println("disposing the service registry failed: " + describe(e));
        }
    }

    // --------------------------------------------------------------- Sirius

    private static void exportSiriusRepresentations(IFile di, Path outPath,
            List<Map<String, Object>> records, NullProgressMonitor monitor)
            throws Exception {
        IFile aird = di.getWorkspace().getRoot().getFile(
            di.getFullPath().removeFileExtension().addFileExtension("aird"));
        if (!aird.exists()) {
            return;
        }
        URI airdUri = URI.createPlatformResourceURI(aird.getFullPath().toString(), true);
        Session session = SessionManager.INSTANCE.getSession(airdUri, monitor);
        if (!session.isOpen()) {
            session.open(monitor);
        }
        List<DRepresentationDescriptor> descriptors = new ArrayList<>(
            DialectManager.INSTANCE.getAllRepresentationDescriptors(session));
        System.out.println("found " + descriptors.size() + " Sirius representations to export");
        ExportFormat format = new ExportFormat(ExportFormat.ExportDocumentFormat.NONE,
            org.eclipse.sirius.common.tools.api.resource.ImageFileFormat.SVG);
        for (DRepresentationDescriptor descriptor : descriptors) {
            String uuid = descriptor.getUid();
            EObject target = descriptor.getTarget();
            String owner = target == null ? null : EcoreUtil.getURI(target).fragment();
            String type = descriptor.getDescription() == null
                ? null : descriptor.getDescription().getName();
            String error = null;
            try {
                DRepresentation representation = descriptor.getRepresentation();
                if (representation == null) {
                    error = "representation not loaded";
                } else {
                    IPath path = org.eclipse.core.runtime.Path.fromOSString(
                        outPath.resolve(uuid + ".svg").toString());
                    DialectUIManager.INSTANCE.export(representation, session, path,
                        format, monitor);
                }
            } catch (Exception e) {
                error = describe(e);
            }
            records.add(record(uuid, "sirius", descriptor.getName(), type, owner, error));
            log(uuid, descriptor.getName(), error);
        }
        session.close(monitor);
    }

    // ------------------------------------------------------------------ utils

    private static Map<String, Object> record(String uuid, String kind, String name,
            String type, String owner, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uuid", uuid);
        r.put("kind", kind);
        r.put("name", name);
        r.put("type", type);
        r.put("owner", owner);
        r.put("success", error == null);
        r.put("error", error);
        return r;
    }

    private static void log(String uuid, String name, String error) {
        if (error == null) {
            System.out.println("  ok   " + uuid + " " + name);
        } else {
            System.err.println("  FAIL " + uuid + " " + name + ": " + error);
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> m = new HashMap<>();
        if (argv == null) return m;
        for (int i = 0; i < argv.length - 1; i++) {
            if (argv[i].startsWith("-")) {
                m.put(argv[i], argv[i + 1]);
                i++;
            }
        }
        return m;
    }

    private static String required(Map<String, String> args, String key) {
        String v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException(
                "missing argument " + key + " (received: " + args.keySet() + ")");
        }
        return v;
    }
}
