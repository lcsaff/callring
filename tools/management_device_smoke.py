"""Local Telecom management checks; fixed test numbers, isolated debug database.

No network call, no deletion of production app data or system call history.
Optional real 30-minute screen-off wait does not poll or open the app.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

PACKAGE = 'com.greatwall.callring'
parser = argparse.ArgumentParser()
parser.add_argument('--standby-minutes', type=float, default=0)
args = parser.parse_args()
evidence = []

def adb(*parts):
    result = subprocess.run(['adb', *parts], capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout.decode('utf-8', errors='replace')

def probe(op, **extras):
    command = ['shell', 'am', 'broadcast', '-n', PACKAGE+'/.DebugProbeReceiver', '--es', 'op', op]
    for key, value in extras.items():
        command += ['--ez' if isinstance(value, bool) else '--es', key, str(value).lower() if isinstance(value, bool) else str(value)]
    output = adb(*command)
    if 'failed:' in output:
        raise AssertionError(output)
    return output

def wait_for(condition, timeout=6):
    until = time.monotonic()+timeout
    while time.monotonic() < until:
        if condition():
            return
        time.sleep(.15)
    raise AssertionError('Timed out waiting for expected state')

def playback():
    return '.RingPlaybackService' in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)

def count():
    return int(re.search(r';count=(\d+)', probe('qa_status')).group(1))

def checkpoint(label, **details):
    evidence.append(dict(at=datetime.now().isoformat(timespec='seconds'), label=label, **details))
    print('PASS '+label, flush=True)
    destination = Path('app/build/qa/management-telecom.json')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')

def configure(schedule='nonwork', quiet='NON_WORK', white=False, rule=True, all=False):
    probe('qa_config', schedule=schedule, quiet=quiet, white=white, rule=rule, all=all)

def call(label, decision, sound, number='31234', action='end'):
    status = probe('status')
    assert 'callState=0' in status and 'inCall=false' in status, 'A call is already active'
    before = count()
    probe('incoming', number=number)
    if number == '31234':
        wait_for(lambda: 'latest='+decision in probe('qa_status'))
    if sound:
        wait_for(playback)
        time.sleep(.8)
        assert playback(), 'Playback stopped at Telecom focus handoff'
    else:
        time.sleep(1)
        assert not playback(), 'Quiet call started custom audio'
    assert 'inCall=true' in probe('status'), 'Call was rejected instead of allowed'
    probe(action)
    if action == 'answer':
        time.sleep(2)
    wait_for(lambda: not playback())
    probe('end')
    wait_for(lambda: 'inCall=false' in probe('status'))
    after = count()
    assert after == before + (number == '31234'), (before, after)
    snapshot = probe('qa_status')
    assert ';nonmatching=0' in snapshot
    checkpoint(label, status=snapshot)
    time.sleep(6)  # Keep synthetic calls outside the conservative association window.

active = False
bucket = None
def restore_bucket():
    if bucket:
        # EXEMPTED (5) is system-assigned and the shell rejects setting it.
        # Clear our forced rare bucket with active; the system keeps its exemption.
        adb('shell', 'am', 'set-standby-bucket', PACKAGE, bucket if int(bucket) >= 10 else 'active')

try:
    assert len([line for line in adb('devices').splitlines() if '\tdevice' in line]) == 1
    assert 'inCall=false' in probe('status')
    try:
        probe('qa_status')
    except AssertionError:
        probe('qa_begin')
    active = True
    probe('register')
    adb('shell', 'telecom', 'set-phone-account-enabled', PACKAGE+'/.DebugConnectionService', 'rule-ring-test', '0')
    configure()
    call('non-work call is silent and counted', '免打扰静音', False)
    configure(white=True)
    call('whitelist bypasses quiet and plays', '白名单放行', True, action='answer')
    configure(schedule='work')
    call('working-time call rings', '规则铃声', True)
    configure(schedule='leave')
    call('personal leave silences call', '免打扰静音', False)
    configure(quiet='CUSTOM', rule=False)
    call('custom quiet works with rule audio disabled', '免打扰静音', False)
    configure(quiet='OFF', rule=False)
    call('audio disabled preserves statistics', '正常响铃', False)
    configure(all=True)
    call('nonmatching call is allowed silently without statistics', '', False, number='41234')
    configure()
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    call('short screen-off quiet call', '免打扰静音', False)
    if args.standby_minutes:
        battery = adb('shell', 'dumpsys', 'battery')
        assert all(power+': false' in battery for power in ['AC powered', 'USB powered', 'Wireless powered'])
        adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        started = time.monotonic()
        print('Screen-off standby started; no device polling during the wait', flush=True)
        while time.monotonic()-started < args.standby_minutes*60:
            time.sleep(min(60, args.standby_minutes*60-(time.monotonic()-started)))
            print('Standby elapsed: %.1f minutes' % ((time.monotonic()-started)/60), flush=True)
        state = adb('shell', 'dumpsys', 'power')
        assert 'mWakefulness=Asleep' in state, 'Phone woke during the standby wait'
        checkpoint('unpowered screen-off wait completed', seconds=round(time.monotonic()-started))
        call('30-minute screen-off quiet call', '免打扰静音', False)
        configure(white=True)
        call('screen-off whitelist bypass', '白名单放行', True)
    bucket = adb('shell', 'am', 'get-standby-bucket', PACKAGE).strip()
    configure(schedule='leave')
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    # Verify that a persisted leave expires without an Activity or a scheduled UI refresh.
    for _ in range(3):
        time.sleep(22)
    call('screen-off leave expiry is evaluated on next call', '规则铃声', True)
    adb('shell', 'am', 'set-standby-bucket', PACKAGE, 'rare')
    actual_bucket = adb('shell', 'am', 'get-standby-bucket', PACKAGE).strip()
    checkpoint('standby bucket observed', requested='rare', actual=actual_bucket, exemption=actual_bucket == '5')
    adb('shell', 'dumpsys', 'deviceidle', 'force-idle')
    assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep').strip() == 'IDLE', 'Device did not enter deep Doze'
    configure()
    call('Doze quiet call', '免打扰静音', False)
    configure(white=True)
    call('Doze whitelist bypass', '白名单放行', True)
    adb('shell', 'dumpsys', 'deviceidle', 'unforce')
    restore_bucket()
    # Ordinary process reclamation, distinct from the stopped-package state.
    configure(white=True)
    assert 'inCall=false' in probe('status')
    original_process = subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, timeout=10).stdout.decode().strip()
    adb('shell', 'am', 'kill', PACKAGE)
    time.sleep(.3)
    remaining_process = subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, timeout=10).stdout.decode().strip()
    if remaining_process:
        assert remaining_process == original_process and remaining_process.isdigit()
        adb('shell', 'run-as', PACKAGE, 'kill', '-9', remaining_process)
        time.sleep(.3)
    assert not subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, timeout=10).stdout.strip(), 'Process was not reclaimed'
    call('system callback restores after process reclamation', '白名单放行', True)
finally:
    failures = []
    cleanup = [lambda: adb('shell', 'dumpsys', 'deviceidle', 'unforce'), restore_bucket,
               lambda: probe('end'), lambda: probe('stop'), lambda: probe('unregister')]
    if active:
        cleanup.append(lambda: probe('qa_finish'))
    cleanup.append(lambda: adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'))
    for action in cleanup:
        try:
            action()
        except Exception as error:
            failures.append(str(error))
            print('CLEANUP PENDING: '+str(error), flush=True)
    if failures and sys.exc_info()[0] is None:
        raise RuntimeError('Device cleanup incomplete; restore connection and retry cleanup')
