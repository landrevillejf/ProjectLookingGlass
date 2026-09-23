package org.lpmconsole;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Executes LPM commands with proper argument handling, concurrency control,
 * and output streaming. All commands go through /usr/bin/lpm.
 */
public class LPMExecutor {
    private static final String LPM_BINARY = "/usr/bin/lpm";
    private static final Object LOCK = new Object();
    private static boolean operationInProgress = false;

    private final Consumer<String> stdoutListener;
    private final Consumer<String> stderrListener;
    private final boolean requiresPrivilege;

    public LPMExecutor(Consumer<String> stdoutListener, Consumer<String> stderrListener, boolean requiresPrivilege) {
        this.stdoutListener = stdoutListener;
        this.stderrListener = stderrListener;
        this.requiresPrivilege = requiresPrivilege;
    }

    /**
     * Execute an LPM command with arguments.
     * @param command The LPM command to execute
     * @param args Arguments to the command
     * @param dryRun If true, add --dry-run flag
     * @return OperationResult with exit code and output
     */
    public OperationResult execute(LPMCommand command, List<String> args, boolean dryRun) 
            throws LPMExecutionException {
        
        synchronized (LOCK) {
            if (operationInProgress) {
                throw new LPMExecutionException("Another LPM operation is already in progress");
            }
            operationInProgress = true;
        }

        try {
            List<String> commandArgs = buildCommandArgs(command, args, dryRun);
            ProcessBuilder processBuilder = buildProcessBuilder(commandArgs);
            
            Process process = processBuilder.start();
            
            // Stream output to listeners
            Future<String> stdoutFuture = streamOutput(process.getInputStream(), stdoutListener);
            Future<String> stderrFuture = streamOutput(process.getErrorStream(), stderrListener);
            
            int exitCode = process.waitFor();
            
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            
            OperationResult result = new OperationResult(exitCode, stdout, stderr);
            
            // Handle special case: verify returns non-zero for integrity issues
            if (command == LPMCommand.VERIFY && !result.isSuccess()) {
                // This is expected - integrity issues found
                // Don't throw, return the result
            } else if (!result.isSuccess()) {
                throw new LPMExecutionException("LPM command failed with exit code " + exitCode + 
                    ": " + stderr, exitCode, stderr);
            }
            
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LPMExecutionException("LPM operation interrupted", e);
        } catch (ExecutionException e) {
            throw new LPMExecutionException("Error reading LPM output", e);
        } catch (IOException e) {
            throw new LPMExecutionException("Failed to execute LPM: " + e.getMessage(), e);
        } finally {
            synchronized (LOCK) {
                operationInProgress = false;
            }
        }
    }

    private List<String> buildCommandArgs(LPMCommand command, List<String> args, boolean dryRun) {
        List<String> commandArgs = new ArrayList<>();
        
        // Always add --no-color to avoid ANSI escape sequences
        commandArgs.add("--no-color");
        
        // Add dry-run flag if requested
        if (dryRun) {
            commandArgs.add("--dry-run");
        }
        
        // Add the command name
        commandArgs.add(command.getCommandName());
        
        // Add arguments
        if (args != null) {
            commandArgs.addAll(args);
        }
        
        return commandArgs;
    }

    private ProcessBuilder buildProcessBuilder(List<String> args) {
        ProcessBuilder builder;
        
        if (requiresPrivilege) {
            builder = PrivilegeEscalator.escalate(LPM_BINARY, args);
        } else {
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(LPM_BINARY);
            fullCommand.addAll(args);
            builder = new ProcessBuilder(fullCommand);
        }
        
        builder.redirectErrorStream(false);
        return builder;
    }

    private Future<String> streamOutput(InputStream inputStream, Consumer<String> listener) 
            throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        
        Future<String> future = executor.submit(() -> {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (listener != null) {
                    listener.accept(line);
                }
            }
            reader.close();
            return output.toString();
        });
        
        executor.shutdown();
        return future;
    }

    public static boolean isOperationInProgress() {
        synchronized (LOCK) {
            return operationInProgress;
        }
    }

    /**
     * Check if LPM is available on the system.
     */
    public static boolean isLPMAvailable() {
        return new File(LPM_BINARY).exists() && new File(LPM_BINARY).canExecute();
    }

    /**
     * Get LPM version.
     */
    public static String getLPMVersion() throws LPMExecutionException {
        try {
            ProcessBuilder builder = new ProcessBuilder(LPM_BINARY, "version");
            Process process = builder.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder version = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                version.append(line).append("\n");
            }
            reader.close();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new LPMExecutionException("Failed to get LPM version");
            }
            
            return version.toString().trim();
        } catch (IOException | InterruptedException e) {
            throw new LPMExecutionException("Failed to get LPM version", e);
        }
    }
}
