package com.github.pms1.e3.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "create-embedded")
public class EmbedMojo extends AbstractMojo {

	public void execute() throws MojoExecutionException, MojoFailureException {
		
		System.err.println("EXECUTING " + this);
		
	}

}
