/*
 * Copyright 2017 Matthias Hanisch
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
package io.mathan.maven.latex;

import io.mathan.maven.latex.internal.Constants;
import io.mathan.maven.latex.internal.LatexPluginLogOutputStream;
import io.mathan.maven.latex.internal.Step;
import io.mathan.maven.latex.internal.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The MathanLatexMojo provides the goal "latex" to generate dvi, ps or pdf out of LaTeX (.tex) documents. Therefore
 * all the LaTeX tools are executed in a defined order. There are pre-defined defaults for all supported output formats.
 * By configuration the arguments for the tool execution can be modified. It is also possible to extend the process to
 * include own tool executions.
 *
 * @author Matthias Hanisch (reallyinsane)
 */
@Mojo(name = "latex")
public class MathanLatexMojo extends AbstractMojo {

    private static final String[] RESOURCES_DEFAULT_EXTENSTIONS = {
            Constants.FORMAT_TEX, Constants.FORMAT_CLS, Constants.FORMAT_CLO, Constants.FORMAT_STY,
            Constants.FORMAT_BIB, Constants.FORMAT_BST, Constants.FORMAT_IDX, Constants.FORMAT_IST,
            Constants.FORMAT_GLO, Constants.FORMAT_EPS, Constants.FORMAT_PDF};

    /**
     * The defualt execution chain defines the order of the tool execution.
     */
    private static final String[] DEFAULT_BUILD_STEPS = {
            Constants.LaTeX, Step.STEP_BIBTEX.getId(), Step.STEP_MAKEINDEX.getId(), Step.STEP_MAKEINDEXNOMENCL.getId(), Constants.LaTeX,
            Constants.LaTeX};

    /**
     * This list includes the predefined execution steps supported by this plugin.
     */
    private static final List<Step> DEFAULT_EXECUTABLES = Arrays.asList(
            Step.STEP_BIBER, Step.STEP_BIBTEX, Step.STEP_DVIPDFM, Step.STEP_DVIPS, Step.STEP_LATEX, Step.STEP_LULATEX,
            Step.STEP_MAKEINDEX, Step.STEP_MAKEINDEXNOMENCL, Step.STEP_PDFLATEX, Step.STEP_PS2PDF,
            Step.STEP_XELATEX);

    /**
     * The output format. Supported are dvi, pdf and ps.
     */
    @Parameter(defaultValue = Constants.FORMAT_PDF)
    private String outputFormat;

    /**
     * The bin directory of the LaTeX distribution.
     */
    @Parameter
    private String texBin;

    /**
     * The list of tools to be executed to create the output format. (without bibtex, biber, makeindex, etc.)
     */
    @Parameter
    private String[] latexSteps;

    /**
     * The list of tools to be executed in the build. (including bibtex, biber, makeindex, etc.). The step to create
     * the output format is set using the placeholder {@link Constants#LaTeX}.
     */
    @Parameter
    private String[] buildSteps;

    /**
     * User-defined steps which can be included in {@link #buildSteps} or {@link #latexSteps}.
     */
    @Parameter
    private Step[] steps;

    /**
     * For injecting the current maven project.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Parameter for controlling if intermediate files created during the build process should be kept or not. The
     * latter is the default.
     */
    @Parameter(defaultValue = "false")
    private boolean keepIntermediateFiles;

    /**
     * Parameter defining the source directory to search for LaTeX documents.
     */
    @Parameter(defaultValue = "src/main/tex")
    private String sourceDirectory;

    /**
     * Parameter defining an optional index style file for makeindex.
     */
    @Parameter
    private String makeIndexStyleFile;


    /**
     * The entry point to Maven Artifact Resolver, i.e. the component doing all the work.
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution.
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", required = true, readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Parameter
    private String texFile;

    @Parameter
    private FileSet resources;

    /**
     * Parameter for controlling if build should be stopped in case the execution of a single step finished with an
     * unexpected (non-zero) exit code. By default this parameter is set to <code>true</code> but in some cases it may
     * be useful to set it to <code>false</code>. This can be necessary if a tool finishes successfully but returns
     * a non-zero exit code.
     */
    @Parameter(defaultValue = "true")
    private boolean haltOnError;


    /**
     * The registry of all steps available. This registry will contain the default steps provided by the mathan-latex-maven-plugin itself
     * and the user defined steps provided with the parameter {@link #steps}.
     */
    private Map<String, Step> stepRegistry = new HashMap<>();

    public void execute() throws MojoExecutionException, MojoFailureException {
        List<Step> stepsToExecute = configureSteps();
        getLog().info("[mathan] bin directory of tex distribution: " + texBin);
        getLog().info("[mathan] output format : " + outputFormat);
        getLog().info("[mathan] latex steps: " + String.join(",", latexSteps));
        getLog().info("[mathan] build steps: " + String.join(",", buildSteps));

        File baseDirectory = project.getBasedir();
        File texDirectory = new File(baseDirectory, sourceDirectory);

        executeSteps(stepsToExecute, texDirectory);
        // remove intermediate files
        if (!keepIntermediateFiles) {
            File workingDirectory = new File(project.getBasedir(), "target/latex");
            try {
                FileUtils.deleteDirectory(workingDirectory);
            } catch (IOException e) {
                getLog().warn(String.format("Could not delete directory %s", workingDirectory.getAbsolutePath()));
            }
        }
    }

    /**
     * Executes the configured steps for a certain directory with a LaTeX source document. If available resources
     * from the commons directory will be added to the execution. In this case files from the source directory will
     * overwrite files from the common directory.
     *
     * @param stepsToExecute The steps to execute.
     * @param source         The directory containing the LaTeX source document.
     * @throws MojoExecutionException Most likely when an IOException occurred during the build.
     */
    private void executeSteps(List<Step> stepsToExecute, File source) throws MojoExecutionException {
        File workingDirectory = new File(project.getBasedir(), "target/latex/");
        if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
            throw new MojoExecutionException(String.format("Could not create directory %s", workingDirectory.getAbsolutePath()));
        }
        List<Dependency> dependencies = project.getDependencies();
        for (Dependency dependency : dependencies) {
            resolveDependency(dependency, workingDirectory);
        }
        try {
            FileUtils.copyDirectory(source, workingDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Could not copy context from %s to %s", source.getAbsolutePath(), workingDirectory.getAbsolutePath()));
        }
        File mainFile;
        if (texFile == null || texFile.isEmpty()) {
            mainFile = Utils.getFile(workingDirectory, Constants.FORMAT_TEX); //TODO: parameterize the name of the source document?
        } else {
            mainFile = new File(workingDirectory, texFile);
        }

        if (mainFile == null || !mainFile.exists()) {
            throw new MojoExecutionException(String.format("No LaTeX source document found in %s", source.getAbsolutePath()));
        }
        getLog().info(String.format("[mathan] processing %s", mainFile.getName()));
        FileWriter completeLog;
        String pureName = mainFile.getName().substring(0, mainFile.getName().lastIndexOf('.'));
        completeLog = createLog(workingDirectory);
        int stepCount = stepsToExecute.size();
        for (int i = 0; i < stepCount; i++) {
            Step step = stepsToExecute.get(i);
            logHeader(completeLog, i + 1, stepCount, step);
            executeStep(step, workingDirectory, mainFile);
            appendLogTo(completeLog, workingDirectory, pureName, step);
        }
        closeLog(completeLog);
        File outputFile = new File(workingDirectory, pureName + "." + outputFormat);
        try {
            File targetDirectory = new File(project.getBasedir(), "target");
            String artifactName = String.format("%s-%s.%s", project.getArtifactId(), project.getVersion(), outputFormat);
            File artifact = new File(targetDirectory, artifactName);
            FileUtils.copyFile(outputFile, artifact);
            project.getArtifact().setFile(artifact);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Could not copy output file %s to target.", outputFile.getAbsolutePath()), e);
        }
        if (!keepIntermediateFiles) {
            try {
                FileUtils.deleteDirectory(workingDirectory);
            } catch (IOException e) {
                getLog().warn(String.format("Could not delete directory %s", workingDirectory.getAbsolutePath()), e);
            }
        }
    }

    private FileWriter createLog(File workingDirectory) throws MojoExecutionException {
        FileWriter completeLog;
        try {
            completeLog = new FileWriter(new File(workingDirectory, "mathan-latex-mojo.log"), true);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create mathan-latext-mojo.log", e);
        }
        return completeLog;
    }

    private void closeLog(FileWriter completeLog) throws MojoExecutionException {
        try {
            completeLog.close();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write mathan-latext-mojo.log", e);
        }
    }

    private void logHeader(FileWriter completeLog, int i, int stepCount, Step step) throws MojoExecutionException {
        try {
            completeLog.write("##################################################\n");
            completeLog.write(String.format("# Step %s/%s %s\n", i, stepCount, step.getId()));
            completeLog.write("##################################################\n");
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write mathan-latext-mojo.log", e);
        }
    }

    private void appendLogTo(FileWriter completeLog, File workingDirectory, String pureName, Step step) throws MojoExecutionException {
        if (step.getLogExtension() == null) {
            return;
        }
        File stepLog = new File(workingDirectory, pureName + "." + step.getLogExtension());
        if (stepLog.exists()) {
            try {
                FileReader reader = new FileReader(stepLog);
                IOUtils.copy(reader, completeLog);
                reader.close();
                stepLog.delete();
            } catch (IOException e) {
                throw new MojoExecutionException("Could not write mathan-latext-mojo.log", e);
            }
        }
    }

    private void resolveDependency(Dependency dependency, File workingDirectory) throws MojoExecutionException {
        //TODO: Check if zip should be supported also
        Artifact artifact = new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), "jar", dependency.getVersion());
        LocalArtifactRequest localRequest = new LocalArtifactRequest();
        localRequest.setArtifact(artifact);
        getLog().info(String.format("[mathan] resolving artifact %s from local", artifact));
        LocalArtifactResult localResult = repoSession.getLocalRepositoryManager().find(repoSession, localRequest);
        if (localResult.isAvailable()) {
            try {
                extractArchive(localResult.getFile(), workingDirectory);
            } catch (IOException e) {
                throw new MojoExecutionException(String.format("Could not copy artifact %s", artifact), e);
            }
        } else {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(artifact);
            request.setRepositories(remoteRepos);
            getLog().info(String.format("[mathan] resolving artifact %s from %s", artifact, remoteRepos));
            ArtifactResult result;
            try {
                result = repoSystem.resolveArtifact(repoSession, request);
            } catch (ArtifactResolutionException e) {
                throw new MojoExecutionException(String.format("Could not resolve artifact %s", artifact), e);
            }
            if (result.isResolved()) {
                try {
                    extractArchive(result.getArtifact().getFile(), workingDirectory);
                } catch (IOException e) {
                    throw new MojoExecutionException(String.format("Could not copy artifact %s", artifact), e);
                }
            } else {
                throw new MojoExecutionException(String.format("Could not resolve artifact %s", artifact));
            }
        }

    }

    private void extractArchive(File archive, File workingDirectory) throws IOException {
        File archiveContent = Utils.extractArchive(archive);
        FileSetManager fileSetManager = new FileSetManager();

        resources.setDirectory(archiveContent.getAbsolutePath());

        String[] includedFiles = fileSetManager.getIncludedFiles(resources);
        for (String includedFile : includedFiles) {
            File src = new File(archiveContent, includedFile);
            File dest = new File(workingDirectory, includedFile);
            getLog().info(String.format("[mathan] including resource %s", includedFile));
            if (!dest.getParentFile().exists() && !dest.getParentFile().mkdirs()) {
                throw new IOException("Could not create directory " + dest.getParentFile().getAbsolutePath());
            }
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dest);
            IOUtils.copy(in, out);
            in.close();
            out.close();
        }
        FileUtils.deleteDirectory(archiveContent);
    }

    /**
     * Configures the steps to execute and checks the configuration for the build.
     *
     * @return The steps to execute.
     * @throws MojoExecutionException If the configuration is invalid.
     */
    private List<Step> configureSteps() throws MojoExecutionException {
        // check source directory
        configureSourceDirectory();
        // check output format
        configureOutputFormat();
        configureResourcesOfDependencies();
        // setup step registry
        configureStepRegistry();
        // setup latex steps
        List<Step> listLatexSteps = configureLatexSteps();
        List<Step> listExecutables = new ArrayList<>(listLatexSteps);
        // setup build steps
        List<Step> listBuildSteps = configureBuildSteps(listLatexSteps, listExecutables);
        // configure pre-defined steps
        configureMakeIndex();
        // check if executables are available
        checkExecutables(listExecutables);
        return listBuildSteps;
    }

    private List<Step> configureBuildSteps(List<Step> listLatexSteps, List<Step> listExecutables) throws MojoExecutionException {
        if (buildSteps == null) {
            buildSteps = DEFAULT_BUILD_STEPS;
        }
        List<Step> listBuildSteps = new ArrayList<>();
        for (String buildStep : buildSteps) {
            if (Constants.LaTeX.equals(buildStep)) {
                listBuildSteps.addAll(listLatexSteps);
            } else {
                Step step = stepRegistry.get(buildStep);
                if (step == null) {
                    throw new MojoExecutionException(String.format("Step '%s' defined in 'buildSteps' is unknown. Consider to provide the definition of the step with the configuration 'steps'.", buildStep));
                }
                listBuildSteps.add(step);
                listExecutables.add(step);
            }
        }
        return listBuildSteps;
    }

    private List<Step> configureLatexSteps() throws MojoExecutionException {
        if (latexSteps == null) {
            switch (outputFormat) {
                case Constants.FORMAT_DVI:
                    latexSteps = new String[]{Step.STEP_LATEX.getId()};
                    break;
                case Constants.FORMAT_PS:
                    latexSteps = new String[]{Step.STEP_LATEX.getId(), Step.STEP_DVIPS.getId()};
                    break;
                case Constants.FORMAT_PDF:
                    latexSteps = new String[]{Step.STEP_PDFLATEX.getId()};
                    break;
                default:
                    throw new MojoExecutionException("Invalid output format");
            }
        }
        List<Step> listLatexSteps = new ArrayList<>();
        for (String latexStep : latexSteps) {
            Step step = stepRegistry.get(latexStep);
            if (step == null) {
                throw new MojoExecutionException(String.format("Step '%s' defined in 'latexSteps' is unknown. Consider to provide the definition of the step with the configuration 'steps'.", latexStep));
            }
            listLatexSteps.add(step);
        }
        return listLatexSteps;
    }

    private void configureStepRegistry() {
        DEFAULT_EXECUTABLES.forEach(e -> stepRegistry.put(e.getId(), e));
        if (steps != null) {
            Arrays.asList(steps).forEach(e -> stepRegistry.put(e.getId(), e));
        }
    }

    private void configureResourcesOfDependencies() {
        if (resources == null) {
            resources = new FileSet();
            for (String include : RESOURCES_DEFAULT_EXTENSTIONS) {
                resources.addInclude("**/*." + include);
            }
        }
    }

    private void configureOutputFormat() throws MojoExecutionException {
        if (outputFormat.length() == 0) {
            throw new MojoExecutionException("No outputFormat specified. Supported values are: dvi, pdf, ps.");
        }
        if (!Arrays.asList(Constants.FORMAT_DVI, Constants.FORMAT_PDF, Constants.FORMAT_PS).contains(outputFormat)) {
            throw new MojoExecutionException(String.format("Invalid outputFormat '%s' specified. Supported values are: dvi, pdf, ps.", outputFormat));
        }
    }

    private void configureSourceDirectory() throws MojoExecutionException {
        File srcDir = new File(project.getBasedir(), sourceDirectory);
        if (!srcDir.exists() || !srcDir.isDirectory()) {
            throw new MojoExecutionException(String.format("Source directory '%s' does not exist.", sourceDirectory));
        }
    }

    /**
     * Checks, if the given executables can be executed by finding the executables either in the configured {@link #texBin bin directory} or
     * on PATH.
     *
     * @param listExecutables The executables to check.
     * @throws MojoExecutionException If at least one executable cannot be executed.
     */
    private void checkExecutables(List<Step> listExecutables) throws MojoExecutionException {
        List<Step> stepsToFail = listExecutables.stream().filter(step -> Utils.getExecutable(texBin, step.getOSName()) == null).collect(Collectors.toList());
        stepsToFail.forEach(step -> getLog().error(String.format("Step %s cannot be executed. Executable neither found in configured texBin '%s' nor on PATH", step.getId(), texBin)));
        if (!stepsToFail.isEmpty()) {
            throw new MojoExecutionException("The executable of at least one step could not be found.");
        }
    }

    /**
     * Special configuration fot the step {@link Step#STEP_MAKEINDEX}. If a {@link #makeIndexStyleFile} is set, this
     * is appended to the arguments of the executable. Otherwise no makeindex style file will be used.
     */
    private void configureMakeIndex() {
        String arguments = Step.STEP_MAKEINDEX.getArguments();
        if (makeIndexStyleFile == null || makeIndexStyleFile.isEmpty()) {
            arguments = arguments.replaceAll("-s\\s+%style", "");
        } else {
            arguments = arguments.replaceAll("%style", makeIndexStyleFile);
        }
        Step.STEP_MAKEINDEX.setArguments(arguments);
    }

    /**
     * Executes a single step and executes the configured command with the specified input file. If the step is
     * {@link Step#isOptional() is optional} the step is not executed if the input file is not found. E.g. if
     * bibtex step is executed and there are no references defined.
     *
     * @param executionStep    The step to execute.
     * @param workingDirectory The working directory for the command execution.
     * @param texFile          The input file to use.
     * @throws MojoExecutionException If an error occurred during the execution of the command.
     */
    private void executeStep(Step executionStep, File workingDirectory, File texFile) throws MojoExecutionException {
        File exec = Utils.getExecutable(texBin, executionStep.getOSName());
        // split command into array
        List<String> list = new ArrayList<>();
        list.add(exec.getAbsolutePath());
        Utils.tokenizeEscapedString(Step.getArguments(executionStep, texFile), list);
        String[] command = list.toArray(new String[0]);

        String prefix = "[mathan][" + executionStep.getId() + "]";

        File inputFile = Step.getInputFile(executionStep, texFile);
        int exitValue = 0;
        try {
            getLog().info("[mathan] execution: " + executionStep.getId());
            getLog().info(Arrays.toString(command));
            exitValue = new ProcessExecutor().command(command).directory(workingDirectory).redirectOutput(LatexPluginLogOutputStream.toMavenDebug(getLog(), prefix)).redirectError(LatexPluginLogOutputStream.toMavenError(getLog(), prefix)).destroyOnExit().execute().getExitValue();
        } catch (Exception e) {
            if (executionStep.isOptional()) {
                getLog().info("[mathan] execution skipped: " + executionStep.getId());
            } else {
                throw new MojoExecutionException("Building the project: ", e);
            }
        }
        if (exitValue != 0) {
            if (inputFile.exists()) {
                if (haltOnError) {
                    throw new MojoExecutionException(String.format("Execution of step %s failed. Process finished with exit code %s.", executionStep.getId(), exitValue));
                } else {
                    getLog().info(String.format("[mathan] execution finished with exit code=%s: %s", exitValue, executionStep.getId()));
                }
            } else {
                getLog().info("[mathan] execution skipped: " + executionStep.getId());
            }
        }
    }

}
