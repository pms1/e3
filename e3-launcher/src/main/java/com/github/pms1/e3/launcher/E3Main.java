package com.github.pms1.e3.launcher;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 
 * Create a {@link URLClassLoader} based classloader (for compatibility with
 * FrameworkExtensionInstaller) and run {@link E3Main1} from there.
 * 
 * @author pms1
 *
 */
public class E3Main {

	public static void main(String[] args) throws Exception {

		String classpath = System.getProperty("java.class.path");
		if (classpath == null)
			throw new Error("Error: require 'java.class.path' system property to be set");

		List<URL> classpathUrls = new ArrayList<>();
		for (String c : classpath.split(Pattern.quote(File.pathSeparator)))
			classpathUrls.add(new File(c).toPath().toAbsolutePath().toUri().toURL());

		// find the parent loader that does not see ourself
		String selfClassResource = E3Main.class.getName().replace(".", "/") + ".class";
		ClassLoader delegate = E3Main.class.getClassLoader();
		for (;;) {
			delegate = delegate.getParent();
			if (delegate.getResource(selfClassResource) == null)
				break;
		}

		try (FrameworkClassloader frameworkClassLoader = new FrameworkClassloader(classpathUrls.toArray(new URL[0]),
				delegate)) {
			Class<?> e3Main1 = frameworkClassLoader.loadClass(E3Main.class.getName() + "1");
			e3Main1.getMethod("main", String[].class).invoke(null, (Object) args);
		}
	}

	public static class FrameworkClassloader extends URLClassLoader {

		public FrameworkClassloader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		// make public to allow reflective access by FrameworkExtensionInstaller
		@Override
		public void addURL(URL url) {
			super.addURL(url);
		}

		@Override
		public String toString() {
			return super.toString() + "(" + Arrays.asList(getURLs()) + ")";
		}
	}
}
