# laptop/ — target machine side

> What gets installed on the user's Kali/Debian laptop so its initramfs
> exposes dropbear and accepts a remote unlock.

Verified: never (installer not yet exercised end-to-end on real hardware)

## TL;DR

`install.sh` installs the `unlock` CLI and an initramfs hook that copies it
into every future initrd, then rebuilds the initramfs. Dropbear must be
configured for public-key auth only. The lab (`../lab/`) builds and tests
the equivalent setup automatically — treat the lab as the reference.

## Install

```sh
sudo ./install.sh
```

What it does:

1. Installs `dropbear-initramfs`, `cryptsetup-initramfs`, `busybox` if
   missing.
2. Copies `bin/unlock` to `/usr/local/bin/unlock`.
3. Writes `/etc/initramfs-tools/hooks/nadamu_unlock` (copies `unlock` into
   initramfs images).
4. Appends `DROPBEAR_OPTIONS="-s -j -k -p 22"` to dropbear config (public
   key auth only, no forwarding).
5. Runs `update-initramfs -u -k all`.

## Manual post-install steps (not automated yet)

- Put your client's OpenSSH-format public key into
  `/etc/dropbear/initramfs/authorized_keys` (or `/etc/dropbear-initramfs/`
  on newer Debian). The app shows its public key for copying.
- The full set of runtime fixes used by the lab (permission hardening of
  `/root/.ssh`, fifo creation, cryptroot watcher script) lives in
  `../lab/build_lab.sh`; the installer does not replicate all of them yet —
  diff against the lab before trusting real-hardware behavior.

## Interactive use

From an SSH session inside initramfs:

```
# unlock
Enter LUKS Password: ****
[NADAMU] Unlock payload delivered to /lib/cryptsetup/passfifo.
```

Success is confirmed by watching for the mapper device; see
`../docs/unlock-flow.md` for why fifo write alone is not success.
