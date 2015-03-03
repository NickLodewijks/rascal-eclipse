/*******************************************************************************
 * Copyright (c) 2009-2011 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
*******************************************************************************/
package org.rascalmpl.eclipse.nature;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.jar.JarInputStream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.rascalmpl.eclipse.Activator;
import org.rascalmpl.eclipse.console.internal.StdAndErrorViewPart;
import org.rascalmpl.eclipse.uri.BundleURIResolver;
import org.rascalmpl.eclipse.uri.PluginURIResolver;
import org.rascalmpl.eclipse.uri.ProjectURIResolver;
import org.rascalmpl.eclipse.util.RascalEclipseManifest;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.staticErrors.StaticError;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.uri.JarInputStreamURIResolver;
import org.rascalmpl.uri.JarURIResolver;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;

public class ProjectEvaluatorFactory {
	
	private final WeakHashMap<IProject, Evaluator> parserForProject = new WeakHashMap<IProject, Evaluator>();
	private final WeakHashMap<IProject, ModuleReloader> reloaderForProject = new WeakHashMap<IProject, ModuleReloader>();
	private final PrintWriter out;
	private final PrintWriter err;
	
	private ProjectEvaluatorFactory() {
		try {
			out = new PrintWriter(new OutputStreamWriter(StdAndErrorViewPart.getStdOut(), "UTF16"));
			err = new PrintWriter(new OutputStreamWriter(StdAndErrorViewPart.getStdErr(), "UTF16"), true);
		} catch (UnsupportedEncodingException e) {
			Activator.getInstance().logException("internal error", e);
			throw new RuntimeException("???", e);
		}
	}
	
	private static class InstanceHolder {
	      public static final ProjectEvaluatorFactory sInstance = new ProjectEvaluatorFactory();
	}
	
	public static ProjectEvaluatorFactory getInstance() {
		return InstanceHolder.sInstance;
	}
	
	public void clear() {
		reloaderForProject.clear();
		parserForProject.clear();
	}
	
	public void resetParser(IProject project) {
		parserForProject.remove(project);
		reloaderForProject.remove(project);
	}
	
	/**
	 * This method returns and shares a single evaluator for each project
	 */
	public Evaluator getEvaluator(IProject project) {
		Evaluator parser = parserForProject.get(project);
		
		if (parser == null) {
			parser = createProjectEvaluator(project);
			reloaderForProject.put(project, new ModuleReloader(parser));
			parserForProject.put(project, parser);
			return parser;
		}
		
		try {
			reloaderForProject.get(project).updateModules(new NullProgressMonitor());
		}
		catch (StaticError e) {
			// things may go wrong while reloading modules, simply because the modules still have parse errors in them.
			// these are safely ignored here, the user will have had feedback on those errors elsewhere
		}
		
		return parser;
	}

	/**
	 * This method creates a fresh evaluator every time you call it.
	 */
	public Evaluator createProjectEvaluator(IProject project) {
		Activator.getInstance().checkRascalRuntimePreconditions(project);
		GlobalEnvironment heap = new GlobalEnvironment();
		Evaluator parser = new Evaluator(ValueFactoryFactory.getValueFactory(), err, out, new ModuleEnvironment("***parser***", heap), heap);
		configure(project, parser);
		return parser;
	}
	
	public Evaluator getBundleEvaluator(Bundle bundle) {
	  GlobalEnvironment heap = new GlobalEnvironment();
    Evaluator parser = new Evaluator(ValueFactoryFactory.getValueFactory(), err, out, new ModuleEnvironment("***parser***", heap), heap);
    initializeBundleEvaluator(bundle, parser);
    return parser;
	}
	
	/**
	 * This method configures an evaluator for use in an eclipse context
	 */
	public static void configure(Evaluator evaluator) {
		// NB. the code in this method is order dependent because it constructs a rascal module path in a particular order
		URIResolverRegistry resolverRegistry = URIResolverRegistry.getInstance();

		ProjectURIResolver resolver = new ProjectURIResolver();
		resolverRegistry.registerInput(resolver);
		resolverRegistry.registerOutput(resolver);

		BundleURIResolver bundleResolver = new BundleURIResolver(resolverRegistry);
		resolverRegistry.registerInput(bundleResolver);
		resolverRegistry.registerOutput(bundleResolver);

		resolverRegistry.registerInput(new JarURIResolver());
		resolverRegistry.registerInput(new PluginURIResolver(resolverRegistry));
		
		evaluator.addClassLoader(ProjectEvaluatorFactory.class.getClassLoader());

		configureRascalLibraryPlugins(evaluator);
	}
	/**
	 * This method configures an evaluator for use in an eclipse context. 
	 * @param project context to run the evaluator in, may be null
	 * @param evaluator the evaluator to configure, may not be null
	 */
	public void configure(IProject project, Evaluator evaluator) {
		if (project != null) {
			try {
				addProjectToSearchPath(project, evaluator);
				
				IProject[] projects = project.getReferencedProjects();
				for (IProject ref : projects) {
					addProjectToSearchPath(ref, evaluator);
				}
			} 
			catch (URISyntaxException usex) {
				Activator.getInstance().logException("could not construct search path", usex);
			} 
			catch (CoreException e) {
				Activator.getInstance().logException("could not construct search path", e);
			}
		}
		
		configure(evaluator);

		try {
		  if (project != null && project.hasNature(JavaCore.NATURE_ID)) {
		    configureClassPath(project, evaluator); 
		  }
		}
		catch (CoreException e) {
		  Activator.getInstance().logException("exception while constructing classpath for evaluator", e);
		}
	}
	
	private static void configure(Bundle bundle, Evaluator evaluator) {
		configure(evaluator);
		configure(bundle, evaluator, new HashSet<String>());
	}
	
	private static void configure(Bundle bundle, Evaluator eval, Set<String> configured) {
		if (bundle != null) {
			configured.add(bundle.getSymbolicName());

			try {
				addBundleToSearchPath(bundle, eval);
				eval.addClassLoader(new BundleClassLoader(bundle));
				configureClassPath(bundle, eval);

				RascalEclipseManifest mf = new RascalEclipseManifest();
				List<String> requiredBundles = mf.getRequiredBundles(bundle);
				
				if (requiredBundles != null) {
					for (String required : requiredBundles) {
						if (!configured.contains(required)) {
							configure(Platform.getBundle(required), eval);
						}
					}
				}
				
				List<String> libs = mf.getRequiredLibraries(bundle);
				if (libs != null) {
					for (String required : libs) {
						try {
							JarInputStreamURIResolver resolver = new JarInputStreamURIResolver(ValueFactoryFactory.getValueFactory().sourceLocation(bundle.getEntry(required).toURI()));
							URIResolverRegistry.getInstance().registerInput(resolver);
							addJarToSearchPath(resolver, eval);
						} catch (IOException e) {
							Activator.log("ignoring lib " + required, e);
						}
					}
				}
			} 
			catch (URISyntaxException e) {
				Activator.getInstance().logException("could not construct search path", e);
			}
		}
	}

	 /**
   * This method configures an evaluator for use in an eclipse context. 
   * @param bundle context to run the evaluator in, may be null
   * @param evaluator the evaluator to configure, may not be null
   */
  public void initializeBundleEvaluator(Bundle bundle, Evaluator evaluator) {
    configure(bundle, evaluator);
  }
	
	public void loadInstalledRascalLibraryPlugins() {
    IExtensionPoint extensionPoint = Platform.getExtensionRegistry()
        .getExtensionPoint("rascal_eclipse", "rascalLibrary");

    if (extensionPoint == null) {
      return; // this may happen when nobody extends this point.
    }
    
    for (IExtension element : extensionPoint.getExtensions()) {
      String name = element.getContributor().getName();
      Bundle bundle = Platform.getBundle(name);
      Evaluator bundleEval = getBundleEvaluator(bundle);
      
      // first load the other plugins
      // TODO: support true dependencies
      configureRascalLibraryPlugins(bundleEval);
      
      // then run the main of the current one
      runLibraryPluginMain(bundleEval, bundle);
    } 
  }
	
	public static void configureRascalLibraryPlugins(Evaluator evaluator) {
	  IExtensionPoint extensionPoint = Platform.getExtensionRegistry()
        .getExtensionPoint("rascal_eclipse", "rascalLibrary");

	  if (extensionPoint == null) {
	    return; // this may happen when nobody extends this point.
	  }
	  
	  try {
	    for (IExtension element : extensionPoint.getExtensions()) {
	      String name = element.getContributor().getName();
	      Bundle bundle = Platform.getBundle(name);
	      configureRascalLibraryPlugin(evaluator, bundle);
	    }
	  } 
	  catch (URISyntaxException e) {
	    Activator.log("could not load some library", e);
	  }
  }

  public static void configureRascalLibraryPlugin(Evaluator evaluator, Bundle bundle) throws URISyntaxException {
    List<String> roots = new RascalEclipseManifest().getSourceRoots(bundle);
    
    for (String root : roots) {
      // TODO: add check to see if library is referenced in RASCAL.MF
      evaluator.addRascalSearchPath(URIUtil.correctLocation("plugin", bundle.getSymbolicName(), "/" + root));
    }
    
    evaluator.addClassLoader(new BundleClassLoader(bundle));
  }

  public static void runLibraryPluginMain(Evaluator evaluator, Bundle bundle) {
    try {
      RascalEclipseManifest mf = new RascalEclipseManifest();
      
      if (!mf.hasManifest(bundle)) {
        return;
      }
      
      String mainModule = mf.getMainModule(bundle);
      String mainFunction = mf.getMainFunction(bundle);
      
      // we only run a function if the main module and function have been configured.
      // this is to give the option to NOT run a main module, but provide only the 
      // plugin as a library to other plugins.
      if (mainModule != null && mainFunction != null) {
        evaluator.doImport(evaluator.getMonitor(), mainModule);
        evaluator.call(mainFunction);
      }
    }
    catch (Throwable e) {
      Activator.log("Library defined by bundle " + bundle.getSymbolicName() + " has no main module or main function", e);
    }
  }

	/**
	 * This code is taken from http://wiki.eclipse.org/BundleProxyClassLoader_recipe
	 */
	private static class BundleClassLoader extends ClassLoader {
	  private Bundle bundle;
	  private ClassLoader parent;
	    
	  public BundleClassLoader(Bundle bundle) {
	    this.bundle = bundle;
	  }
	  
	  @Override
	  public Enumeration<URL> getResources(String name) throws IOException {
	    return bundle.getResources(name);
	  }
	  
	  @Override
	  public URL findResource(String name) {
	      return bundle.getResource(name);
	  }

	  @Override
	  public Class<?> findClass(String name) throws ClassNotFoundException {
	      return bundle.loadClass(name);
	  }
	  
	  @Override
	  public URL getResource(String name) {
	    return (parent == null) ? findResource(name) : super.getResource(name);
	  }

	  @Override
	  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
	    Class<?> clazz = (parent == null) ? findClass(name) : super.loadClass(name, false);
	    if (resolve)
	      super.resolveClass(clazz);
	    
	    return clazz;
	  }
	}
	
	public static void addProjectToSearchPath(IProject project, Evaluator eval)
			throws URISyntaxException {
		RascalEclipseManifest mf = new RascalEclipseManifest();
		for (String root : mf.getSourceRoots(project)) {
			eval.addRascalSearchPath(URIUtil.correctLocation("project", project.getName(), "/" + root.trim()));
		}
		
		List<String> requiredBundles = mf.getRequiredBundles(project);
		if (requiredBundles != null) {
			for (String lib : requiredBundles) {
				configure(Platform.getBundle(lib), eval);
			}
		}
		
		List<String> requiredLibraries = mf.getRequiredLibraries(project);
		if (requiredLibraries != null) {
			for (String lib : requiredLibraries) {
				JarInputStreamURIResolver resolver = new JarInputStreamURIResolver(ValueFactoryFactory.getValueFactory().sourceLocation(project.getFile(lib).getLocationURI()));
				URIResolverRegistry.getInstance().registerInput(resolver);
				
				try {
					addJarToSearchPath(resolver, eval);
				} catch (IOException e) {
					Activator.log("ignoring lib " + lib, e);
				}
			}
		}
	}
  
  public static void addJarToSearchPath(JarInputStreamURIResolver resolver, Evaluator eval) throws URISyntaxException, IOException {
	  try (JarInputStream jarStream = new JarInputStream(resolver.getJarStream())) {
		  List<String> roots = new RascalManifest().getSourceRoots(jarStream);

		  if (roots != null) {
			  for (String root : roots) {
				  eval.addRascalSearchPath(URIUtil.correctLocation(resolver.scheme(), "", "/" + root));
			  }
		  }
	  }
  }
  
  public static void addBundleToSearchPath(Bundle bundle, Evaluator eval) throws URISyntaxException {
	  RascalEclipseManifest mf = new RascalEclipseManifest();
	  List<String> srcs = mf.getSourceRoots(bundle);

	  if (srcs != null) {
		  for (String root : srcs) {
			  eval.addRascalSearchPath(URIUtil.correctLocation("plugin", bundle.getSymbolicName(), "/" + root.trim()));
		  }
	  }
	  else {
		  eval.addRascalSearchPath(URIUtil.correctLocation("plugin", bundle.getSymbolicName(), "/"));
	  }
  }

  private static void collectClassPathForBundle(Bundle bundle, List<URL> classPath, List<String> compilerClassPath) {
    try {
      File file = FileLocator.getBundleFile(bundle);
      
      // in the case we are loading a bundle which is actually a first Eclipse level source project,
      // we now just concatenate the bin folder and hope to find our class files there.
      // this is only relevant in the context of people developing Rascal, not people using it.
      if (file.isDirectory()) {
        File bin = new File(file, "bin");
        if (bin.exists()) {
          file = bin;
        }
      }
      
      URL url = file.toURI().toURL();
      
      if (classPath.contains(url)) {
        return; // kill infinite loop
      }

      
      classPath.add(0, url);
      compilerClassPath.add(0, file.getAbsolutePath());

      BundleWiring wiring = bundle.adapt(BundleWiring.class);

      for (BundleWire dep : wiring.getRequiredWires(null)) {
        collectClassPathForBundle(dep.getProviderWiring().getBundle(), classPath, compilerClassPath);
      }
    } 
    catch (IOException e) {
     Activator.log("error construction classpath for bundle: " + bundle.getSymbolicName(), e);
    } 
  }
  
	private void collectClassPathForProject(IProject project, List<URL> classPath, List<String> compilerClassPath, Evaluator parser) {
		try {
			if (!project.hasNature(JavaCore.NATURE_ID)) {
				for (IProject ref : project.getReferencedProjects()) {
					collectClassPathForProject(ref, classPath, compilerClassPath, parser);
				}
			}
			else {
				IJavaProject jProject = JavaCore.create(project);
				
				IPath binFolder = jProject.getOutputLocation();
				String binLoc = project.getLocation() + "/" + binFolder.removeFirstSegments(1).toString();
				compilerClassPath.add(binLoc);
				
				URL url = new URL("file", "",  binLoc + "/");
				parser.addClassLoader(new URLClassLoader(new URL[] {url}, getClass().getClassLoader()));
				classPath.add(url);
				
				if (!jProject.isOpen()) {
					return;
				}
				IClasspathEntry[] entries = jProject.getResolvedClasspath(true);
				
				for (int i = 0; i < entries.length; i++) {
					IClasspathEntry entry = entries[i];
					switch (entry.getEntryKind()) {
					case IClasspathEntry.CPE_LIBRARY:
						if (entry.getPath().segment(0).equals(project.getName())) {
							String file = project.getLocation() + "/" + entry.getPath().removeFirstSegments(1).toString();
							URL url = new URL("file", "", file);
							if (!classPath.contains(url)) {
								classPath.add(url);
								compilerClassPath.add(file);
							}
						}
						else {
							URL url = new URL("file", "", entry.getPath().toString());
							if (!classPath.contains(url)) {
								classPath.add(url);
								compilerClassPath.add(entry.getPath().toString());
							}
						}
						break;
					case IClasspathEntry.CPE_PROJECT:
						collectClassPathForProject((IProject) project.getWorkspace().getRoot().findMember(entry.getPath()), classPath, compilerClassPath, parser);
						break;
					}
				}
			}
		}
		catch (CoreException e) {
			Activator.getInstance().logException("failed to configure classpath", e);
		} 
		catch (MalformedURLException e) {
			Activator.getInstance().logException("failed to configure classpath", e);
		}
	}
	
	public void configureClassPath(IProject project, Evaluator parser) {
		List<URL> classPath = new LinkedList<URL>();
		List<String> compilerClassPath = new LinkedList<String>();
		Bundle rascalBundle = Activator.getInstance().getBundle();
		
		// order is important
		collectClassPathForBundle(rascalBundle, classPath, compilerClassPath);
		collectClassPathForProject(project, classPath, compilerClassPath, parser);
		
		configureClassPath(parser, classPath, compilerClassPath);
	}
	
	public static void configureClassPath(Bundle bundle, Evaluator evaluator) {
	  List<URL> classPath = new LinkedList<URL>();
	  List<String> compilerClassPath = new LinkedList<String>();
	  collectClassPathForBundle(bundle, classPath, compilerClassPath);
	  Bundle rascalBundle = Activator.getInstance().getBundle();
	  if (!bundle.getSymbolicName().equals(rascalBundle.getSymbolicName())) {
		  collectClassPathForBundle(rascalBundle, classPath, compilerClassPath);
	  }
	  configureClassPath(evaluator, classPath, compilerClassPath);
	}
	
	private static void configureClassPath(Evaluator parser, List<URL> classPath, List<String> compilerClassPath) {
		// this registers the run-time path:
		URL[] urls = new URL[classPath.size()];
		classPath.toArray(urls);
		URLClassLoader classPathLoader = new URLClassLoader(urls, ProjectEvaluatorFactory.class.getClassLoader());
		parser.addClassLoader(classPathLoader);
		
		// this registers the compile-time path:
		String ccp = "";
		for (String elem : compilerClassPath) {
			ccp += File.pathSeparatorChar + elem;
		}
		

		
		parser.getConfiguration().setRascalJavaClassPathProperty(ccp.substring(1));
	}
}
