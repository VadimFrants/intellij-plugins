package com.intellij.flex.uiDesigner.libraries;

import com.intellij.flex.uiDesigner.*;
import com.intellij.flex.uiDesigner.io.InfoList;
import com.intellij.flex.uiDesigner.io.StringRegistry;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.Consumer;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class LibraryManager extends EntityListManager<VirtualFile, Library> {
  public static LibraryManager getInstance() {
    return ServiceManager.getService(LibraryManager.class);
  }

  public boolean isRegistered(@NotNull Library library) {
    return list.contains(library);
  }

  public int add(@NotNull Library library) {
    return list.add(library);
  }

  public boolean isSdkRegistered(Sdk sdk, Module module) {
    ProjectInfo info = Client.getInstance().getRegisteredProjects().getNullableInfo(module.getProject());
    return info != null && info.getSdk() == sdk;
  }

  public void initLibrarySets(@NotNull final Module module, final File appDir) throws IOException, InitException {
    initLibrarySets(module, appDir, true, null);
  }

  public void initLibrarySets(@NotNull final Module module, final File appDir, boolean collectLocalStyleHolders, @Nullable LibrarySet sdkLibrarySet)
    throws InitException, IOException {
    final ProblemsHolder problemsHolder = new ProblemsHolder();
    final Project project = module.getProject();
    final LibraryCollector libraryCollector = new LibraryCollector(this);
    final StringRegistry.StringWriter stringWriter = new StringRegistry.StringWriter(16384);
    stringWriter.startChange();

    final Client client;
    try {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          libraryCollector.collect(module, new LibraryStyleInfoCollector(project, module, stringWriter, problemsHolder));
        }
      });

      client = Client.getInstance();
      if (stringWriter.hasChanges()) {
        client.updateStringRegistry(stringWriter);
      }
      else {
        stringWriter.finishChange();
      }
    }
    catch (Throwable e) {
      stringWriter.rollbackChange();
      throw new InitException(e, "error.collect.libraries");
    }

    LibrarySet librarySet = null;
    final InfoList<Project, ProjectInfo> registeredProjects = client.getRegisteredProjects();
    final String projectLocationHash = project.getLocationHash();
    ProjectInfo info = registeredProjects.getNullableInfo(project);
    if (info != null) {
      // different flex sdk version for module
      if (libraryCollector.sdkLibraries != null) {
        sdkLibrarySet = createLibrarySet(Integer.toHexString(module.getName().hashCode()) + "_fdk", null, libraryCollector.sdkLibraries,
                                         libraryCollector.getFlexSdkVersion(), new SwcDependenciesSorter(appDir, module), true);
      }
      else {
        librarySet = info.getLibrarySet();
      }
    }
    else {
      if (sdkLibrarySet == null) {
        sdkLibrarySet = createLibrarySet(projectLocationHash + "_fdk", null, libraryCollector.sdkLibraries,
                                         libraryCollector.getFlexSdkVersion(), new SwcDependenciesSorter(appDir, module), true);
        client.registerLibrarySet(sdkLibrarySet);
      }

      info = new ProjectInfo(project, sdkLibrarySet, libraryCollector.getFlexSdk());
      registeredProjects.add(info);
      client.openProject(project);
    }

    if (libraryCollector.externalLibraries.isEmpty()) {
      if (librarySet == null) {
        assert sdkLibrarySet != null;
        librarySet = sdkLibrarySet;
        info.setLibrarySet(sdkLibrarySet);
      }
    }
    else if (librarySet == null) {
      librarySet = createLibrarySet(projectLocationHash, sdkLibrarySet, libraryCollector.externalLibraries,
                                    libraryCollector.getFlexSdkVersion(), new SwcDependenciesSorter(appDir, module), false);
      client.registerLibrarySet(librarySet);
      info.setLibrarySet(librarySet);
    }
    else {
      // todo merge existing libraries and new. create new custom external library set for myModule,
      // if we have different version of the artifact
      throw new UnsupportedOperationException("merge existing libraries and new");
    }

    ModuleInfo moduleInfo = new ModuleInfo(module);
    if (collectLocalStyleHolders) {
      stringWriter.startChange();
      try {
        ModuleInfoUtil.collectLocalStyleHolders(moduleInfo, libraryCollector.getFlexSdkVersion(), stringWriter, problemsHolder);
        client.registerModule(project, moduleInfo, new String[]{librarySet.getId()}, stringWriter);
      }
      catch (Throwable e) {
        stringWriter.rollbackChange();
        throw new InitException(e, "error.collect.local.style.holders");
      }
    }
    else {
      client.registerModule(project, moduleInfo, new String[]{librarySet.getId()}, stringWriter);
    }

    if (!problemsHolder.isEmpty()) {
      DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
    }
  }

  @NotNull
  private LibrarySet createLibrarySet(String id, @Nullable LibrarySet parent, List<Library> libraries, String flexSdkVersion,
                                      SwcDependenciesSorter swcDependenciesSorter, final boolean isFromSdk)
    throws InitException {
    try {
      swcDependenciesSorter.sort(libraries, id, flexSdkVersion, isFromSdk);
      return new LibrarySet(id, parent, ApplicationDomainCreationPolicy.ONE, swcDependenciesSorter.getItems(),
        swcDependenciesSorter.getResourceBundleOnlyitems(), swcDependenciesSorter.getEmbedItems());
    }
    catch (Throwable e) {
      throw new InitException(e, "error.sort.libraries");
    }
  }

  public Library createOriginalLibrary(@NotNull final VirtualFile virtualFile, @NotNull final VirtualFile jarFile,
                                               @NotNull final Consumer<Library> initializer) {
    if (list.contains(jarFile)) {
      return list.getInfo(jarFile);
    }
    else {
      final String path = virtualFile.getPath();
      Library library =
        new Library(virtualFile.getNameWithoutExtension() + "." + Integer.toHexString(path.hashCode()), jarFile);
      initializer.consume(library);
      return library;
    }
  }

  @Nullable
  public PropertiesFile getResourceBundleFile(String locale, String bundleName, ProjectInfo projectInfo) {
    LibrarySet librarySet = projectInfo.getLibrarySet();
    PropertiesFile propertiesFile;
    do {
      for (LibrarySetItem item : librarySet.getItems()) {
        Library library = item.library;
        if (library.hasResourceBundles() && (propertiesFile = getResourceBundleFile(locale, bundleName, library, projectInfo)) != null) {
          return propertiesFile;
        }
      }

      for (LibrarySetItem item : librarySet.getResourceBundleOnlyitems()) {
        if ((propertiesFile = getResourceBundleFile(locale, bundleName, item.library, projectInfo)) != null) {
          return propertiesFile;
        }
      }
    }
    while ((librarySet = librarySet.getParent()) != null);

    return null;
  }

  private PropertiesFile getResourceBundleFile(String locale, String bundleName, Library library, ProjectInfo projectInfo) {
    final THashSet<String> bundles = library.resourceBundles.get(locale);
    if (!bundles.contains(bundleName)) {
      return null;
    }
    
    //noinspection ConstantConditions
    VirtualFile virtualFile = library.getFile().findChild("locale").findChild(locale).findChild(
      bundleName + CatalogXmlBuilder.PROPERTIES_EXTENSION);
    //noinspection ConstantConditions
    return (PropertiesFile)PsiDocumentManager.getInstance(projectInfo.getElement()).getPsiFile(
      FileDocumentManager.getInstance().getDocument(virtualFile));
  }
}