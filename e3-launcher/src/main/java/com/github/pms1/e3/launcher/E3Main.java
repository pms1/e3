package com.github.pms1.e3.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

import org.eclipse.core.runtime.internal.adaptor.EclipseAppLauncher;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.runnable.ApplicationLauncher;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

public class E3Main {

	static class B {
		String file;
		int startLevel;
		boolean autostart;
		public Bundle bundle;
	}

	public static void main(String[] args1) throws Exception {
		System.out.println("RUNNING " + Arrays.toString(args1));

		Properties launcherProperties = new Properties();
		try (InputStream in = E3Main.class.getResourceAsStream("/launcher.properties")) {
			launcherProperties.load(in);
		}

		System.out.println("PROP " + launcherProperties);

		ClassLoader cl = ClassLoader.getSystemClassLoader();

		if (true) {
			URLStreamHandler fooHandler = new URLStreamHandler() {

				@Override
				protected URLConnection openConnection(URL u) throws IOException {
					URLConnection x = cl.getResource("plugins/" + u.getPath()).openConnection();
					System.err.println("IS " + x.getInputStream());
					return cl.getResource("plugins/" + u.getPath()).openConnection();

					// Path p = root.resolve("plugins").resolve(u.getPath());
					//
					// System.err.println("P=" + p);
					//
					// return p.toFile().toURI().toURL().openConnection();
				}
			};
			URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {

				@Override
				public URLStreamHandler createURLStreamHandler(String protocol) {
					if (protocol.equals("foo"))
						return fooHandler;
					else
						return null;
				}
			});
		}

		FrameworkFactory frameworkFactory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
		Map<String, String> config = new HashMap<String, String>();
		config.put(Constants.FRAMEWORK_STORAGE_CLEAN, "true");
		// set by EclipseStarter and needed to make some jvm packages visiable
		// at runtime
		config.put("osgi.compatibility.bootdelegation.default", "true");
		config.put("eclipse.application", launcherProperties.getProperty("eclipse.application"));
		config.put("eclipse.consoleLog", "true");

		// FIXME
		config.put(Constants.FRAMEWORK_STORAGE, "c:/temp/temp");
		// for (Object k : configIni.keySet())
		// config.put((String) k, configIni.getProperty((String) k));
		// config.remove("osgi.framework");

		// TODO: add some config properties
		Framework framework = frameworkFactory.newFramework(config);
		framework.start();

		String property = framework.getBundleContext().getProperty(Constants.FRAMEWORK_SYSTEMPACKAGES);

		System.err.println("X " + property);

		int maxStartLevel = -1;

		List<B> bs = new LinkedList<>();

		for (String bundle : launcherProperties.getProperty("osgi.bundles").split(",", -1)) {

			B b = new B();
			int x = bundle.indexOf("@");
			if (x == -1)
				throw new Error();

			String ref = bundle.substring(0, x);
			String p = "reference:file:";
			if (!ref.startsWith(p))
				throw new Error();

			b.file = ref.substring(p.length());

			String rest = bundle.substring(x + 1);
			if (rest.endsWith(":start")) {
				b.autostart = true;
				rest = rest.substring(0, rest.length() - 6);
			}
			b.startLevel = Integer.valueOf(rest);
			maxStartLevel = Math.max(b.startLevel, maxStartLevel);

			if (!b.file.endsWith(".jar")) {
				System.err.println("SKIP " + b.file);
				continue;
			}
			bs.add(b);

		}

		System.err.println("S1");
		BundleContext context = framework.getBundleContext();
		System.err.println("S1.1");
		for (String bundle : launcherProperties.getProperty("osgi.framework.extensions").split(",", -1)) {

			String p = "reference:file:";
			if (!bundle.startsWith(p))
				throw new Error();

			System.err.println("INSTALL " + bundle);

			context.installBundle("foo:" + bundle.substring(p.length()));
		}

		for (B b : bs) {
			System.err.println("INSTALL " + b.file);
			b.bundle = context.installBundle("foo:" + b.file);
		}
		System.err.println("INSTALLED " + bs.size());

		for (int sl = 0; sl != maxStartLevel + 1; ++sl) {
			for (B b : bs) {
				if (b.startLevel != sl)
					continue;
				if (!b.autostart)
					continue;
				System.err.println("START " + sl + " " + b.bundle.getSymbolicName());
				b.bundle.start();
			}
		}

		System.err.println("S3");

		bs.stream().filter(b -> b.file.contains("hello-world-bundle")).findFirst().get().bundle.start();
		bs.stream().filter(b -> b.file.contains("org.eclipse.core.runtime")).findFirst().get().bundle.start();

		for (Bundle b : context.getBundles()) {
			System.err.println("STATE " + b.getSymbolicName() + " " + b.getState());
		}
		try {

			boolean launchDefault = true;
			EquinoxConfiguration equinoxConfig = null;
			FrameworkLog log = new FrameworkLog() {

				@Override
				public void log(FrameworkEvent frameworkEvent) {
					System.err.println("LOG " + frameworkEvent);
				}

				@Override
				public void log(FrameworkLogEntry logEntry) {
					System.err.println("LOG " + logEntry);

				}

				@Override
				public void setWriter(Writer newWriter, boolean append) {
					new Throwable().printStackTrace();
					// TODO Auto-generated method stub

				}

				@Override
				public void setFile(File newFile, boolean append) throws IOException {
					// TODO Auto-generated method stub
					new Throwable().printStackTrace();

				}

				@Override
				public File getFile() {
					new Throwable().printStackTrace();
					// TODO Auto-generated method stub
					return null;
				}

				@Override
				public void setConsoleLog(boolean consoleLog) {
					new Throwable().printStackTrace();
					// TODO Auto-generated method stub

				}

				@Override
				public void close() {
					new Throwable().printStackTrace();
					// TODO Auto-generated method stub

				}

			};
			EclipseAppLauncher appLauncher = new EclipseAppLauncher(context, false, launchDefault, log, equinoxConfig);
			ServiceRegistration<?> appLauncherRegistration = context
					.registerService(ApplicationLauncher.class.getName(), appLauncher, null);

			System.err.println("S3");

			appLauncher.start(args1);

			System.err.println("S4");
			framework.stop();
			System.err.println("S4.1");
			framework.waitForStop(0);
			System.err.println("S5");
		} catch (Throwable t) {
			t.printStackTrace();
			System.err.println("S6");
		} finally {
			System.err.println("S7");
			System.exit(0);
		}
	}
}
