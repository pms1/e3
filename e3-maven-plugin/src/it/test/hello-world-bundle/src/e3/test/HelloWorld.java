package e3.test;

import java.util.Arrays;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class HelloWorld implements IApplication {

	@Override
	public Object start(IApplicationContext context) throws Exception {
		
		System.out.println("hello, world " + Arrays.toString((String[])context.getArguments().get(IApplicationContext.APPLICATION_ARGS)));
		return 0;
	}

	@Override
	public void stop() {
		
	}

}
