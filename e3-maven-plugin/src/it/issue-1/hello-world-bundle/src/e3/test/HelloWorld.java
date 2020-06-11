package e3.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

public class HelloWorld implements IApplication {

	@Override
	public Object start(IApplicationContext context) throws Exception {
		try {
			System.out.println("hello, world "
					+ Arrays.toString((String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS)));

			Bundle ourBundle = FrameworkUtil.getBundle(IApplicationContext.class);
			if (ourBundle == null)
				throw new Error("failed: get bundle for IApplicationContext");

			Bundle systemBundle = ourBundle.getBundleContext().getBundle(0);
			String res = "e3/frameworkextension/Formatter.class";

			URL u = systemBundle.getResource(res);
			if (u == null)
				throw new Error("Cannot find '" + res
						+ "' in system.bundle, i.e. extension classloading of 'com.capgemini.fisgui.tools.companion.framework' failed.");

			String nu = u.toString();
			if (!nu.endsWith(res))
				throw new Error(
						"Location of resource '" + res + "' is '" + u + "'. Expected it to end with resource path.");
			nu = nu.substring(0, nu.length() - res.length());

			Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			method.setAccessible(true);
			method.invoke(ClassLoader.getSystemClassLoader(), new Object[] { new URL(nu) });

			Properties loggingProperties = new Properties();
			loggingProperties.setProperty(".level", "WARNING");
			loggingProperties.setProperty("handlers", "java.util.logging.ConsoleHandler");
			loggingProperties.setProperty("java.util.logging.ConsoleHandler.level", "ALL");
			loggingProperties.setProperty("java.util.logging.ConsoleHandler.formatter",
					"e3.frameworkextension.Formatter");

			try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
				loggingProperties.store(baos, "");
				try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
					LogManager.getLogManager().readConfiguration(is);
				}
			}

			Logger.getLogger("test").warning("warning");

			//
			try {
				System.out.println(
						"launcher.properties stream is " + new URL("embedded:launcher.properties").openStream());
			} catch (Throwable e) {
				System.out.println("launcher.properties failed " + e);
			}

			return 0;
		} catch (Throwable t) {
			t.printStackTrace();
			return 1;
		}

	}

	@Override
	public void stop() {

	}

}
