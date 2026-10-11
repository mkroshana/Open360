#!/usr/bin/env python3
"""
Open360 - Insta360 GO 3S Camera Diagnostic & Connection Verification Tool
Tests connectivity over Wi-Fi to the camera at 192.168.42.1.
"""

import json
import socket
import struct
import sys
import time
import urllib.request
import urllib.error

CAMERA_IP = "192.168.42.1"
OSC_HTTP_PORT = 80
SOCKET_PORT = 6666

def check_ping():
    print(f"[*] Checking TCP port {OSC_HTTP_PORT} on {CAMERA_IP}...")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3.0)
    try:
        s.connect((CAMERA_IP, OSC_HTTP_PORT))
        s.close()
        print(f"[+] HTTP port {OSC_HTTP_PORT} is reachable!")
        return True
    except Exception as e:
        print(f"[-] Could not connect to {CAMERA_IP}:{OSC_HTTP_PORT}: {e}")
        return False

def test_osc_info():
    print("\n[*] Testing Google OSC v2 /osc/info endpoint...")
    url = f"http://{CAMERA_IP}/osc/info"
    req = urllib.request.Request(url, headers={"User-Agent": "Open360/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = resp.read().decode('utf-8', errors='ignore')
            print(f"[+] Status: {resp.status}")
            info = json.loads(data)
            print("[+] Camera Information:")
            for k, v in info.items():
                print(f"    - {k}: {v}")
            return info
    except urllib.error.HTTPError as e:
        print(f"[-] HTTP Error {e.code}: {e.read().decode('utf-8', errors='ignore')}")
    except Exception as e:
        print(f"[-] Failed to query /osc/info: {e}")
    return None

def test_osc_state():
    print("\n[*] Testing Google OSC v2 /osc/state endpoint...")
    url = f"http://{CAMERA_IP}/osc/state"
    req = urllib.request.Request(url, method="POST", headers={"User-Agent": "Open360/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = resp.read().decode('utf-8', errors='ignore')
            print(f"[+] Status: {resp.status}")
            state = json.loads(data)
            print("[+] Camera State:")
            for k, v in state.items():
                print(f"    - {k}: {v}")
            return state
    except urllib.error.HTTPError as e:
        print(f"[-] HTTP Error {e.code}: {e.read().decode('utf-8', errors='ignore')}")
    except Exception as e:
        print(f"[-] Failed to query /osc/state: {e}")
    return None

def test_socket_port():
    print(f"\n[*] Testing binary command port TCP {SOCKET_PORT}...")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3.0)
    try:
        s.connect((CAMERA_IP, SOCKET_PORT))
        print(f"[+] Connected to binary socket {CAMERA_IP}:{SOCKET_PORT}")
        # Send keepalive / test probe
        s.close()
        return True
    except Exception as e:
        print(f"[-] Could not connect to port {SOCKET_PORT}: {e}")
        return False

def main():
    print("=" * 60)
    print("Open360 - Insta360 GO 3S Connection Diagnostic")
    print("=" * 60)
    print(f"Target Camera IP: {CAMERA_IP}")
    print("Ensure your computer's Wi-Fi is connected to the camera's Wi-Fi network.")
    print("Default camera Wi-Fi password is usually '88888888'.\n")

    reachable = check_ping()
    if not reachable:
        print("\n[!] Camera is not reachable over Wi-Fi.")
        print("    1. Turn on Wi-Fi in the GO 3S / Action Pod settings.")
        print("    2. On this PC, connect your Wi-Fi interface to the camera's SSID.")
        print("    3. Run this script again.")
        sys.exit(1)

    info = test_osc_info()
    state = test_osc_state()
    sock_ok = test_socket_port()

    print("\n" + "=" * 60)
    print("Diagnostic Summary:")
    print(f"  - HTTP OSC: {'OK' if info else 'Unavailable'}")
    print(f"  - Camera State: {'OK' if state else 'Unavailable'}")
    print(f"  - Port 6666 Socket: {'OK' if sock_ok else 'Unavailable'}")
    print("=" * 60)

if __name__ == "__main__":
    main()
