package de.stefankrupop.jvcmultiremote;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.albroco.barebonesdigest.DigestAuthentication;
import com.albroco.barebonesdigest.DigestChallengeResponse;

public class Camera {
	private final String LOGIN_URL = "/cgi-bin/session.cgi";
	private final String CMD_URL = "/cgi-bin/cmd.cgi";
	
	private String _name;
	private String _ipAddress;
	private String _username;
	private String _password;
	private boolean _isAuthenticated;
	private String _sessionId;
	
	public Camera(String name, String ipAddress, String username, String password) {
		_name = name;
		_ipAddress = ipAddress;
		_username = username;
		_password = password;
		_isAuthenticated = false;
		_sessionId = "";
	}

	public String getName() {
		return _name;
	}
	
	public boolean connect() throws IOException {
		System.out.println("Connecting to camera " + this.toString() + "...");
		URL url = new URL("http://" + _ipAddress + LOGIN_URL);
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		connection.setRequestMethod("GET");
		
		// Handle "Digest" authentication
		if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
			// Step 3. Create a authentication object from the challenge...
			DigestAuthentication auth = DigestAuthentication.fromResponse(connection);
			// ...with correct credentials
			auth.username(_username).password(_password);

			// Step 4 (Optional). Check if the challenge was a digest challenge of a
			// supported type
			if (!auth.canRespond()) {
				// No digest challenge or a challenge of an unsupported type - do something else
				// or fail
				return false;
			}

			// Step 5. Create a new connection, identical to the original one...
			connection = (HttpURLConnection)url.openConnection();
			// ...and set the Authorization header on the request, with the challenge
			// response
			connection.setRequestProperty(DigestChallengeResponse.HTTP_HEADER_AUTHORIZATION,
					auth.getAuthorizationForRequest("GET", connection.getURL().getPath()));
		}
		
		connection.connect();
		if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
			throw new IOException("Could not connect: Invalid username and/or password");
		}
		String cookie = connection.getHeaderField("Set-Cookie");
		if (cookie == null || !cookie.startsWith("SessionID=")) {
			throw new IOException("Could not connect: Unexpected reply, not a session ID");
		}
		
		_sessionId = cookie.replace("SessionID=", "");
		_isAuthenticated = true;

		System.out.println("Connected successfully");
		
		return true;
	}

	public boolean setRecording(boolean state) throws IOException {
		//return sendCmd("SetCamCtrl", "{\"CamCtrl\":\"" + (state ? "Rec" : "Stop") + "\"}") // Returns success, but does not seem to do anything
		return sendCmd("SetWebKeyEvent", "{\"Kind\":\"Rec\",\"Key\":\"" + (state ? "Start" : "Stop") + "\"}").toLowerCase().contains("success");
	}

	public String getCamStatus() throws IOException {
		return sendCmd("GetCamStatus", null);
	}
	
	private String sendCmd(String cmd, String params) throws IOException {
		if (!_isAuthenticated) connect();
		
		System.out.println("Sending command '" + cmd + "' to camera " + this.toString() + "...");
		
		URL url = new URL("http://" + _ipAddress + CMD_URL);
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		connection.setRequestMethod("POST");
		
		StringBuilder sb = new StringBuilder();
		sb.append("{\"Request\": {");
		sb.append("\"Command\":\"");
		sb.append(cmd);
		sb.append("\",");
		sb.append("\"SessionID\":\"");
		sb.append(_sessionId);
		sb.append("\"");
		if (params != null) {
			sb.append(",");
			sb.append("\"Params\":");
			sb.append(params);
		}
		sb.append("}}");

		connection.setDoOutput(true);
		DataOutputStream out = new DataOutputStream(connection.getOutputStream());
		out.writeBytes(sb.toString());
		out.flush();
		out.close();
		
		connection.connect();

		if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
			throw new IOException("Failed to execute command, HTTP status was " + connection.getResponseCode());
		}
		
		BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		String inputLine;
		StringBuffer content = new StringBuffer();
		while ((inputLine = in.readLine()) != null) {
			content.append(inputLine);
		}
		in.close();
		
		System.out.println("Sent successfully");
		return content.toString();
	}
	
	@Override
	public String toString() {
		return _name + " (" + _ipAddress + ")";
	}
}
