"""Generate simple placeholder icons (no external image tooling needed).

Run once if the committed icons are missing: python generate_icons.py
Replace these with the real NewPGP branding before release.
"""

import struct
import zlib
from pathlib import Path

HERE = Path(__file__).parent
ICONS = HERE / "icons"
ICONS.mkdir(exist_ok=True)


def png(size: int, color=(37, 99, 235, 255)) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes(color) * size for _ in range(size))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


png64 = png(64)
(ICONS / "icon.png").write_bytes(png64)

# ICO with a PNG-compressed 32x32 entry (supported by Windows Vista+).
png32 = png(32)
ico = struct.pack("<HHH", 0, 1, 1)
ico += struct.pack("<BBBBHHII", 32, 32, 0, 0, 1, 32, len(png32), 6 + 16)
ico += png32
(ICONS / "icon.ico").write_bytes(ico)

print(f"Wrote {ICONS / 'icon.png'} and {ICONS / 'icon.ico'}")
