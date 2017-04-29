package com.github.pms1.e3.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

import com.github.pms1.e3.launcher.E3Main;

@Mojo(name = "create-embedded", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class EmbedMojo extends AbstractMojo {

	@Parameter(property = "project", readonly = true)
	private MavenProject project;

	@Parameter(readonly = true, required = true, defaultValue = "${project.remoteArtifactRepositories}")
	private List<ArtifactRepository> remoteRepositories;

	@Parameter(readonly = true, required = true, defaultValue = "${localRepository}")
	private ArtifactRepository localRepository;

	@Parameter(defaultValue = "${mojoExecution}", readonly = true)
	private MojoExecution mojoExecution;

	@Component(hint = "default")
	private DependencyGraphBuilder dependencyGraphBuilder;

	@Parameter(property = "session", readonly = true)
	private MavenSession session;

	@Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
	private File classesDir;

	@Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/MANIFEST.MF", readonly = true)
	private File manifestPath;

	@Component
	private RepositorySystem repositorySystem;

	@Component
	private ResolutionErrorHandler resolutionErrorHandler;

	MessageDigest digest;
	ByteBuffer digestBuffer = ByteBuffer.allocateDirect(32 * 1024);
	{
		try {
			digest = MessageDigest.getInstance("SHA1");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	synchronized byte[] digest(Path p) {
		digest.reset();

		try {
			FileChannel fileChannel = FileChannel.open(p);

			for (;;) {
				int read = fileChannel.read(digestBuffer);
				if (read == -1)
					break;

				digestBuffer.flip();
				digest.update(digestBuffer);
				digestBuffer.clear();
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to digest " + p, e);
		}
		return digest.digest();
	}

	private static class FileInfo {
		Path p;
		long size;
		byte[] sha1sum;
	}

	private List<FileInfo> files = new ArrayList<>();

	private void register(Path p) throws IOException {
		if (files.stream().anyMatch(p1 -> p1.p.equals(p)))
			throw new IllegalArgumentException();
		FileInfo fi = new FileInfo();
		fi.p = p;
		fi.size = Files.size(p);
		files.add(fi);
	}

	private Path find(Path p) throws IOException {
		long size = Files.size(p);

		List<FileInfo> sizeMatch = files.stream().filter(p1 -> p1.size == size).collect(Collectors.toList());
		if (sizeMatch.isEmpty())
			return null;

		byte[] file = digest(p);

		return sizeMatch.stream().filter(p1 -> {
			if (p1.sha1sum == null)
				p1.sha1sum = digest(p1.p);
			return Arrays.equals(p1.sha1sum, file);
		}).findAny().orElse(null).p;
	}

	private void addBundle(Path p, int startLevel, boolean start) throws IOException {
		Path path = find(p);

		if (path == null) {
			Path dest = classesDir.toPath().resolve("plugins").resolve(p.getFileName());
			if (Files.exists(dest))
				throw new Error("Duplicate: " + dest);
			Files.copy(p, dest);
			register(dest);
			path = dest;
		}

		URI relPath = URI.create(classesDir.toPath().relativize(path).toString().replace(File.separatorChar, '/'));

		List<BundleSpec> existing = bundles.stream().filter(bs -> bs.relPath.equals(relPath))
				.collect(Collectors.toList());

		switch (existing.size()) {
		case 0:
			BundleSpec bs = new BundleSpec();
			bs.relPath = relPath;
			bs.startLevel = startLevel;
			bs.start = start;
			bundles.add(bs);
			break;
		case 1:
			bs = existing.get(0);
			if (bs.start != start)
				throw new Error("conflict");
			if (bs.startLevel != startLevel)
				throw new Error("conflict");
			break;
		default:
			throw new Error("duplicate");
		}
	}

	List<BundleSpec> bundles = new ArrayList<>();

	public static void main(String[] args) throws URISyntaxException {
		Path p = Paths.get("c:/temp/");
		Path p1 = Paths.get("c:/temp/a/b");
		Path r = p.relativize(p1);
		System.err.println("r " + r);
		System.err.println(r.toUri());
		System.err.println(new URI(r.toString()));
	}

	static class BundleSpec {
		URI relPath;
		int startLevel;
		boolean start;
	}

	public void execute() throws MojoExecutionException, MojoFailureException {

		System.err.println("EXECUTING " + this);

		ProjectBuildingRequest pbRequest = new DefaultProjectBuildingRequest();
		pbRequest.setLocalRepository(localRepository);
		pbRequest.setProject(project);
		pbRequest.setRemoteRepositories(remoteRepositories);
		pbRequest.setRepositorySession(session.getRepositorySession());
		pbRequest.setResolveDependencies(true);
		pbRequest.setResolveVersionRanges(true);

		try {
			DependencyNode n = dependencyGraphBuilder.buildDependencyGraph(pbRequest, null);

			Set<Artifact> directDependencies = new HashSet<>();

			n.accept(new DependencyNodeVisitor() {
				@Override
				public boolean visit(DependencyNode node) {
					// ourself
					if (node.getParent() == null)
						return true;

					directDependencies.add(node.getArtifact());

					return false;
				}

				@Override
				public boolean endVisit(DependencyNode node) {
					return true;
				}
			});

			if (Files.isDirectory(classesDir.toPath()))
				for (Path p : Files.walk(classesDir.toPath()).filter(Files::isRegularFile).collect(Collectors.toList()))
					register(p);

			for (Artifact a : directDependencies) {

				try (FileSystem fs = FileSystems.newFileSystem(a.getFile().toPath(), null)) {
					Properties p = new Properties();

					try (InputStream in = Files.newInputStream(fs.getPath("configuration", "config.ini"))) {
						p.load(in);
					}

					String framework = null;
					String bundles = null;
					Integer defaultStartLevel = null;
					String application = null;
					String frameworkExtensions = "";
					URI simpleConfigurator = null;

					for (Map.Entry<Object, Object> e : p.entrySet()) {
						String key = (String) e.getKey();
						String value = (String) e.getValue();

						switch (key) {
						case "osgi.bundles":
							bundles = value;
							break;
						case "osgi.bundles.defaultStartLevel":
							defaultStartLevel = Integer.valueOf(value);
							break;
						case "osgi.framework":
							framework = value;
							break;
						case "osgi.framework.extensions":
							frameworkExtensions = value;
							break;
						case "eclipse.application":
							application = value;
							break;
						case "org.eclipse.equinox.simpleconfigurator.configUrl":
							simpleConfigurator = URI.create(value);
							break;
						case "eclipse.p2.data.area":
							break;
						case "eclipse.p2.profile":
							break;
						default:
							getLog().warn("Unknown config.ini property '" + key + "' = '" + value + "' ignored.");
						}
					}

					if (framework == null)
						throw new MojoExecutionException("Missing config.ini: 'osgi.framework'");

					if (bundles == null)
						throw new MojoExecutionException("Missing config.ini: 'osgi.bundles'");

					if (defaultStartLevel == null)
						throw new MojoExecutionException("Missing config.ini: 'osgi.bundles.defaultStartLevel'");

					if (application == null)
						throw new MojoExecutionException("Missing config.ini: 'eclipse.application'");

					Properties launcherProperties = new Properties();
					launcherProperties.put("osgi.bundles.defaultStartLevel", defaultStartLevel.toString());
					launcherProperties.put("eclipse.application", application);

					URI frameworkUri = URI.create(framework);

					if (!frameworkUri.getScheme().equals("file"))
						throw new MojoExecutionException(
								"Framework URI must have \"file\" scheme: '" + frameworkUri + "'");

					Manifest manifest = null;

					Path p1 = fs.getPath(frameworkUri.getSchemeSpecificPart());
					try (InputStream in = Files.newInputStream(p1); ZipInputStream zis = new ZipInputStream(in)) {
						ZipEntry entry;
						while ((entry = zis.getNextEntry()) != null) {
							String name = entry.getName();
							if (name.equals("META-INF/MANIFEST.MF")) {
								if (manifest != null)
									throw new MojoExecutionException("Duplicate manifest in " + p1);
								manifest = new Manifest(zis);
							} else if (entry.isDirectory()) {
								continue;
							} else if (name.matches("META-INF/[^/]+[.]SF")) {
								// remove signature information as it
								// becomes
								// invalid by re-packaging
								continue;
							} else if (!entry.isDirectory()) {
								Path path = classesDir.toPath().resolve(entry.getName());
								Files.createDirectories(path.getParent());
								Files.copy(zis, path);
							}
						}
					}

					manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, E3Main.class.getName());

					// remove signature information as it becomes invalid by
					// re-packaging
					manifest.getEntries().clear();

					// add launcher
					Artifact launcher = resolveDependency(repositorySystem.createArtifact("com.github.pms1.e3",
							"e3-launcher", mojoExecution.getVersion(), "jar"));
					try (InputStream in = Files.newInputStream(launcher.getFile().toPath());
							ZipInputStream zis = new ZipInputStream(in)) {
						ZipEntry entry;
						while ((entry = zis.getNextEntry()) != null) {
							String name = entry.getName();
							if (name.equals("META-INF/MANIFEST.MF")) {
								continue;
							} else if (entry.isDirectory()) {
								continue;
							} else if (name.matches("META-INF/[^/]+[.]SF")) {
								// remove signature information as it
								// becomes
								// invalid by re-packaging
								continue;
							} else if (!entry.isDirectory()) {
								Path path = classesDir.toPath().resolve(entry.getName());
								Files.createDirectories(path.getParent());
								Files.copy(zis, path);
							}
						}
					}

					Files.createDirectories(manifestPath.toPath().getParent());
					try (OutputStream out = Files.newOutputStream(manifestPath.toPath())) {
						manifest.write(out);
					}

					Path pluginsDest = classesDir.toPath().resolve("plugins");
					Files.createDirectory(pluginsDest);

					for (String spec : bundles.split(",", -1)) {
						Matcher m = Pattern.compile("reference:file:(?<file>.+)@(?<runLevel>\\d)+(?<start>:start)?")
								.matcher(spec);
						if (!m.matches())
							throw new MojoExecutionException("Unhandled specification: " + spec);

						Path plugin = fs.getPath("plugins", m.group("file"));
						if (Files.isDirectory(plugin)) {
							getLog().error("Not supported: directory: " + spec);
						} else {
							addBundle(plugin, Integer.valueOf(m.group("runLevel")), m.group("start") != null);
						}
					}

					for (String spec : frameworkExtensions.split(",", -1)) {
						Matcher m = Pattern.compile("reference:file:(?<file>.+)").matcher(spec);
						if (!m.matches())
							throw new MojoExecutionException("Unhandled specification: " + spec);

						Path plugin = fs.getPath("plugins", m.group("file"));
						if (Files.isDirectory(plugin)) {
							getLog().error("Not supported: directory: " + spec);
						} else {
							addBundle(plugin, 0, false);
						}
					}

					if (simpleConfigurator != null) {
						try (BufferedReader r = Files.newBufferedReader(
								fs.getPath("configuration", simpleConfigurator.getSchemeSpecificPart()),
								StandardCharsets.UTF_8)) {

							for (String line = r.readLine(); line != null; line = r.readLine()) {
								// javax.inject,1.0.0.v20091030,plugins/javax.inject_1.0.0.v20091030.jar,4,false

								if (line.startsWith("#encoding=")) {
									if (!line.equals("#encoding=UTF-8"))
										throw new MojoExecutionException("Only UTF-8 supported");
								} else if (line.startsWith("#version=")) {
									if (!line.equals("#version=1"))
										throw new MojoExecutionException("Only version 1 supported");
								} else if (line.startsWith("#")) {
									getLog().error("Not supported: " + line);
								} else {
									String[] s = line.split(",");
									if (s[2].equals(frameworkUri.getSchemeSpecificPart()))
										continue;

									if (s.length != 5)
										throw new MojoExecutionException("Not supported: " + line);
									if (!s[2].startsWith("plugins/"))
										throw new MojoExecutionException("Not supported: " + s[2]);

									Path plugin = fs.getPath(s[2]);
									if (Files.isDirectory(plugin)) {
										getLog().error("Not supported: directory: " + line);
										continue;
									} else {
										boolean start;
										switch (s[4]) {
										case "true":
											start = true;
											break;
										case "false":
											start = false;
											break;
										default:
											throw new MojoExecutionException("Not supported: " + line);
										}

										addBundle(plugin, Integer.valueOf(s[3]), start);
									}
								}
							}
						}
					}

					launcherProperties.put("osgi.bundles", this.bundles.stream().map(bs -> {
						String s = bs.relPath + "@" + bs.startLevel;
						if (bs.start)
							s += ":start";
						return s;
					}).collect(Collectors.joining(",")));

					try (OutputStream out = Files.newOutputStream(classesDir.toPath().resolve("launcher.properties"))) {
						launcherProperties.store(out, "");
					}

				}
			}
		} catch (DependencyGraphBuilderException | IOException | MavenExecutionException e) {
			throw new MojoExecutionException("failed", e);
		}

	}

	private Artifact resolveDependency(Artifact artifact) throws MavenExecutionException {

		ArtifactResolutionRequest request = new ArtifactResolutionRequest();
		request.setArtifact(artifact);
		request.setResolveRoot(true).setResolveTransitively(false);
		request.setLocalRepository(localRepository);
		request.setRemoteRepositories(remoteRepositories);
		request.setOffline(session.isOffline());
		request.setProxies(session.getSettings().getProxies());
		request.setForceUpdate(session.getRequest().isUpdateSnapshots());

		ArtifactResolutionResult result = repositorySystem.resolve(request);

		try {
			resolutionErrorHandler.throwErrors(request, result);
		} catch (ArtifactResolutionException e) {
			throw new MavenExecutionException("Could not resolve artifact for " + artifact, e);
		}

		return artifact;
	}
}
