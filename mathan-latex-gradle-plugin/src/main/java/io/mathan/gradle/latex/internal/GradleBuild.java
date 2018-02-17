/*
 * Copyright 2018 Matthias Hanisch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mathan.gradle.latex.internal;

import io.mathan.gradle.latex.MathanGradleLatexConfiguration;
import io.mathan.latex.core.Build;
import io.mathan.latex.core.BuildLog;
import io.mathan.latex.core.LatexExecutionException;
import io.mathan.latex.core.Utils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.zeroturnaround.exec.stream.LogOutputStream;

public class GradleBuild implements Build {

  private Project project;
  private DefaultTask task;
  private final MathanGradleLatexConfiguration configuration;

  public GradleBuild(Project project, DefaultTask task, MathanGradleLatexConfiguration configuration) {

    this.project = project;
    this.task = task;
    this.configuration = configuration;
  }

  @Override
  public BuildLog getLog() {
    return new GradleBuildLog(task.getLogger());
  }

  @Override
  public File getBasedir() {
    return project.getProjectDir();
  }

  @Override
  public String getArtifactId() {
    return project.getName();
  }

  @Override
  public String getVersion() {
    return project.getVersion().toString();
  }

  @Override
  public void setArtifact(File artifact) {
  }

  @Override
  public void resolveDependencies(File workingDirectory) throws LatexExecutionException {
    Configuration compile = getProject().getConfigurations().findByName(this.configuration.getConfigurationName());
    if (compile != null) {
      for (File file : compile.getFiles()) {
        extractArchive(file, workingDirectory);
      }
    }
  }

  @Override
  public LogOutputStream getRedirectOutput(String prefix) {
    return GradleLogOutputStream.toDebug(task.getLogger(), prefix);
  }

  @Override
  public LogOutputStream getRedirectError(String prefix) {
    return GradleLogOutputStream.toError(task.getLogger(), prefix);
  }

  private Project getProject() {
    return this.project;
  }

  private Task getTask() {
    return this.task;
  }

  private void extractArchive(File archive, File workingDirectory) throws LatexExecutionException {
    File archiveContent = null;
    try {
      archiveContent = Utils.extractArchive(archive);
    } catch (IOException e) {
      throw new LatexExecutionException(String.format("Could not copy artifact %s", archive.getName()), e);
    }
    ConfigurableFileTree fileTree = configuration.getResources();
    if (fileTree == null) {
      fileTree = project.fileTree(archiveContent.getAbsolutePath());
    } else {
      fileTree.setDir(archiveContent.getAbsolutePath());
    }
    fileTree.visit(new FileVisitor() {
      @Override
      public void visitDir(FileVisitDetails dirDetails) {

      }

      @Override
      public void visitFile(FileVisitDetails fileDetails) {
        try {
          File dest = new File(workingDirectory, fileDetails.getRelativePath().getPathString());
          getLog().info(String.format("[mathan] including resource %s", fileDetails.getRelativePath().getPathString()));
          if (!dest.getParentFile().exists() && !dest.getParentFile().mkdirs()) {
            throw new IOException("Could not create directory " + dest.getParentFile().getAbsolutePath());
          }
          FileInputStream in = new FileInputStream(fileDetails.getFile());
          FileOutputStream out = new FileOutputStream(dest);
          IOUtils.copy(in, out);
          in.close();
          out.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });

    try {
      FileUtils.deleteDirectory(archiveContent);
    } catch (IOException e) {
      throw new LatexExecutionException(String.format("Could not copy artifact %s", archive.getName()), e);
    }
  }
}
