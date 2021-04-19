package de.stefankrupop.jvcmultiremote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CameraManager {
	private static final Path CAMERAS_FILENAME;
	
	private final List<Camera> _cameras;
	private final ExecutorService _simultaneousExecutors;

	private final Logger _logger = LoggerFactory.getLogger(CameraManager.class);
	
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

		CAMERAS_FILENAME = FileSystems.getDefault().getPath(jarDir, "conf", "cameras.txt");
	}
	
	public CameraManager() throws IOException {
		_cameras = new ArrayList<Camera>();		
		_simultaneousExecutors = Executors.newFixedThreadPool(5);
		readCamerasFromFile();
	}
	
	public List<Camera> getCameras() {
		return Collections.unmodifiableList(_cameras); 
	}

	private void readCamerasFromFile() throws IOException {
		List<Camera> cams = new ArrayList<Camera>();

		String line;
		try (BufferedReader br = new BufferedReader(new FileReader(CAMERAS_FILENAME.toFile()))) {
			int lineNr = 1;
			while ((line = br.readLine()) != null) {
				if (!(line == null || line.trim().isEmpty()) && !line.startsWith("#") && !line.startsWith("//")) {
					String parts[] = line.split(",");
					if (parts.length >= 4) {
						Camera cam = new Camera(parts[0], parts[1], parts[2], parts[3]);
						cams.add(cam);
					} else {
						_logger.error("Invalid formatting in line " + lineNr + ": Expected four parts separated by comma");
					}
				}
				lineNr++;
			}
			_cameras.clear();
			_cameras.addAll(cams);
			_logger.info("Initialized " + cams.size() + " cameras");
		} catch (IOException e) {
			throw new IOException("Could not read cameras file: " + e.toString());
		}
	}
	
	public void connectAllCameras() throws IOException {
		for (Camera c : _cameras) {
			try {
				c.connect();
			} catch (IOException e) {
				throw new IOException("Failed to connect to camera " + c.toString() + ": " + e.toString(), e);
			}
		}
	}
	
	public void setRecordingSimultaneous(List<Camera> cams, boolean state) throws IOException {
		List<Callable<Boolean>> tasks = new ArrayList<Callable<Boolean>>(cams.size());
		for (Camera c : cams) {
			tasks.add(new Callable<Boolean>() {
				@Override
				public Boolean call() throws IOException {
					return c.setRecording(state);
				}
			});
		}
		try {
			List<Future<Boolean>> results = _simultaneousExecutors.invokeAll(tasks);
			for (Future<Boolean> f : results) {
				try {
					if (!f.get()) {
						throw new IOException("Command failed on at least one camera");
					}
				} catch (ExecutionException e) {
					throw new IOException("Could not execute command: " + e.toString(), e);
				}
			}
		} catch (InterruptedException e) {
		}
	}
}

