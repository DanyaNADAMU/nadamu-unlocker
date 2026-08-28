# laptop/ — Target Machine Side

> Complete documentation and automated installer for Kali / Debian / Ubuntu laptops.

## Documentation

Full step-by-step guides, kernel modules, Dropbear flags, and Wi-Fi configuration:
- 📖 **[English Documentation](../docs/en/laptop-setup.md)**
- 📖 **[Документация на русском](../docs/ru/laptop-setup.md)**

---

## Quick Install

```sh
sudo ./install.sh
```

Or configure Wi-Fi Hotspot (Modes A3/A4) directly:
```sh
sudo ./install.sh --ssid "MyHotspot" --psk "SecretPass123"
```

After running `install.sh`:
1. Copy the **Client SSH Public Key** from the Android app.
2. Add it to `/etc/dropbear/initramfs/authorized_keys` on your laptop.
3. Rebuild the initramfs: `sudo update-initramfs -u -k all`.

---

## Interactive Use

From an SSH session inside initramfs:

```sh
# unlock
Enter LUKS Password:
[NADAMU] Unlock payload delivered to /lib/cryptsetup/passfifo.
```
