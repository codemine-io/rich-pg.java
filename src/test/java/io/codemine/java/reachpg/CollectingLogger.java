package io.codemine.java.reachpg;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.Marker;

/**
 * SLF4J logger implementation that records warn-level messages for testing.
 */
public final class CollectingLogger implements Logger {

    private final List<String> warnings = new ArrayList<>();

    public List<String> warnings() {
        return warnings;
    }

    @Override
    public String getName() {
        return "test";
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(String msg) {
        warnings.add(msg);
    }

    @Override
    public void warn(String format, Object arg) {
        warnings.add(substitute(format, arg));
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        warnings.add(substitute(format, arg1, arg2));
    }

    @Override
    public void warn(String format, Object... arguments) {
        warnings.add(substitute(format, arguments));
    }

    @Override
    public void warn(String msg, Throwable t) {
        warnings.add(msg);
    }

    private static String substitute(String template, Object... args) {
        StringBuilder result = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < template.length()) {
            int placeholder = template.indexOf("{}", i);
            if (placeholder == -1) {
                result.append(template, i, template.length());
                break;
            }
            result.append(template, i, placeholder);
            if (argIndex < args.length) {
                result.append(args[argIndex++]);
            } else {
                result.append("{}");
            }
            i = placeholder + 2;
        }
        return result.toString();
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public void trace(String msg) {}

    @Override
    public void trace(String format, Object arg) {}

    @Override
    public void trace(String format, Object arg1, Object arg2) {}

    @Override
    public void trace(String format, Object... arguments) {}

    @Override
    public void trace(String msg, Throwable t) {}

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return false;
    }

    @Override
    public void trace(Marker marker, String msg) {}

    @Override
    public void trace(Marker marker, String format, Object arg) {}

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {}

    @Override
    public void trace(Marker marker, String format, Object... argArray) {}

    @Override
    public void trace(Marker marker, String msg, Throwable t) {}

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(String msg) {}

    @Override
    public void debug(String format, Object arg) {}

    @Override
    public void debug(String format, Object arg1, Object arg2) {}

    @Override
    public void debug(String format, Object... arguments) {}

    @Override
    public void debug(String msg, Throwable t) {}

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return false;
    }

    @Override
    public void debug(Marker marker, String msg) {}

    @Override
    public void debug(Marker marker, String format, Object arg) {}

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {}

    @Override
    public void debug(Marker marker, String format, Object... arguments) {}

    @Override
    public void debug(Marker marker, String msg, Throwable t) {}

    @Override
    public boolean isInfoEnabled() {
        return false;
    }

    @Override
    public void info(String msg) {}

    @Override
    public void info(String format, Object arg) {}

    @Override
    public void info(String format, Object arg1, Object arg2) {}

    @Override
    public void info(String format, Object... arguments) {}

    @Override
    public void info(String msg, Throwable t) {}

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return false;
    }

    @Override
    public void info(Marker marker, String msg) {}

    @Override
    public void info(Marker marker, String format, Object arg) {}

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {}

    @Override
    public void info(Marker marker, String format, Object... arguments) {}

    @Override
    public void info(Marker marker, String msg, Throwable t) {}

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    @Override
    public void warn(Marker marker, String msg) {
        warnings.add(msg);
    }

    @Override
    public void warn(Marker marker, String format, Object arg) {
        warnings.add(substitute(format, arg));
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        warnings.add(substitute(format, arg1, arg2));
    }

    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        warnings.add(substitute(format, arguments));
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        warnings.add(msg);
    }

    @Override
    public boolean isErrorEnabled() {
        return false;
    }

    @Override
    public void error(String msg) {}

    @Override
    public void error(String format, Object arg) {}

    @Override
    public void error(String format, Object arg1, Object arg2) {}

    @Override
    public void error(String format, Object... arguments) {}

    @Override
    public void error(String msg, Throwable t) {}

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return false;
    }

    @Override
    public void error(Marker marker, String msg) {}

    @Override
    public void error(Marker marker, String format, Object arg) {}

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {}

    @Override
    public void error(Marker marker, String format, Object... arguments) {}

    @Override
    public void error(Marker marker, String msg, Throwable t) {}
}
