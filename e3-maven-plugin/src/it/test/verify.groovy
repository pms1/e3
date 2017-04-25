import java.util.zip.ZipFile

if(false) {
def zf = new ZipFile(new File(basedir,"repository/target/repository-0.0.0-SNAPSHOT.zip"));
try {
	assert zf.getEntry("plugins/com.github.pms1.tppt.bundle1_0.0.0.SNAPSHOT.jar")
	assert zf.getEntry("plugins/org.osgi.core_4.0.0.jar")
} finally {
	zf.close();
}

def buildLog = new File(basedir, 'build.log').readLines();

assert buildLog.grep { it.contains('Deploying to ') }.size() == 2
assert buildLog.grep { it.contains(' Equal to existing repository, skipping deployment') }.size() == 1
}
