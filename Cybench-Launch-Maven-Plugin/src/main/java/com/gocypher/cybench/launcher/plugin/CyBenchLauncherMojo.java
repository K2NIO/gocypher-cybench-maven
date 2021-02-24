/*
 * Copyright (C) 2020, K2N.IO.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 */

package com.gocypher.cybench.launcher.plugin;

import com.gocypher.cybench.core.utils.IOUtils;
import com.gocypher.cybench.core.utils.JMHUtils;
import com.gocypher.cybench.core.utils.JSONUtils;
import com.gocypher.cybench.core.utils.SecurityUtils;
import com.gocypher.cybench.launcher.environment.model.HardwareProperties;
import com.gocypher.cybench.launcher.environment.model.JVMProperties;
import com.gocypher.cybench.launcher.environment.services.CollectSystemInformation;
import com.gocypher.cybench.launcher.model.BenchmarkOverviewReport;
import com.gocypher.cybench.launcher.model.BenchmarkReport;
import com.gocypher.cybench.launcher.plugin.utils.PluginUtils;
import com.gocypher.cybench.launcher.report.DeliveryService;
import com.gocypher.cybench.launcher.report.ReportingService;
import com.gocypher.cybench.launcher.utils.ComputationUtils;
import com.gocypher.cybench.launcher.utils.Constants;
import com.gocypher.cybench.launcher.utils.SecurityBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.HotspotRuntimeProfiler;
import org.openjdk.jmh.profile.HotspotThreadProfiler;
import org.openjdk.jmh.profile.SafepointsProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.text.MessageFormat;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "cybench", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.INTEGRATION_TEST)
public class CyBenchLauncherMojo extends AbstractMojo {
    private static final String benchSource = "Maven plugin";
    @Parameter(property = "cybench.classpathScope", defaultValue = "runtime")
    protected String classpathScope;
    @Parameter(property = "cybench.forks", defaultValue = "1")
    private int forks = 1;
    @Parameter(property = "cybench.threads", defaultValue = "1")
    private int threads = 1;
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;
    @Parameter(property = "cybench.measurementIterations", defaultValue = "5")
    private int measurementIterations = 5;
    @Parameter(property = "cybench.measurementTime", defaultValue = "10")
    private int measurementTime = 10;
    /**
     * The warm-up iteration count for all benchmarks
     */
    @Parameter(property = "cybench.warmUpIterations", defaultValue = "3")
    private int warmUpIterations = 3;
    /**
     * The warm-up time for all benchmarks
     */
    @Parameter(property = "cybench.warmUpTime", defaultValue = "5")
    private int warmUpTime = 5;
    @Parameter(property = "cybench.expectedScore", defaultValue = "-1.0")
    private double expectedScore = -1.0d;
    @Parameter(property = "cybench.shouldSendReportToCyBench", defaultValue = "false")
    private boolean shouldSendReportToCyBench = false;
    @Parameter(property = "cybench.shouldStoreReportToFileSystem", defaultValue = "true")
    private boolean shouldStoreReportToFileSystem = true;
    @Parameter(property = "cybench.reportUploadStatus", defaultValue = "public")
    private String reportUploadStatus = "public";
    @Parameter(property = "cybench.reportsFolder", defaultValue = "")
    private String reportsFolder = "";
    @Parameter(property = "cybench.reportName", defaultValue = "CyBench Report")
    private String reportName = "CyBench Report";
    @Parameter(property = "cybench.customBenchmarkMetadata", defaultValue = "")
    private String customBenchmarkMetadata = "";
    @Parameter(property = "cybench.userProperties", defaultValue = "")
    private String userProperties = "";
    @Parameter(property = "cybench.skip", defaultValue = "false")
    private boolean skip = false;
    @Parameter(property = "cybench.shouldFailBuildOnReportDeliveryFailure", defaultValue = "false")
    private boolean shouldFailBuildOnReportDeliveryFailure = false;
    @Parameter(property = "cybench.useCyBenchBenchmarkSettings", defaultValue = "true")
    private boolean useCyBenchBenchmarkSettings =true;

    public void execute() throws MojoExecutionException {
        if (!skip && System.getProperty(PluginUtils.KEY_SKIP_CYBENCH) == null) {
            System.setProperty("collectHw", "true");
            boolean isReportSentSuccessFully = false;
            long start = System.currentTimeMillis();
            getLog().info("-----------------------------------------------------------------------------------------");
            getLog().info("                                 Starting CyBench benchmarks (Maven Plugin)                             ");
            getLog().info("-----------------------------------------------------------------------------------------");
            try {
                PluginUtils.resolveAndUpdateClasspath(this.getLog(), this.project, this.getPluginContext(), this.classpathScope);

                getLog().info("Collecting hardware, software information...");
                HardwareProperties hwProperties = CollectSystemInformation.getEnvironmentProperties();
                getLog().info("Collecting JVM properties...");
                JVMProperties jvmProperties = CollectSystemInformation.getJavaVirtualMachineProperties();

                SecurityBuilder securityBuilder = new SecurityBuilder();

                Map<String, Object> benchmarkSettings = new HashMap<String, Object>();

                Map<String, Map<String, String>> customBenchmarksMetadata = ComputationUtils.parseBenchmarkMetadata(customBenchmarkMetadata);

                benchmarkSettings.put("benchSource", benchSource);
                benchmarkSettings.put("benchWarmUpIteration", warmUpIterations);
                benchmarkSettings.put("benchWarmUpSeconds", warmUpTime);
                benchmarkSettings.put("benchMeasurementIteration", measurementIterations);
                benchmarkSettings.put("benchMeasurementSeconds", measurementTime);
                benchmarkSettings.put("benchForkCount", forks);
                benchmarkSettings.put("benchThreadCount", threads);

                reportName = MessageFormat.format("Benchmark for {0}:{1}:{2}", project.getGroupId(),  project.getArtifactId(), project.getVersion());

                benchmarkSettings.put("benchReportName", reportName);

                getLog().info("Executing benchmarks...");

                OptionsBuilder optBuild = new OptionsBuilder();
                Options opt;
                if(useCyBenchBenchmarkSettings) {
                    opt = optBuild.forks(forks)
                            .measurementTime(TimeValue.seconds(measurementTime))
                            .measurementIterations(measurementIterations)
                            .warmupIterations(warmUpIterations)
                            .warmupTime(TimeValue.seconds(warmUpTime))
                            .threads(threads)
                            .shouldDoGC(true)
                            .addProfiler(GCProfiler.class)
                            .addProfiler(HotspotThreadProfiler.class)
                            .addProfiler(HotspotRuntimeProfiler.class)
                            .addProfiler(SafepointsProfiler.class)
                            .detectJvmArgs()
                            .build();
                }else{
                    opt = optBuild.shouldDoGC(true)
                            .addProfiler(GCProfiler.class)
                            .addProfiler(HotspotThreadProfiler.class)
                            .addProfiler(HotspotRuntimeProfiler.class)
                            .addProfiler(SafepointsProfiler.class)
                            .detectJvmArgs()
                            .build();
                }

                Runner runner = new Runner(opt);

                Map<String, String> generatedFingerprints = new HashMap<String, String>();
                Map<String, String> manualFingerprints = new HashMap<String, String>();
                Map<String, String> classFingerprints = new HashMap<String, String>();

                List<String> benchmarkNames = JMHUtils.getAllBenchmarkClasses();
                for (String benchmarkClass : benchmarkNames) {
                    try {
                        Class<?> classObj = Class.forName(benchmarkClass);
                        SecurityUtils.generateMethodFingerprints(classObj, manualFingerprints, classFingerprints);
                        SecurityUtils.computeClassHashForMethods(classObj, generatedFingerprints);
                    } catch (ClassNotFoundException exc) {
                        getLog().error("Class not found in the classpath for execution", exc);
                    }


                }

                Collection<RunResult> results = runner.run();

                BenchmarkOverviewReport report = ReportingService.getInstance().createBenchmarkReport(results, customBenchmarksMetadata);
                report.updateUploadStatus(reportUploadStatus);

                report.getEnvironmentSettings().put("environment", hwProperties);
                report.getEnvironmentSettings().put("jvmEnvironment", jvmProperties);
                report.getEnvironmentSettings().put("unclassifiedProperties", CollectSystemInformation.getUnclassifiedProperties());
                report.getEnvironmentSettings().put("userDefinedProperties", PluginUtils.extractKeyValueProperties(userProperties));
                report.setBenchmarkSettings(benchmarkSettings);


                Iterator<String> it = report.getBenchmarks().keySet().iterator() ;

                while (it.hasNext()) {
                    List<BenchmarkReport> custom = report.getBenchmarks().get(it.next()).stream().collect(Collectors.toList());
                    custom.stream().forEach(benchmarkReport -> {
                        String name = benchmarkReport.getName();
                        benchmarkReport.setClassFingerprint(classFingerprints.get(name));
                        benchmarkReport.setGeneratedFingerprint(generatedFingerprints.get(name));
                        benchmarkReport.setManualFingerprint(manualFingerprints.get(name));
                        try {
                            JMHUtils.ClassAndMethod classAndMethod = new JMHUtils.ClassAndMethod(name).invoke();
                            String clazz = classAndMethod.getClazz();
                            String method = classAndMethod.getMethod();
                            getLog().info("Adding metadata for benchamrk: " + clazz + " test: " + method);
                            Class<?> aClass = Class.forName(clazz);
                            Optional<Method> benchmarkMethod = JMHUtils.getBenchmarkMethod(method, aClass);
                            PluginUtils.appendMetadataFromMethod(benchmarkMethod, benchmarkReport);
                            PluginUtils.appendMetadataFromClass(aClass, benchmarkReport);
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }

                    });
                }
                List<BenchmarkReport> customBenchmarksCategoryCheck = report.getBenchmarks().get("CUSTOM");
                report.getBenchmarks().remove("CUSTOM");
                for(BenchmarkReport benchReport : customBenchmarksCategoryCheck) {
                    report.addToBenchmarks(benchReport);
                }
                report.computeScores();
                PluginUtils.getReportUploadStatus(report);
                getLog().info("-----------------------------------------------------------------------------------------");
                getLog().info("Report score - " + report.getTotalScore());
                getLog().info("-----------------------------------------------------------------------------------------");

                if (expectedScore > 0 && report.getTotalScore().doubleValue() < expectedScore) {
                        throw new MojoFailureException("CyBench score is less than expected:" + report.getTotalScore().doubleValue() + " < " + expectedScore);
                    }

                String reportEncrypted = ReportingService.getInstance().prepareReportForDelivery(securityBuilder, report);
                reportsFolder = PluginUtils.checkReportSaveLocation(reportsFolder);
                String responseWithUrl;
                if (report.isEligibleForStoringExternally() && shouldSendReportToCyBench) {
                    responseWithUrl = DeliveryService.getInstance().sendReportForStoring(reportEncrypted);
                    report.setReportURL(responseWithUrl);
                    if (responseWithUrl != null && !responseWithUrl.isEmpty()) {
                        isReportSentSuccessFully = true;
                    }
                } else {
                    getLog().info("You may submit your report '" + IOUtils.getReportsPath(reportsFolder, Constants.CYB_REPORT_CYB_FILE) + "' manually at " + Constants.CYB_UPLOAD_URL);
                }
                String reportJSON = JSONUtils.marshalToPrettyJson(report);
                getLog().info(reportJSON);
                if (shouldStoreReportToFileSystem) {
                    getLog().info("Saving test results to '" + IOUtils.getReportsPath(reportsFolder, ComputationUtils.createFileNameForReport(reportName, start, report.getTotalScore(), false)) + "'");
                    IOUtils.storeResultsToFile(IOUtils.getReportsPath(reportsFolder, ComputationUtils.createFileNameForReport(reportName, start, report.getTotalScore(), false)), reportJSON);
                    getLog().info("Saving encrypted test results to '" + IOUtils.getReportsPath(reportsFolder, ComputationUtils.createFileNameForReport(reportName, start, report.getTotalScore(), true)) + "'");
                    IOUtils.storeResultsToFile(IOUtils.getReportsPath(reportsFolder, ComputationUtils.createFileNameForReport(reportName, start, report.getTotalScore(), true)), reportEncrypted);
                }
                getLog().info("Removing all temporary auto-generated files....");
                IOUtils.removeTestDataFiles();
                getLog().info("Removed all temporary auto-generated files!!!");


            } catch (Throwable t) {
                getLog().error(t);
                if (t.getMessage() != null && t.getMessage().contains("/META-INF/BenchmarkList")) {
                    getLog().info("-------------------No benchmark tests found-------------------");
                } else {
                    throw new MojoExecutionException("Error during benchmarks run", t);
                }
            }
            if (!isReportSentSuccessFully && shouldSendReportToCyBench && shouldFailBuildOnReportDeliveryFailure) {
                throw new MojoExecutionException("Error during benchmarks run, report was not sent to CyBench as configured!");
            }
            getLog().info("-----------------------------------------------------------------------------------------");
            getLog().info("         Finished CyBench benchmarking (" + ComputationUtils.formatInterval(System.currentTimeMillis() - start) + ")");
            getLog().info("-----------------------------------------------------------------------------------------");
        } else {
            getLog().info("Skipping CyBench execution");
        }
    }


}
