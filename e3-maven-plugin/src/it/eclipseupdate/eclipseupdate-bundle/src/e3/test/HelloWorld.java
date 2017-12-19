package e3.test;

import org.eclipse.core.runtime.IBundleGroup;
import org.eclipse.core.runtime.IBundleGroupProvider;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class HelloWorld implements IApplication {

	@Override
	public Object start(IApplicationContext context) throws Exception {
		try {
			System.err.println("configuration location = " + Platform.getConfigurationLocation().getURL());
			System.err.println("configuration location default = " + Platform.getConfigurationLocation().getDefault());
			
			IBundleGroupProvider[] providers = Platform.getBundleGroupProviders();

			if (providers != null) {
				for (IBundleGroupProvider provider : providers) {
					System.out.println("provider " + provider.getName());
					IBundleGroup[] bundleGroups = provider.getBundleGroups();

					for (IBundleGroup bundleGroup : bundleGroups) {
						System.out.println(
								"\tbundle group " + bundleGroup.getIdentifier() + " " + bundleGroup.getVersion());
					}
				}
			} else {
				System.out.println("no bundle group providers");
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
