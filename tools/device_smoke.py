"""Run short local Telecom tests on one connected Android device.

Requires the installed debug APK, role + permissions, and its enabled test account.
Never places a network call. Stops only DebugConnectionService's synthetic call.
"""
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.greatwall.callring'

def adb(*args):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=15)
    if result.returncode:
        raise RuntimeError('ADB command failed')
    return result.stdout.decode('utf-8', errors='replace')

def probe(op, number=None):
    args = ['shell', 'am', 'broadcast', '-n', PACKAGE + '/.DebugProbeReceiver', '--es', 'op', op]
    if number:
        args += ['--es', 'number', number]
    result = adb(*args)
    if 'failed:' in result:
        raise AssertionError('Probe failed: ' + op)
    return result

def logs():
    doc = ET.fromstring(adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/ring_settings.xml'))
    return next((node.text or '' for node in doc if node.get('name') == 'events'), '')

def running():
    return '.RingPlaybackService' in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)

def wait_for(condition, timeout=4):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(.1)
    raise AssertionError('Timed out waiting for expected state')

def incoming(number):
    assert 'callState=0' in probe('status'), 'A call is already active; refusing to inject another'
    probe('incoming', number)

def matched_end(action):
    incoming('31234')
    wait_for(running)
    time.sleep(.8)  # Reproduce the original focus-loss regression after Telecom takes focus.
    assert running(), 'Playback died during ringing'
    assert '自定义来电铃声开始' in logs().split('已建立模拟来电')[0]
    probe(action)
    wait_for(lambda: not running())
    probe('end')
    wait_for(lambda: 'callState=0' in probe('status'))

try:
    assert 'callState=0' in probe('status'), 'Device must be idle'
    probe('enable')
    matched_end('answer')
    print('PASS matching call plays past focus handoff; answer stops', flush=True)
    matched_end('end')
    print('PASS matching call disconnect stops', flush=True)
    incoming('41234')
    time.sleep(1)
    assert not running()
    assert '未命中，正常放行' in logs().split('已建立模拟来电')[0]
    probe('end')
    wait_for(lambda: 'callState=0' in probe('status'))
    print('PASS nonmatching call leaves system ringtone', flush=True)
    probe('disable')
    incoming('31234')
    time.sleep(1)
    assert not running()
    assert '命中但条件不满足，保留系统铃声' in logs().split('已建立模拟来电')[0]
    probe('end')
    wait_for(lambda: 'callState=0' in probe('status'))
    probe('enable')
    print('PASS disabled rule leaves system ringtone', flush=True)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(.4)
    matched_end('end')
    print('PASS background incoming call', flush=True)
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    time.sleep(.4)
    matched_end('end')
    print('PASS locked/screen-off incoming call', flush=True)
finally:
    probe('end')
    probe('stop')
    probe('enable')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
