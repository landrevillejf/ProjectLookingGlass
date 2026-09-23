package com.protonmail.landrevillejf.swingide.update;

import lombok.Value;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Value
public class Version implements Comparable<Version> {
    private static final Pattern VERSION_PATTERN = 
        Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([a-zA-Z0-9]+))?$");
    
    int major;
    int minor;
    int patch;
    String qualifier;
    
    public static Version parse(String versionString) {
        Matcher matcher = VERSION_PATTERN.matcher(versionString);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                "Invalid version format: " + versionString
            );
        }
        
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String qualifier = matcher.group(4) != null ? matcher.group(4) : "";
        
        return new Version(major, minor, patch, qualifier);
    }
    
    @Override
    public int compareTo(Version other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        if (this.patch != other.patch) {
            return Integer.compare(this.patch, other.patch);
        }
        
        // Compare qualifiers: release > alpha > beta > rc > snapshot
        return compareQualifiers(this.qualifier, other.qualifier);
    }
    
    private int compareQualifiers(String q1, String q2) {
        if (q1.isEmpty() && q2.isEmpty()) return 0;
        if (q1.isEmpty()) return 1; // Release is higher than any qualifier
        if (q2.isEmpty()) return -1;
        
        String q1Lower = q1.toLowerCase();
        String q2Lower = q2.toLowerCase();
        
        int priority1 = getQualifierPriority(q1Lower);
        int priority2 = getQualifierPriority(q2Lower);
        
        if (priority1 != priority2) {
            return Integer.compare(priority2, priority1); // Higher priority first
        }
        
        return q1.compareTo(q2);
    }
    
    private int getQualifierPriority(String qualifier) {
        if (qualifier.contains("alpha")) return 1;
        if (qualifier.contains("beta")) return 2;
        if (qualifier.contains("rc")) return 3;
        if (qualifier.contains("snapshot")) return 0;
        return 4; // Other qualifiers
    }
    
    @Override
    public String toString() {
        if (qualifier == null || qualifier.isEmpty()) {
            return String.format("%d.%d.%d", major, minor, patch);
        }
        return String.format("%d.%d.%d-%s", major, minor, patch, qualifier);
    }
}
