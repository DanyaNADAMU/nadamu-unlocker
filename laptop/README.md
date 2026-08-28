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

1. Installs `dropbear-initramfs`, `cryptsetup-initramfs`, `busybox`, `wpasupplicant`, `rfkill`, `iw`.
2. Configures network DHCP (`IP=dhcp`) and `DROPBEAR=y` in `/etc/initramfs-tools/initramfs.conf`.
3. Sets up optional Wi-Fi Hotspot configuration (`/etc/nadamu/wifi/wpa_supplicant.conf`).
4. Copies `bin/unlock` to `/usr/local/bin/unlock`.
5. Writes `/etc/initramfs-tools/hooks/nadamu_unlock` and `/etc/initramfs-tools/hooks/nadamu_wifi`.
6. Writes premount permission fix, wireless activation (`nadamu_wifi_up`), and local-top passfifo watcher scripts.
7. Sets `DROPBEAR_OPTIONS="-p 22 -s -j -k -E"` (public key auth only, no forwarding).
8. Runs `update-initramfs -u -k all`.

## Manual post-install steps

- Put your client's OpenSSH-format public key into
  `/etc/dropbear/initramfs/authorized_keys` (or `/etc/dropbear-initramfs/authorized_keys`).
  The app shows its public key for copying. Re-run `update-initramfs -u -k all`.

## Interactive use

From an SSH session inside initramfs:

```
# unlock
Enter LUKS Password: ****
[NADAMU] Unlock payload delivered to /lib/cryptsetup/passfifo.
```

Success is confirmed by watching for the mapper device; see
`../docs/unlock-flow.md` for why fifo write alone is not success.
