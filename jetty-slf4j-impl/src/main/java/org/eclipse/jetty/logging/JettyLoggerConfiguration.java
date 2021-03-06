//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.function.Function;

import org.slf4j.event.Level;

/**
 * JettyLogger specific configuration:
 * <ul>
 *  <li>{@code <name>.LEVEL=(String:LevelName)}</li>
 *  <li>{@code <name>.STACKS=(boolean)}</li>
 * </ul>
 */
public class JettyLoggerConfiguration
{
    private static final int DEFAULT_LEVEL = Level.INFO.toInt();
    private static final boolean DEFAULT_HIDE_STACKS = false;
    private static final String SUFFIX_LEVEL = ".LEVEL";
    private static final String SUFFIX_STACKS = ".STACKS";

    private final Properties properties = new Properties();

    /**
     * Default JettyLogger configuration (empty)
     */
    public JettyLoggerConfiguration()
    {
    }

    /**
     * JettyLogger configuration from provided Properties
     *
     * @param props A set of properties to base this configuration off of
     */
    public JettyLoggerConfiguration(Properties props)
    {
        load(props);
    }

    public boolean getHideStacks(String name)
    {
        if (properties.isEmpty())
            return DEFAULT_HIDE_STACKS;

        String startName = name;

        // strip trailing dot
        while (startName.endsWith("."))
        {
            startName = startName.substring(0, startName.length() - 1);
        }

        // strip ".STACKS" suffix (if present)
        if (startName.endsWith(SUFFIX_STACKS))
        {
            startName = startName.substring(0, startName.length() - SUFFIX_STACKS.length());
        }

        Boolean hideStacks = walkParentLoggerNames(startName, (key) ->
        {
            String stacksBool = properties.getProperty(key + SUFFIX_STACKS);
            if (stacksBool != null)
            {
                return Boolean.parseBoolean(stacksBool);
            }
            return null;
        });

        if (hideStacks != null)
            return hideStacks;

        return DEFAULT_HIDE_STACKS;
    }

    /**
     * Get the Logging Level for the provided log name. Using the FQCN first, then each package segment from longest to
     * shortest.
     *
     * @param name the name to get log for
     * @return the logging level int
     */
    public int getLevel(String name)
    {
        if (properties.isEmpty())
            return DEFAULT_LEVEL;

        String startName = name != null ? name : "";

        // strip trailing dot
        while (startName.endsWith("."))
        {
            startName = startName.substring(0, startName.length() - 1);
        }

        // strip ".LEVEL" suffix (if present)
        if (startName.endsWith(SUFFIX_LEVEL))
        {
            startName = startName.substring(0, startName.length() - SUFFIX_LEVEL.length());
        }

        Integer level = walkParentLoggerNames(startName, (key) ->
        {
            String levelStr = properties.getProperty(key + SUFFIX_LEVEL);
            if (levelStr != null)
            {
                return getLevelInt(key, levelStr);
            }
            return null;
        });

        if (level == null)
        {
            // try legacy root logging config
            String levelStr = properties.getProperty("log" + SUFFIX_LEVEL);
            if (levelStr != null)
            {
                level = getLevelInt("log", levelStr);
            }
        }

        if (level != null)
            return level;

        return DEFAULT_LEVEL;
    }

    public TimeZone getTimeZone(String key)
    {
        String zoneIdStr = properties.getProperty(key);
        if (zoneIdStr == null)
            return null;

        return TimeZone.getTimeZone(zoneIdStr);
    }

    /**
     * Load the Configuration from the ClassLoader
     *
     * @param loader the classloader to use when finding the {@code jetty-logging.properties} resources in.
     * Passing {@code null} means the {@link ClassLoader#getSystemClassLoader()} is used.
     * @return the configuration
     */
    public JettyLoggerConfiguration load(ClassLoader loader)
    {
        return AccessController.doPrivileged((PrivilegedAction<JettyLoggerConfiguration>)() ->
        {
            // First see if the jetty-logging.properties object exists in the classpath.
            // * This is an optional feature used by embedded mode use, and test cases to allow for early
            // * configuration of the Log class in situations where access to the System.properties are
            // * either too late or just impossible.
            load(readProperties(loader, "jetty-logging.properties"));

            // Next see if an OS specific jetty-logging.properties object exists in the classpath.
            // This really for setting up test specific logging behavior based on OS.
            String osName = System.getProperty("os.name");
            if (osName != null && osName.length() > 0)
            {
                // NOTE: cannot use jetty-util's StringUtil.replace() as it may initialize logging itself.
                osName = osName.toLowerCase(Locale.ENGLISH).replace(' ', '-');
                load(readProperties(loader, "jetty-logging-" + osName + ".properties"));
            }

            // Now load the System.properties as-is into the properties,
            // these values will override any key conflicts in properties.
            load(System.getProperties());
            return this;
        });
    }

    public boolean getBoolean(String key, boolean defValue)
    {
        String val = properties.getProperty(key, Boolean.toString(defValue));
        return Boolean.parseBoolean(val);
    }

    public int getInt(String key, int defValue)
    {
        String val = properties.getProperty(key, Integer.toString(defValue));
        if (val == null)
        {
            return defValue;
        }
        try
        {
            return Integer.parseInt(val);
        }
        catch (NumberFormatException e)
        {
            return defValue;
        }
    }

    private Integer getLevelInt(String levelSegment, String levelStr)
    {
        if (levelStr == null)
        {
            return null;
        }

        String levelName = levelStr.trim().toUpperCase(Locale.ENGLISH);
        switch (levelName)
        {
            case "ALL":
                return JettyLogger.ALL;
            case "TRACE":
                return Level.TRACE.toInt();
            case "DEBUG":
                return Level.DEBUG.toInt();
            case "INFO":
                return Level.INFO.toInt();
            case "WARN":
                return Level.WARN.toInt();
            case "ERROR":
                return Level.ERROR.toInt();
            case "OFF":
                return JettyLogger.OFF;
            default:
                System.err.println("Unknown JettyLogger/Slf4J Level [" + levelSegment + "]=[" + levelName + "], expecting only [ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF] as values.");
                return null;
        }
    }

    private URL getResource(ClassLoader loader, String resourceName)
    {
        if (loader == null)
        {
            return ClassLoader.getSystemResource(resourceName);
        }
        else
        {
            return loader.getResource(resourceName);
        }
    }

    /**
     * Overlay existing properties with provided properties.
     *
     * @param props the properties to load
     */
    private void load(Properties props)
    {
        if (props == null)
            return;

        for (String name : props.stringPropertyNames())
        {
            if (name.startsWith("org.eclipse.jetty.logging.") ||
                name.endsWith(".LEVEL") ||
                name.endsWith(".STACKS"))
            {
                String val = props.getProperty(name);
                // Protect against application code insertion of non-String values (returned as null).
                if (val != null)
                    properties.setProperty(name, val);
            }
        }
    }

    private Properties readProperties(ClassLoader loader, String resourceName)
    {
        URL propsUrl = getResource(loader, resourceName);
        if (propsUrl == null)
        {
            return null;
        }

        try (InputStream in = propsUrl.openStream())
        {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
        catch (IOException e)
        {
            System.err.println("[WARN] Error loading logging config: " + propsUrl);
            e.printStackTrace();
        }
        return null;
    }

    private <T> T walkParentLoggerNames(String startName, Function<String, T> nameFunction)
    {
        String nameSegment = startName;

        // Checking with FQCN first, then each package segment from longest to shortest.
        while ((nameSegment != null) && (nameSegment.length() > 0))
        {
            T ret = nameFunction.apply(nameSegment);
            if (ret != null)
                return ret;

            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            if (idx >= 0)
            {
                nameSegment = nameSegment.substring(0, idx);
            }
            else
            {
                nameSegment = null;
            }
        }

        return null;
    }
}
