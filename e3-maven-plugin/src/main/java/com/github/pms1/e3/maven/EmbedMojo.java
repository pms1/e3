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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

		FileInfo fi = sizeMatch.stream().filter(p1 -> {
			if (p1.sha1sum == null)
				p1.sha1sum = digest(p1.p);
			return Arrays.equals(p1.sha1sum, file);
		}).findAny().orElse(null);
		return fi != null ? fi.p : null;
	}

	private void addBundle(Path p, Integer startLevel, Boolean start) throws IOException {
		Path path = find(p);

		if (path == null) {
			Path dest = classesDir.toPath().resolve("plugins").resolve(p.getFileName().toString());
			if (Files.exists(dest))
				throw new Error("Duplicate: " + dest);
			if (!Files.isDirectory(dest.getParent()))
				Files.createDirectories(dest.getParent());
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
			if (bs.start != null && !Objects.equals(bs.start, start))
				throw new Error("conflict");
			if (bs.startLevel != null && !Objects.equals(bs.startLevel, startLevel))
				throw new Error("startLevel conflict for " + bs.relPath + ": " + bs.startLevel + " " + startLevel);
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
		Integer startLevel;
		Boolean start;
	}

	public void execute() throws MojoExecutionException, MojoFailureException {

		if (false) {
			System.err.println("EXECUTING " + this);

			for (MavenProject p : session.getAllProjects()) {
				System.err.println("PROJECT " + p);
				for (Artifact a : p.getAttachedArtifacts()) {
					System.err.println("PROJECT " + p + " ARTIFACT " + a.getGroupId() + ":" + a.getArtifactId() + ":"
							+ a.getVersion() + ":" + a.getClassifier() + ":" + a.getType() + " " + a.getFile());
				}
			}
			System.err.println("EXECUTING2 " + this);
		}

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

			String framework0 = null;

			Properties launcherProperties = new Properties();
			Set<String> configurationCopy = new HashSet<>();

			for (Artifact a : directDependencies) {

				try (FileSystem fs = FileSystems.newFileSystem(a.getFile().toPath(), null)) {

					List<Path> inis = new LinkedList<>();

					for (Path p1 : fs.getRootDirectories()) {
						Files.list(p1).forEach((p2) -> {
							if (p2.getFileName().toString().endsWith(".ini"))
								inis.add(p2);
						});
					}

					Path ini;
					switch (inis.size()) {
					case 0:
						ini = null;
						break;
					case 1:
						ini = inis.iterator().next();
						if (!ini.getFileName().toString().equals("eclipse.ini"))
							getLog().info("Using '" + ini.getFileName() + "' as eclipse.ini");
						break;
					default:
						Optional<Path> oini = inis.stream()
								.filter(p1 -> p1.getFileName().toString().equals("eclipse.ini")).findAny();
						if (oini.isPresent()) {
							ini = oini.get();
						} else {
							ini = null;
							getLog().warn("Multiple candidates for eclipse.ini found, using neither of them");
						}
						break;
					}

					if (ini != null)
						try (BufferedReader br = Files.newBufferedReader(ini)) {
							boolean inVmargs = false;
							for (String s = br.readLine(); s != null; s = br.readLine()) {
								if (s.equals("-vmargs")) {
									inVmargs = true;
								} else if (inVmargs) {
									if (s.startsWith("-D")) {
										int idx = s.indexOf("=");
										String key = s.substring(2, idx);
										String value = s.substring(idx + 1);
										Object old = launcherProperties.put("system." + key, value);
										if (old != null && !Objects.equals(old, value))
											throw new MojoFailureException(
													"System property '" + key + "' in " + ini.getFileName()
															+ " has different values in different artifacts: '" + old
															+ "', '" + value + "'");
									}
								}
							}
						}

					Properties configIni = new Properties();

					Path configuration = fs.getPath("/configuration");
					Files.walkFileTree(configuration, new SimpleFileVisitor<Path>() {

						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							Path rel = configuration.relativize(file);

							boolean copy = false;

							switch (rel.toString()) {
							case "config.ini":
								try (InputStream in = Files.newInputStream(fs.getPath("configuration", "config.ini"))) {
									configIni.load(in);
								}
								break;
							case "org.eclipse.update/platform.xml":
								copy = true;
								break;
							case "org.eclipse.equinox.simpleconfigurator/bundles.info":
								break;
							default:
								// FIXME: remove later
								System.err.println("UNHANDLED CONFIGURATION FILE " + rel);
								break;
							}

							if (copy) {
								Path target = classesDir.toPath().resolve(".configuration").resolve(rel.toString());
								// org.eclipse.update/platform.xml differs, but the differences should not be
								// relevant. If other files are copied, they must be merged here.
								if (!Files.exists(target)) {
									Files.createDirectories(target.getParent());
									Files.copy(file, target);

									configurationCopy.add(rel.toString());
								}
							}

							return super.visitFile(file, attrs);
						};
					});

					String framework = null;
					String bundles = null;
					String frameworkExtensions = "";
					URI simpleConfigurator = null;

					for (Map.Entry<Object, Object> e : configIni.entrySet()) {
						String key = (String) e.getKey();
						String value = (String) e.getValue();

						boolean passThrough = true;

						switch (key) {
						case "osgi.bundles":
							bundles = value;
							passThrough = false;
							break;
						case "osgi.framework":
							framework = value;
							passThrough = false;
							break;
						case "osgi.framework.extensions":
							frameworkExtensions = value;
							passThrough = false;
							break;
						case "org.eclipse.equinox.simpleconfigurator.configUrl":
							simpleConfigurator = URI.create(value);
							passThrough = false;
							break;
						}

						if (passThrough) {
							Object old = launcherProperties.put("framework." + key, value);
							if (old != null && !Objects.equals(old, value))
								throw new MojoFailureException("Property '" + key
										+ "' in config.ini has different values in different artifacts: '" + old
										+ "', '" + value + "'");
						}
					}

					if (framework == null)
						throw new MojoExecutionException("Missing property 'osgi.framework' in config.ini");

					if (bundles == null)
						throw new MojoExecutionException("Missing property 'osgi.bundles' in config.ini");

					URI frameworkUri = URI.create(framework);

					if (framework0 == null) {
						framework0 = framework;

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
					} else if (!framework0.equals(framework)) {
						throw new Error();
					}

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

					if (!frameworkExtensions.isEmpty())
						for (String spec : frameworkExtensions.split(",", -1)) {
							Matcher m = Pattern.compile("reference:file:(?<file>.+)").matcher(spec);
							if (!m.matches())
								throw new MojoExecutionException("Unhandled specification: " + spec);

							Path plugin = fs.getPath("plugins", m.group("file"));
							if (Files.isDirectory(plugin)) {
								getLog().error("Not supported: directory: " + spec);
							} else {
								addBundle(plugin, null, null);
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
				}
			}

			launcherProperties.put("osgi.bundles", this.bundles.stream().map(bs -> {
				String s = bs.relPath + "@" + (bs.startLevel != null ? bs.startLevel : "0");
				if (bs.start != null && bs.start)
					s += ":start";
				return s;
			}).collect(Collectors.joining(",")));

			launcherProperties.put("configuration.copy", configurationCopy.stream().collect(Collectors.joining(",")));
			try (OutputStream out = Files.newOutputStream(classesDir.toPath().resolve("launcher.properties"))) {
				launcherProperties.store(out, "");
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
