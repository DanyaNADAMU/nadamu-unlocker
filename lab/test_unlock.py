#!/usr/bin/env python3
"""
Automated tester for nadamu-unlocker lab.
Connects via SSH to Dropbear inside initramfs and unlocks LUKS via passfifo.
"""
import sys
import time
import socket
import subprocess

SSH_HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
SSH_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 22
KEY_PATH = sys.argv[3] if len(sys.argv) > 3 else "data/keys/id_ed25519"
PASS = sys.argv[4] if len(sys.argv) > 4 else "password"

def wait_for_port(host, port, timeout=60):
    print(f"[*] Waiting for SSH service at {host}:{port}...")
    start = time.time()
    while time.time() - start < timeout:
        try:
            with socket.create_connection((host, port), timeout=2):
                print(f"[+] SSH port {port} is open and ready!")
                return True
        except (socket.timeout, ConnectionRefusedError, OSError):
            time.sleep(1)
    print("[-] Timeout waiting for port.")
    return False

def inject_unlock_payload():
    cmd = (
        f"for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do "
        f"[ -p \"$f\" ] && printf \"%s\" \"{PASS}\" > \"$f\" && exit 0; "
        f"done; exit 1"
    )
    ssh_cmd = [
        "ssh",
        "-i", KEY_PATH,
        "-p", str(SSH_PORT),
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "ConnectTimeout=5",
        f"root@{SSH_HOST}",
        cmd
    ]
    print(f"[*] Sending unlock payload over SSH to {SSH_HOST}:{SSH_PORT}...")
    res = subprocess.run(ssh_cmd, capture_output=True, text=True)
    if res.returncode == 0:
        print("[+] Payload successfully delivered to passfifo!")
        return True
    else:
        print(f"[-] Failed to execute remote unlock command: {res.stderr}")
        return False

if __name__ == "__main__":
    if not wait_for_port(SSH_HOST, SSH_PORT, timeout=45):
        sys.exit(1)
    time.sleep(1)
    if inject_unlock_payload():
        print("[*] Unlock command finished successfully.")
        sys.exit(0)
    else:
        sys.exit(1)
