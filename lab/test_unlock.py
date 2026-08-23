#!/usr/bin/env python3
"""
Automated tester for nadamu-unlocker lab.
Connects via SSH to Dropbear inside initramfs and unlocks LUKS via passfifo.
"""
import os
import sys
import time
import socket
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_KEY_PATH = os.path.join(SCRIPT_DIR, "data", "keys", "id_ed25519")

SSH_HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
SSH_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 2222
KEY_PATH = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_KEY_PATH
PASS = sys.argv[4] if len(sys.argv) > 4 else "password"

def wait_for_port(host, port, timeout=60):
    print(f"[*] Waiting for SSH service at {host}:{port}...")
    start = time.time()
    while time.time() - start < timeout:
        try:
            with socket.create_connection((host, port), timeout=5) as sock:
                sock.settimeout(5)
                banner = sock.recv(1024)
                if b"SSH" in banner:
                    print(f"[+] SSH service at {port} is open and ready!")
                    return True
        except (socket.timeout, ConnectionRefusedError, OSError, ConnectionResetError):
            pass
        time.sleep(1)
    print("[-] Timeout waiting for SSH service.")
    return False

def inject_unlock_payload(key_path):
    if not os.path.exists(key_path):
        print(f"[-] ERROR: SSH key not found at {key_path}")
        print("[-] The lab container might still be building or failed to generate keys.")
        return False

    cmd = (
        f"for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do "
        f"[ -p \"$f\" ] && printf \"%s\" \"{PASS}\" > \"$f\" && exit 0; "
        f"done; exit 1"
    )
    
    env = os.environ.copy()
    env.pop("SSH_AUTH_SOCK", None)
    
    ssh_cmd = [
        "ssh",
        "-F", "/dev/null",
        "-i", key_path,
        "-p", str(SSH_PORT),
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "IdentitiesOnly=yes",
        "-o", "BatchMode=yes",
        "-o", "PasswordAuthentication=no",
        "-o", "KbdInteractiveAuthentication=no",
        "-o", "PubkeyAuthentication=yes",
        "-o", "ConnectTimeout=5",
        f"root@{SSH_HOST}",
        cmd
    ]
    
    print(f"[*] Sending unlock payload over SSH using key {os.basename(key_path)}...")
    res = subprocess.run(ssh_cmd, capture_output=True, text=True, env=env)
    
    if res.returncode == 0:
        print("[+] Payload successfully delivered to passfifo!")
        return True
    else:
        print(f"[-] Failed: {res.stderr.strip()}")
        return False

if __name__ == "__main__":
    if not wait_for_port(SSH_HOST, SSH_PORT, timeout=60):
        sys.exit(1)
    time.sleep(1)
    
    print("[*] Attempting unlock with Ed25519 key...")
    if inject_unlock_payload(KEY_PATH):
        print("[*] Unlock command finished successfully.")
        sys.exit(0)
        
    sys.exit(1)
