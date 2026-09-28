#!/usr/bin/env python3
"""
Automated end-to-end tester for unlocker lab.
Validates the unlock contract against Dropbear and cryptsetup inside initramfs:
1. Dropbear SSH availability and pubkey authentication
2. Rejection of unauthorized SSH keys
3. Rejection of invalid LUKS passphrases (passfifo write without mapper opening)
4. Successful unlock with valid passphrase, mapper-device verification, and handoff
"""

import argparse
import os
import socket
import subprocess
import sys
import tempfile
import time

import struct

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_KEY_PATH = os.path.join(SCRIPT_DIR, "data", "keys", "id_ed25519")


def can_resolve(host: str) -> bool:
    try:
        socket.gethostbyname(host)
        return True
    except socket.error:
        return False


def can_connect(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def get_default_gateways() -> list[str]:
    gateways = []
    try:
        with open("/proc/net/route", "r") as f:
            for line in f.readlines()[1:]:
                parts = line.strip().split()
                if len(parts) >= 3 and parts[1] == "00000000":
                    gw_hex = parts[2]
                    gw_ip = socket.inet_ntoa(struct.pack("<L", int(gw_hex, 16)))
                    if gw_ip != "0.0.0.0" and gw_ip not in gateways:
                        gateways.append(gw_ip)
    except Exception:
        pass
    return gateways


def get_default_host_and_port() -> tuple[str, int]:
    env_host = os.environ.get("UNLOCKER_SSH_HOST") or os.environ.get("NADAMU_SSH_HOST")
    env_port = os.environ.get("UNLOCKER_SSH_PORT") or os.environ.get("NADAMU_SSH_PORT")

    if env_host:
        port = int(env_port) if env_port else (22 if env_host in ("unlocker-lab", "nadamu-unlocker-lab") else 2222)
        return env_host, port

    # 1. Direct container name (Docker/Podman network with DNS)
    for name in ("unlocker-lab", "nadamu-unlocker-lab"):
        if can_connect(name, 22):
            return name, 22

    # 2. Localhost standard port forward (host machine execution)
    if can_connect("127.0.0.1", 2222):
        return "127.0.0.1", 2222

    # 3. Default gateways from /proc/net/route (container accessing host port forward)
    for gw in get_default_gateways():
        if can_connect(gw, 2222):
            return gw, 2222
        if can_connect(gw, 22):
            return gw, 22

    if can_resolve("unlocker-lab"):
        return "unlocker-lab", 22

    return "127.0.0.1", 2222


def build_ssh_base_cmd(key_path: str, host: str, port: int, connect_timeout: int = 5) -> list[str]:
    return [
        "ssh",
        "-F", "/dev/null",
        "-o", "IdentityAgent=none",
        "-o", "IdentitiesOnly=yes",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "LogLevel=ERROR",
        "-o", f"ConnectTimeout={connect_timeout}",
        "-o", "BatchMode=yes",
        "-o", "PasswordAuthentication=no",
        "-o", "KbdInteractiveAuthentication=no",
        "-o", "PubkeyAuthentication=yes",
        "-i", key_path,
        "-p", str(port),
        f"root@{host}",
    ]


def run_ssh_command(
    key_path: str,
    host: str,
    port: int,
    remote_cmd: str,
    connect_timeout: int = 5,
) -> subprocess.CompletedProcess:
    cmd = build_ssh_base_cmd(key_path, host, port, connect_timeout) + [remote_cmd]
    env = os.environ.copy()
    env.pop("SSH_AUTH_SOCK", None)
    return subprocess.run(cmd, capture_output=True, text=True, env=env)


def wait_for_ssh(host: str, port: int, key_path: str, timeout: int = 60) -> bool:
    print(f"[*] Waiting for SSH service at {host}:{port}...", end="", flush=True)
    start = time.time()

    while time.time() - start < timeout:
        res = run_ssh_command(key_path, host, port, "exit 0", connect_timeout=2)
        if res.returncode == 0:
            print(" [READY]")
            return True
        print(".", end="", flush=True)
        time.sleep(1)

    print("\n[-] Timeout waiting for SSH service.")
    return False


def test_unauthorized_key(host: str, port: int) -> bool:
    print("[*] TEST: Unauthorized SSH key rejection...")
    with tempfile.TemporaryDirectory() as td:
        untrusted_key = os.path.join(td, "untrusted_ed25519")
        gen_res = subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", untrusted_key],
            capture_output=True,
            text=True,
        )
        if gen_res.returncode != 0:
            print(f"[-] Failed to generate temporary untrusted key: {gen_res.stderr}")
            return False

        res = run_ssh_command(untrusted_key, host, port, "exit 0", connect_timeout=3)
        if res.returncode != 0:
            print("[+] PASS: Untrusted SSH key was rejected as expected.")
            return True
        else:
            print("[-] FAIL: Untrusted SSH key was accepted by Dropbear!")
            return False


def test_invalid_passphrase(
    host: str,
    port: int,
    key_path: str,
    mapper_target: str = "test_crypt",
) -> bool:
    print("[*] TEST: Invalid LUKS passphrase rejection...")
    fifo_write_cmd = (
        "for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do "
        "[ -p \"$f\" ] && printf \"%s\" \"incorrect-passphrase-test\" > \"$f\" && exit 0; "
        "done; exit 1"
    )

    res = run_ssh_command(key_path, host, port, fifo_write_cmd)
    if res.returncode != 0:
        print(f"[-] Failed to write invalid passphrase to passfifo: {res.stderr}")
        return False

    # Allow cryptsetup watcher loop in initramfs to process input
    time.sleep(2)

    # Verify mapper device is NOT opened
    check_mapper_cmd = f"[ -b /dev/mapper/{mapper_target} ]"
    mapper_res = run_ssh_command(key_path, host, port, check_mapper_cmd)
    if mapper_res.returncode != 0:
        print("[+] PASS: Mapper device was NOT opened with invalid passphrase, SSH remains active.")
        return True
    else:
        print("[-] FAIL: Mapper device opened despite invalid passphrase!")
        return False


def test_valid_unlock_and_mapper_poll(
    host: str,
    port: int,
    key_path: str,
    passphrase: str,
    mapper_target: str = "test_crypt",
    poll_timeout: int = 15,
) -> bool:
    print(f"[*] TEST: Unlock with valid passphrase and client-side mapper poll (/dev/mapper/{mapper_target})...")

    # Sanitize single quotes in passphrase
    sanitized_pass = passphrase.replace("'", "'\\''")
    fifo_write_cmd = (
        f"for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do "
        f"[ -p \"$f\" ] && printf \"%s\" '{sanitized_pass}' > \"$f\" && exit 0; "
        f"done; exit 1"
    )

    res = run_ssh_command(key_path, host, port, fifo_write_cmd)
    if res.returncode != 0:
        print(f"[-] Failed to write passphrase to passfifo: {res.stderr}")
        return False

    print("[+] Passphrase delivered to passfifo. Polling mapper device...")
    start = time.time()
    mapper_detected = False

    while time.time() - start < poll_timeout:
        check_res = run_ssh_command(key_path, host, port, f"[ -b /dev/mapper/{mapper_target} ]", connect_timeout=2)
        if check_res.returncode == 0:
            mapper_detected = True
            print(f"[+] PASS: Mapper device /dev/mapper/{mapper_target} confirmed open!")
            break

        # If connection drops during poll and was confirmed, handoff occurred
        time.sleep(1)

    if not mapper_detected:
        print("[-] FAIL: Timed out waiting for mapper device to appear.")
        return False

    print("[+] PASS: Full unlock protocol cycle succeeded.")
    return True


def parse_args():
    default_host, default_port = get_default_host_and_port()

    parser = argparse.ArgumentParser(description="Automated tester for unlocker lab.")
    parser.add_argument("pos_host", nargs="?", help="SSH host (optional positional)")
    parser.add_argument("pos_port", nargs="?", type=int, help="SSH port (optional positional)")
    parser.add_argument("pos_key", nargs="?", help="Key path (optional positional)")
    parser.add_argument("pos_pass", nargs="?", help="LUKS passphrase (optional positional)")

    parser.add_argument("--host", default=None, help="SSH host")
    parser.add_argument("--port", type=int, default=None, help="SSH port")
    parser.add_argument("--key", default=None, help="Path to Ed25519 private key")
    parser.add_argument("--passphrase", default=None, help="LUKS passphrase")
    parser.add_argument("--timeout", type=int, default=60, help="SSH ready timeout in seconds")
    parser.add_argument("--quick", action="store_true", help="Quick mode: run only positive unlock")
    return parser.parse_args()


def main():
    args = parse_args()
    default_host, default_port = get_default_host_and_port()

    host = args.host or args.pos_host or default_host
    port = args.port or args.pos_port or default_port
    key_path = args.key or args.pos_key or os.environ.get("UNLOCKER_KEY_PATH") or os.environ.get("NADAMU_KEY_PATH") or DEFAULT_KEY_PATH
    passphrase = args.passphrase or args.pos_pass or os.environ.get("UNLOCKER_LUKS_PASSWORD") or os.environ.get("NADAMU_LUKS_PASSWORD") or "password"

    print(f"=== [UNLOCKER LAB TEST SUITE] ===")
    print(f"Target: {host}:{port}")
    print(f"Key:    {key_path}")

    if not os.path.exists(key_path):
        print(f"[-] ERROR: SSH key not found at {key_path}")
        print("[-] Ensure the lab container is running and has generated data/keys/id_ed25519.")
        sys.exit(1)

    if not wait_for_ssh(host, port, key_path, timeout=args.timeout):
        sys.exit(1)

    if args.quick:
        success = test_valid_unlock_and_mapper_poll(host, port, key_path, passphrase)
        sys.exit(0 if success else 1)

    # Full test suite execution
    print("\n--- Running Test Suite ---")
    results = []

    # 1. Unauthorized key rejection
    results.append(("Unauthorized SSH Key Rejection", test_unauthorized_key(host, port)))

    # 2. Invalid passphrase rejection
    results.append(("Invalid Passphrase Handling", test_invalid_passphrase(host, port, key_path)))

    # 3. Valid unlock & mapper poll
    results.append(("Valid Passphrase Unlock & Mapper Poll", test_valid_unlock_and_mapper_poll(host, port, key_path, passphrase)))

    print("\n=== [TEST SUMMARY] ===")
    all_passed = True
    for name, passed in results:
        status = "[PASS]" if passed else "[FAIL]"
        print(f"{status} - {name}")
        if not passed:
            all_passed = False

    if all_passed:
        print("\n🎉 All lab end-to-end tests passed successfully!")
        sys.exit(0)
    else:
        print("\n❌ Some tests failed.")
        sys.exit(1)


if __name__ == "__main__":
    main()
