package de.stefankrupop.jvcmultiremote;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Config {
	private static final Path PROPERTIES_FILENAME;
	private final Properties mProperties;
	private static Config mInstance;

	private static final Logger _logger = LoggerFactory.getLogger(Camera.class);
	
	static {
		String jarDir = "";
		try {
			CodeSource codeSource = JvcMultiRemote.class.getProtectionDomain().getCodeSource();
			File jarFile = new File(codeSource.getLocation().toURI().getPath());
			jarDir = jarFile.getParentFile().getPath();
			if (!jarDir.isEmpty()) {
				jarDir += "/";
			}
		} catch (URISyntaxException e) {
			// Should not happen, ignore
		}
		
		PROPERTIES_FILENAME = FileSystems.getDefault().getPath(jarDir, "conf", "config.txt");
	}
	
	private Config() {
		// Private constructor to avoid instantiation of this Util class
		
		mProperties = new Properties();
		readProperties(PROPERTIES_FILENAME);
	}
		
	public static synchronized Config getInstance() { //NOPMD
		if (mInstance == null) {
			mInstance = new Config();
		}
		return mInstance;
	}

	protected void readProperties(Path filename) { // NOPMD - Protected so JUnit can call it
		mProperties.clear();
		try {
			InputStream is = Files.newInputStream(filename);
			mProperties.load(is);
			is.close();
		} catch (IOException e) {
			_logger.error("Could not open/parse settings file " + e);
		}		
	}
	
	private String getPropertyInternal(String key, String defaultValue) {
		synchronized (mProperties) {
			return mProperties.getProperty(key, defaultValue).trim();				
		}
	}

	private void setPropertyInternal(String key, String value) {
		synchronized (mProperties) {
			mProperties.setProperty(key, value);
			try (OutputStream os = Files.newOutputStream(PROPERTIES_FILENAME)) {
				mProperties.store(os, "");
			} catch (RuntimeException e) {
				throw e;
			}
			catch (Exception e) {
				_logger.error("Could not store user-defined value " + key + " in settings file " + PROPERTIES_FILENAME + "! (" + e.getMessage() + ")");
			}
		}
	}
	
	public static String getProperty(String key, String defaultValue) {
		return getInstance().getPropertyInternal(key, defaultValue);
	}
	
	public static int getPropertyInt(String key, int defaultValue) {
		int ret = defaultValue;
		try {
			ret = Integer.parseInt(getProperty(key, Integer.toString(defaultValue)).trim());
		} catch (NumberFormatException e) {
			_logger.error("Could not read Integer value " + key + " in settings file " + PROPERTIES_FILENAME + "! (" + e.getMessage() + ")");
		}
		return ret;
	}
	
	public static double getPropertyDouble(String key, double defaultValue) {
		double ret = defaultValue;
		try {
			ret = Double.parseDouble(getProperty(key, Double.toString(defaultValue)).trim());
		} catch (NumberFormatException e) {
			_logger.error("Could not read Double value " + key  + " in settings file " + PROPERTIES_FILENAME + "! (" + e.getMessage() + ")");
		}
		return ret;
	}

	public static boolean getPropertyBool(String key, boolean defaultValue) {
		return Boolean.parseBoolean(getProperty(key, Boolean.toString(defaultValue)).trim());
	}
	
	public static List<String> getPropertyList(String key, String defaultValue) {
		List<String> ret;
		String str = getProperty(key, defaultValue);
		str = str.replace(",", " ");
		str = str.replace("  ", " ");
		str = str.replace(";", "");
	    ret = Arrays.asList(str.split(" "));
	    
	    return ret;
	}
	
	public static void setProperty(String key, boolean value) {
		setProperty(key, String.valueOf(value));
	}

	public static void setProperty(String key, int value) {
		setProperty(key, String.valueOf(value));
	}

	public static void setProperty(String key, double value) {
		setProperty(key, String.valueOf(value));
	}

	public static void setProperty(String key, String value) {
		getInstance().setPropertyInternal(key, value);
	}
}