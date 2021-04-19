package de.stefankrupop.jvcmultiremote;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import com.tulskiy.keymaster.common.HotKey;
import com.tulskiy.keymaster.common.HotKeyListener;

public class JvcMultiRemote extends JFrame {
	private static final long serialVersionUID = -8908451151977084899L;

	private final CameraManager _cameraManager;
	private final JList<Camera> _lstCameras;
	private final JButton _cmdRecord;
	private final JButton _cmdStop;
	private final JCheckBox _chkAlwaysOnTop;
	private final JCheckBox _chkHotkeys;
	private com.tulskiy.keymaster.common.Provider _hotkeyProvider = null;
	
	public JvcMultiRemote(CameraManager mgr) {
		_cameraManager = mgr;
		this.setTitle("JVC MultiRemote");
		this.setSize(Config.getPropertyInt("windowWidth", 350), Config.getPropertyInt("windowHeight", 122));
		this.setLocation(Config.getPropertyInt("windowX", 0), Config.getPropertyInt("windowY", 0));
		this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		
		this.setLayout(new BorderLayout());
		
		_lstCameras = new JList<Camera>(_cameraManager.getCameras().toArray(new Camera[0]));
		_lstCameras.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		_lstCameras.addSelectionInterval(0, _cameraManager.getCameras().size() - 1);
		JScrollPane lstCamerasScroll = new JScrollPane(_lstCameras);
		this.add(lstCamerasScroll, BorderLayout.CENTER);
		
		_cmdRecord = new JButton("Record");
		_cmdRecord.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
					@Override
					public Void doInBackground() {
						_cmdRecord.setEnabled(false);
						_cmdStop.setEnabled(false);
						try {
							_cameraManager.setRecordingSimultaneous(_lstCameras.getSelectedValuesList(), true);
						} catch (IOException e) {
							JOptionPane.showMessageDialog(JvcMultiRemote.this, "Could not execute command: " + e.toString(), "Executing command failed", JOptionPane.ERROR_MESSAGE);
						}
						_cmdRecord.setEnabled(true);
						_cmdStop.setEnabled(true);
						return null;
					}
				};
				worker.execute();
			}
		});
		_cmdStop = new JButton("Stop");
		_cmdStop.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
					@Override
					public Void doInBackground() {
						_cmdStop.setEnabled(false);
						_cmdRecord.setEnabled(false);
						try {
							_cameraManager.setRecordingSimultaneous(_lstCameras.getSelectedValuesList(), false);
						} catch (IOException e) {
							JOptionPane.showMessageDialog(JvcMultiRemote.this, "Could not execute command: " + e.toString(), "Executing command failed", JOptionPane.ERROR_MESSAGE);
						}
						_cmdStop.setEnabled(true);
						_cmdRecord.setEnabled(true);
						return null;
					}
				};
				worker.execute();
			}
		});
		_chkAlwaysOnTop = new JCheckBox("Always on top");
		_chkAlwaysOnTop.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JvcMultiRemote.this.setAlwaysOnTop(_chkAlwaysOnTop.isSelected());
			}
		});

		_chkHotkeys = new JCheckBox("Global hotkeys");
		_chkHotkeys.setToolTipText("Record: " + Config.getProperty("globalHotkeyRecord", "ctrl alt R") + " - Stop: " + Config.getProperty("globalHotkeyStop", "ctrl alt S"));
		_chkHotkeys.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (_chkHotkeys.isSelected()) {
					if (_hotkeyProvider == null) {
						_hotkeyProvider = com.tulskiy.keymaster.common.Provider.getCurrentProvider(true);
						if (_hotkeyProvider == null) {
							JOptionPane.showMessageDialog(null, "Could not get hotkey provider", "Global hotkeys error", JOptionPane.ERROR_MESSAGE);
							return;
						}
					}

					_hotkeyProvider.register(KeyStroke.getKeyStroke(Config.getProperty("globalHotkeyRecord", "ctrl alt R")), new HotKeyListener() {
						@Override
						public void onHotKey(HotKey arg0) {
							_cmdRecord.doClick();
						}
					});
					_hotkeyProvider.register(KeyStroke.getKeyStroke(Config.getProperty("globalHotkeyStop", "ctrl alt S")), new HotKeyListener() {
						@Override
						public void onHotKey(HotKey arg0) {
							_cmdStop.doClick();
						}
					});
				} else {
					if (_hotkeyProvider != null) {
						_hotkeyProvider.reset();
						_hotkeyProvider.stop();
						_hotkeyProvider = null;
					}
				}
			}
		});
		
		JPanel buttons = new JPanel();
		buttons.add(_cmdRecord);
		buttons.add(_cmdStop);
		buttons.add(_chkAlwaysOnTop);
		buttons.add(_chkHotkeys);
		this.add(buttons, BorderLayout.SOUTH);
		
		_chkAlwaysOnTop.setSelected(!Config.getPropertyBool("alwaysOnTop", false));
		_chkAlwaysOnTop.doClick();
		_chkHotkeys.setSelected(!Config.getPropertyBool("enableGlobalHotkeys", false));
		_chkHotkeys.doClick();
		
		this.setVisible(true);
		SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
			@Override
			public Void doInBackground() {
				_lstCameras.setEnabled(false);
				_cmdRecord.setEnabled(false);
				_cmdStop.setEnabled(false);
				try {
					_cameraManager.connectAllCameras();
				} catch (IOException e) {
					JOptionPane.showMessageDialog(JvcMultiRemote.this, "Could not connect to cameras: " + e.toString(), "Camera connection failed", JOptionPane.ERROR_MESSAGE);
				}
				_lstCameras.setEnabled(true);
				_cmdRecord.setEnabled(true);
				_cmdStop.setEnabled(true);
				return null;
			}
		};
		worker.execute();
	}
	
	@Override
	public void dispose() {
		Config.setProperty("windowWidth", this.getWidth());
		Config.setProperty("windowHeight", this.getHeight());
		Config.setProperty("windowX", this.getLocation().x);
		Config.setProperty("windowY", this.getLocation().y);
		Config.setProperty("alwaysOnTop", _chkAlwaysOnTop.isSelected());
		Config.setProperty("enableGlobalHotkeys", _chkHotkeys.isSelected());
		if (_hotkeyProvider != null) {
			_hotkeyProvider.reset();
			_hotkeyProvider.stop();
			_hotkeyProvider = null;
		}
	    super.dispose();
	    System.exit(0);
	}
		
	public static void main(String[] args) {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
		}
		try {
			CameraManager mgr = new CameraManager();
			new JvcMultiRemote(mgr);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, e.toString(), "Initialization error", JOptionPane.ERROR_MESSAGE);			
		}
	}
}
