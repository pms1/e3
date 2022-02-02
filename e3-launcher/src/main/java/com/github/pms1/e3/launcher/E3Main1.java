package com.github.pms1.e3.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.internal.adaptor.EclipseAppLauncher;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.environment.EnvironmentInfo;
import org.eclipse.osgi.service.runnable.ApplicationLauncher;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

public class E3Main1 {

	static class BundleConfiguration {
		String file;
		int startLevel;
		boolean autostart;
		public Bundle bundle;
	}

	static Logger logger = Logger.getLogger(E3Main1.class.getName());

	static interface Loader {
		URL lookup(String name);
	}

	public static void main(String[] args1) throws Exception {
		Properties launcherProperties = new Properties();
		ClassLoader cl = E3Main1.class.getClassLoader();

		Function<String, URL> loader = cl::getResource;
		if (false)
			loader = n -> {
				try {
					return new URL("file:/c:/temp/u1/" + n);
				} catch (MalformedURLException e1) {
					throw new RuntimeException(e1);
				}
			};

		try (InputStream in = loader.apply("launcher.properties").openStream()) {
			if (in == null)
				throw new Error("Resource with configuration not found: /launcher.properties");
			launcherProperties.load(in);
		}

		for (Map.Entry<Object, Object> e : launcherProperties.entrySet()) {
			String key = (String) e.getKey();
			if (key.startsWith("system."))
				System.setProperty(key.substring(7), (String) e.getValue());
		}

		FrameworkFactory frameworkFactory = ServiceLoader.load(FrameworkFactory.class, cl).iterator().next();

		Map<String, String> config = new HashMap<String, String>();
		for (Map.Entry<Object, Object> e : launcherProperties.entrySet()) {
			String key = (String) e.getKey();
			if (key.startsWith("framework."))
				config.put(key.substring(10), (String) e.getValue());
		}
		config.put(Constants.FRAMEWORK_STORAGE_CLEAN, "true");
		/*
		 * set by EclipseStarter and needed to make some jvm packages (e.g. org.sax.xml,
		 * javax.xml.parser) visible to plugins that do not import them (e.g. p2)
		 */
		config.put(EquinoxConfiguration.PROP_COMPATIBILITY_BOOTDELEGATION, "true");

		// not sure how to handle this
		// config.put("eclipse.consoleLog", "true");

		Path storage = Files.createTempDirectory("e3");
		logger.fine("Using temporary storage " + storage);
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					logger.fine("Deleting temporary storage " + storage);
					Files.walk(storage).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
					logger.fine("Deleted temporary storage " + storage);
				} catch (IOException e) {
					logger.log(Level.WARNING, "Failed to delete temporary storage " + storage + ": " + e, e);
				}
			}
		});
		config.put(Constants.FRAMEWORK_STORAGE, storage.toString());

		// copy pass-through configuration files to the configuration area
		for (String s : launcherProperties.getProperty("configuration.copy").split(",", -1)) {
			Path dest = storage.resolve(s);
			Files.createDirectories(dest.getParent());
			try (InputStream resourceAsStream = loader.apply(".configuration/" + s).openStream()) {
				Files.copy(resourceAsStream, dest);
			}
		}
		// for (Object k : configIni.keySet())
		// config.put((String) k, configIni.getProperty((String) k));
		// config.remove("osgi.framework");

		// TODO: add some config properties

		logger.fine("creating framework with config = " + config);
		Framework framework = frameworkFactory.newFramework(config);

		logger.fine("starting framework");
		framework.start();

		// install protocol handler for "embedded" protocol that is used to load
		// from the fat jar
		{
			Hashtable<String, String[]> properties = new Hashtable<>(1);
			properties.put(URLConstants.URL_HANDLER_PROTOCOL, new String[] { "embedded" });

			framework.getBundleContext().registerService(URLStreamHandlerService.class.getName(),
					new AbstractURLStreamHandlerService() {

						@Override
						public URLConnection openConnection(URL u) throws IOException {
							String p = u.getPath();
							if (p.startsWith("/"))
								p = p.substring(1);

							URL u2 = loader.apply(p);

							if (u2 == null)
								throw new IOException("Not found: " + u);

							return u2.openConnection();
						}

					}, properties);
		}

		List<BundleConfiguration> bs = new LinkedList<>();

		for (String bundle : launcherProperties.getProperty("osgi.bundles").split(",", -1)) {

			BundleConfiguration b = new BundleConfiguration();
			int x = bundle.indexOf("@");
			if (x == -1)
				throw new Error();

			// FIXME: use RE

			b.file = bundle.substring(0, x);

			String rest = bundle.substring(x + 1);
			if (rest.endsWith(":start")) {
				b.autostart = true;
				rest = rest.substring(0, rest.length() - 6);
			}
			b.startLevel = Integer.valueOf(rest);

			bs.add(b);
		}

		BundleContext context = framework.getBundleContext();

		if (false)
			context.addFrameworkListener(new FrameworkListener() {

				@Override
				public void frameworkEvent(FrameworkEvent event) {
					logger.fine("got Framework event: " + event + " " + event.getType() + " " + event.getBundle() + " "
							+ event.getSource() + " " + event.getThrowable());
				}
			});

		if (false) {
			LogListener l = new LogListener() {

				@Override
				public void logged(LogEntry entry) {
					logger.fine("got LogEntry: " + entry.getLevel() + " " + entry.getMessage() + " " + entry.getTime()
							+ " " + entry.getBundle() + " " + entry.getServiceReference());
				}
			};
			ServiceReference<LogReaderService> sr = context.getServiceReference(LogReaderService.class);
			LogReaderService service = context.getService(sr);
			service.addLogListener(l);
		}

		logger.fine("installing bundles");
		for (BundleConfiguration b : bs) {
			logger.fine("installing bundle " + b.file);
			b.bundle = context.installBundle("embedded:" + b.file);
		}

		logger.fine("starting bundles");
		for (int sl = 0; sl <= Integer.valueOf(config.get("osgi.bundles.defaultStartLevel")); ++sl) {
			logger.fine("starting bundles in runlevel " + sl);
			for (BundleConfiguration b : bs) {
				if (b.startLevel != sl)
					continue;
				if (!b.autostart)
					continue;
				logger.fine("starting bundle " + b.bundle.getSymbolicName() + " " + b.bundle.getState());
				b.bundle.start();
				logger.fine("started bundle " + b.bundle.getSymbolicName() + " " + b.bundle.getState());
			}
		}

		if (logger.isLoggable(Level.FINEST))
			for (Bundle b : context.getBundles())
				logger.fine("bundle state: " + b.getSymbolicName() + " " + b.getState());

		int exitCode;
		try {

			boolean failOnNoDefault = true;
			boolean relaunch = false;

			ServiceReference<FrameworkLog> logRef = context.getServiceReference(FrameworkLog.class);
			FrameworkLog log = context.getService(logRef);
			ServiceReference<EnvironmentInfo> configRef = context.getServiceReference(EnvironmentInfo.class);
			EquinoxConfiguration equinoxConfig = (EquinoxConfiguration) context.getService(configRef);

			EclipseAppLauncher appLauncher = new EclipseAppLauncher(context, relaunch, failOnNoDefault, log,
					equinoxConfig);
			ServiceRegistration<?> appLauncherRegistration = context
					.registerService(ApplicationLauncher.class.getName(), appLauncher, null);

			logger.fine("Starting EclipseAppLauncher with arguments " + Arrays.toString(args1));
			Object result = appLauncher.start(args1);
			logger.fine("EclipseAppLauncher exited normally");

			appLauncherRegistration.unregister();

			if (result instanceof Integer)
				exitCode = (Integer) result;
			else
				exitCode = 0;
		} catch (Throwable t) {
			logger.log(Level.FINE, "EclipseAppLauncher terminated with exception: " + t, t);
			t.printStackTrace();
			exitCode = 1;
		} finally {
			logger.fine("Initiating framework stop");
			framework.stop();
			logger.fine("Waiting for framework stop");
			framework.waitForStop(0);
		}
		logger.fine("Exiting");
		System.exit(exitCode);
	}
}
