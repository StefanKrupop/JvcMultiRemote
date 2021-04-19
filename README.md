# JVC MultiRemote

The JVC MultiRemote is a small utility to "simultaneously" start/stop recording on multiple JVC cameras that support JVC’s REST API (https://www3.jvckenwood.com/pro/video/web-api/pdf/JvcCamcorderApiReferenceV107_public.pdf), e.g. the GY-HC500E.

## Usage

The GUI is quite self-explanatory: On start up the program tries to connect to all configured cameras. After that one or multiple cameras (use Ctrl or Shift keys for multiselection) can be selected from the list. Clicking "Record" or "Stop" will execute the action on all selected cameras. When enabled, you can also use the global key shortcuts instead.

## Configuration

There are two configuration files: One configures the available cameras (conf/cameras.txt), the other is for program configuration (conf/config.txt).
The cameras.txt file lists one camera per line and has the following format:

```Camera 1,192.168.0.100,user,pass```

* Camera name (To be displayed in the program)
* IP address or hostname of the camera
* Username
* Password

### Configuring hotkeys

JVC MultiRemote supports global hotkeys that can be enabled in the GUI. The defaults are "Ctrl + Alt + R" to start recording and "Ctrl + Alt + S" to stop recording. These can be changed in the config.txt by adding the entries ```globalHotkeyRecord``` and ```globalHotkeyStop```. These expect the wanted key combination as a string, e.g. ```ctrl alt R``` or ```F11```.