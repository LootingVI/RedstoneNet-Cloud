package net.redstone.cloud.node.logging;

public class Logger {
    public static final String RESET = "\u001B[0m";
    public static final String CYAN = "\u001B[36m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String RED = "\u001B[31m";
    public static final String GRAY = "\u001B[90m";
    public static final String BOLD = "\u001B[1m";
    
    public static final String PREFIX = CYAN + BOLD + "Cloud " + GRAY + ">> " + RESET;
    public static org.jline.reader.LineReader lineReader;

    public static void info(String message) {
        log(PREFIX + message);
    }
    public static void success(String message) {
        log(PREFIX + GREEN + message + RESET);
    }
    public static void warn(String message) {
        log(PREFIX + YELLOW + message + RESET);
    }
    public static void error(String message) {
        log(PREFIX + RED + "FEHLER: " + message + RESET);
    }
    public static void raw(String message) {
        log(message);
    }
    
    private static void log(String out) {
        if (lineReader != null) {
            lineReader.printAbove(out);
        } else {
            System.out.println(out);
        }
    }
}
