package com.winsw.mavenplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Maven Mojo to generate Windows executable service wrapper using WinSW.
 * This goal will download WinSW executable, configure it with the project's JAR, and generate a ready-to-deploy EXE.
 */
@Mojo(name = "generate-exe")
public class GenerateExeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "outputDir", defaultValue = "${project.build.directory}/bin")
    private String outputDir;
    
    // Log path is now configured via logPath parameter only
    
    @Parameter(property = "logPath", defaultValue = "%BASE%\\..\\logs")
    private String logPath;
    
    @Parameter(property = "jarOutputDir", defaultValue = "${project.build.directory}")
    private String jarOutputDir;

    @Parameter(property = "serviceId", defaultValue = "${project.artifactId}")
    private String serviceId;
    
    @Parameter(property = "projectName")
    private String projectName;

    @Parameter(property = "serviceName", defaultValue = "${project.name}")
    private String serviceName;

    @Parameter(property = "serviceDescription", defaultValue = "${project.description}")
    private String serviceDescription;

    @Parameter(property = "winswVersion", defaultValue = "2.12.0")
    private String winswVersion;

    @Parameter(property = "winswDownloadUrl")
    private String winswDownloadUrl;

    @Parameter(property = "javaPath", defaultValue = "java")
    private String javaPath;

    @Parameter(property = "additionalJvmOptions")
    private String additionalJvmOptions;
    
    @Parameter(property = "additionalAppArgs")
    private String additionalAppArgs;
    
    @Parameter(property = "additionalDirectories")
    private List<String> additionalDirectories;

    @Parameter(property = "jarPath", defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
    private String jarPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Starting WinSW executable generation");
        
        try {
            // Use projectName parameter if provided, otherwise use serviceId
            String actualServiceId = serviceId;
            if (projectName != null && !projectName.isEmpty()) {
                actualServiceId = projectName;
                getLog().info("Using custom project name: " + projectName);
            }
            
            // Get file paths for cleanup
            Path outputPath = Paths.get(outputDir);
            Path jarOutputPath = Paths.get(jarOutputDir);
            String jarName = new File(jarPath).getName();
            
            // Clean up previous artifacts
            cleanupPreviousArtifacts(outputPath, jarOutputPath, actualServiceId, jarName);
            
            // Create directories if they don't exist
            Files.createDirectories(outputPath);
            Files.createDirectories(jarOutputPath);
            
            // Determine executable name
            String exeName = actualServiceId + ".exe";
            String xmlName = actualServiceId + ".xml";
            
            Path exePath = outputPath.resolve(exeName);
            Path xmlPath = outputPath.resolve(xmlName);
            
            // Path for jar file in root directory
            Path targetJarPath = jarOutputPath.resolve(jarName);
            
            // Set default download URL if not provided
            if (winswDownloadUrl == null || winswDownloadUrl.isEmpty()) {
                winswDownloadUrl = "https://github.com/winsw/winsw/releases/download/v" + winswVersion + "/WinSW.NET4.exe";
            }
            
            // Download WinSW executable if not already present
            downloadWinSW(exePath);
            
            // Copy the project's JAR to the jar output directory
            getLog().info("Copying JAR to: " + targetJarPath);
            
            // Verify JAR file exists before copying
            Path sourceJarPath = Paths.get(jarPath);
            if (!Files.exists(sourceJarPath)) {
                throw new MojoExecutionException("Source JAR file not found: " + sourceJarPath + ". Please build the project first with 'mvn package'");
            }
            
            Files.copy(sourceJarPath, targetJarPath, StandardCopyOption.REPLACE_EXISTING);
            
            // Copy additional directories (like lib, resources, etc.) if specified
            copyAdditionalDirectories(outputPath);
            
            // Determine log path - if it contains %BASE%, we need to handle it differently
            String logPathValue;
            Path actualLogPath;
            
            if (logPath.contains("%BASE%")) {
                // For %BASE% paths, we need to create the directory relative to the bin directory
                // Convert %BASE% to the actual bin directory path
                String actualBasePath = outputPath.toAbsolutePath().toString();
                String resolvedLogPath = logPath.replace("%BASE%", actualBasePath);
                actualLogPath = Paths.get(resolvedLogPath);
                logPathValue = logPath; // Keep the original %BASE% path for XML
            } else {
                // For absolute paths, use them directly
                actualLogPath = Paths.get(logPath);
                logPathValue = logPath;
            }
            
            Files.createDirectories(actualLogPath);
            getLog().info("Created log directory: " + actualLogPath);
            getLog().info("Using log path in XML: " + logPathValue);
            
            // Generate XML configuration
            getLog().info("Generating XML configuration: " + xmlPath);
            generateXmlConfig(xmlPath, targetJarPath, actualServiceId, logPathValue);
            
            // Generate batch scripts
            generateBatchScripts(outputPath, actualServiceId);
            
            getLog().info("WinSW executable generation completed successfully!");
            getLog().info("Generated files:");
            getLog().info(" - " + exePath.toAbsolutePath());
            getLog().info(" - " + xmlPath.toAbsolutePath());
            getLog().info(" - " + targetJarPath.toAbsolutePath());
            getLog().info(" - " + outputPath.resolve("install.bat").toAbsolutePath());
            getLog().info(" - " + outputPath.resolve("uninstall.bat").toAbsolutePath());
            getLog().info(" - " + outputPath.resolve("start.bat").toAbsolutePath());
            getLog().info(" - " + outputPath.resolve("stop.bat").toAbsolutePath());
            getLog().info(" - " + outputPath.resolve("restart.bat").toAbsolutePath());
            getLog().info("\nTo install the service, run as administrator:");
            getLog().info("\"" + outputPath.resolve("install.bat").toAbsolutePath() + "\"");
            
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate WinSW executable: " + e.getMessage(), e);
        }
    }

    private void downloadWinSW(Path exePath) throws IOException {
        if (Files.exists(exePath)) {
            getLog().info("WinSW executable already exists: " + exePath);
            return;
        }
        
        // First try to copy from built-in resource
        String resourcePath = "/winsw/WinSW.NET4.exe";
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in != null) {
                getLog().info("Copying WinSW from built-in resource");
                Files.copy(in, exePath, StandardCopyOption.REPLACE_EXISTING);
                // Make the file executable on Unix systems
                exePath.toFile().setExecutable(true);
                getLog().info("WinSW copied from resource to: " + exePath);
                return;
            }
        }
        
        // Fallback to downloading from URL if resource is not available
        getLog().info("Downloading WinSW from: " + winswDownloadUrl);
        try (InputStream in = new URL(winswDownloadUrl).openStream()) {
            Files.copy(in, exePath, StandardCopyOption.REPLACE_EXISTING);
            // Make the file executable on Unix systems
            exePath.toFile().setExecutable(true);
        }
        
        getLog().info("WinSW downloaded to: " + exePath);
    }

    private void copyJar(Path targetJarPath) throws IOException {
        Path sourceJarPath = Paths.get(jarPath);
        if (!Files.exists(sourceJarPath)) {
            throw new IOException("JAR file not found: " + sourceJarPath.toAbsolutePath());
        }
        
        Files.copy(sourceJarPath, targetJarPath, StandardCopyOption.REPLACE_EXISTING);
        getLog().info("Copied JAR to: " + targetJarPath);
    }
    
    private void copyAdditionalDirectories(Path outputPath) throws IOException {
        if (additionalDirectories == null || additionalDirectories.isEmpty()) {
            return; // No additional directories to copy
        }
        
        // Get the base directory of the project
        String projectBaseDir = project.getBasedir().getAbsolutePath();
        
        // Get parent directory (root directory) to copy additional directories
        Path rootDir = outputPath.getParent();
        
        for (String dir : additionalDirectories) {
            Path sourceDir = Paths.get(projectBaseDir, dir);
            
            if (Files.exists(sourceDir) && Files.isDirectory(sourceDir)) {
                // Copy to root directory instead of output directory
                Path targetDir = rootDir.resolve(dir);
                
                // Create the target directory if it doesn't exist
                Files.createDirectories(targetDir);
                
                // Copy the directory contents
                copyDirectory(sourceDir, targetDir);
                getLog().info("Copied directory: " + sourceDir + " to " + targetDir);
            } else {
                getLog().warn("Additional directory not found: " + sourceDir);
            }
        }
    }
    
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLog().error("Error copying file from " + sourcePath + " to " + target.resolve(source.relativize(sourcePath)), e);
            }
        });
    }

    private void generateXmlConfig(Path xmlPath, Path jarPath, String actualServiceId, String logPathValue) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(xmlPath)) {
            writer.write("<service>\n");
            writer.write("  <id>" + actualServiceId + "</id>\n");
            writer.write("  <name>" + serviceName + "</name>\n");
            writer.write("  <description>" + serviceDescription + "</description>\n");
            writer.write("  <executable>" + javaPath + "</executable>\n");
            
            // JVM options
            StringBuilder arguments = new StringBuilder();
            if (additionalJvmOptions != null && !additionalJvmOptions.isEmpty()) {
                arguments.append(additionalJvmOptions).append(" ");
            }
            // Use %BASE% variable with parent directory reference since jar is in root directory and exe is in bin
            arguments.append("-Xrs -Xmx2048m -Dhudson.lifecycle=hudson.lifecycle.WindowsServiceLifecycle ");
            arguments.append("-jar -Dloader.path=\"%base%\\..\\lib\",\"%base%\\..\\resources\" %BASE%\\..\\").append(jarPath.getFileName());
            
            if (additionalAppArgs != null && !additionalAppArgs.isEmpty()) {
                arguments.append(" ").append(additionalAppArgs);
            }
            
            writer.write("  <arguments>" + arguments.toString() + "</arguments>\n");
            
            // Configure logging with the specified logPath
            writer.write("  <logpath>%base%\\..\\logs\\out</logpath>\n");
            writer.write("  <logmode>rotate</logmode>\n");
            writer.write("  <logsize>100MB</logsize>\n");
            writer.write("  <logkeepfiles>100</logkeepfiles>\n");
            
            // Add extensions
            writer.write("  <extensions>\n");
            writer.write("    <!-- Runaway Process Killer Extension -->\n");
            writer.write("    <extension enabled=\"true\" className=\"winsw.Plugins.RunawayProcessKiller.RunawayProcessKillerExtension\" id=\"killOnStartup\">\n");
            writer.write("      <pidfile>%base%\\..\\logs\\out\\" + actualServiceId + ".pid</pidfile>\n");
            writer.write("      <stopTimeout>10000</stopTimeout>\n");
            writer.write("      <stopParentFirst>false</stopParentFirst>\n");
            writer.write("    </extension>\n");
            writer.write("  </extensions>\n");
            
            // Restart options
            writer.write("  <onfailure action=\"restart\" delay=\"10 sec\"/>\n");
            
            writer.write("</service>\n");
        }
        getLog().info("Generated XML configuration: " + xmlPath);
    }
    
    /**
     * Generates batch scripts for service management operations
     * @param outputPath Path to the output directory where scripts will be generated
     * @param serviceId The service ID used for the scripts
     * @throws IOException if there's an error writing the scripts
     */
    private void generateBatchScripts(Path outputPath, String serviceId) throws IOException {
        String exeName = serviceId + ".exe";
        
        // Generate install.bat
        generateBatchScript(outputPath.resolve("install.bat"), 
                "@echo off\n" +
                "echo Installing Windows Service: " + serviceId + "\n" +
                "echo Please make sure you run this script as Administrator!\n" +
                "echo.\n" +
                ".\\" + exeName + " install\n" +
                "echo.\n" +
                "echo Installation completed. Press any key to exit.\n" +
                "pause > nul");
        
        // Generate uninstall.bat
        generateBatchScript(outputPath.resolve("uninstall.bat"), 
                "@echo off\n" +
                "echo Uninstalling Windows Service: " + serviceId + "\n" +
                "echo Please make sure you run this script as Administrator!\n" +
                "echo.\n" +
                ".\\" + exeName + " stop\n" +
                ".\\" + exeName + " uninstall\n" +
                "echo.\n" +
                "echo Uninstallation completed. Press any key to exit.\n" +
                "pause > nul");
        
        // Generate start.bat
        generateBatchScript(outputPath.resolve("start.bat"), 
                "@echo off\n" +
                "echo Starting Windows Service: " + serviceId + "\n" +
                "echo Please make sure you run this script as Administrator!\n" +
                "echo.\n" +
                ".\\" + exeName + " start\n" +
                "echo.\n" +
                "echo Service start command sent. Press any key to exit.\n" +
                "pause > nul");
        
        // Generate stop.bat
        generateBatchScript(outputPath.resolve("stop.bat"), 
                "@echo off\n" +
                "echo Stopping Windows Service: " + serviceId + "\n" +
                "echo Please make sure you run this script as Administrator!\n" +
                "echo.\n" +
                ".\\" + exeName + " stop\n" +
                "echo.\n" +
                "echo Service stop command sent. Press any key to exit.\n" +
                "pause > nul");
        
        // Generate restart.bat
        generateBatchScript(outputPath.resolve("restart.bat"), 
                "@echo off\n" +
                "echo Restarting Windows Service: " + serviceId + "\n" +
                "echo Please make sure you run this script as Administrator!\n" +
                "echo.\n" +
                ".\\" + exeName + " restart\n" +
                "echo.\n" +
                "echo Service restart command sent. Press any key to exit.\n" +
                "pause > nul");
    }
    
    /**
     * Helper method to write a batch script file
     * @param scriptPath Path to the script file to be created
     * @param content The content of the batch script
     * @throws IOException if there's an error writing the script file
     */
    private void generateBatchScript(Path scriptPath, String content) throws IOException {
        Files.write(scriptPath, content.getBytes(StandardCharsets.UTF_8));
        // Make the file executable on Unix systems
        scriptPath.toFile().setExecutable(true);
        getLog().info("Generated script: " + scriptPath);
    }
    
    /**
     * Cleans up previous artifacts before generating new ones
     * @param outputPath Path to the output directory containing exe and configuration files
     * @param jarOutputPath Path to the directory containing the JAR file
     * @param serviceId The service ID used for naming the files
     * @param jarName The name of the JAR file
     * @throws IOException if there's an error during cleanup
     */
    private void cleanupPreviousArtifacts(Path outputPath, Path jarOutputPath, String serviceId, String jarName) throws IOException {
        // Delete all .exe files in output directory
        try (DirectoryStream<Path> exeStream = Files.newDirectoryStream(outputPath, "*.exe")) {
            for (Path file : exeStream) {
                Files.delete(file);
                getLog().info("Deleted previous artifact: " + file);
            }
        }
        
        // Delete all .xml files in output directory
        try (DirectoryStream<Path> xmlStream = Files.newDirectoryStream(outputPath, "*.xml")) {
            for (Path file : xmlStream) {
                Files.delete(file);
                getLog().info("Deleted previous artifact: " + file);
            }
        }
        
        // Delete batch scripts
        List<String> scriptNames = Arrays.asList("install.bat", "uninstall.bat", "start.bat", "stop.bat", "restart.bat");
        for (String scriptName : scriptNames) {
            Path scriptPath = outputPath.resolve(scriptName);
            if (Files.exists(scriptPath)) {
                Files.delete(scriptPath);
                getLog().info("Deleted previous artifact: " + scriptPath);
            }
        }
        
        // Delete JAR file only if it's not the source JAR
        Path targetJarPath = jarOutputPath.resolve(jarName);
        Path sourceJarPath = Paths.get(jarPath);
        if (!targetJarPath.equals(sourceJarPath) && Files.exists(targetJarPath)) {
            Files.delete(targetJarPath);
            getLog().info("Deleted previous artifact: " + targetJarPath);
        }
    }
}