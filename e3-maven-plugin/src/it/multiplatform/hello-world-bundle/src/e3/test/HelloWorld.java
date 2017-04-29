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
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

public class HelloWorld implements IApplication {

	@Override
	public Object start(IApplicationContext context) throws Exception {
		
		Display display = new Display ();
		Shell shell = new Shell(display);
		
		Text helloWorldTest = new Text(shell, SWT.NONE);
		helloWorldTest.setText("Hello World SWT");
		helloWorldTest.pack();
		
		shell.pack();
		shell.open ();
		while (!shell.isDisposed ()) {
			if (!display.readAndDispatch ()) display.sleep ();
		}
		display.dispose ();
		
		return 0;
	}

	@Override
	public void stop() {

	}

}
